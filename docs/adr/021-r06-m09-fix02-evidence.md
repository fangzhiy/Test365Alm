# ADR-021: M09 FIX02 real execution evidence gates

## Context

FIX01 proved the normal manual-execution path, but its final report contained only two `ManualExecutionDatabaseIT` cases.  The concurrency, frozen-idempotency, rollback, and real HTTP/browser paths therefore needed independently discoverable evidence.

## Decision

- Keep V12–V14 unchanged.  FIX02 adds no migration; it exercises the existing runtime datasource and integrity boundaries.
- Require all fifteen named `ManualExecutionDatabaseIT` cases in `verify_r06_manual_execution_report.py`.  The gate checks exact test names, suite identity, and zero failures, errors, skips, or duplicate cases.
- Add one real OIDC/random-port Spring HTTP test to `OidcCallbackSecurityIT`; owner SQL is limited to disposable fixtures and post-request assertions, while all M09 requests use the restricted runtime pool and real session/CSRF chain.
- Keep browser workers at one and retries at zero.  The browser gate requires the dedicated three-user M09 flow in addition to the existing project, requirement, and test-case flows.

## Consequences

The local Windows Docker/JNA limitation remains a genuine BLOCKED result for Testcontainers and Keycloak.  Ubuntu CI is the authoritative environment for those tests.  A green job with only historical test counts, skipped browser cases, or a missing named report is not accepted as M09 evidence.

