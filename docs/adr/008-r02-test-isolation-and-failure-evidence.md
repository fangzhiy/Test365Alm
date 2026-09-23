# ADR-008: R02 temporary database isolation and failure evidence

- Status: Accepted for R02 engineering verification
- Date: 2026-09-23

## Context

The first R02 integration test used a configured JDBC URL and dropped
`platform_metadata` in that database. Restoring one row in `finally` did not
prove that other data was preserved and could have damaged a developer or
external database if configuration was wrong. The outage script also trusted a
port response without proving which process and build served it.

## Decision

- Backend integration tests use Testcontainers PostgreSQL `2.0.5` with the
  pinned PostgreSQL 17.11 image. Spring datasource properties are injected from
  the container at runtime; the integration profile does not read `.env` or
  `TEST365ALM_IT_DATASOURCE_*`.
- The destructive isolation test owns a target container and a separate
  control container containing two sentinel rows. Only the target is mutated;
  the control rows must remain unchanged. Testcontainers/Ryuk owns cleanup.
- Manual outage and migration-failure scripts use a dedicated `.env.r02-test`,
  unique Compose project, and bounded commands. They record PID, build commit,
  Compose project and container identity. They refuse external JDBC URLs,
  occupied backend ports, unknown listeners, non-loopback listeners, early
  process exit, or unconfirmed resource ownership.
- The migration-failure script adds `V2__intentional_failure.sql` only in a
  temporary filesystem location and starts an independent JVM. The formal
  migration directory is unchanged; success requires a non-zero exit and a
  log that identifies Flyway V2 failure, while ready=200 is rejected.

## Alternatives rejected

- Reusing the daily development database and restoring a default row: cannot
  prove isolation and risks user data.
- Treating a project name, profile, or `flyway.clean-disabled` as ownership:
  these are labels, not resource identity.
- Allowing CI `observe` mode or ignoring listener enumeration: would turn
  unverified evidence into a false pass.

## Consequences

Docker is required for integration and failure-injection tests, but a clean
checkout no longer needs a pre-existing PostgreSQL service or developer
credentials. CI records the checkout SHA and uploads command/test/log reports;
the code SHA and any later documentation delivery SHA remain distinct.
