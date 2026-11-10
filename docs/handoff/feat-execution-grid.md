# C: execution grid and manual runner

Code checkpoints: contract `a799e5e`, API/DB `4473978`, UI `19ab02c`, regression/legacy-clock fixes `3ec2f9c`. Latest local dev `8bd7408e3c7dc1f65ed19721d40c98136b0fa5b7` merged as `0aee514bfd9adc89b34694168f0f5e2e6c05c901`, including the announced audit integration. No push, PR or merge into dev by this worker.

Contract: `contracts/execution-grid.md`. Reuses directories, saved revisions, immutable manifests, attempts, step results, HistoryPanel and real scanned assets. V24 adds effective project-writer assignment with independent versions, frozen manifest identity/name, separate business/save clocks, and typed RUN_STEP evidence. SQL filters precede LIMIT; the UI preserves opaque cursors and opens the row's exact run. Unknown assignment outcomes retain their original version/key and lock navigation until the original intent resolves.

## Actual offline verification

- Explicit JDK21: `mvn -B -ntp test`: **89 passed, zero failures/errors/skips**; all integration sources compiled. A missing test import was corrected before this green result.
- After latest-dev merge, `npm run test:run`: **262 passed / 27 files**, no skips. Before merge: 258 / 25 files. The status-filter regression was reproduced red, then passed after correcting local row projection.
- `npm run lint`: exit 0, 9 existing warnings. `npm run build`: passed.
- `python -m unittest tools.tests.test_browser_resource_guard -v`: **7 passed**, offline guards only; expected refusal diagnostics are not real resource operations.
- `npx playwright test e2e/manual-execution.spec.ts --list`: one test discovered, **not a browser run**.
- `trail build` refreshed; `trail check` exit 0, no conflicts (20 informational matches); `git diff --check` passed.
- Docker, PostgreSQL integration, MinIO/ClamAV, real OIDC HTTP and browser validation for C: **NOT_RUN**. No development service or Docker workload was started; next serial slot is assigned to B.

## Database inventory (compiled, NOT_RUN)

Source inventory: **179 integration methods** (baseline 167 + 12), not a passing runtime count. ManualExecutionDatabaseIT: 31; ManualExecutionMigrationUpgradeIT: 4; AssetDatabaseIT: 19; OidcCallbackSecurityIT: 25.

New ManualExecutionDatabaseIT methods:

- gridFiltersBeforeLimitAndContinuesAcrossInstancesWithoutOtherSetLeak
- assignmentsRejectViewerNonmemberCrossProjectDisabledAndExpiredIdentities
- assignmentConcurrentWritersFreezeReplayAndReauthorizeBeforeReturningIt
- assignmentAuditAndOutboxFailuresRollBackAssignmentEventAndClaim
- manifestAssignmentAndOldAttemptStayFrozenAfterReassignmentRenameAndRevision
- actualTextConclusionAndFirstExecutionTimePersistIndependentlyAcrossReloadAndReplay
- assignmentDirectoryPagesOnlyActiveWritersAndPreservesOpaqueCursor
- runtimeCannotForgeAssignmentIdentityVersionScopeOrFrozenEvidenceIdentity

New AssetDatabaseIT methods:

- stepEvidenceUsesRealCleanRevisionAndPreservesOldAttemptOnRetry
- stepEvidenceRejectsViewerForeignPausedAndFinishedTargets
- stepEvidenceAuditAndOutboxFailureRollBackLinkAndFrozenClaim

New upgrade method: `v22NonEmptyExecutionDatabaseUpgradesToV24WithUnknownHistoricalClocks`. Preserves nonempty V22 data, unknown historical timestamps, manifest hashes and every original frozen-response field. Original V13/V14 to V16 and V16 to V17 target checks remain; current-schema migration precedes current runtime calls.

Extended existing `OidcCallbackSecurityIT.realOidcManualExecutionHttpRunsAndRejectsViewerWrites`: actual OIDC/CSRF/HTTP assignment (viewer target/write denial), grid identity/server date, text/NOT_RUN then empty/PASS saves, frozen assignment response/manifest and separate clocks. Existing browser `real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run` now performs member assignment, real scanner publication and step linking, independent text/conclusion restoration, frozen owner, empty retry evidence and historical read-only evidence. No mock scanner or new skip was introduced.

Proposed first serial focus: **39 integration methods**, not a complete suite:

`mvn -B -ntp -Pintegration verify '-Dit.test=ManualExecutionDatabaseIT,ManualExecutionMigrationUpgradeIT,AssetDatabaseIT#stepEvidence*,OidcCallbackSecurityIT#realOidcManualExecutionHttpRunsAndRejectsViewerWrites'`

## Coordinator integration points / remaining work

1. **Shared upgrade fixture compatibility:** AssetMigrationUpgradeIT seeds V19 using current ExecutionService before its V20 upgrade; DefectMigrationUpgradeIT seeds V17; ReleaseMigrationUpgradeIT seeds V18. Current ExecutionService requires V24 columns; AssetService attachment queries require run_step_id. These need genuine historical fixtures and current-schema runtime phases; migration-count adjustment alone cannot fix them. C fixed its execution-only upgrade suite; coordinate shared-suite ownership with B because its business fields affect these fixtures too. C has not edited those shared files or V1–V22.
2. Shared hardcoded counts (e.g. original 22 in AssetDatabaseIT), V25 failure probe and exact Linux runner inventory remain coordinator-owned. This branch has **23 formal migrations**, maximum version 24; B+C together should have 24 formal files. Runner/count assertions were not loosened.
3. Run the approved serial real database/scan/HTTP focus, fix actual failures, then the complete combined gates. Run real browser using owned disposable resources, single worker/zero retries. Do not promote compiled or NOT_RUN evidence to passing acceptance.
4. Both pre-existing automatic configuration edits remain uncommitted and undiscarded. No secrets or evidence logs are committed.

## Historical execution/asset compatibility checkpoint

C now owns the execution/asset portions of shared upgrade fixtures. Test-only `HistoricalExecutionService61711294` keeps the seven required original methods and their genuine old response DTOs from frozen SHA `61711294ff10e16b8df29418badaa8b455bc80ea`; no production component scan registers it. `LegacyAttachmentService61711294` preserves old attachment SQL and frozen JSON while inheriting real current storage/scanning. Original target migrations, full snapshots, manifest hashes and replay assertions remain; a separate latest-schema phase exercises the current transaction-proxied runtime and verifies every original execution column, unknown historical clocks and frozen old create responses.

Changed shared ITs: AssetMigrationUpgradeIT, DefectMigrationUpgradeIT, ReleaseMigrationUpgradeIT and AssetProvisioningUpgradeIT. B's `197ac06114e0648b25301b698e855ea6c233f9d4` changes content services in these same files; coordinator must retain both sets, combine context legacy selection and new-column snapshot projections, and reconcile actual migration counts. No B worktree or shared runner/counts were modified. Explicit JDK21 `mvn -B -ntp test` again passed **89 / zero F/E/S**, including all IT source compilation; database/scanner/browser execution remains **NOT_RUN**. `trail check` and `git diff --check` passed before committing.
