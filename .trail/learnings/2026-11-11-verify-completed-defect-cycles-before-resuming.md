---
title: Verify completed defect cycles before resuming and keep navigation tied to live instances
author: "周"
date: 2026-11-11
branch: codex-main
id: l-3f42c978
scope: repo
status: active
src: agent
touches:
  - docs/coordinator-handoff.md
  - apps/web/e2e/oqDefectResume.ts
  - apps/web/src/ExecutionGrid.tsx
---

## Decided
A completed synthetic defect stage is a server-verified identity, not permission to restore a planned status. Persist its terminal revision/version, actual resolution entry and exact retest identities; recheck before business actions. Legacy v1.1 markers can be enriched locally only after confirming the expected terminal and unique original cycle. Read the existing authorized report pointer and immutable revision history together: a content edit while RESOLVED is not a new solution cycle.

## Tried and ruled out
DefectView has no resolvedRevisionId. Falling back to the first retest reused an old PASS after a user legitimately created a new RESOLVED cycle and would write REOPENED again. Do not replace the missing field with currentRevision.id or UUID ordering. In navigation, caching a clicked instance froze its Attempt; simply clearing selection when rows disappear also closed it during ordinary grid refresh because the parent temporarily emptied the rows.

## Watch out
Same-filter refresh retains selection identity but hides latest-report access while awaiting the current response. Changed filters, missing rows, scope loss and late responses still invalidate it. Preserve grid-read pending state and original history refresh locks: trail check's l-494264d8 lexical warning is not authorization to remove either lock. Real fault regression uses a separate owned project, never the persistent fourteen-case data. Interrupted terminal writes are recovered by querying before repeating an action, not by relying on expiring idempotency keys.

In the code: apps/web/e2e/oqDefectResume.ts, oq-demo.spec.ts, oqFixRegressions.ts; apps/web/src/ExecutionGrid.tsx, InstanceNavigator.tsx and TestExecutionPanel.tsx. OQ-DEMO-01-FIX01 keeps the standard 14 instances, 16 Attempts and 29 simulated signatures separate from fault fixtures.
