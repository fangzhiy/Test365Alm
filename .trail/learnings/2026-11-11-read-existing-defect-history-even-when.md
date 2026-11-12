---
title: Read existing defect history even when interrupted checkpoints have no completion markers
author: "周"
date: 2026-11-11
branch: codex-main
id: l-8dc66234
scope: repo
status: active
src: agent
touches:
  - apps/web/e2e/oqDefectResume.ts
  - apps/web/e2e/oqFixRegressions.ts
  - apps/web/src/oqDefectResume.test.ts
---

## Decided
A stored defect identity must trigger server history preflight even when an interrupted v1.1 checkpoint has neither a per-defect completion marker nor the global closed-and-reopened stage. More than one server resolution cycle cannot be adopted as that checkpoint's original cycle. Reject before all completion backfills and business work; only a truly new scene without a defect identity skips reading.

## Tried and ruled out
The previous condition skipped stages lacking both markers. The exact old checkpoint counterexample on a67be50 produced proofReads=0, actionsExecuted=1 and conflict=null. Treating every existing defect as already complete would break legitimate first-cycle RESOLVED, content edits within RESOLVED and running retests. Pass the actual completed flag to completion validation, and backfill only a verifiable terminal result from one cycle.

## Watch out
Do not construct completed=true or inject a marker to test legacy recovery. Persist and reload the old shape; assert defectCompletion, resolutionId and the global completion stage are absent. Prove real API write observation with a separate successful POST, then clear it before the conflict window. Verify complete paginated server snapshots and the unchanged checkpoint. Keep the previously accepted grid freshness fix unchanged.

In the code: apps/web/e2e/oqDefectResume.ts resumeAfterDefectPreflight; apps/web/src/oqDefectResume.test.ts; apps/web/e2e/oqFixRegressions.ts. Evidence: local-evidence/oq-fix03-exact-legacy-red.log.
