# ADR-007: Platform health and version contract

- Status: Accepted for R02 engineering slice
- Date: 2026-09-22

## Decision

The contract in `contracts/platform-health.json` is the authoritative shape for the three platform endpoints:

- `GET /health/live` always returns HTTP 200 with `{ "status": "UP" }` while the process can serve requests. It does not use a database connection.
- `GET /health/ready` performs a bounded, read-only PostgreSQL connection check and verifies the migration-owned platform structure plus the successful Flyway V1 history row. It returns HTTP 200 with `status: UP` only when both are available. A connection failure returns HTTP 503 with `database: DOWN, migration: UNKNOWN`; an established connection whose required structure is absent returns HTTP 503 with `database: UP, migration: NOT_APPLIED`; an established connection whose structure cannot be checked returns HTTP 503 with `database: UP, migration: UNKNOWN`. Recovery is observed on the next probe without an application restart.
- `GET /api/v1/version` returns `productName`, build `version`, and `commit`. Spring Boot build metadata is preferred; local builds without Git metadata use `unknown` rather than inventing a SHA.

Only the standard Actuator health endpoint is exposed in addition to these paths. Environment, heap dump, config, and other management endpoints remain closed.

## Consequences

Readiness is suitable for a load balancer or local orchestration check, while liveness avoids restarting a healthy process solely because PostgreSQL is temporarily unavailable. The response deliberately omits connection strings, credentials, and stack traces. The JDBC connection acquisition/validation and metadata query each use the configured bounded readiness timeout; they are separate operation budgets, not a claim that the entire HTTP request is limited to one such interval. Business API contracts remain out of scope for this round.
