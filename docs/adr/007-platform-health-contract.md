# ADR-007: Platform health and version contract

- Status: Accepted for R02 engineering slice
- Date: 2026-09-22

## Decision

The contract in `contracts/platform-health.json` is the authoritative shape for the three platform endpoints:

- `GET /health/live` always returns HTTP 200 with `{ "status": "UP" }` while the process can serve requests. It does not use a database connection.
- `GET /health/ready` checks a bounded PostgreSQL connection and the presence of the Flyway-created `platform_metadata` table. It returns HTTP 200 with `status: UP` only when both are available; database or migration failure returns HTTP 503 with a non-sensitive `DOWN` response. Recovery is observed on the next probe without an application restart.
- `GET /api/v1/version` returns `productName`, build `version`, and `commit`. Spring Boot build metadata is preferred; local builds without Git metadata use `unknown` rather than inventing a SHA.

Only the standard Actuator health endpoint is exposed in addition to these paths. Environment, heap dump, config, and other management endpoints remain closed.

## Consequences

Readiness is suitable for a load balancer or local orchestration check, while liveness avoids restarting a healthy process solely because PostgreSQL is temporarily unavailable. The response deliberately omits connection strings, credentials, and stack traces. Business API contracts remain out of scope for this round.
