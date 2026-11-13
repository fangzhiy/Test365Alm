---
title: Authenticate idle browser identities again at the phase that uses them
author: "周"
date: 2026-11-13
branch: codex-main
id: l-50e27f39
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/oq-demo.spec.ts
---

## Decided
A long multi-identity browser scenario must open a fresh authenticated context when the late read-only evidence phase starts. Resolve the VIEWER identity at bootstrap, close that context, and sign in through real OIDC again for report verification, asserting the same principal. Keep failures strict after phase entry; do not retry failed requests or keep idle identities alive.

## Tried and ruled out
PR run 43386644917 on ac0a724 ran 17/18 browser tests successfully; OQ-DEMO-01 failed on its first late VIEWER review read with 401 after the scenario took over one minute. CI intentionally sets the servlet idle timeout to 1m for the real expiry test. The old flow authenticated VIEWER before fourteen review/sign/execute flows and did not use that identity during the work. Reusing that idle session made success depend on runner speed. Enlarging the timeout, sending keepalives, or retrying the 401 would hide the intended expiration boundary.

## Watch out
The same identity must still pass every report/history assertion and the CSRF-protected VIEWER write must still return 403. Retain the separate real idle-expiration/disabled-principal test, one browser worker and zero retries. Older all-green evidence at 9f2f801 does not certify later PR heads. Stage-scoped contexts close in finally, including on failure.

In the code: apps/web/e2e/oq-demo.spec.ts identity bootstrap and evidence stage; apps/web/e2e/oidc.spec.ts real session expiry test; .github/workflows/ci.yml TEST365ALM_SESSION_TIMEOUT=1m. Original red evidence is preserved under ignored local-evidence/ci-prep/43386644917/.
