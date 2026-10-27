# SAST pipeline: design decisions

This document explains **why** each part of the pipeline is built the way it is. Each decision lists the choice, the reason, the alternative and the trade-off. To follow one scan through the running code, read the [walkthrough](WALKTHROUGH.md).

The pipeline spans four repos: [samples](https://github.com/ImohitI/samples), [os-sast](https://github.com/ImohitI/os-sast), [sast-platform](https://github.com/ImohitI/sast-platform) and [sast-dashboard](https://github.com/ImohitI/sast-dashboard). Where the code and this document disagree, the code wins and this document gets fixed.

Contents:

1. [The setup in simple terms](#1-the-setup-in-simple-terms)
2. [System at a glance](#2-system-at-a-glance)
3. [Where everything runs (docker compose)](#3-where-everything-runs-docker-compose)
4. [Contracts](#4-contracts)
5. [ingest-api](#5-ingest-api)
6. [Kafka topic `scan-results`](#6-kafka-topic-scan-results)
7. [consumer](#7-consumer)
8. [Postgres schema](#8-postgres-schema)
9. [Redis cache](#9-redis-cache)
10. [Upstream and downstream](#10-upstream-and-downstream)
11. [Bugs found and fixed](#11-bugs-found-and-fixed)
12. [Which experiment tests which decision](#12-which-experiment-tests-which-decision)
13. [Production follow-ups](#13-production-follow-ups)
14. [Self-check questions](#14-self-check-questions)

---

## 1. The setup in simple terms

Think of it as a **restaurant kitchen for security reports**.

1. **You push code** to `samples`.
2. **Jenkins (the waiter)** notices and runs **os-sast (the inspector)**, which writes a report of security bugs. A local AI adds an opinion to each bug, but only adds a note. It never throws a bug away.
3. Jenkins hands the report to **ingest-api (the front desk)**. It checks the form is filled in, pins it on the order board, and says *"Got it, we'll handle it"* (**202**). It doesn't cook.
4. **Kafka (the order board)** holds reports in line. 500 reports at 6 PM just queue up; nothing is lost.
5. **consumer (the cook)** takes reports off the board and files them in **Postgres (the filing cabinet)**. Only after filing does it tick the order as done.
6. **Redis (the sticky note)** keeps a quick copy of "latest results for this repo". When a new report is filed, the cook tears up the old note.
7. **The dashboard** reads the sticky note first; if there isn't one, it opens the cabinet and writes a fresh note.

| Problem | How the setup handles it |
|---|---|
| A rush of builds at once | Kafka absorbs it; Postgres isn't flooded |
| Postgres is down | Reports wait in Kafka; Jenkins still gets its 202 |
| The consumer crashes halfway | The order isn't ticked, so it's redone. Postgres sees the same build number and ignores the copy, so there are no duplicates |
| Redis is lost | The dashboard reads Postgres instead: slower, still works |
| The AI is wrong | It only annotates, so a real bug can never be hidden |

**The one idea that matters most:** Kafka can deliver the same report twice, and that's fine, because the database refuses duplicates.

---

## 2. System at a glance

The write path and the read path never block each other. Kafka sits between CI and the database; Redis sits between readers and the database.

```
WRITE PATH
[Jenkins + os-sast] ─POST─► [ingest-api] ─send, key=repoId─► [Kafka: scan-results, 2 partitions] ◄─pull─ [consumer]
  scan each commit           validate                                                                 one DB transaction
  Opengrep + Bandit          wait for Kafka ack                                                       duplicate = no-op
  LLM annotates only         reply 202                                                                then commit offset
                                                                                                        │           │
                                                                                         DEL after commit           write
                                                                                                        ▼           ▼
READ PATH
[Angular UI] ─GET─► [dashboard-api] ─read─► [Redis: latest:{repoId}, TTL 60 s]               [Postgres]
                     cache-aside             evicted on new build                                ▲
                     diff by fingerprint                                                         │
                         └──────────────────────────── on miss: query Postgres ─────────────────┘
```

The only line that crosses the two paths is the consumer's `DEL` on Redis. That is where all the staleness questions come from.

---

## 3. Where everything runs (docker compose)

`sast-platform/docker-compose.yml` starts the **infrastructure only**, the off-the-shelf pieces your code talks to:

| Service | What it is | Laptop address |
|---|---|---|
| `kafka` | Message queue (KRaft mode, no ZooKeeper) | `localhost:9092` |
| `kafka-ui` | Web page to watch topics, offsets, lag, rebalances | http://localhost:8081 |
| `postgres` | Database | `localhost:5432` |
| `redis` | Cache | `localhost:6379` |

There are no volumes, so **`docker compose down` wipes all data**. That's deliberate: each experiment starts clean.

**Why Kafka has two addresses:** `localhost:9092` is for programs on the laptop (`mvn spring-boot:run`). `kafka:29092` is for programs inside Docker (`kafka-ui`), because inside a container "localhost" means the container itself.

**Where the modules run, now and later:**

| Module | Repo | Runs where | When it changes |
|---|---|---|---|
| `ingest-api` | `sast-platform` | Laptop (`mvn spring-boot:run`, :8080) or compose profile `apps` | Same jar; env vars switch `localhost` to service names |
| `consumer` | `sast-platform` | Laptop or compose profile `apps` | E1/E2 need the container: `docker compose kill consumer`, `--scale consumer=2`. Inside Docker it uses `kafka:29092`, `postgres:5432`, `redis:6379` |
| `kafka-init` | `sast-platform` compose | One-shot container | Creates `scan-results` and `scan-results.DLT` (2 partitions each); broker auto-create is off |
| Jenkins | `sast-platform` compose profile `ci`, :8090 | Custom image with Python + `os-sast` installed (no Docker-in-Docker); job defined as code | Polls `../samples` (bind-mounted) every 2 minutes |
| `os-sast` | `os-sast` | Not a service: a CLI inside the Jenkins image | |
| `dashboard-api` (:8082) + Angular (:4200) | `sast-dashboard` | Laptop, or its own compose joining the `sast-platform_default` network | Reads Postgres as `dashboard_ro`, caches in the same Redis |

The compose file lives in `sast-platform` because it is the shared backbone every repo plugs into.

**Decision: apps outside compose while developing.** Edit, restart, no image rebuild, so the loop is fast. The cost is that the config differs between laptop and container (`localhost` vs service names), so the addresses go in `application.yml` and get overridden by environment variables in compose.

---

## 4. Contracts

The scan result has one shape everywhere: the os-sast output, the POST body and the Kafka message are the same JSON. No mapping layer between stages, and one place to change when the format changes.

- `ScanResult` = `repoId`, `buildId`, `commitSha`, `scannedAt`, `tools[]`, `findings[]` (Java records in `sast-platform/contracts`)
- `Finding` = `fingerprint`, `cwe`, `ruleId`, `foundBy[]`, `severity`, `file`, `line`, `message`, `ai` (null when triage is off)
- `POST /api/scan-results` → 202 `{"status":"accepted"}` or 400
- `V1__init.sql`: tables `scan_runs` and `findings`

### Decision: contract first

| | |
|---|---|
| Choice | Write the message, API and schema before any service code. |
| Why | Four repos are built at different times. A fixed contract lets each be built and tested alone, with fake data. |
| Alternative | Code first, extract the contract once it works. |
| Trade-off | Early contract mistakes are paid for in every repo, so keep it small. |

### Decision: a shared `contracts` module, not a schema registry

| | |
|---|---|
| Choice | ingest-api and consumer depend on the same Java records. |
| Why | They change together, live in one repo and deploy together; the compiler catches a mismatch. |
| Alternative | Avro or Protobuf with a schema registry and compatibility rules. |
| Trade-off | No independent deploys. Old messages still in Kafka must still deserialize after a change, so only make additive changes (new nullable fields). |

### Decision: fingerprint = `sha256(cwe|file|line)`

| | |
|---|---|
| Choice | Identity of a finding = what, where, which line. |
| Why | It is the idempotency key in Postgres and lets the dashboard diff two builds into new vs fixed. |
| Alternative | Hash the code snippet or the AST path, so the finding survives line moves. |
| Trade-off | Adding one line above a bug makes it look "fixed" plus "new". Known limitation; stretch task. |

---

## 5. ingest-api

One job: accept the scan result safely, hand it to Kafka, reply fast. It never touches Postgres, which keeps it cheap, stateless and easy to scale behind the load balancer.

Request path: validate → `kafkaTemplate.send("scan-results", repoId, result)` → wait for the broker ack → 202.

### Decision: reply 202, not 200 or 201

| | |
|---|---|
| Choice | `202 Accepted` with `{"status":"accepted"}`. |
| Why | 202 means "I've taken responsibility", not "it's stored". The write happens later, in the consumer. |
| Alternative | 201 after a direct Postgres insert (synchronous). |
| Trade-off | The caller can't read its result back right away; the system is eventually consistent. A status URL would fix that (out of scope). |

### Decision: wait for the Kafka ack before replying

| | |
|---|---|
| Choice | `send(...).get(5, SECONDS)`; on timeout or error reply 503 so Jenkins retries. |
| Why | Without waiting, a broker hiccup after the 202 silently loses the scan while Jenkins shows green. |
| Alternative | Fire and forget: reply 202 immediately, log failures in a callback. |
| Trade-off | One broker round trip of latency per request. Virtual threads make the blocking cheap, so throughput holds. |

### Decision: never write to Postgres here

| | |
|---|---|
| Choice | The API's only dependency is Kafka. |
| Why | 500 builds at 6 PM become a queue, not 500 concurrent DB transactions. Postgres down (E3) doesn't fail CI. |
| Alternative | Write to Postgres and Kafka in the same request. |
| Trade-off | Dual writes can't be atomic without the outbox pattern; avoiding them is simpler. |

### Decision: validate at the edge

| | |
|---|---|
| Choice | `@Valid` on the body; 400 if `repoId`, `buildId` or `scannedAt` is missing. |
| Why | A bad message that reaches Kafka becomes a poison pill for every consumer. Rejecting it at the door is cheapest. |
| Alternative | Accept anything and let the consumer sort it out. |
| Trade-off | Validation rules live in two places (the API, and the consumer's tolerance). Keep the API strict. |

### Decision: virtual threads on

`spring.threads.virtual.enabled: true`. The request thread mostly waits on the Kafka ack, which is I/O. Virtual threads make that wait nearly free, so you don't need a big platform thread pool. E6 measures this, and shows CPU-heavy work gets no benefit.

---

## 6. Kafka topic `scan-results`

Kafka is the buffer between "CI produced a result" and "the database stored it". 2 partitions, keyed by `repoId`: builds of one repo stay in order, different repos spread across partitions.

The facts this design relies on:

- Order is guaranteed **only within a partition**. The partition is `hash(key) mod partitions`.
- In one consumer group, each partition goes to **exactly one** consumer thread. More threads than partitions = idle threads.
- Consumers **pull** and track their position as a committed offset. Reading doesn't delete messages; retention (7 days by default) does.
- Producer idempotence is on by default (Kafka 3.0+), so producer retries don't duplicate *inside Kafka*. Duplicates can still come from the consumer side.

### Decision: key = `repoId`

| | |
|---|---|
| Choice | Every build of a repo goes to the same partition. |
| Why | Build 1053 is never processed before 1052 for the same repo, so "latest build" is always right. |
| Alternative | Key = `repoId:buildId` (even spread), or no key (round robin). |
| Trade-off | One busy repo = a hot partition. **In this POC only `samples` sends, so all traffic lands on one partition and the second consumer thread sits idle.** |

### Decision: 2 partitions, created explicitly

| | |
|---|---|
| Choice | `kafka-topics.sh --create --partitions 2`, not auto-create. |
| Why | Partition count caps consumer parallelism. Small and explicit makes E2 visible (3 threads, 2 partitions → 1 idle). |
| Alternative | Many partitions up front (e.g. 12) for headroom. |
| Trade-off | Adding partitions later changes `hash(key) mod n`, so a repo's new messages can move partition and lose ordering with its old ones. |

### Decision: `acks=all`

With one broker it changes nothing, since the leader is the only replica. It's the right habit: with 3 brokers and `min.insync.replicas=2`, `acks=all` means a broker crash after the 202 can't lose the message.

### Decision: the whole result in one message

The broker's default message limit is about 1 MB, and a big monorepo scan can exceed it. Fixes: compression (`compression.type=zstd`), raising the limit on broker and producer, or **claim-check**: store the result in MinIO/S3 and put only a pointer in Kafka (stretch task).

---

## 7. consumer

The consumer turns at-least-once delivery into effectively-once storage. Kafka may hand it the same message twice; the database key makes the second write a no-op.

Per message, in this order:

1. Begin a DB transaction.
2. `INSERT INTO scan_runs ... ON CONFLICT DO NOTHING`. If 0 rows were inserted, this build is already stored: stop.
3. Batch-insert all findings (also `ON CONFLICT DO NOTHING`).
4. Commit the DB transaction.
5. After the commit, delete Redis key `latest:{repoId}`.
6. Method returns; Spring Kafka commits the offset.

**Why duplicates happen:** the DB commit (step 4) and the offset commit (step 6) are in two separate systems. A crash between them means the row is stored but Kafka thinks the message is unread, so it comes back after restart. E1 forces exactly this.

### Decision: at-least-once + idempotent writes

| | |
|---|---|
| Choice | Commit the offset after the DB write; dedupe with the primary key. |
| Why | Losing a scan is worse than processing it twice, and the second time costs one no-op insert. |
| Alternative | At-most-once (commit offset first) loses data on a crash. Kafka exactly-once transactions only cover Kafka-to-Kafka, not Postgres. |
| Trade-off | Every write must stay idempotent forever. A non-idempotent side effect (sending an email) would fire twice. |

### Decision: one transaction per scan

| | |
|---|---|
| Choice | `scan_runs` and all its `findings` commit together or not at all. |
| Why | The "already stored?" check in step 2 is only safe if a stored run always has all its findings. A half-written build would be skipped forever. |
| Alternative | Insert findings one by one in autocommit mode. |
| Trade-off | A huge scan = one long transaction. Fine at POC size; batch inserts keep it short. |

### Decision: when the offset is committed

Spring Kafka's default ack mode is `BATCH`: offsets are committed after **all** records from one `poll()` are processed, not after each method call. A crash mid-batch replays every record from that poll, not just the one in flight. `AckMode.RECORD` commits after each record (more commits, smaller replay). Both are safe here because writes are idempotent. The difference shows up in E1.

### Decision: `concurrency = "2"`

Two listener threads match the 2 partitions. A third thread gets no partition (E2). A second container in the same group triggers a rebalance that splits partitions across containers. You still never get more than 2 active threads.

### Decision: handle poison pills

| | |
|---|---|
| Choice | Wrap the value deserializer in `ErrorHandlingDeserializer`; after N retries send to `scan-results.DLT`. |
| Why | Without the wrapper, a message that can't be deserialized fails inside `poll()`, before the listener. The container retries the same offset forever and the partition stalls. |
| Alternative | Rely on ingest-api validation only. |
| Trade-off | A dead-letter topic needs someone to watch and replay it. |

---

## 8. Postgres schema

The primary keys are the idempotency mechanism, and also the only indexes the two dashboard queries need. Flyway owns the schema (`V1__init.sql` in the consumer).

| Table | Primary key | Serves |
|---|---|---|
| `scan_runs` | `(repo_id, build_id)` | Dedupe of a redelivered build; "latest build" = `WHERE repo_id = ? ORDER BY build_id DESC LIMIT 1`, a backward index scan |
| `findings` | `(repo_id, build_id, fingerprint)` | Dedupe of each finding; the diff loads both builds' findings by key prefix and compares fingerprints |

### Decision: natural key, not a surrogate id

| | |
|---|---|
| Choice | `(repo_id, build_id)` from the message is the key. |
| Why | A redelivered message carries the same key, so the DB rejects the duplicate by itself. A `SERIAL id` would happily insert it twice. |
| Alternative | Surrogate `id` + a separate `UNIQUE (repo_id, build_id)`. |
| Trade-off | Wider keys in `findings`. Irrelevant at this size. |

### Decision: the consumer owns the schema

Only one service runs migrations, so two services can't race on `flyway_schema_history`. dashboard-api reads with its own read-only DB user. If the dashboard needs a new column, it goes through the consumer's migrations.

### Smaller choices worth knowing

- **FK `findings → scan_runs`**: no orphan findings. Its columns are the leading part of the `findings` primary key, so no extra index is needed.
- **`found_by TEXT[]`**: a Postgres array keeps "which tools agreed" on one row. A join table is the normalized alternative.
- **`severity TEXT`**: simple, but accepts any string. A `CHECK (severity IN (...))` would match the Java enum.
- **`finding_count`** on `scan_runs`: denormalized so the "latest" view doesn't count rows on every request.

---

## 9. Redis cache

Redis makes the dashboard's "latest build" read cheap. Two mechanisms keep it fresh: a 60-second TTL bounds how stale it can ever be, and the consumer's eviction makes it fresh right after a new build lands.

**Cache-aside read** (dashboard-api): `GET latest:{repoId}` → hit: return it → miss: query Postgres, `SET ... EX 60`, return it. Log HIT/MISS.

### Decision: the consumer deletes the key, not updates it

| | |
|---|---|
| Choice | After the DB commit, `DEL latest:{repoId}`; the next read rebuilds it. |
| Why | Deleting is idempotent and order-free. Writing a new value would make the consumer compute the dashboard's view and risk two writers racing. |
| Alternative | Write-through (consumer `SET`s the new view), or TTL only with no eviction. |
| Trade-off | The first read after a build pays a miss. The consumer must know the dashboard's key name, which couples the two repos. |

### Decision: evict after the commit, not before

Evict before the DB commit, and a dashboard read can miss, read the **old** row and put it back, so the new build stays hidden for 60 s. Evicting after the commit (`TransactionSynchronization.afterCommit`, or simply after the `@Transactional` method returns) shrinks that window.

It doesn't close completely:

```
reader:    MISS ── SELECT (sees build 1052) ─────────────── SET latest = 1052   ← stale until TTL
consumer:                  INSERT 1053 ── COMMIT ── DEL
```

**The TTL is what bounds this race.**

### Decision: Redis is optional at runtime

The cache is an optimization, so a Redis outage must cost only speed (E5). In dashboard-api, catch Redis errors on read and fall back to Postgres. In the consumer, a failed `DEL` is logged and doesn't fail the message; otherwise a Redis outage would stall Kafka consumption.

---

## 10. Upstream and downstream

These live in other repos, but their choices shape what the platform receives and serves.

### os-sast: Opengrep with our own rules, not Semgrep's registry

| | |
|---|---|
| Choice | Opengrep (LGPL-2.1 fork of Semgrep CE, same CLI and rule syntax) with os-sast's own rules: one YAML file per CWE (89, 78, 22, 79, 328), shipped inside the package. |
| Why | The Semgrep registry rules use the Semgrep Rules License, which is not open source. Own rules keep the whole stack open, run offline, and are the part of a SAST tool that is real domain work: sources, sinks and sanitizers per framework. |
| Alternative | Semgrep CE + `p/default` (more rules for free, licence and network strings attached), or the archived community `opengrep-rules` fork (unclear licence). |
| Trade-off | Far fewer rules, so less coverage on code they weren't written for, and rules written by the person who planted the bugs overfit. Measure on a holdout set. |

### os-sast: the AI annotates, it never deletes

| | |
|---|---|
| Choice | The LLM adds `ai.verdict`, `confidence`, `reason`, `suggestedFix`. Every finding is still sent. |
| Why | A wrong "likely false positive" must not hide a real vulnerability. A missed bug (false negative) costs far more than a noisy one. |
| Alternative | Drop findings the AI calls false positives (higher precision, risk to recall). |
| Trade-off | Triage noise stays visible; the UI must help sort by verdict instead. |

- **Local LLM (Ollama):** no source code leaves the machine, so the same setup works on proprietary code.
- **De-duplication across tools:** same `cwe + file + line` → one finding with `foundBy: ["bandit","opengrep"]`. Agreement between tools is a useful confidence signal.

### Jenkins: what triggers a scan

Poll SCM (`H/2 * * * *`) needs no network setup. A GitHub webhook is instant but can't reach a laptop, hence the smee.io relay. The pipeline fails unless ingest-api answers 202. That only means something because ingest-api waits for the Kafka ack before saying 202.

### Dashboard: diff by fingerprint

`new = fingerprints(build) − fingerprints(base)` and `fixed = fingerprints(base) − fingerprints(build)`. This is the regression view: what did this commit introduce, and what did it fix? Its quality depends entirely on the fingerprint's stability (section 4).

---

## 11. Bugs found and fixed

Found in `sast-platform` on 26 Oct (commit `c03b166`). Each one broke a guarantee above. All were fixed in the end-to-end implementation the same day.

- [x] **`buildId` is a primitive `long`** (`contracts/ScanResult.java`). A body without `buildId` either becomes `0` or fails with an unclear Jackson error. If it becomes 0, every such build collides on `(repo, 0)` and `ON CONFLICT DO NOTHING` drops it silently. **Fixed:** `Long` + `@NotNull @Positive`; also `Integer line`, `Double confidence`.
- [x] **No validation at all** (`ScanResultController`). **Fixed:** constraints live in `contracts` (annotations only, via `jakarta.validation-api`), enforced at the edge with `@Valid`. `BadRequestHandler` returns `{"status":"invalid","errors":["buildId must not be null"]}`. Tested in `ScanResultControllerTest`.
- [x] **Raw `JacksonJsonDeserializer`** (`consumer/application.yml`). **Fixed:** `ErrorHandlingDeserializer` with Jackson as its delegate; `KafkaErrorHandlingConfig` sends permanent failures to `scan-results.DLT` and retries transient ones forever with backoff. Tested in `ConsumerIT`.
- [x] **`suggestedFix` has no column** (`V1__init.sql` vs `AiTriage`). **Fixed:** `V2__ai_suggested_fix.sql`.
- [x] **Redis failure would fail the message.** **Fixed:** `LatestBuildCache` catches and logs; Redis timeout 500 ms. The listener also evicts on a *duplicate*, in case the first delivery crashed between the commit and the evict.
- [x] **One repo → one busy partition.** **Fixed in the load test:** k6 spreads builds over 4 repoIds.
- [x] **Consumer not in compose yet.** **Fixed:** root `Dockerfile` (`MODULE` build arg) and an `apps` compose profile; knobs `CONSUMER_DEBUG_SLEEP` (E1) and `CONSUMER_CONCURRENCY` (E2).

One more bug turned up while smoke-testing with no broker running: ingest-api returned **500 instead of 503**. `KafkaTemplate.send()` doesn't only fail through the future. When it can't get topic metadata within `max.block.ms`, it throws **Spring's** `org.springframework.kafka.KafkaException` synchronously, and the code caught Apache's class of the same name. A mock-based test had passed because the mock only failed the future. Fixed, and `brokerUnreachableBeforeSendingIs503Too` covers it. Lesson: a mock only fails the way you told it to.

### Found by CI on 27 Oct

`ConsumerIT` (real Kafka, Postgres and Redis through Testcontainers) had never run, because the development machine had no Docker. Its first run in GitHub Actions failed three times in a row, each fix exposing the next. Two of the three were real bugs in the consumer, not in the test.

- [x] **The Kafka container exited on startup** with `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`. Kafka 3.9.0's storage-format step rejects the `CONTROLLER://0.0.0.0:9094` listener that Testcontainers configures for combined broker + controller mode; the compose file wasn't affected because it writes `CONTROLLER://:9093`. **Fixed:** `apache/kafka:3.9.1`, in the test and in compose, so the two stay identical.
- [x] **The dead-letter producer pointed at the wrong broker.** `deadLetterTemplate` was built from `KafkaProperties`, which only knows `spring.kafka.bootstrap-servers` (`localhost:9092`). A Testcontainers `@ServiceConnection` supplies the broker through `KafkaConnectionDetails`, which only Boot's own factories apply. Every dead letter failed to publish, so the poison pill was retried forever and **its whole partition stalled**. The symptom pointed straight at it: the two failing tests' keys (`dup-repo`, `poison-repo`) both hash to partition 0, and the passing one (`cache-repo`) to partition 1. **Fixed:** the template copies the configuration of Boot's `ProducerFactory`, which already has the right address everywhere (tests, compose, IDE).
- [x] **Dead letters went to a topic nobody reads.** Spring Kafka 4 changed `DeadLetterPublishingRecoverer`'s default destination from `<topic>.DLT` to `<topic>-dlt`. In the test the broker auto-creates topics, so poison pills quietly landed in `scan-results-dlt`. In compose auto-create is **off**, so every dead letter would have failed to publish and blocked its partition: the exact outage the DLT exists to prevent, triggered by a framework upgrade with no compile error. **Fixed:** the destination resolver is set explicitly to `<topic>.DLT`, same partition. The test's DLT reader now reads both partitions directly (no consumer group to join, no polling from another thread) and prints the DLT end offsets if it finds nothing.

How it was debugged without access to the CI log (downloading logs needs a signed-in user): a CI step that runs only on failure writes each failing test's report as an annotation on the commit, and annotations are public. Lesson: an integration test that never runs proves nothing, and defaults move between major versions, so pin the names your infrastructure depends on.

---

## 12. Which experiment tests which decision

How to run E1–E5 is in the [README](../README.md#experiment-knobs). E6 is a load test with `spring.threads.virtual.enabled` on and off; E7 is `os-sast eval` with and without `--ai-triage`. Write the prediction **before** running.

| # | Tests the decision |
|---|---|
| E1 kill consumer mid-write | At-least-once + idempotent writes; offset commit timing (`BATCH` vs `RECORD`) |
| E2 3 threads, 2 partitions, second container | Partitions cap parallelism; rebalancing |
| E3 Postgres down 2 min | ingest-api never touches Postgres; Kafka as buffer |
| E4 k6, 500 POSTs in 10 s | 202 + wait for ack under load; consumer lag and drain time |
| E5 Redis down | Redis is optional at runtime |
| E6 virtual threads, I/O vs CPU | Virtual threads only help while waiting on I/O |
| E7 eval with/without AI | The AI annotates, never deletes: did recall hold? |

In Kafka UI during E1–E4, watch: the committed offset per partition, the lag, and the group's members list during a rebalance.

---

## 13. Production follow-ups

Left out of the POC on purpose (along with Kubernetes and a production-grade UI).

| Gap | What I'd add | Why |
|---|---|---|
| One broker | 3 brokers, replication factor 3, `min.insync.replicas=2` | `acks=all` only protects data once there are replicas |
| Shared Java records | Schema registry with backward-compatibility checks | Independent deploys; old messages still readable |
| No auth | mTLS or a token per CI system on ingest-api | Anyone on the network can post fake scans |
| Dual-write risk (if ingest-api ever needs a DB) | Transactional outbox | Atomic "store + publish" without two-phase commit |
| Large results | Claim-check: payload in object storage, pointer in Kafka | Stay under the message size limit |
| Poison messages | DLT + alert + replay tool | Bad data costs one message, and someone notices |
| Observability | Consumer lag alert, p99 latency, DLT size | Lag is the first sign of a stuck or slow consumer |

---

## 14. Self-check questions

Answer first, then check the section.

1. Why 202 and not 201? → §5
2. What happens if you reply 202 before Kafka acks? → §5
3. Why key by `repoId`? What breaks if you add partitions later? → §6
4. 2 partitions, 3 consumer threads: how many work? → §6
5. The consumer crashes after the DB commit but before the offset commit. What happens? → §7
6. Why not Kafka exactly-once? → §7
7. Why must the scan run and its findings be in one transaction? → §7
8. One malformed message reaches the topic. What happens without a fix? → §7
9. Why delete the cache key instead of updating it? Why after the commit? → §9
10. Can the dashboard still show a stale build? For how long? → §9
11. Redis is down. What should the user see? → §9
12. Why can't the AI remove findings? → §10
13. A developer adds a line above a bug. What does the diff show, and why? → §4
14. Why do Kafka clients on the laptop and in Docker use different addresses? → §3
15. Why is the dead-letter topic name set explicitly instead of using the framework default? → §11
