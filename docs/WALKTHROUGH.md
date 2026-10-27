# Walkthrough: follow one scan through the system

We follow one change from a commit to the dashboard. At each stop: **what happens**, **the code** (links go to the line on GitHub), **why it's built this way** (the full trade-offs are in [DESIGN.md](DESIGN.md)), **see it yourself**, and **check yourself** questions. Try each one before opening the answer.

The story: you add a new SQL injection to `samples/python/app.py` and push. Jenkins builds it as **build 1052**.

```
 STOP 1          STOP 2-3          STOP 4-5              STOP 6           STOP 7            STOP 8
[commit] ─► [Jenkins + os-sast] ─POST─► [ingest-api] ─► [Kafka topic] ◄─pull─ [consumer] ─► [Postgres]
                                                                                   │
                                                                                   └─ DEL ─► [Redis]   STOP 9
                                                                                                │
                                    STOP 10  [Angular UI] ─► [dashboard-api] ───────────────────┘
```

| Port | What |
|------|------|
| 8080 | ingest-api |
| 8081 | Kafka UI |
| 8082 | dashboard-api |
| 8090 | Jenkins (admin / admin) |
| 4200 | Angular UI |
| 9092 | Kafka (from the laptop) · `kafka:29092` from containers |
| 5432 / 6379 | Postgres / Redis |

