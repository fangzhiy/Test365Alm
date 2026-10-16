# M06 Release / Cycle / frozen quality snapshot — R10

Finite synchronous slice; project ADMIN writes, effective project MEMBER/VIEWER read. Tenant administration alone grants no project access. All requests use the existing OIDC session/CSRF/dynamic authorization/restricted runtime. No full planning, approvals, artifact storage or compatibility claim.

## HTTP and data

Root `/api/v1/projects/{projectId}/releases`. All lists use `{items,nextCursor}`, UUID ascending cursor, default 25/max100, no automatic all-page download.

- POST root: `{name,description?,startDate,endDate}` → 201 Release. Server assigns stable UUID, project display key `REL-{number}`, PLANNED/version1 and actor/timestamps. Name trimmed nonblank <=200, description <=10000; dates strict real `YYYY-MM-DD` business dates (no timezone shift).
- GET root, GET `/{releaseId}`; PATCH `/{releaseId}` accepts optional `name,description,startDate,endDate,status` only, nonempty patch. GET/PATCH return strong quoted numeric ETag.
- POST `/{releaseId}/cycles`: `{name,startDate,endDate}` → 201 Cycle. GET cycles list, GET/PATCH `/{releaseId}/cycles/{cycleId}`; PATCH optional `name,startDate,endDate,status`, strong ETag.
- GET `/{releaseId}/scope`; POST same path accepts exactly `{kind:"REQUIREMENT",requirementId,cycleId?}` or `{kind:"TEST_CASE",testCaseId,cycleId?}`. Server freezes current requirement revision or sealed test revision. DELETE `/{releaseId}/scope/{scopeId}` returns `{id,removed:true}`. IDs and every relation are constrained to the same tenant/project/release; cycle optional, never a generic JSON reference. Same object/cycle duplicate is 409 DUPLICATE_SCOPE. One object has one frozen revision across the release's active memberships; adding a new current revision while old memberships exist returns 409 SCOPE_REVISION_CONFLICT (explicitly remove old memberships first). Existing scope never follows a new source revision.
- POST `/{releaseId}/milestones/evaluate` accepts `{}` only → 201 immutable Snapshot. GET `/{releaseId}/snapshots` paginated summaries; GET `/{releaseId}/snapshots/{snapshotId}` frozen detail, never recalculated on read.

Release `{id,projectId,displayNumber,name,description,startDate,endDate,status,rowVersion,scopeVersion,createdBy,createdAt,updatedAt}`. Cycle `{id,projectId,releaseId,name,startDate,endDate,status,rowVersion,createdBy,createdAt,updatedAt}`. Scope `{id,projectId,releaseId,cycleId,kind,requirementId,requirementRevisionId,testCaseId,testRevisionId,createdBy,createdAt}` with the inapplicable typed pair null.

Snapshot `{id,projectId,releaseId,evaluatedAt,evaluatedBy,metricVersion,rule,scopeVersion,scopeFingerprint,requirementCount,coverage,plannedTests,completed,effectivePass,openDefects,evidence}`. Each metric is `{numerator,denominator}` (requirementCount is an integer). No combined quality score; zero denominator shown as not applicable, not 100%. Evidence is server-generated frozen IDs/revisions/trace IDs/attempt states and defect states, not arbitrary client references. Maximum 1000 active scope memberships per release; exceeding this finite bound returns 409 SCOPE_LIMIT, not truncated metrics. Detail/list evidence size remains bounded; snapshot summaries omit evidence.

## Dates and finite lifecycle

startDate <= endDate; Cycle dates inclusive inside parent Release. Parent shrinking past any existing cycle is rejected 422 CYCLE_OUTSIDE_RELEASE, never silently truncated. Invalid business date/range 422 VALIDATION_FAILED; malformed JSON/types/unknown fields/enum 400 INVALID_REQUEST (existing error structure).

