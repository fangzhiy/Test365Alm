# C: execution grid and manual runner

Code checkpoints: contract `a799e5e`, API/DB `4473978`, UI `19ab02c`, regression/legacy-clock fixes `3ec2f9c`. Latest local dev `8bd7408e3c7dc1f65ed19721d40c98136b0fa5b7` merged as `0aee514bfd9adc89b34694168f0f5e2e6c05c901`, including the announced audit integration. No push, PR or merge into dev by this worker.

Latest combination: B runtime/test/facility source `a2b45d29f42138e3d4d4c8ca38bab12e7e745e2e` merged as `f13547530f1fb40623e6ef80a18356482364f3b6`; accepted B documentation/archive source `23d138cda8fd6d34ec598064778b42b121175109` merged as `3f94d4d5fcb03f015788c8cd2f835c654ad3c714`. Current evidence and pending work are in the final section below; earlier entries record the earlier independent checkpoints.

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

1. **Shared upgrade fixture compatibility:** execution/asset compatibility is now implemented in checkpoint `f9df894` as described below, still pending actual database execution. Coordinate the shared files with B's content fixtures; retain both changes and current-schema runtime phases. V1–V22 remain unchanged.
2. Shared hardcoded counts (e.g. original 22 in AssetDatabaseIT), V25 failure probe and exact Linux runner inventory remain coordinator-owned. This branch has **23 formal migrations**, maximum version 24; B+C together should have 24 formal files. Runner/count assertions were not loosened.
3. Run the approved serial real database/scan/HTTP focus, fix actual failures, then the complete combined gates. Run real browser using owned disposable resources, single worker/zero retries. Do not promote compiled or NOT_RUN evidence to passing acceptance.
4. Both pre-existing automatic configuration edits remain uncommitted and undiscarded. No secrets or evidence logs are committed.

## Historical execution/asset compatibility checkpoint

C now owns the execution/asset portions of shared upgrade fixtures. Test-only `HistoricalExecutionService61711294` keeps the seven required original methods and their genuine old response DTOs from frozen SHA `61711294ff10e16b8df29418badaa8b455bc80ea`; no production component scan registers it. `LegacyAttachmentService61711294` preserves old attachment SQL and frozen JSON while inheriting real current storage/scanning. Original target migrations, full snapshots, manifest hashes and replay assertions remain; a separate latest-schema phase exercises the current transaction-proxied runtime and verifies every original execution column, unknown historical clocks and frozen old create responses.

Changed shared ITs: AssetMigrationUpgradeIT, DefectMigrationUpgradeIT, ReleaseMigrationUpgradeIT and AssetProvisioningUpgradeIT. B's `197ac06114e0648b25301b698e855ea6c233f9d4` changes content services in these same files; coordinator must retain both sets, combine context legacy selection and new-column snapshot projections, and reconcile actual migration counts. No B worktree or shared runner/counts were modified. Explicit JDK21 `mvn -B -ntp test` again passed **89 / zero F/E/S**, including all IT source compilation; database/scanner/browser execution remains **NOT_RUN**. `trail check` and `git diff --check` passed before committing.

## Real local browser asset facilities (offline checkpoint)

`tools/local_browser_runner.py --source <exact SHA> --with-assets` creates the two existing digest-pinned R11 services with fresh loopback ports, provenance labels and exact resource IDs. The new helper verifies both service identities/bindings before every readiness probe and scanner provisioning connection, uses real benign INSTREAM/private S3 readiness, and invokes the formal repository provision tool for a fresh restricted scanner child LOGIN after latest Flyway readiness. Capability group bootstrap LOGIN is disabled in this mode. The manual execution browser flow checks both owned asset services before its first write; no attachment assertion was removed or skipped. Stage failure paths stop owned processes, redact secrets, remove generated secret files and use exact manifest cleanup. No real service has been started by C.

