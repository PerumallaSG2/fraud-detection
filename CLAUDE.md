# Fraud Detection System

Real-time card transaction fraud detection. Transactions arrive via REST, are scored by a rules engine, and receive a risk score (0-100) and a decision.

## Stack

- Java 21, Spring Boot 3, Maven
- PostgreSQL (persistence), Redis (later: velocity counters/caching), Kafka (later)
- Docker Compose for local infra

## Scoring

Rules: velocity, amount spike, geo mismatch, risky merchant, odd hours.

| Risk score | Decision |
|------------|----------|
| < 40       | APPROVE  |
| 40-70      | REVIEW   |
| > 70       | BLOCK    |

Boundaries: 40 and 70 are both REVIEW (`< 40` approve, `> 70` block). Keep thresholds in one place (config or a single constant), not scattered across rules or services.

## Roadmap

- **Phase 1 (current):** single Spring Boot app, rules engine + PostgreSQL. **No Kafka yet.**
- **Later:** split into services via Kafka; AI-generated explanations via Ollama (Bedrock optional); Prometheus/Grafana monitoring.

Don't add Phase 2+ pieces (Kafka, Ollama, metrics stack) until asked. Design Phase 1 so they can slot in later, but don't build speculative abstractions.

## Architecture & Conventions

- Layers: `controller -> service -> repository`. Controllers don't touch repositories; repositories don't contain business logic.
- DTOs are separate from JPA entities. Never expose entities in the API; map at the service/controller boundary.
- Every fraud rule implements the `FraudRule` interface. Adding a rule = a new class implementing it, with no changes to the engine's core logic.
- Constructor injection only (no field or setter `@Autowired`).
- Keep changes small and focused. One concern per change.
- **Do not add libraries/dependencies without asking first.**

## Testing & Definition of Done

- Every feature needs tests (unit tests for rules/services; slice or integration tests for controllers/repositories).
- Run `mvn test` before saying a task is done, and report the actual result. If tests fail or weren't run, say so.

## Commands

```
mvn test                  # run all tests (required before finishing a task)
mvn spring-boot:run       # run the app locally
docker compose up -d      # start local infra (Postgres, etc.)
```

Update this section as the project gains real build/run details (profiles, ports, env vars).

## Working Agreements

- Ask before: adding dependencies, changing the scoring thresholds, or changing the public API contract.
- Prefer matching existing code style and naming over introducing new patterns.
