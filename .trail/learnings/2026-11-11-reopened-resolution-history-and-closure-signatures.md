---
title: Reopened resolution history and closure signatures must not imply current approval
author: "周"
date: 2026-11-11
branch: codex-main
id: l-99f3e4d4
scope: repo
status: active
src: agent
---

## Decided
A deviation's stored resolved revision is its last resolution, including after reopen. Reports expose that history separately from currentResolutionId, which is effective only in RESOLVED/CLOSED, matching DefectEvidence. Presentation consumes the effective field consistently in both report types.

Execution reports must merge the original related DEVIATION_CLOSURE records with the requested TEST_APPROVAL/RUN_COMPLETION records, deduplicate by record ID, sort stably and enforce the combined cap. A closure signature proves its exact historical closure revision, never the current deviation state or an unsigned Attempt.

Report permission denial must invalidate its owning execution/defect scope, not just its overlay. Existing cancellation/round checks reject late host and report responses. Ordinary read errors retain their ordinary error semantics.

## Tried and ruled out
Clearing resolved_revision_id would erase intentional history. Comparing a retest with the last resolution without checking effective state labels reopened old PASS as current. Looking up signing wrappers or regenerating snapshots would replace original evidence. Clearing only report state leaves stale protected lists behind.

## Watch out
DTO validation must compare per-row categories with counters and ratios, and distinguish explicit historical nulls from absent fields. Signature snapshots stay available but are excluded from default business printing unless the user explicitly opts into the technical appendix. Test source/retest reports against the same stored closure record; include unrelated and foreign-project signatures as exclusion controls.

In the code: ReportService, reportsApi.ts, ReportContent.tsx, Reports.tsx, TestExecutionPanel.invalidateScope, DefectsPanel.onDenied. Regressions: ReportDatabaseIT and e2e/reports.spec.ts; no state-machine or history migration changes.