TDD observed the absent feature and frontend endpoint guard fail, then pass. Actual latest offline validation: **263 frontend tests / 27 files**, lint exit 0 with 9 existing warnings, production build passed; **29 Python tests** across `test_local_browser_assets` (7), `test_browser_resource_guard` (7), `test_asset_scanner_provision` (12), `test_r11_service_probe` (3). One initial command used a nonexistent probe module name and was corrected; the explicit-local Playwright config correctly refused `--list` without an ownership manifest, while the normal config discovered the one manual test. Neither discovery nor simulated offline subprocesses count as a real scan, Docker, HTTP or browser pass.

Proposed serial database selector remains focus39 above, plus the **four changed historical-upgrade methods**: AssetMigrationUpgradeIT's V19 method, DefectMigrationUpgradeIT's V17 method, ReleaseMigrationUpgradeIT's V18 method, and `AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin` (43 total if approved). Selecting all of AssetProvisioningUpgradeIT also includes its second unsafe-role method (44 total). These are method inventories, not results or an authorization to start Docker. Await coordinator's fixed selector and serial slot; do not change shared runner/counts. Browser real-mode command must use this facilities checkpoint or a later reviewed combined SHA, single worker, zero retries, and exact-ID cleanup.

## Approved combined focused preparation

Coordinator selected **C43 + B8 = focused51**, keeping only `AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin`, and authorized an independent facilities adaptation from B's `55490a957879d5c1b5a476813ca32c3c72d62042`. C reused its scan→unit→focused→full sequencing and report validation, froze all 51 exact identities, and added offline negative gates for every substituted method, duplicates/missing/extra methods, per-case and aggregate F/E/S, real clean/EICAR identity, phase early stop and arbitrary selector/count arguments. Initial missing-feature tests were red; **17 offline tests passed** (7 new + 10 existing Linux guards). No Java/frontend source changed in this preparation.

**Not an execution-ready combined source:** B remains in its exclusive real validation window. B's final features/fixes have not been merged here. The existing audited global 85-unit/167-integration counts are deliberately retained as fail-closed gates until the final B+C merge is inventoried by method identity and updated in a reviewed checkpoint; 93/187 remain forecasts. C's actual prior offline results remain 89 Java units, 263 frontend and 29 asset-related Python tests. Real DB/HTTP/browser/scan for C remain NOT_RUN; no Docker or service was started. Next input required: B's final accepted SHA, the combined inventory review, and coordinator's exact source/window authorization. The coordinator separately reviewed `70d7cfa` statically and ran its 7 new offline facility tests; this is not a real browser/scan pass.

Final preparation check: all **46 relevant Python offline regressions** passed together, `git diff --check` and `trail check` passed. A read-only pinned-source check matched every C43 identity at `70d7cfa` and B8 at `7433ef2`; this verifies only the approved focused manifest, not the final B implementation or combined full inventory. No guessed 93/187 totals were installed.

## Combined inventory checkpoint (real integration still NOT_RUN)

B's accepted source is now merged in this worktree only. All six conflicts were resolved: B legacy content and full original-column snapshot assertions coexist with C historical execution, step-ID/clock projections and old hash/frozen replay assertions. The four shared upgrade tests use one explicit, table-specific original-column projection, retaining every original row and field and stable projected ordering; only B23/C24 added columns are omitted. AssetProvisioning registers both legacy requirement and legacy attachment during V20 seeding, retains V20→V21 and V21→V23 checks, then migrates V24 before current services. Original target checks remain. ManualExecutionMigrationUpgradeIT also excludes the new B23 test_revision.business_fields when comparing old-schema rows; completed current history still compares every current field.

The merged source was inventoried by every exact class/method: **93 units / 30 suites**, **187 integrations / 25 suites**, **51 approved focused methods**. The committed `tools/linux-test/combined-methods.json` contains all 280 identities; an offline regression compares it with every Java test and refuses unsupported annotations. The runner uses fixed 93/1 scan, 93 unit, 93/51 focused and 93/187 full gates, exact unit identities in every phase and exact full identities in the full phase. Replacements cannot preserve acceptance by keeping the same totals. The B-specific phase regressions were retained and adapted to the approved combined selector.

