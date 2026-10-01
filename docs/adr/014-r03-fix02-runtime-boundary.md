# ADR 014: R03 FIX02 runtime boundary and owned evidence

## Status

Accepted for R03-M03-002-FIX02. V1--V4 remain immutable; the authorization
changes in this round are carried by `V5__project_access_security_boundaries`.

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
- R03 Compose resources carry a unique run label on containers, network and
  volume. CI records the context, engine and resource IDs before cleanup and
  removes only manifest entries whose labels still match. A project-level
  `down` is not an allowed cleanup fallback.

## Consequences and limits

The full Testcontainers and Keycloak/browser evidence remains CI-dependent on
this Windows host. The round does not claim the physical-pool maximum-size-one
scenario or injected audit-write rollback until a dedicated test proves them.
The R03 guard reuses the R02 manifest implementation but uses a distinct label
key, so old R02 manifests remain readable without treating an R03 resource as
owned by an R02 run.
