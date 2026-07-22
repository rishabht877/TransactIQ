# TransactIQ

A distributed payment-processing system with event streaming, idempotent processing, an
LLM-based fraud triage layer, and full observability.

> **Status:** built in phases. This is **Phase 0 — scaffold + infra**. Full architecture
> docs, the honest *effectively-once (not exactly-once)* correctness explanation, and the
> *why an LLM for fraud triage (not detection)* rationale land in Phase 7.

## Tech stack (locked)

| Concern              | Choice                                              |
|----------------------|-----------------------------------------------------|
| Language / framework | Java 21, Spring Boot 3.x                            |
| Build                | Gradle (Kotlin DSL), multi-module                   |
| Messaging            | Apache Kafka (KRaft, no Zookeeper), Spring Kafka    |
| Datastore            | MySQL 8 (business data + outbox + processed-events) |
| Cache / dedup        | Redis                                               |
| LLM                  | LangChain4j + Ollama (local, swappable) — Phase 3   |
| Frontend             | React (Vite) — Phase 5                              |
| Observability        | Micrometer + Actuator → Prometheus → Grafana        |
| Local orchestration  | docker-compose                                      |
| Prod orchestration   | Kubernetes (Minikube) + Helm — Phase 6              |

## Repo layout

```
services/
  payment-gateway/      REST edge — idempotency, outbox, publish (Phases 1–2)
  payment-processor/    idempotent Kafka consumer + retry/DLQ (Phases 1–3)
  fraud-service/        LangChain4j AI triage (Phase 3)
dashboard/              React (Vite) UI (Phase 5)
ops/
  prometheus/           scrape config
  grafana/provisioning/ datasource + dashboards
docker-compose.yml      local infra: Kafka, MySQL, Redis, Prometheus, Grafana
```

## Phase 0 — run it

**1. Start infra** (Docker Desktop must be running):

```bash
docker compose up -d
docker compose ps          # all services should reach "healthy"
```

Services and ports: Kafka `9092`, MySQL `3307` (host) → `3306` (container), Redis `6379`,
Prometheus `9090`, Grafana `3000` (admin/admin).

> **Kafka healthcheck note:** the KafkaCLI path can vary by image tag. If Kafka shows
> `unhealthy`, first confirm the binary path with
> `docker exec transactiq-kafka ls /opt/kafka/bin` — it is far more likely a healthcheck
> path issue than a broken broker.

**2. Run the services** (each in its own terminal — they run on the host in Phase 0):

```bash
./gradlew :services:payment-gateway:bootRun      # http://localhost:8080
./gradlew :services:payment-processor:bootRun    # http://localhost:8081
./gradlew :services:fraud-service:bootRun        # http://localhost:8082
```

**3. Verify health & metrics:**

```bash
curl -s localhost:8080/actuator/health           # {"status":"UP"}
curl -s localhost:8081/actuator/health
curl -s localhost:8082/actuator/health
curl -s localhost:8080/actuator/prometheus | head # Micrometer metrics
```

Then open Grafana at http://localhost:3000 and confirm the **Prometheus** datasource is green.

## Submitting a payment (Phases 1–2)

```bash
# Idempotency-Key is REQUIRED. Re-sending the same key never double-charges.
curl -i -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"amount":49.99,"currency":"USD","customerId":"cust-1","cardLast4":"4242","country":"US","merchant":"Acme"}'
# -> 202 Accepted {"paymentId":"...","status":"RECEIVED"}, becomes PROCESSED asynchronously.

curl -s localhost:8080/api/payments        # list payments and their statuses
```

## Correctness model (the part interviewers probe)

End-to-end guarantee: **effectively-once** — idempotent processing over Kafka's at-least-once
delivery. We deliberately do **not** claim exactly-once (unachievable across a DB and a broker).

- **Idempotency at the edge** — `Idempotency-Key` header → Redis `SETNX` fast-path (hot dedup);
  the **UNIQUE constraint on `payments.idempotency_key`** is the durable source of truth. Same
  key → same payment id, never a second charge.
- **Transactional outbox** — the payment row and an `outbox` row are written in ONE DB
  transaction (no dual-write). A `@Scheduled` relay publishes unpublished rows to Kafka with
  `SKIP LOCKED`, stamping `published_at` only on broker ack. Both gateway and processor use it.
- **Idempotent consumer** — the processor claims each `event_id` in `processed_events`
  (PRIMARY KEY) in the SAME transaction as the business effect, so a re-delivered event is
  detected and skipped.
- **Retry + DLQ** — `@RetryableTopic` gives non-blocking retries
  (`payments.requested-retry-0/1/2`); poison messages land in `payments.requested-dlt`.

## Tests

Integration tests exercise the real Kafka + idempotency paths and **require the compose infra
to be running** (`docker compose up -d`). They use an in-JVM EmbeddedKafka broker + the compose
MySQL/Redis. (Testcontainers would be more hermetic, but this machine's Docker Desktop 29 drops
JDBC connections to Testcontainers' ephemeral ports — see `ReplayIdempotencyIT` Javadoc.)

```bash
./gradlew test                 # unit + integration tests (IdempotencyIT, ReplayIdempotencyIT)

# Soak test: fire N payments, SIGKILL the processor mid-run, restart, assert no loss / no dupes.
# Requires the gateway running on :8080.
./gradlew :services:payment-gateway:bootRun &   # in one terminal
scripts/soak-test.sh 200                         # in another
```

- `ReplayIdempotencyIT` — the same event delivered 3× produces exactly one business effect.
- `IdempotencyIT` — the same Idempotency-Key posted twice returns one payment / one outbox row.
- `soak-test.sh` — crash-recovery: **zero loss, zero duplicates** across a processor SIGKILL.

## Phased build plan

- **Phase 0** — scaffold + infra ✅
- **Phase 1** — happy-path payment flow ✅
- **Phase 2** — idempotency, transactional outbox, DLQ + retry ✅
- **Phase 3** — LLM fraud triage (LangChain4j + Ollama)
- **Phase 4** — observability dashboards
- **Phase 5** — React dashboard
- **Phase 6** — Kubernetes + Helm (Minikube)
- **Phase 7** — docs + interview defense notes
