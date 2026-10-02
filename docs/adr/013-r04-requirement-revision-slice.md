# ADR-013: R04 requirement identity and immutable revision slice

## Status

Accepted for R04-M07-001 only.

## Context

The first M07 business slice must provide a project-scoped requirement that
can be created, read, edited with optimistic concurrency, and inspected as an
immutable history. The existing V1--V6 migrations and the R03 project/RLS
boundary are already shared by the application and must not be rewritten.

## Decision

- V7 adds `requirement`, `requirement_revision`, a per-project number
  allocator, `requirement_idempotency`, and the generic `outbox_event` table.
- The stable requirement UUID and display number remain separate from the
  revision UUID/number. Every cross-scope reference repeats
  `(tenant_id, project_id)` and is protected by composite foreign keys.
- A create or update transaction authorizes the current project member,
  allocates the display number with a row lock (not `MAX()+1`), appends the
  revision, advances the current pointer, and writes audit/outbox/idempotency
  records in the same transaction. A failed write rolls all of these back.
- Requirement revisions have insert/select grants only for the restricted
  runtime role. Requirement identity updates are limited to the service-owned
  current pointer, row version, and timestamp columns. Flyway/migration roles
  retain schema ownership.
- `PROJECT_ADMIN` and `PROJECT_MEMBER` receive distinct
  `requirement:create`/`requirement:update` actions; all active project
  members receive `requirement:read` and `requirement:history:read`.
  `PROJECT_VIEWER` is read-only. Tenant membership alone is not project
  access.
- HTTP writes require same-origin CSRF and an `Idempotency-Key`; PATCH also
  requires a concrete quoted `If-Match` ETag. Missing/wildcard ETags return
  428 and stale versions return 412 after authorization. The actual response
  contract is `contracts/requirements.json`.
- The UI receives project ID and permissions only from the existing project
  context. It does not accept manually entered tenant/project scope, clears
  stale data when the project changes, and keeps a draft after a 412 conflict.

## Consequences and limits

This is intentionally root-level plain-text requirements only. Parent/tree
editing, rich text, attachments, reviews, traceability, import/export, AI and
other M07/M04--M06 work remain planned. Real PostgreSQL/Testcontainers
integration is required in CI; this Windows environment cannot initialize the
Docker JNA named-pipe client, so local integration results must remain
NOT_RUN/blocked rather than being inferred from unit tests.
