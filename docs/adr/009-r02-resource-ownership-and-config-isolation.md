# ADR-009: R02 resource ownership and child configuration isolation

- Status: Accepted for R02 verification
- Date: 2026-09-23

## Context

R02-M02-003 checked a Compose project label only after `up` and used project-level
`down --volumes`. An existing project could therefore be changed before its
ownership was checked. The scripts also copied the parent process environment;
Spring, Flyway, JVM or Compose overrides could redirect the test target.

## Decision

- Each invocation generates a UUID run ID. Before the first `up`, the scripts
  inspect all matching containers (including stopped ones), networks and volumes
  and refuse a project with pre-existing resources. A project name alone never
  grants ownership.
- The local Docker context and daemon ID are pinned before mutation. Compose
  creates container, network and volume labels containing the run ID. Their
  actual IDs, labels, project, context and daemon ID are recorded in a manifest.
  Stop, start and cleanup recheck identity. Cleanup removes only recorded IDs;
  failed checks produce a non-zero result. CI uses the same manifest guard.
- Child processes receive a minimal allowlist of runtime variables, not a copy
  of the parent environment. The dedicated test file rejects unsupported keys.
  The application starts in an isolated evidence directory, loads only the
  packaged `application.properties`, and receives explicit matching datasource
  and Flyway URL/credentials for the temporary PostgreSQL instance.
- A disposable populated control project verifies that both scripts reject a
  pre-existing project while its two sentinel rows and resource IDs remain
  unchanged. The control itself is cleaned through its own manifest.

## Consequences

The scripts fail closed when Docker identity or resource ownership cannot be
proved. A failure before manifest capture may require manual inspection of
newly created resources; broad project-level deletion remains prohibited.
Windows keeps runtime variables such as `SYSTEMROOT` case-insensitively in the
allowlist because the JVM needs them to initialize sockets. This was verified
with a real PostgreSQL outage/recovery run.