Actual latest validation: explicit JDK21 `mvn -B -ntp test` **93 passed / zero F/E/S**, all 66 test sources compiled, including integration fixtures; all 93 emitted unit XML identities exactly match the frozen inventory. Frontend **275 passed / 29 files**, lint exit 0 with 9 existing warnings, production build passed. Full Python offline discovery **181 passed**, including RED→GREEN combined inventory/guard regressions. Temporary servers created internally by existing unit tests stopped themselves; no product dev service or Docker workload was started. Simulated Python scanner/probe messages are offline tests, not real scan/probe results.

Actual formal migration files are **24** (V1–V24); compared with accepted B, only C's V24 file is added, and V1–V23 are unchanged. Latest-schema count assertions and formal-failure gate now require V24; intentional failure stays V25. This does not allocate D's future migration. The frozen historical target/version checks were not replaced with maximum-version counting. Auto `.claude/settings.json` / `.trail/hook.cjs` remain preserved and uncommitted.

Pending: coordinator reviews the independent combined inventory/gate commit, supplies exact source and serial authorization, then C runs the existing Linux scan→unit→focused51→full187 facility, followed by the approved real browser `--with-assets` flow and precise cleanup. Combined PostgreSQL, real scan/EICAR, real OIDC HTTP and browser remain **NOT_RUN**. B's earlier accepted runtime counts and browser evidence do not stand in for this combined execution.

## First combined real run and repair checkpoint

Approved source `df72d4faae95159a4cd13664e1d22528442fd352`, run `ca55b950c8314f09989cf8d74d1b05bf`: scan93/1 and unit93 passed with zero F/E/S. Focused93/51 failed with 1 failure, 2 errors, no skips; full and browser were stopped and remain NOT_RUN. The original failure evidence is retained in `local-evidence/linux-tests/ca55b950c8314f09989cf8d74d1b05bf/`; all 22 captured container IDs were independently absent afterward, same-run labels empty and same Engine/context confirmed. No retry of that source was performed.

- FAILURE: ManualExecutionDatabaseIT.manifestAssignmentAndOldAttemptStayFrozenAfterReassignmentRenameAndRevision. Static trigger is deterministic: preparedRun("grid-snapshot", ...) already claims grid-snapshot-run before assignment, so the later same-key call correctly returns a frozen unassigned creation response. The test now retains that replay assertion and uses a distinct new-intent key, preserving original owner/history checks and additionally asserting replay cannot rewrite the persisted FINISHED attempt.
- ERROR: AssetMigrationUpgradeIT.v19NonEmptyDatabaseUpgradesToV20WithoutLosingExistingBusinessHistory and ReleaseMigrationUpgradeIT.nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable. Both old-stage fromStep calls go through LegacyDefectService.createCommand → DefectEvidence.prepare → current ExecutionService.attempt/run, whose V24 columns do not exist at V19/V18. A test-only HistoricalDefectEvidence61711294 now uses the exact fixed-source prepare/requirementLink bodies with the matching historical execution dependency; latest stages continue with production evidence/runtime. Original target snapshots, every old-column projection, manifest hashes and frozen replay/current-state assertions remain. Original reports contain no exception locations, so further causes remain unproven until a new real run.
- Diagnostic-only commit `3c1d624` adds fixed allowlisted exception types/SQLSTATE values and at most eight class/file/line frames to failure reports. No original messages, SQL, properties, system output or raw stack traces leave Linux; counts/selectors/status gates are unchanged. The two diagnostic regressions were first RED for missing diagnostics, then all 24 relevant Python gate/runner checks passed.
- Repair offline checks: explicit JDK21 Maven93 units passed with zero F/E/S; all 67 test sources compiled. Python discover183 passed, including unchanged frozen 93/187 source identities and 51 focus names. Three actual Spring context binding checks used a datasource that throws on every call: Asset-old, Release-old, Release-latest all selected the correct historical/current dependency without any datasource call. This is wiring evidence, not database acceptance. Production/UI/migration trees did not change, so earlier frontend275/lint/build remain the same-tree offline evidence; no new frontend run was performed for this repair.

New-source scan/unit/focused/full and the --with-assets real Keycloak browser sequence await the coordinator's fixed-source review/release. Do not report either original upgrade error as proven fixed before those executions.
