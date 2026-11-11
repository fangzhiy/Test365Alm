---
title: Preserve code-only security errors before classifying report scope loss
author: "周"
date: 2026-11-11
branch: codex-main
id: l-88daf47d
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/reports.spec.ts
  - apps/web/src/DefectsPanel.test.tsx
  - apps/web/src/Reports.test.tsx
  - apps/web/src/TestExecutionPanel.test.tsx
  - apps/web/src/projectAccess.ts
  - apps/web/src/reportsApi.ts
---

## Decided
Preserve a server error code even when its response has no message. Use safe local text and retain HTTP status. Reports treat confirmed HTTP 401 as authentication loss even if its body is malformed; other statuses require an explicit scope-denial code. CSRF_REJECTED, unknown 403, parameter/range errors, server and transport failures stay ordinary read failures.

## Tried and ruled out
Mocking the final loadReport exception hid the defect: IdentitySecurity emits code-only UNAUTHENTICATED/FORBIDDEN, while the shared parser required a message and substituted HTTP_401/403. The report classifier then ignored status because a string code existed. Raw Response tests through requestJson reproduced both hosts retaining old content. Treating every 403 as loss would wrongly clear the host on CSRF or unknown upstream failures.

## Watch out
Keep response-shape tests at the actual parser boundary, alongside loaded host lifecycle tests and a real same-session second-tab logout. The original tab must retain loaded content until its real report GET receives 401; refreshing it first would hide this bug. Preserve independent member-revocation coverage. Late report/name/detail responses must not restore scope contents.

In the code:
apps/web/src/projectAccess.ts parseError; apps/web/src/reportsApi.ts reportScopeDenied; projectAccess.errors.test.ts and both host tests; apps/web/e2e/reports.spec.ts legal CSRF logout and code-only 401.