Release PLANNED→ACTIVE/CLOSED/ARCHIVED; ACTIVE→CLOSED/ARCHIVED; CLOSED→ARCHIVED; no reverse transition. Cycle PLANNED→ACTIVE/CLOSED; ACTIVE→CLOSED. Same status is allowed with an actual content edit. CLOSED Release/Cycle content is read-only; CLOSED Release can only archive. ARCHIVED Release rejects every new mutation including evaluate; current/history reads remain available. New cycle/scope and scope removal require PLANNED/ACTIVE Release; scope targeting CLOSED Cycle is rejected. Illegal transitions 409 INVALID_TRANSITION; readonly state 409 RELEASE_READ_ONLY/CYCLE_READ_ONLY. No client author/time/version assignment.

## Metric definition v1: RELEASE_FROZEN_SCOPE_LATEST_FINISHED_NO_ACTIVE_V1

The evaluation uses one consistent authorized PostgreSQL snapshot under a transaction. Scope is the entire release including its cycles, not the loaded UI page; distinct stable requirement/test identities are counted once. scopeVersion increments on add/remove, fingerprint hashes sorted typed memberships and their frozen revisions/cycle IDs. Every evaluate stores full raw counts, rule/version and traceable source facts in immutable history.

1. requirementCount = distinct scoped requirement identities. coverage denominator = that count; numerator = those with at least one ACTIVE M11 relation matching the scoped requirement revision, whose requirement and test bindings are still their current revisions. No INACTIVE/stale relation counts; coverage means design linkage, not PASS and does not require the linked test also be planned here.
2. plannedTests = distinct scoped test identities / itself (zero is N/A); each has one frozen test revision.
3. completed denominator = planned tests; numerator = scoped tests whose latest FINISHED attempt across this project/test identity (finished_at DESC, attempt UUID DESC) uses the frozen revision, with no RUNNING/PAUSED attempt for this test in the project. The latest finished unrelated revision does not qualify. An active rerun suppresses an old completion/PASS.
4. effectivePass denominator = planned tests; numerator = completed qualifying attempts with conclusion PASS. Same scope/revision rule, not any historic or unrelated PASS.
5. openDefects numerator = unique related defects whose current status != CLOSED; denominator = all unique related defects. Related means an existing M10 typed REQUIREMENT link matching a scoped requirement and frozen revision, or RUN_STEP link matching a scoped test and frozen revision. No title matching, indirect guessing, cross-project or duplicate counting. REOPENED counts as open.

Old snapshots preserve even subsequently removed scope or changed trace/execution/defect facts. Re-evaluate creates a different UUID unless replaying the original idempotent intent. No snapshot UPDATE/DELETE/TRUNCATE privilege for runtime.

## Version, permissions, idempotency and transaction

All writes require valid CSRF and 8..128 character Idempotency-Key. Release/Cycle PATCH additionally requires one strong positive decimal If-Match; missing/empty/* 428, malformed/weak/bare/overflow400, stale412 STALE_VERSION. Authorization precedes version/resource disclosure. Same project/principal/route/key and original input/version replays original frozen result for24h; different body409 IDEMPOTENCY_KEY_REUSED; expired follows existing new-intent rule. Every replay rechecks effective ADMIN write authorization, even after archive. Request cancellation/timeout never generates a new key automatically.

Permissions: all effective members `release:read`, `milestone:read`; only PROJECT_ADMIN `release:create`, `release:update`, `release:archive`, `cycle:create`, `cycle:update`, `scope:update`, `milestone:evaluate`. Existing member/tenant/identity invalidation applies on the next direct request, no /me precondition. RLS applies the same admin-only writer restriction.

Project command lock serializes mutation/number allocation; rowVersion still rejects concurrent same-base edits precisely. Objects/version/scope/snapshot/audit/typed Outbox/idempotency commit atomically. Events RELEASE_CREATED/UPDATED/ARCHIVED, CYCLE_CREATED/UPDATED, RELEASE_SCOPE_ADDED/REMOVED, QUALITY_SNAPSHOT_CREATED contain only necessary IDs/version/rule/count metadata (no full content/evidence dump). Snapshot evaluation is finite and synchronous, not an asynchronous quality center.

UI isolates project/principal/permissions/release generations, separates reads/writes, locks submitted inputs through pending/unknown intent, preserves412 draft until explicit adopt/discard, and uses GET-only recovery after confirmed write. Historical snapshot explicitly labelled frozen; new server reads never rewrite it.
