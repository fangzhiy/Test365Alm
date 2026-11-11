---
title: Lock stale Attempt actions during refresh while allowing run switches
author: "周"
date: 2026-11-11
branch: codex-main
id: l-494264d8
scope: repo
status: active
src: agent
touches:
  - apps/web/src/TestExecutionPanel.tsx
  - apps/web/src/TestExecutionPanel.test.tsx
---

## Decided
Lock dependent history actions while refreshing a run with its old Attempt still displayed. Keep ordinary run selection interruptible: selecting another run clears the old history and invalidates the previous response.

## Tried and ruled out
The real report flow printed Attempt A successfully but then never discovered B. Refresh had no pending state; clicking the still-visible A history button cancelled the new history request. Waiting for a report button alone cannot prove the history refresh completed. Locking all run reads then broke the existing late-response switching regression, so the lock is limited to preserveDraft refreshes.

## Watch out
Do not add retry or sleep. Controlled promises must prove both that old history cannot interrupt refresh and that a newer run selection wins over a late earlier response. Trail's attempt-version-only audit warning on the new fixture is a lexical false positive: audit keys still include independent saved step versions, with their original lifecycle regression retained.

In the code:
apps/web/src/TestExecutionPanel.tsx loadRun; apps/web/src/TestExecutionPanel.test.tsx refresh lock and late run regressions. Real browser diagnostics name only safe checkpoints and Attempt labels.
