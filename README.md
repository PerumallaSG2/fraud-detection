# Fraud Detection

[![CI](https://github.com/PerumallaSG2/fraud-detection/actions/workflows/ci.yml/badge.svg)](https://github.com/PerumallaSG2/fraud-detection/actions/workflows/ci.yml)

A REST API that scores card transactions for fraud. A client POSTs a transaction (card, amount, merchant,
category, location, timestamp); it is queued on Kafka and the API answers `202 Accepted` right away. A consumer runs
it through a set of rules, adds up the points of the rules that fired into a risk score from 0 to 100, and decides:
APPROVE, REVIEW (a person should look at it) or BLOCK. The score, decision and the rules that fired are stored in
PostgreSQL, where the client fetches them. Every REVIEW or BLOCK opens an alert, which an analyst resolves as FRAUD or
FALSE_POSITIVE.

One Spring Boot application with a rules engine, Kafka between ingestion, scoring and alerts, PostgreSQL, and Redis
for the velocity counter. There is no ML yet.

## Architecture

```mermaid
flowchart TD
    Client -->|POST /api/transactions| Controller
    Controller -->|validated request| Accept["TransactionService.accept<br/>assign id"]
    Accept -->|TransactionSubmitted, key cardId| T[["Kafka: transactions"]]
    Controller -.->|202 + Location| Client
    T -->|group fraud-scoring| Process["TransactionService.process"]
    Process -->|1. score| FraudScoringService
    FraudScoringService --> Rules["FraudRule beans<br/>OddHours, RiskyMerchant, AmountSpike,<br/>Velocity, GeoMismatch"]
    Rules -.->|lazy card history| Repository
    Rules -.->|lazy transaction count| Redis[(Redis)]
    Process -->|2. save with rule hits| Repository
    Process -->|3. after commit: TransactionDecided, key cardId| D[["Kafka: decisions"]]
    D -->|group fraud-alerts| AlertService["AlertService.openIfFlagged<br/>REVIEW/BLOCK only"]
    AlertService --> Repository
    Repository --> Postgres[(PostgreSQL)]
    Client -->|GET /api/transactions/id: 404 until scored| Controller
    Analyst -->|GET, PATCH /api/alerts| AlertController --> AlertService
    T -.->|after 3 failed attempts| TDLT[["transactions.DLT"]]
    D -.->|after 3 failed attempts| DDLT[["decisions.DLT"]]
```

- `controller`: HTTP, validation, error responses. It never touches repositories.
- `messaging`: the events, topic names, the publisher, and two thin listeners that hand each message to a service
  (the role a controller plays for HTTP).
- `service`: `TransactionService` accepts (publishes) and processes (scores, saves, publishes the decision);
  `FraudScoringService` runs the rules and maps the score to a decision; `AlertService` opens, lists and resolves
  alerts.
- `rules`: one class per rule, each implementing `FraudRule`. A new rule is a new class; the engine is unchanged.
- `repository`, `domain`: Spring Data JPA and the entities. DTOs are separate records and entities are never returned.

## Rules and scoring

Score is the sum of the points of triggered rules, capped at 100.

| Rule | Triggers when | Default points |
|---|---|---|
| `AMOUNT_SPIKE` | Amount is more than 3.0 x the average of the card's last 20 non-BLOCK transactions. With fewer than 5 such transactions, it triggers above 5000. | 40 |
| `RISKY_MERCHANT` | Merchant category is GAMBLING, CRYPTO or WIRE_TRANSFER (case-insensitive). | 30 |
| `ODD_HOURS` | Local hour is in [00:00, 05:00), read in the timestamp's own UTC offset. | 20 |
| `VELOCITY` | The card has more than 5 transactions in the 60 seconds ending at this transaction's timestamp, counting this one (so the 6th within 60s). Blocked attempts count. If Redis is down the rule does not fire (fails open). | 40 |
| `GEO_MISMATCH` | "Impossible travel": the distance from the card's previous non-BLOCK transaction (haversine), divided by the time between the two transactions' own timestamps, is above 900 km/h. Hops under 50 km never trigger. | 40 |

| Score | Decision |
|---|---|
| below 40 | APPROVE |
| 40 to 70 (both inclusive) | REVIEW |
| above 70 | BLOCK |

Points, thresholds, the score cap, the risky categories, the odd-hours window, the spike parameters, the velocity limit and window, and the geo-mismatch speed limit and minimum distance are all under
`fraud.*` in `src/main/resources/application.yml`.

## Tech stack

Java 21, Spring Boot 3.5.6, Maven, Spring Data JPA (Hibernate), Bean Validation, Flyway, PostgreSQL 16,
Redis 7 (Spring Data Redis, Lettuce), Apache Kafka 3.9 (Spring for Apache Kafka; one KRaft node, `apache/kafka`
image), Spring Boot Actuator, Docker Compose. Tests use JUnit 5, Mockito, AssertJ, Awaitility and Testcontainers
(PostgreSQL, Redis, Kafka).

## How to run

Needs Java 21, Maven and Docker. Kafka must be running to accept transactions (without it POST returns `503`).
Redis must be running for velocity to count; without it the app still scores, but the velocity rule never fires and
a WARN is logged.

```
docker compose up -d --wait   # Postgres 16 on host port 5433, Redis 7 on 6380, Kafka on 9094
mvn spring-boot:run           # app on localhost:8080; Flyway creates the schema, the app creates the topics
```

Submit a transaction:

```
curl -i -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' -d '{
  "cardId":"c1","amount":120.50,"merchant":"Casino","merchantCategory":"GAMBLING",
  "city":"Austin","latitude":30.27,"longitude":-97.74,"timestamp":"2026-10-25T02:30:00-05:00"}'
```

Response: `202 Accepted` with a `Location` header. The transaction is queued, not scored yet (copied from a run
against local Docker Compose):

```
HTTP/1.1 202
Location: http://localhost:8080/api/transactions/6fd73df6-d22e-4cf6-b629-3ef46ddedb74

{"id":"6fd73df6-d22e-4cf6-b629-3ef46ddedb74","status":"PENDING"}
```

`202` is sent only after Kafka has acknowledged the write (`acks=all`), so an accepted transaction is not lost. If
Kafka can't be reached, POST returns `503` ("Transaction could not be accepted, try again") within about 3 seconds
and nothing is queued, so the client can retry. A `400` (validation) publishes nothing.

Poll the `Location`. It is `404` until the transaction is scored (usually a few milliseconds; in that run the 5th
poll, 50 ms apart, got it), then `200` with the decision (`400` if the id is not a UUID):

```
curl localhost:8080/api/transactions/6fd73df6-d22e-4cf6-b629-3ef46ddedb74
```

```
{
  "id": "6fd73df6-d22e-4cf6-b629-3ef46ddedb74",
  "cardId": "c1",
  "amount": 120.50,
  "merchant": "Casino",
  "merchantCategory": "GAMBLING",
  "city": "Austin",
  "latitude": 30.27,
  "longitude": -97.74,
  "timestamp": "2026-10-25T02:30:00-05:00",
  "riskScore": 50,
  "decision": "REVIEW",
  "triggeredRules": [
    {"rule": "RISKY_MERCHANT", "points": 30, "reason": "Merchant category GAMBLING is high risk"},
    {"rule": "ODD_HOURS", "points": 20, "reason": "Transaction at 02:30 local time is within odd hours (00:00-05:00)"}
  ],
  "createdAt": "2026-10-31T18:51:21.890440Z"
}
```

A `404` means "not scored yet", "unknown id" or "dead-lettered" (see below); the API doesn't tell them apart. There is
no PENDING row in the database: writing one would put PostgreSQL back on the POST path and make POST write to two
systems that can disagree.

### Alerts

A REVIEW or BLOCK transaction opens an alert, a moment after the transaction is scored (a separate consumer reads
the decision from Kafka). List alerts newest first, optionally by status (`OPEN`, `FRAUD`,
`FALSE_POSITIVE`; all statuses if omitted). `page` starts at 0 and `size` is 1 to 100 (default 20):

```
curl 'localhost:8080/api/alerts?status=OPEN&page=0&size=20'
```

```
{
  "alerts": [
    {"id": "…", "transactionId": "…", "cardId": "c1", "riskScore": 50, "decision": "REVIEW",
     "status": "OPEN", "note": null, "createdAt": "…", "resolvedAt": null}
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

Resolve an OPEN alert as `FRAUD` or `FALSE_POSITIVE`, with an optional note of up to 500 characters. The response is
the updated alert (`200`). An unknown id is `404`; an alert that is already resolved is `409`, and its first outcome is kept:

```
curl -X PATCH localhost:8080/api/alerts/{id} -H 'Content-Type: application/json' \
  -d '{"status":"FRAUD","note":"Cardholder confirmed the card was stolen"}'
```

Errors share one shape: `{status, error, message, fieldErrors}`, where `fieldErrors` is `[{field, message}]` and is
empty except for validation errors. Responses never include stack traces. Stop with `docker compose down`
(add `-v` to delete the data). DB settings can be overridden with `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, and
Redis with `REDIS_HOST`, `REDIS_PORT`, and Kafka with `KAFKA_HOST`, `KAFKA_PORT` (default 9094, so it doesn't clash
with a local Kafka on 9092). Kafka keeps no volume: topics and offsets are gone after `docker compose down`.

## Delivery guarantees

- **At least once.** The scoring consumer commits a message's offset only after it has scored, saved and published
  the decision. A crash or failure anywhere before that redelivers the message.
- **Idempotent consumers.** A redelivered transaction whose id is already saved is not scored or saved again; its
  decision is just published again (in case the earlier attempt saved and then failed to publish). The alert consumer
  skips decisions whose transaction already has an alert, and the unique `transaction_id` stops a racing duplicate.
  The Redis velocity entry is keyed by transaction id, so a retry isn't counted twice.
- **Decision after commit.** `decisions` is published only after the transaction row commits, so the alert consumer
  always finds the row (the alert has a foreign key to it). No outbox table is needed: redelivery covers a lost
  publish.
- **Retry, then dead-letter.** A failing message is retried in place 3 times in total, 1 second apart; then it goes to
  `<topic>.DLT` (same partition, with the exception and original offset in its headers), an ERROR is logged and the
  partition moves on. A message that can't be deserialized goes there at once. A dead-lettered transaction stays
  `404`. Replaying a DLT is manual for now.
- **Per-card ordering.** Both topics are keyed by card id, so one card's messages share a partition, and each
  partition is consumed by one thread in offset order. The producer is idempotent, so its own retries can't reorder,
  and retries are blocking (no retry topics), so a failing message can't be overtaken. This matters because the spike
  baseline and geo mismatch read the card's previously saved transactions: the results match what a synchronous API
  would give for the same arrival order. Across cards nothing is ordered, and nothing needs it. Changing the
  partition count (`fraud.messaging.partitions`, 3) of existing topics re-maps cards and breaks this once.

### Trade-offs of going asynchronous

Gains: POST's latency and availability depend only on Kafka, not on PostgreSQL, Redis or scoring cost; bursts queue
in Kafka instead of piling onto the database; scoring scales with partitions and consumers; a failing alert no
longer fails or rolls back scoring; events can be replayed, and new consumers can subscribe without touching POST.

Costs:

- The caller doesn't get the decision in the response and has to poll. That suits post-authorization monitoring,
  not inline card authorization, which needs a synchronous answer.
- Eventual consistency: GET is `404` briefly, and alerts lag behind transactions. Before, a transaction and its
  alert were one database transaction; now they are joined by redelivery.
- At-least-once delivery makes every consumer idempotent.
- Poison messages and outages land in a DLT that someone has to watch and replay. Known limit: a long database
  outage dead-letters everything that arrives during it, not only bad messages.
- Blocking retries hold up a failing message's partition (about a third of the cards) for about 2 seconds.
- One more piece of infrastructure (about 217 MB of image, a 256 MB heap) and slower, asynchronous tests.

## Testing

`mvn test` runs 134 tests, all passing at the last run (0 skipped). It needs Docker: tests that touch the database run
against real PostgreSQL 16 (`postgres:16-alpine`, the same image as Docker Compose), the full-app tests also against
Kafka (`apache/kafka:3.9.2`, same as Compose) and the velocity tests against Redis, all through Testcontainers.
Without Docker every container test fails rather than skips. Rule, service, mapper and controller tests use no
containers and take well under a second in total.

| Layer | Tests | Covers |
|---|---|---|
| Rules | 39 | Each rule's boundaries, offsets, case-insensitivity, history baseline, BLOCK exclusion; velocity limit, one count call, empty count; geo distance, speed limit, out-of-order timestamps |
| Scoring service | 11 | Point sum, rule order, cap at 100, decisions at 39/40/70/71, history lookup excludes BLOCK, velocity count and fail-open on Redis errors |
| Transaction service and mapper | 11 | Accept publishes under the returned id, publish failure propagates; process scores then saves then publishes after commit, rule hits, an already saved id is not rescored but republished, scoring failure saves and publishes nothing; 404, 2-decimal amounts |
| Alert service | 9 | REVIEW/BLOCK open an alert and APPROVE doesn't, a redelivered decision opens no second alert, newest-first paging with and without status, resolve, 404, 409 keeps the first outcome |
| Repositories (`@DataJpaTest`, real PostgreSQL) | 9 | Save/load with ordered rule hits, offset round trip, history query; alert save/load, status filter with newest-first paging, one alert per transaction, exists by transaction |
| Velocity counter (`@DataRedisTest`, real Redis) | 9 | Window bounds, transaction timestamps, cards isolated, same id counted once, old entries dropped, TTL |
| Controllers (`@WebMvcTest`) | 24 | 202 with `Location` and PENDING body, 503 when Kafka doesn't accept, 400 cases (nothing published), 404, 405, 500 without leaked details; alert list params and shape, PATCH 200/400/404/409 |
| End to end (`@SpringBootTest`, real HTTP, Kafka and PostgreSQL) | 7 | POST 202 then GET round trip, offset kept through Kafka, rule order, spike scenario, blocked baseline, impossible travel (velocity mocked) |
| Alerts end to end (real HTTP, Kafka and PostgreSQL) | 5 | REVIEW and BLOCK open alerts, APPROVE doesn't, newest first, resolve then 409, 404 |
| Kafka processing (real Kafka and PostgreSQL) | 5 | A duplicated message is scored, saved and alerted once; a transient failure is retried with nothing dead-lettered; a permanent one is dead-lettered after 3 attempts and the partition moves on; unreadable JSON is dead-lettered unscored; one card's 10 transactions are processed in order |
| Velocity end to end (real HTTP, Kafka, PostgreSQL and Redis) | 3 | 6th within 60s fires and reviews, timestamp not server time, blocked attempts count |
| Application context | 2 | Context loads, health endpoint |

Every database test runs Flyway's V1 to V3 on a fresh PostgreSQL 16 and Hibernate validates the schema against it,
so the suite checks the same SQL and types that production uses. Each distinct Spring test context starts its own
containers. The full-app tests share one context (`AbstractKafkaIntegrationTest`), so one Kafka serves them all; the
velocity tests have their own (real Redis), and with it their own Kafka, so the two contexts' consumers never take
each other's messages.

## Key design decisions

- **Blocked transactions are excluded from the spike baseline.** A fraudulent amount that was blocked must not raise
  the average and make the next large amount look normal. An end-to-end test covers this.
- **Score before save.** The transaction is scored first and saved after, in one database transaction, so it
  never counts toward its own baseline.
- **Lazy history in `RuleContext`.** Rules do not inject repositories. The context carries a lookup function that
  runs the query only if a rule asks for history. It is always built with the BLOCK-excluding query.
- **Local offset for odd hours.** 02:30 at -05:00 is 02:30 for the cardholder, not 07:30 UTC. Jackson's default
  conversion to UTC is turned off, and V2 stores the original offset, so GET returns the timestamp as submitted.
- **Amounts with more than 2 decimals are rejected (400), not rounded,** so the scored amount and the stored amount
  are always identical.
- **Flyway owns the schema.** Hibernate only validates. Schema changes are new `V<n>` files; applied ones are never edited.
- **Thresholds live in one place** (`FraudProperties`, bound from `application.yml`), not in rules or services.
- **Velocity fails open.** If Redis is down, the velocity rule is skipped with a WARN and the other rules still
  score, so a cache outage doesn't decline every customer. The health endpoint shows Redis as down.
- **Velocity counts blocked attempts.** Card testing is many fast attempts that get blocked, so they must count.
  This is the opposite of the spike baseline, which excludes them.
- **Alerts are opened by a Kafka consumer of `decisions`,** not in the scoring transaction. A failing alert no
  longer rolls back the transaction; instead the decision is retried and, after 3 attempts, dead-lettered to
  `decisions.DLT`, where it can be replayed.
- **Kafka's JSON uses the application's `ObjectMapper`.** spring-kafka's own mapper would shift timestamps to UTC
  and break odd hours; an end-to-end test checks the offset survives.
- **Alerts copy the card, score and decision** from their transaction, so the list needs no join. One alert per
  transaction (unique `transaction_id`).
- **An alert is resolved once.** Resolving locks the row (`SELECT ... FOR UPDATE`), so of two concurrent resolves the
  second gets `409`. `OPEN` is not a valid resolution.
- **One id per transaction.** The id is generated before scoring and used as both the Redis entry and the database
  key. The Redis update runs as one Lua script, so concurrent requests for the same card can't miscount. Known limit:
  if the save fails after scoring and the message ends up dead-lettered, that attempt stays counted for up to 60
  seconds. (A retry that succeeds isn't counted twice: same id.)

## Roadmap

Not built yet:

- Client idempotency key, so a retried request is not scored, counted or stored twice
- Splitting ingestion, scoring and alerts into separate deployable services (they are already decoupled by Kafka)
- Replaying dead-lettered messages
- AI-generated explanations of decisions with Ollama (Bedrock optional)
- Prometheus and Grafana monitoring
- k6 load tests
- Replaying a Kaggle fraud dataset through the API

