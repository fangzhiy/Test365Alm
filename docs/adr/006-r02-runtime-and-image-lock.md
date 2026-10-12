# ADR-006: R02 runtime and PostgreSQL image lock

- Status: Accepted for R02 engineering slice
- Date: 2026-09-22

## Decision

Keep the planned Spring Boot 4.1.1 + Maven, React/TypeScript + Vite, and PostgreSQL direction. The reproducible development database is PostgreSQL `17.11` pinned to digest `sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232`. Compose publishes the database only on `127.0.0.1` and uses a named volume; the application uses Flyway migrations and does not use automatic DDL or H2 as a substitute.

The target CI/runtime toolchain is Java 21 and Node 24 LTS. The current Windows workstation has Java 17.0.2 and Node 26.0.0, so local verification is explicitly a compatibility check, not proof that the target toolchain is installed. GitHub Actions selects Temurin 21 and Node 24.

The Maven Wrapper is used for server verification. The checked-in wrapper launches Maven 3.9.16 in this repository; the local system Maven is not part of the reproducible command. The wrapper executable bit is tracked for Linux checkouts.

## Alternatives rejected

- `latest` image tags: not reproducible.
- H2 or disabled datasource/Flyway: would not verify PostgreSQL behavior or controlled migrations.
- Broad CORS/public database binding: outside the development-only authorization boundary.
- Replacing the Spring/React/PostgreSQL direction: not needed for this slice and would increase migration risk.

## Follow-up

Install or provide Java 21 and Node 24 for an independent local rerun. Reconfirm the image digest when the database patch level is intentionally changed.
