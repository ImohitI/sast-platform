# sast-platform

Backend for the SAST pipeline POC: `ingest-api` → Kafka → `consumer` → Postgres.

```
[Jenkins + os-sast] ─POST─► [ingest-api :8080] ─key=repoId─► [Kafka: scan-results, 2 partitions] ◄─pull─ [consumer] ─► [Postgres]
                                                                                                                 └─► evict Redis latest:{repoId}
```

## Modules

| Module | What it does |
|--------|-------------|
| `contracts` | The scan-result message (Java records). Shared because ingest-api and consumer change together when the format changes. |
| `ingest-api` | `POST /api/scan-results` → validate → publish to Kafka → **202**. Never touches Postgres. |
| `consumer` | `@KafkaListener` → one transaction, `ON CONFLICT DO NOTHING` → evict Redis. Owns the Flyway migrations. |
| `load-test/` | k6 script for experiment E4 (later). |

`dashboard-api` and the Angular UI live in the separate `sast-dashboard` repo.

## Run

```bash
docker compose up -d
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic scan-results --partitions 2 --replication-factor 1

mvn -q install -DskipTests
mvn -pl ingest-api spring-boot:run      # terminal 1
mvn -pl consumer spring-boot:run        # terminal 2
```

Kafka UI: http://localhost:8081

## Status

- [ ] Step 0: `contracts/src/main/resources/scan-result.schema.json` (JSON Schema for the message; the records are already in `contracts`)
- [ ] Step 3: `ScanResultController` (202 + publish) and `ScanResultListener` (idempotent write + Redis evict)
- [ ] Step 3 done when: POST → 202 → message in Kafka UI → row in Postgres; same build twice → still 1 row
