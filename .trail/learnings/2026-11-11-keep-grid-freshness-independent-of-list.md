---
title: Keep grid freshness independent of list retries and observe resume at the real API sender
author: "周"
date: 2026-11-11
branch: codex-main
id: l-6668910d
scope: repo
status: active
src: agent
touches:
  - apps/web/src/TestExecutionPanel.tsx
  - apps/web/src/TestExecutionPanel.gridRecovery.test.tsx
  - apps/web/e2e/oqDefectResume.ts
  - apps/web/e2e/oqRequestAudit.ts
  - apps/web/e2e/oqFixRegressions.ts
---

## Decided
Grid report eligibility must remain invalid after a failed or timed-out refresh until that grid, in the same scope, is successfully read again. A successful set-list retry establishes list readiness only. Keep selection during a normal same-filter refresh, while withholding latest-report actions.

Resume evidence must exercise the same preflight-and-business-work orchestrator as the scene runner. Observe APIRequestContext write methods at their real send boundary, prove the observer with a successful isolated write, clear it, then assert the conflict window sent no writes and complete server snapshots did not change.

## Tried and ruled out
Using component state ready as freshness allowed a timed-out grid to revive an old finished Attempt report through unrelated list retry. Timeout invalidates the request round before catch, so catch-only clearing cannot close this path. Page request events did not observe APIRequestContext writes, and calling only a pure completion predicate did not test subsequent business work.

## Watch out
Do not relax request rounds, aborts, history switching locks, CSRF or defect-cycle guards. Preserve real responses in the write observer; blocking writes would make a zero count meaningless. Full revision, retest, audit and Attempt pages and exact signature snapshots are compared. A synthetic fixture is separate from the persistent fourteen-case scenario.

In the code: apps/web/src/TestExecutionPanel.tsx gridVerified; apps/web/src/TestExecutionPanel.gridRecovery.test.tsx; apps/web/e2e/oqDefectResume.ts resumeAfterDefectPreflight; apps/web/e2e/oqRequestAudit.ts captureApiWrites; apps/web/e2e/oqFixRegressions.ts.
