---
title: Historical execution fixtures must preserve creation intent and transitive readers
author: "周"
date: 2026-11-10
branch: feat-execution-grid
id: l-65449ae1
scope: repo
status: active
src: agent
touches:
  - apps/server/src/test/java/com/test365alm/server/asset/AssetMigrationUpgradeIT.java
  - apps/server/src/test/java/com/test365alm/server/execution/ManualExecutionDatabaseIT.java
  - apps/server/src/test/java/com/test365alm/server/fixture/historical-execution-61711294.md
  - apps/server/src/test/java/com/test365alm/server/release/ReleaseMigrationUpgradeIT.java
---

## Decided
When an execution fixture helper has already created a run, keep that creation key for frozen replay assertions and use a distinct key for a later new run intent. A new assignment cannot retroactively change the frozen create response.

Historical defect source preparation must read the matching historical execution service while seeding old schemas. Explicit test-only registration ends at latest migration; current runtime still verifies old state and frozen replay.

## Tried and ruled out
Do not change production replay to reconstruct the latest assignment or attempt. Do not weaken old-column snapshots or hash assertions to accommodate a fixture. The failed focused run retained status identities but not exception details, so the old-schema dependency correction alone is not proof that both reported upgrade errors have no other cause.

## Watch out
Review transitive readers as well as the historical producer: DefectEvidence.prepare calls execution.attempt and run. The test-only prepare body and helper are frozen at 61711294; current columns are unavailable at V18/V19. Retest creation is unsupported in this reduced historical fixture and fails explicitly.

In the code: ManualExecutionDatabaseIT.preparedRun uses runKey + "-run". The grid-snapshot test collided with that key; it now keeps the old replay and uses grid-snapshot-assigned-run. HistoricalDefectEvidence61711294 is selected only by AssetMigrationUpgradeIT and ReleaseMigrationUpgradeIT old-stage contexts. Original failure evidence: local-evidence/linux-tests/ca55b950c8314f09989cf8d74d1b05bf. Failure diagnostics now export only fixed exception types, SQLSTATE values and at most eight source locations; raw logs stay in the disposable Linux runner.
