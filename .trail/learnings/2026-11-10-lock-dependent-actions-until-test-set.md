---
title: Lock dependent actions until test set details finish loading
author: "周"
date: 2026-11-10
branch: feat-execution-grid
id: l-f3623bad
scope: repo
status: active
src: agent
touches:
  - apps/web/src/TestExecutionPanel.test.tsx
  - apps/web/src/TestExecutionPanel.tsx
---

## Decided
A selected test set must hold the existing pending lock until its complete detail read settles. The heading and case selector render immediately after selection, before the parallel detail/grid/run/summary reads are ready. Without a pending lock, loading saved cases starts a new request round, aborts those reads, and leaves the panel in loading; adding an instance can then succeed while all grid actions remain disabled.

## Tried and ruled out
The child grid does not feed its disabled prop back into the parent lock: it reports only unresolved assignment intent. Two real browser flows stopped after instance201 with no run request; delayed-detail and delayed-grid component regressions proved the early discovery click aborted their shared signal. Use the existing pending mechanism rather than add independent selector locks or browser sleeps. The browser failure is retained until the new exact source is actually run.

## Watch out
Clear the pending lock only for the active round, and retain the bounded timeout invalidation so a late response cannot revive rows. Test failure and timeout recovery as well as the successful case selection/add/start sequence. Do not bypass the UI with API calls or weaken assignment, attachment or defect assertions.

In the code: apps/web/src/TestExecutionPanel.tsx loadSet; apps/web/src/TestExecutionPanel.test.tsx delayed set-detail/grid, read-error recovery and late timeout regressions. Browser evidence: local-evidence/local-browser/491f4279dda843e1a0a1e0f11ca45142/ (1 passed, 2 failed, cleanup true).
