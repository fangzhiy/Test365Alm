# ADR 014: R03 FIX02 runtime boundary and owned evidence

## Status

Accepted for R03-M03-002-FIX02. V1--V4 remain immutable; the authorization
changes in this round are carried by `V5__project_access_security_boundaries`
and the follow-up `V6__isolate_tenant_bootstrap` migration.

## Decision

- Keep Flyway migration credentials separate from the application runtime
  role. The HTTP integration suite sets the application datasource to the
  restricted role and uses the owner only for migration and fixture setup.
- Replace the inherited permissive RLS policies with command-specific
  SELECT/INSERT/UPDATE/DELETE policies. Tenant administrators do not acquire
  project visibility without an explicit project membership, and project
  writes require project administration.
- Store before/after member roles and authorization versions in audit events;
  membership changes use a required HTTP authorization version and a project
  row lock to serialize first grants and concurrent changes.
- Keep tenant/domain bootstrap outside the restricted runtime write surface:
  V6 records `tenant.created_by`, binds bootstrap policy to that principal, and
  revokes runtime tenant and tenant-member INSERT/UPDATE/DELETE. Runtime HTTP
  project access therefore consumes owner/fixture-provisioned tenants.
- R03 Compose resources carry a unique run label on containers, network and
  volume. CI records the context, engine and resource IDs before cleanup and
  removes only manifest entries whose labels still match. A project-level
  `down` is not an allowed cleanup fallback.

## Consequences and limits

The Testcontainers suite now exercises a restricted runtime role, a one-
connection pool/context sequence, concurrent version checks, and injected audit
write failures on disposable databases. Full Keycloak/browser evidence remains
dependent on the CI environment that owns the Compose resources.
The R03 guard reuses the R02 manifest implementation but uses a distinct label
key, so old R02 manifests remain readable without treating an R03 resource as
owned by an R02 run.
