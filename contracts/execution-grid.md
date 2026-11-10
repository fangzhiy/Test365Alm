# OQ execution grid and manual runner (1b / 1d)

This additive slice reuses test sets, fixed revision instances, manifests, attempts,
step results and the private asset scanner. It adds no review, signature or report.

- `GET /projects/{project}/test-sets/{set}/execution-grid` (under `/api/v1`):
  bounded keyset page `{items,nextCursor}`. Each row contains `instance` (the
  existing instance shape), `assigneeId`, `assigneeName`, `assignmentVersion`,
  `runId`, `attemptId`, `status`, `conclusion`, `executionDate`.
  The latest attempt across all runs is selected by server `started_at`, then
  run/attempt identity. No run means `NOT_RUN` and null executionDate.
  `status` (NOT_RUN/RUNNING/PAUSED/FINISHED) and `assigneeId` filters apply in SQL
  before LIMIT. Set placement/folder filtering remains on test-set pagination.
  Callers preserve the opaque cursor and reset it when filters change.
- `GET /projects/{project}/execution-assignees`: bounded cursor page of active
  project ADMIN/MEMBER identities `{id,displayName}`; revoked/expired tenant or
  project memberships and disabled identities are excluded. VIEWER is not an
  executable assignment. No tenant role alone grants project membership.
- `PUT /projects/{project}/test-instances/{instance}/assignee`:
  `{assigneeId: UUID|null,expectedVersion?}`, strong `If-Match` for the independent
  `assignmentVersion`, durable `Idempotency-Key`, same-origin CSRF. Missing target
  differs from explicit null. Only project writers can change it; the server
  checks and locks effective membership. Replay first reauthorizes the caller,
  then returns the original frozen assignment response. Audit, execution event,
  Outbox and assignment persist in one transaction. Concurrent stale updates fail.
- A new manifest snapshots assigned identity/name and includes them in its new
  format/hash. Historical manifests retain their original format/hash and null
  assignment when never recorded. Reorganization, renaming, reassignment or
  revocation never rewrites a manifest or old attempt. New runs revalidate a
  non-null assigned identity; reruns retain the original manifest.
- Step actual text and conclusion remain independent values under the existing
  strong step version. Text with NOT_RUN and empty text with PASS are valid;
  FAIL/BLOCKED still require actual text. No UI inference changes a conclusion.
  `executedAt` records the first server-observed non-NOT_RUN conclusion; text
  saves leave it null, later corrections keep it. `updatedAt` is the last save
  time. Old rows keep executedAt unknown (null), never backfilled from save time.
  The client supplies neither timestamp. Drafts retain their original version
  until the existing explicit conflict/rebase flow confirms a new basis.
- Each persisted run step has a scoped `runStepId`. The existing typed attachment
  API gains `kind: RUN_STEP, runStepId` with a real scoped FK. Linking locks and
  requires its exact RUNNING attempt; paused/finished attempts reject new links.
  Reads/downloads of historical evidence remain authorized. Each retry gets
  separate step identities. The existing upload/PUT/real-scan/complete/CLEAN
  asset revision flow, immutable links and proxy downloads are unchanged.

This slice adds formal V24 only; V1–V22 remain unchanged and V23 belongs to business
fields. C's independent branch had 23 actual migrations (original 22 plus V24).
The combined B+C tree has 24 actual migration files (original 22 plus B's V23 and
C's V24); counts come from those files, not the highest version number. The
coordinator owns shared upgrade counts and the V25 intentional failure probe;
later D migration/probe numbering must be coordinated at integration.
Unit/frontend checks precede any approved serial Docker slot; database/browser
results must be reported separately.

Coordinator acceptance: `docs/reviews/execution-grid-coordinator-review.md`.
The final worker handoff is archived at
`docs/reviews/execution-grid-worker-handoff.md`; review/signature/report work
continues separately, with D formal V25 and its failure probe V26 reserved.
