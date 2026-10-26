# sast-platform

Backend + infra for the SAST pipeline POC: `ingest-api` → Kafka → `consumer` → Postgres, plus Jenkins and the k6 load test.

```
[Jenkins + os-sast] ─POST─► [ingest-api :8080] ─key=repoId─► [Kafka: scan-results, 2 partitions] ◄─pull─ [consumer] ─► [Postgres]
                            validate, wait for ack, 202                                          │        └─ bad message ─► scan-results.DLT
                                                                                                  └─► DEL Redis latest:{repoId}
```

The reasoning behind every choice is in `101-prep/pocs/sast-dashboard/DESIGN.md`.

## Modules and folders

| Path | What it is |
|------|-----------|
| `contracts/` | The ScanResult message: Java records with validation annotations, `scan-result.schema.json`, `CacheKeys` |
| `ingest-api/` | `POST /api/scan-results` → validate (400) → publish, key = repoId → wait for broker ack → **202** (or 503). Never touches Postgres |
| `consumer/` | `@KafkaListener`, concurrency 2 → one transaction, `ON CONFLICT DO NOTHING` → evict Redis. Retries transient errors forever, sends poison pills to `scan-results.DLT`. Owns the Flyway migrations |
| `Dockerfile` | One multi-stage image for either service (`--build-arg MODULE=...`) |
| `docker-compose.yml` | Kafka (KRaft), topic init, Kafka UI, Postgres, Redis; profiles `apps`, `ci`, `load` |
| `postgres/init/` | Creates the read-only `dashboard_ro` user for dashboard-api |
| `jenkins/` | Jenkins image with os-sast installed + configuration as code (the `samples` job) |
| `load-test/` | k6 burst test (E4) |

## Run

Prerequisites: Docker (Desktop with WSL integration on), Java 25, Maven. The repos `samples` and `os-sast` checked out next to this one.

```bash
# 1. infra only, run the apps from the IDE / mvn (fast edit loop)
docker compose up -d
mvn -q install -DskipTests
mvn -pl ingest-api spring-boot:run      # terminal 1
mvn -pl consumer spring-boot:run        # terminal 2

# 2. or everything in containers (needed for E1/E2: kill and scale the consumer)
docker compose --profile apps up -d --build

# 3. add Jenkins: http://localhost:8090 (admin / admin), job "samples" polls ../samples every 2 minutes
docker compose --profile apps --profile ci up -d --build
```

Send one scan by hand (scan result from os-sast):

```bash
curl -i -X POST localhost:8080/api/scan-results -H 'Content-Type: application/json' --data-binary @result.json
docker compose exec postgres psql -U sast -c "SELECT repo_id, build_id, finding_count FROM scan_runs"
```

Kafka UI: http://localhost:8081: messages, partitions, consumer group `scan-results-writer`, lag.

## Tests

```bash
mvn test      # unit tests: controller (202 / 400 / 503), listener ordering, Redis failure
mvn verify    # + ConsumerIT: real Kafka, Postgres, Redis via Testcontainers (needs Docker)
```

`ConsumerIT` proves the three core claims: the same build delivered twice is stored once, a new build evicts `latest:{repoId}`, and a poison pill goes to the DLT while the next message on that partition is still processed.

## Experiment knobs

| Experiment | How |
|-----------|-----|
| E1 kill mid-write | `CONSUMER_DEBUG_SLEEP=15s docker compose --profile apps up -d consumer`, POST a build, `docker compose kill consumer` while the log says "sleeping", start it again |
| E2 idle consumers | `CONSUMER_CONCURRENCY=3 docker compose --profile apps up -d consumer`, then `--scale consumer=2` |
| E3 Postgres down | `docker compose stop postgres` for 2 minutes while sending builds; watch lag in Kafka UI, then `start` it |
| E4 burst | `docker compose --profile load run --rm k6` |
| E5 Redis down | `docker compose stop redis`, load the dashboard |

## License

[Apache-2.0](LICENSE)
