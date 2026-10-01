# ADR 013: R03 project access hardening

## Status

Accepted for R03-M03-002-FIX01.

## Context

V3 established the first tenant/project tables and RLS policies, but a
tenant context alone was enough to expose project rows. The service query also
treated `TENANT_ADMIN` as an implicit member of every project. A revoked
tenant membership therefore did not invalidate an existing project
membership. The project-management UI additionally called administrator-only
member endpoints before it knew the current project permissions.

## Decision

- Keep V1--V3 immutable and add V4 as the only schema change in this fix.
- Use owner-controlled `SECURITY DEFINER` helpers for active tenant/project
  membership and project administration. Runtime connections cannot bypass
  those checks by choosing a tenant setting.
- Project reads require an active tenant membership and an active explicit
  `project_member` row. Tenant administrators do not receive implicit project
  visibility; project creation grants the creator an explicit
  `PROJECT_ADMIN` row in the same transaction.
- The first project-member insert is limited to the recorded `created_by`
  principal; later member changes require an active project administrator.
- Service project commands re-check active tenant membership after resolving a
  project, so tenant revocation invalidates an already established project
  session on its next request. Authorization versions remain monotonic and
  last-administrator checks are serialized by row locks.
- The web panel fetches effective permissions before administrator-only
  member endpoints. Requests carry an abort signal and round identity, and a
  successful logout increments the session generation to clear project data.

## Consequences

Runtime DML remains separate from Flyway ownership and no health or request
path performs migration or repair. Project-admin users that are not tenant
administrators may need a later, explicitly authorized candidate-directory
endpoint; this slice does not broaden tenant-level member visibility to make
that directory implicit.

Evidence is provided by the FIX01 Testcontainers tests and the CI-owned
two-user Keycloak browser scenario. Local Windows Testcontainers/browser
execution remains blocked by the Docker named-pipe/JNA permission environment;
that limitation is recorded in the round report rather than hidden by skips.
