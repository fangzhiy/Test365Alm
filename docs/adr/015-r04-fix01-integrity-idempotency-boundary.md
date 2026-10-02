# ADR-015: R04 FIX01 integrity, runtime write boundary and idempotent replay

## Status

Accepted for the R04-M07-001 first requirement slice.

## Context

The initial requirement migration protected each UUID independently, and its
project-member predicate also matched read-only viewers. A response-loss retry
could therefore replay against later state, while a failed audit or Outbox
write needed an explicit proof that the whole business transaction rolled back.

## Decision

- V8 is an additive Flyway migration. Before adding constraints it rejects
  existing out-of-scope current pointers, revision references, aggregate IDs,
  and idempotency references with a constraint SQLSTATE; it does not rewrite
  V1--V7 history.
- Every requirement/revision/outbox/idempotency reference repeats the tenant,
  project and requirement scope. The current pointer, Outbox aggregate and
  revision, and idempotency result pair are enforced by composite constraints.
- Runtime writes require an active project member with `PROJECT_MEMBER` or
  `PROJECT_ADMIN`. Viewers retain reads but cannot write the requirement slice,
  alter immutable revisions or truncate events. The runtime role remains
  separate from migration ownership and has no `BYPASSRLS` requirement.
- Idempotency keys are scoped to tenant, project, principal and route. Expired
  rows are removed only at a matching claim, a changed request hash is a 409,
  and completed rows contain a response snapshot. Replays first re-check
  authorization and then return that snapshot; a new business intent gets a
  new key. Existing completed V7 rows are backfilled where their immutable
  requirement/revision reference is available; in-progress rows remain
  explicitly unresolved.
- Audit and Outbox writes stay inside the create/update transaction. Real
  PostgreSQL tests revoke each insert privilege in an isolated container and
  verify requirement, revision, allocator, idempotency, audit and Outbox state
  rolls back together. A two-client service test uses one ETag and expects one
  commit plus one 412.
- The front end treats ETag as server data, keeps stable intent keys across a
  response-loss retry, and guards read/write completion by session, project,
  requirement and operation round. A 412 preserves the title/body draft.

## Consequences and limits

The V8 migration and Testcontainers suite must be exercised on CI where this
workstation's Docker/JNA named-pipe permission prevents local startup. The
legacy snapshot backfill cannot reconstruct a response field that V7 never
stored; newly completed FIX01 intents use the exact response snapshot. This
round does not add requirement trees, reviews, attachments, import/export or
other M07 modules.