The four repos are checked out side by side: [samples](https://github.com/ImohitI/samples), [os-sast](https://github.com/ImohitI/os-sast), [sast-platform](https://github.com/ImohitI/sast-platform), [sast-dashboard](https://github.com/ImohitI/sast-dashboard). Everything below "see it yourself" needs Docker running (`cd sast-platform && docker compose --profile apps --profile ci up -d --build`). Outputs marked **(captured)** are real runs from 26 Oct.

---

## Stop 1: a commit lands, Jenkins notices

**What happens.** Jenkins checks the `samples` git repo every 2 minutes (Poll SCM). It sees a new commit and starts the `samples` pipeline job.

**The code.**
- The job itself is created by configuration as code, with no clicking: [casc.yaml#L21](https://github.com/ImohitI/sast-platform/blob/main/jenkins/casc.yaml#L21) (poll spec) and [#L29](https://github.com/ImohitI/sast-platform/blob/main/jenkins/casc.yaml#L29) (`file:///repos/samples`, a bind mount of the local `samples` checkout).
- The pipeline: [Jenkinsfile#L7](https://github.com/ImohitI/samples/blob/main/Jenkinsfile#L7) declares the same trigger.
- Jenkins has os-sast, Opengrep and Bandit installed inside its own image: [jenkins/Dockerfile](https://github.com/ImohitI/sast-platform/blob/main/jenkins/Dockerfile). There is no Docker-in-Docker and no agents.

**Why this way.** Polling needs no network setup. A GitHub webhook is instant, but GitHub can't reach a laptop, so it needs a relay (smee.io). `H/2` spreads the poll time across jobs, so not every job hits git on the same second.

**See it yourself.** Commit anything in `samples`, open http://localhost:8090/job/samples/ and wait at most 2 minutes. Then open *Polling Log*.

**Check yourself**

1. Webhook vs polling: which one has lower latency, and which needs inbound network access?
2. Why does the Jenkins image need `git config --system --add safe.directory '*'`?

<details><summary>Answers</summary>

1. A webhook is push-based: near-instant, but GitHub must be able to reach Jenkins (inbound). Polling is pull-based: up to 2 minutes of delay, but only outbound access is needed.
2. The repo is bind-mounted and owned by the host user, not the `jenkins` user. Git refuses to read repos owned by another user unless they're marked safe ("dubious ownership").
</details>

---

## Stop 2: os-sast scans the code

**What happens.** [Jenkinsfile#L23](https://github.com/ImohitI/samples/blob/main/Jenkinsfile#L23) runs
`os-sast scan . --repo samples --build 1052 --commit <sha> --out result.json`. Inside [`cli.scan`](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/cli.py#L41):

1. **Run scanners.** Opengrep with os-sast's own rules, one YAML file per CWE ([rules/](https://github.com/ImohitI/os-sast/tree/main/src/os_sast/rules), [run_opengrep](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L59)), and Bandit ([run_bandit](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L94)), each as a subprocess with JSON output.
2. **Normalize** each tool's JSON into one `RawFinding`: CWE as `CWE-89` ([_first_cwe](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L48)), severity `ERROR→HIGH, WARNING→MEDIUM, INFO→LOW`, and the path relative to the repo root ([_relative](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L42)).
3. **De-duplicate.** Findings with the same `cwe + file + line` become one ([merge](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/merge.py#L15)). `foundBy` lists every tool, and the most severe report leads ([#L23](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/merge.py#L23)).
4. **Fingerprint** = `sha256(cwe|file|line)` ([fingerprint](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/merge.py#L9)).

**(captured)** on `samples`:

```
21 raw results -> 19 findings after de-duplication
app.py 34 CWE-78 HIGH ['bandit', 'opengrep']   ← two tools, one finding
app.py 19 CWE-89 ['bandit'] ... app.py 20 CWE-89 ['opengrep']   ← ONE bug, two lines: dedupe can't merge them
```

**Why this way.**
- **Paths are normalized** because Bandit says `./python/app.py` and Opengrep reports an absolute path (it runs from the rules directory). The path is part of the fingerprint, so without this the same bug would get two identities.
- **Exit codes:** Opengrep returns 1 when it *finds* something, and Bandit does too. A real failure is detected differently for each tool ([#L70](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L70), [#L101](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/scanners.py#L101)).
- **Opengrep runs from the rules directory** because it prefixes every rule id with the config path relative to its working directory. Run from the repo root with an absolute rules path, every finding would carry a local path like `home.<user>.….java-sqli-statement` all the way to the public dashboard.
- **The fingerprint is naive on purpose.** It's simple and deterministic, but it breaks when code moves (stop 10).

**See it yourself.**

```bash
cd samples
os-sast scan . --repo samples --build 1 --commit test --out /tmp/r.json   # needs the opengrep binary + pip install -e ../os-sast[scanners]
python3 -m json.tool /tmp/r.json | head -40
```

**Check yourself**

1. Bandit flags `app.py:19` with CWE-89 and Opengrep flags `app.py:20` with CWE-89. How many findings after merge, and why?
2. Why is a scanner's exit code 1 not an error here?
3. Name one input change that changes a finding's fingerprint even though the bug is unchanged.

<details><summary>Answers</summary>

1. Two. The merge key includes the line number, and 19 ≠ 20. Tools disagree on *which* line a multi-line bug lives on.
2. Both tools use exit code 1 to mean "I found issues". Only other codes (Opengrep) or empty output (Bandit) mean the tool itself failed.
3. Adding or removing a line above the bug (the line number changes). A different CWE mapping from a new rule version would change it too.
</details>

---

## Stop 3: AI triage (optional) and eval

**What happens.** With `--ai-triage`, [triage](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/triage.py#L96) sends each finding to Ollama (`POST /api/generate`). The request carries the rule, the CWE, the message and ±15 lines of code with the flagged line marked `>>` ([code_snippet](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/triage.py#L46)). The answer is constrained by a JSON schema ([RESPONSE_SCHEMA](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/triage.py#L19)) and validated ([parse_verdict](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/triage.py#L62)). It becomes `ai: {verdict, confidence, reason, suggestedFix}`.

**The rule that matters:** the AI **annotates, it never deletes**. If Ollama is down or answers garbage, the finding keeps `ai: null` and the scan still succeeds ([#L103](https://github.com/ImohitI/os-sast/blob/main/src/os_sast/triage.py#L103)).

`os-sast eval result.json expected.json` scores the result against the ground truth. **(captured)** baseline, no AI, Opengrep with the bundled rules + Bandit:

```
                 findings   TP  precision   recall  bait hit
all findings           19   15       0.79     1.00         1
Missed planted bugs (0):
```

Before the switch, Semgrep `p/default` + Bandit scored 0.57 / 0.71 and missed 4 bugs (Python path traversal, Java `Runtime.exec`, Kotlin SQLi, PHP XSS). The 1.00 is on the set the rules were written next to; a holdout (bugs and bait added after the rules were frozen) gives the honest number.

With AI, a second row shows what *would* happen if you hid every `likely_fp`. It also lists every real bug the AI wrongly called a false positive. That list is the cost of using AI as a filter.

**Why this way.**
- **A local model** means no code leaves the machine, so the same setup works on proprietary code.
- **The samples carry no "VULN" comments**, because the model sees the surrounding lines and a comment would give it the answer.
- **`temperature: 0`** plus a schema-constrained output means the answer is repeatable and parseable.

**Check yourself**

1. Suppose (made-up numbers, run E7 for real ones) precision goes from 0.57 to 0.80 with "hide likely_fp", but recall drops from 0.71 to 0.64. Ship the filter?
2. Why do two findings on the same bug both count as TP for precision but only once for recall?

<details><summary>Answers</summary>

1. Not as a filter. A recall drop means a real vulnerability was hidden. Use the verdict to *sort* the list, not to remove findings.
2. Precision asks "is this finding pointing at something real?", and both are. Recall asks "how many distinct bugs did we catch?", and that's one.
</details>

---

## Stop 4: Jenkins publishes the result

**What happens.** [Jenkinsfile#L39-44](https://github.com/ImohitI/samples/blob/main/Jenkinsfile#L39-L44): `curl --retry 5 POST $INGEST_URL/api/scan-results --data-binary @result.json`, then **fail the build unless the final status is 202**. `result.json` is archived as a build artifact.

**Why this way.** curl retries 5xx responses (including our 503). A retry can send the same build twice. That's safe, because the consumer is idempotent on `(repoId, buildId)` (stop 7). **Retries are only safe because of idempotency.**

**Check yourself**

1. ingest-api published the message, but the 202 got lost on the network, so curl retried and got a second 202. How many rows end up in `scan_runs`?

<details><summary>Answer</summary>

1. One. Kafka now holds two copies of build 1052. The consumer stores the first, and the second hits `ON CONFLICT DO NOTHING`.
</details>

---

## Stop 5: ingest-api accepts it (validate → publish → 202)

**What happens.** [ScanResultController](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/ScanResultController.java#L49):

1. `@Valid` checks the body against the constraints in `contracts`, e.g. [buildId is `@NotNull @Positive Long`](https://github.com/ImohitI/sast-platform/blob/main/contracts/src/main/java/dev/sast/contracts/ScanResult.java#L21). Bad input → **400** with a readable reason ([BadRequestHandler](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/BadRequestHandler.java)).
2. `kafka.send(topic, repoId, result)`: **key = repoId** ([#L53](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/ScanResultController.java#L53)).
3. **Wait for the broker ack** (`acks=all`), then reply **202** ([#L58](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/ScanResultController.java#L58)).
4. If Kafka can't take it → **503** with `Retry-After: 5`. There are two ways to fail: the future fails or times out ([#L62](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/ScanResultController.java#L62)), or `send()` itself throws ([#L65](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/java/dev/sast/ingest/ScanResultController.java#L65)).

**The timeout chain** ([application.yml#L20-34](https://github.com/ImohitI/sast-platform/blob/main/ingest-api/src/main/resources/application.yml#L20-L34)):

| Setting | Value | Meaning |
|---------|-------|---------|
| `max.block.ms` | 5 s | `send()` waits at most this long for topic metadata. With no broker it throws after 5 s |
| `delivery.timeout.ms` | 10 s | The producer's total time to deliver, retries included; then the future fails |
| `sast.ack-timeout` | 12 s | Our wait on the future. It's longer than delivery, so the producer's own failure normally ends the wait |

If our timer fired first, the record might still be delivered *after* we said 503. Jenkins would retry, and there would be a duplicate. Idempotency absorbs that, but the ordering keeps it rare.

**(captured)** smoke test with no Kafka running:

```
POST without buildId  → HTTP 400 {"status":"invalid","errors":["buildId must not be null"]}
POST valid, no broker → HTTP 503 in 5.09 s {"status":"unavailable", ...}     ← max.block.ms
```

**The bug this stop caught.** The first version returned **500** here. `KafkaTemplate.send()` throws **Spring's** `org.springframework.kafka.KafkaException` synchronously, and the code caught Apache's class of the same name. The mock test had passed because the mock only failed the future. Lesson: a mock only fails the way you told it to.

**Why this way.** 202 = "durably accepted", not "processed". ingest-api never touches Postgres, so a DB outage doesn't fail CI (E3). Virtual threads make the blocking wait cheap.

**See it yourself.**

```bash
curl -i -X POST localhost:8080/api/scan-results -H 'Content-Type: application/json' --data-binary @/tmp/r.json
docker compose logs ingest-api | grep accepted     # "accepted repo=samples build=1 findings=19 -> partition=<0 or 1> offset=0"
```

**Check yourself**

1. Why would a primitive `long buildId` silently lose builds, rather than just failing?
2. Why 202 and not 201?
3. With fire-and-forget (reply 202 without waiting), what's the failure scenario?

<details><summary>Answers</summary>

1. A missing field becomes `0`. Every such build collides on `(repo, 0)`, and the consumer's `ON CONFLICT DO NOTHING` drops each one after the first as a "duplicate".
2. Nothing has been created yet. The data is queued, not stored. 201 would promise a resource you can't fetch.
3. The broker fails after the 202. The scan is lost while Jenkins shows green.
</details>

---

## Stop 6: Kafka holds the message

**What happens.** The message lands in topic `scan-results`, partition = `murmur2(key) mod 2`. Every build of `samples` goes to the *same* partition, in order, at the next offset.

**The code.**
- The topic is created on purpose by a one-shot `kafka-init` container ([compose#L45](https://github.com/ImohitI/sast-platform/blob/main/docker-compose.yml#L45)), together with `scan-results.DLT`. Broker auto-create is **off** ([#L28](https://github.com/ImohitI/sast-platform/blob/main/docker-compose.yml#L28)), so a typo in a topic name fails loudly.
- Single broker, KRaft mode (no ZooKeeper), two listeners: `localhost:9092` for the laptop and `kafka:29092` for containers.

**Why this way.** Kafka absorbs bursts (E4), decouples the API from the database (E3), and keeps the message after it's read. A consumer can crash and re-read from its last committed offset.

**See it yourself.** Kafka UI http://localhost:8081 → Topics → `scan-results` → Messages. Note the partition and offset. Then Consumers → `scan-results-writer`: committed offset and lag per partition.

**Check yourself**

1. Only `samples` sends builds. How many of the consumer's 2 threads do real work?
2. You change the topic to 4 partitions. What can happen to ordering for `samples`?
3. Why must `scan-results.DLT` have at least 2 partitions?

<details><summary>Answers</summary>

1. One. A single key always hashes to one partition, and a partition is owned by exactly one thread in the group. That's why the k6 test uses 4 repoIds.
2. `hash mod 4` ≠ `hash mod 2`, so new messages can land on a different partition than older ones. For a while, two partitions hold `samples` builds and they can be processed out of order.
3. The dead-letter recoverer publishes to the *same partition number* the record came from. A record from partition 1 needs a DLT partition 1.
</details>

---

## Stop 7: the consumer stores it, exactly once in effect

**What happens.** [ScanResultListener](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultListener.java#L41), `concurrency = 2`. For each record, in this order:

1. **Deserialize.** [`ErrorHandlingDeserializer`](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/resources/application.yml#L29) wraps Jackson. Bad bytes become an error the handler can see, instead of an exception inside `poll()`.
2. **Store** in **one transaction** ([ScanResultWriter#L31](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultWriter.java#L31)):
   - `INSERT scan_runs ... ON CONFLICT (repo_id, build_id) DO NOTHING` ([#L36](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultWriter.java#L36))
   - 0 rows inserted → duplicate → stop ([#L40](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultWriter.java#L40))
   - otherwise batch-insert the findings ([#L44](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultWriter.java#L44))
   - commit
3. **Evict** `latest:samples` from Redis, *after* the commit, and **even for a duplicate** ([#L50](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultListener.java#L50)).
4. **Return.** Spring Kafka commits the offset (default `AckMode.BATCH`: after the whole `poll()` batch).

**Failure paths** ([KafkaErrorHandlingConfig](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/KafkaErrorHandlingConfig.java#L37)):

| Failure | Kind | What happens |
|---------|------|--------------|
| Postgres down, network blip | transient | Retry the same record: 1 s, 2 s, 4 s … then every 30 s, **forever**. The partition pauses, lag grows, and it drains when Postgres is back (E3) |
| Bytes aren't a ScanResult | permanent | Straight to `scan-results.DLT`; the partition moves on |
| Row rejected by a DB constraint | permanent | `DataIntegrityViolationException` is not retryable → DLT |
| Redis down | not a failure | Logged and ignored; the TTL bounds staleness |

**The crash window (E1).** The DB commit (step 2) and the offset commit (step 4) live in two different systems. A crash between them means the row is stored but the offset isn't committed. After restart Kafka redelivers, `store()` returns false, and nothing is written twice. `CONSUMER_DEBUG_SLEEP=15s` holds that window open so you can kill the consumer inside it ([#L54](https://github.com/ImohitI/sast-platform/blob/main/consumer/src/main/java/dev/sast/consumer/ScanResultListener.java#L54)).

**Why this way.** Exactly-once *delivery* across Kafka and Postgres doesn't exist. Kafka transactions only cover Kafka-to-Kafka. So: at-least-once delivery plus an idempotent write = effectively-once storage. The early stop on a duplicate is only correct because the write is atomic.

**See it yourself.**

```bash
docker compose logs -f consumer      # "stored repo=samples build=1052 findings=19 partition=<p> offset=<n>"
# POST the same result.json again   → "duplicate, skipped repo=samples build=1052 ..."
mvn -pl consumer verify              # ConsumerIT: duplicate stored once, cache evicted, poison pill → DLT
```

**Check yourself**

1. Why evict Redis on a duplicate, when nothing new was written?
2. Why retry Postgres errors forever, instead of 3 times and then the DLT?
3. Why must the "already stored?" check and the findings insert be in one transaction?
4. With `AckMode.BATCH`, the consumer crashes after record 3 of a 5-record poll. Which records are redelivered?

<details><summary>Answers</summary>

1. The first delivery may have crashed after the DB commit but *before* the evict. The redelivery is the only chance to fix the stale cache.
2. A DB outage isn't the message's fault. Sending good messages to the DLT during an outage would "lose" them to a side topic. Blocking the partition and letting lag grow is the honest behaviour.
3. Otherwise a crash between the two inserts leaves a run without its findings. The next delivery sees the run, says "duplicate", and the findings are gone for good.
4. All 5. No offset was committed for that batch. Records 1–3 hit `ON CONFLICT DO NOTHING`.
</details>

---

## Stop 8: Postgres holds the truth

**What happens.** Two tables. The primary keys are the idempotency mechanism *and* the indexes both dashboard queries need.

```sql
scan_runs  PK (repo_id, build_id)               -- one row per build
findings   PK (repo_id, build_id, fingerprint)  -- FK → scan_runs; + ai_* columns (V2 added ai_suggested_fix)
```

Flyway migrations belong to the consumer, the only service that writes. dashboard-api connects as `dashboard_ro`, created by [postgres/init](https://github.com/ImohitI/sast-platform/blob/main/postgres/init/01-dashboard-readonly.sql) with `ALTER DEFAULT PRIVILEGES`, so the tables Flyway creates *later* are readable too.

**See it yourself.**

```bash
docker compose exec postgres psql -U sast -c "SELECT repo_id, build_id, finding_count FROM scan_runs ORDER BY build_id DESC"
docker compose exec postgres psql -U sast -c "SELECT file_path, line, cwe, found_by, ai_verdict FROM findings WHERE build_id = 1052"
```

**Check yourself**

1. Why a natural key `(repo_id, build_id)` and not a `SERIAL id`?
2. Why did the `suggestedFix` fix go into `V2__...sql` instead of editing `V1`?

<details><summary>Answers</summary>

1. A redelivered message carries the same natural key, so the DB rejects it by itself. A surrogate id would insert it again happily.
2. V1 has already run on existing databases. Flyway checksums it, and editing it would fail validation, or leave old databases without the column.
</details>

---

## Stop 9: Redis and dashboard-api serve it

**What happens.** `GET /api/repos/samples/latest` → [LatestBuildService.latest](https://github.com/ImohitI/sast-dashboard/blob/main/dashboard-api/src/main/java/dev/sast/dashboard/LatestBuildService.java#L47):

```
GET latest:samples ─ HIT ─► return cached JSON                       (log "cache HIT")
                   └ MISS ─► query Postgres ─► SET latest:samples EX 60 ─► return   (log "cache MISS")
```

Any Redis error → log it and read Postgres ([#L64](https://github.com/ImohitI/sast-dashboard/blob/main/dashboard-api/src/main/java/dev/sast/dashboard/LatestBuildService.java#L64)). Timeouts are 500 ms, so an outage costs about 0.5 s instead of a 60 s hang.

**Two freshness mechanisms.**
1. **The consumer `DEL`s the key** after every commit, so a new build is visible on the very next read.
2. **TTL 60 s** bounds the one race the `DEL` can't prevent:

```
reader:    MISS ── SELECT (sees build 1051) ─────────────────── SET latest = 1051   ← stale ≤ 60 s
consumer:                    INSERT 1052 ── COMMIT ── DEL
```

**See it yourself.**

```bash
curl -s localhost:8082/api/repos/samples/latest | head -c 300; echo
curl -s localhost:8082/api/repos/samples/latest > /dev/null      # second call: "cache HIT" in the log
docker compose exec redis redis-cli TTL latest:samples           # counts down from 60
docker compose stop redis && curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" localhost:8082/api/repos/samples/latest   # E5: 200, slower
```

**Check yourself**

1. Why does the consumer *delete* the key instead of writing the new value?
2. In the race above, what would close it completely? (Hint: nothing simple. Why is the TTL acceptable?)
3. Why is Redis's command timeout set explicitly?

<details><summary>Answers</summary>

1. A delete is idempotent and order-free. Writing the value would make the consumer compute the dashboard's view, and two writers could race (an old build's SET landing after a new one's).
2. You'd need versioned writes (set only if the build id is newer) or a lock. The TTL turns "stale forever" into "stale for at most 60 s", which is fine for a dashboard.
3. The default is 60 s. When Redis is down, every request would hang for a minute: "optional" in name only.
</details>

---

## Stop 10: the UI shows new vs fixed

**What happens.** The Angular page ([app.ts](https://github.com/ImohitI/sast-dashboard/blob/main/ui/src/app/app.ts)) loads the repo list, the latest build and the diff against the previous build: `GET .../builds/1052/diff` ([controller#L44](https://github.com/ImohitI/sast-dashboard/blob/main/dashboard-api/src/main/java/dev/sast/dashboard/DashboardController.java#L44)).

```
new   = fingerprints(1052) − fingerprints(1051)       BuildDiff.newFindings
fixed = fingerprints(1051) − fingerprints(1052)       BuildDiff.fixedFindings
```

Your new SQL injection shows up under **New**. Each finding has an AI badge (`AI: likely TP 0.90`, `likely FP`, or `no AI`). Hover it for the reason and the suggested fix ([aiLabel](https://github.com/ImohitI/sast-dashboard/blob/main/ui/src/app/app.ts#L56)).

**The known limit.** Insert a blank line at the top of `app.py` and push. Every finding below it changes line number, so its fingerprint changes too. The diff shows them all as **fixed** *and* **new**, even though nothing changed. This is the stretch task: a fingerprint that survives line moves (hash the code snippet or the AST path instead of the line).

**See it yourself.** `cd sast-dashboard/ui && npm start` → http://localhost:4200 (`/api` is proxied to :8082). Or `docker compose up -d --build` in `sast-dashboard` (nginx proxies `/api`).

**Check yourself**

1. Why does the UI call `/api/...` instead of `http://localhost:8082/api/...`?
2. Which single design choice upstream decides how good this diff is?

<details><summary>Answers</summary>

1. Same origin: the dev proxy (`proxy.conf.json`) or nginx forwards `/api`, so the browser never makes a cross-origin call and no CORS config is needed.
2. The fingerprint definition in os-sast (`sha256(cwe|file|line)`).
</details>

---

## The whole journey, as failure points

| If this fails… | …this happens | Data lost? |
|----------------|---------------|------------|
| Ollama | `ai: null` on each finding, scan continues | No |
| Opengrep / Bandit crash | `os-sast` exits 2, build fails | No (nothing sent) |
| ingest-api down | curl retries, then the build fails | No (Jenkins knows) |
| Kafka down | 503 in ~5 s, curl retries, then the build fails | No |
| 202 lost in transit | curl retries, a duplicate goes to Kafka | No, idempotent consumer |
| Consumer crash mid-batch | Redelivery from the last committed offset | No, `ON CONFLICT DO NOTHING` |
| Postgres down | Consumer retries forever, lag grows, drains later | No |
| Malformed message | → `scan-results.DLT`, partition moves on | Parked, not lost |
| Redis down | Dashboard reads Postgres, ~0.5 s slower | No |
| Evict missed (Redis blip) | Dashboard stale ≤ 60 s (TTL) | No |

In one sentence: **"Every hop either retries, parks the message, or degrades to slower, and the database key makes every retry safe."**

---

## Run it end to end (once Docker is up)

- [ ] `cd sast-platform && mvn verify`: all unit tests + `ConsumerIT` green
- [ ] `docker compose --profile apps --profile ci up -d --build`
- [ ] Kafka UI shows `scan-results` and `scan-results.DLT` with 2 partitions each
- [ ] Jenkins http://localhost:8090 → job `samples` → first build green, `result.json` archived
- [ ] `scan_runs` has the build; consumer log says `stored`
- [ ] `cd ../sast-dashboard && docker compose up -d --build` → http://localhost:4200 shows the build
- [ ] Add a bug in `samples`, commit → within 2 minutes the UI shows it under **New**
- [ ] Then the experiments E1–E7 ([DESIGN.md §12](DESIGN.md#12-which-experiment-tests-which-decision)), predictions first
