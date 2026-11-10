"""Inside-runner entrypoint. Raw Maven output and upload temp files never leave Linux."""
from __future__ import annotations
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PHASES = {
    "scan": ["-Pintegration", "verify", "-Dit.test=AssetDatabaseIT#realClamdCleanAndEicarProduceSafeTerminalStates"],
    "unit": ["test"],
    "focused": ["-Pintegration", "verify", "-Dit.test=BusinessFieldsDatabaseIT,BusinessFieldsMigrationUpgradeIT,ManualExecutionDatabaseIT,ManualExecutionMigrationUpgradeIT,AssetDatabaseIT#stepEvidence*,OidcCallbackSecurityIT#realOidcManualExecutionHttpRunsAndRejectsViewerWrites,AssetMigrationUpgradeIT,DefectMigrationUpgradeIT,ReleaseMigrationUpgradeIT,AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin"],
    "integration": ["-Pintegration", "verify"],
}

BUSINESS_METHODS = {
    ("com.test365alm.server.businessfields.BusinessFieldsDatabaseIT", name) for name in (
        "fieldsPersistOnExactRevisionReplayAndHistory",
        "customDefinitionRevisionsRemainFrozenAndCrossProjectIsRejected",
        "reviewerRejectsForeignDisabledExpiredAndRevokedIdentitiesAndReplayReauthorizes",
        "requirementAndDefectFieldsPersistAndStatusCopiesFrozenValues",
        "runtimeSqlCannotRewriteSealedFieldsOrInsertForeignReviewerAndViewerCannotWrite",
        "outboxFailureRollsBackBusinessFieldsAndIdempotency",
        "reviewerDirectoryIsReadableByMembersBoundedSearchableAndRejectsForeignOrInactiveScope",
    )
} | {("com.test365alm.server.businessfields.BusinessFieldsMigrationUpgradeIT",
      "nonemptyV22UpgradePreservesAllHistoricalContentAndRuntimeCannotRewriteIt")}

# Coordinator-approved C43 + B8, frozen by method identity, never CLI counts.
EXECUTION_METHODS = {
    ('com.test365alm.server.asset.AssetDatabaseIT', 'stepEvidenceAuditAndOutboxFailureRollBackLinkAndFrozenClaim'),
    ('com.test365alm.server.asset.AssetDatabaseIT', 'stepEvidenceRejectsViewerForeignPausedAndFinishedTargets'),
    ('com.test365alm.server.asset.AssetDatabaseIT', 'stepEvidenceUsesRealCleanRevisionAndPreservesOldAttemptOnRetry'),
    ('com.test365alm.server.asset.AssetMigrationUpgradeIT', 'v19NonEmptyDatabaseUpgradesToV20WithoutLosingExistingBusinessHistory'),
    ('com.test365alm.server.asset.AssetProvisioningUpgradeIT', 'upgradedDatabaseUsesProvisionedRestrictedScannerLogin'),
    ('com.test365alm.server.defect.DefectMigrationUpgradeIT', 'nonemptyV17RequirementsTestsRunsAndTraceSurviveV18AndRemainUsable'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'actualTextConclusionAndFirstExecutionTimePersistIndependentlyAcrossReloadAndReplay'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'assignmentAuditAndOutboxFailuresRollBackAssignmentEventAndClaim'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'assignmentConcurrentWritersFreezeReplayAndReauthorizeBeforeReturningIt'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'assignmentDirectoryPagesOnlyActiveWritersAndPreservesOpaqueCursor'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'assignmentsRejectViewerNonmemberCrossProjectDisabledAndExpiredIdentities'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'auditInsertFailureRollsBackFinish'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'auditInsertFailureRollsBackRunCreation'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'auditInsertFailureRollsBackStepSave'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentRerunsAllowOnlyOneActiveAttempt'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentSameKeyRerunReplaysTheFrozenResponse'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentSameKeyRunCreationReplaysTheFrozenResponse'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentSameKeyStepSaveReplaysTheFrozenResponse'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentStepWriteAndFinishHaveOneVersionedWinner'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'concurrentStepWritesRejectExactlyOneStaleVersion'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'createsImmutableManifestExecutesAndRerunsWithoutChangingSource'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'executionBuildingStillRejectsHistoricalManifestAndTerminalAttemptWrites'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'finishedAttemptRejectsLateStepEventsButFinishEventIsPersisted'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'gridFiltersBeforeLimitAndContinuesAcrossInstancesWithoutOtherSetLeak'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'manifestAssignmentAndOldAttemptStayFrozenAfterReassignmentRenameAndRevision'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'outboxInsertFailureRollsBackFinish'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'outboxInsertFailureRollsBackRunCreation'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'outboxInsertFailureRollsBackStepAndAttemptVersion'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'pagedExecutionReadsAndSummaryUseStableProjectScopedCounts'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'pauseReplayReturnsTheOriginalFrozenResponseAfterResume'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'runtimeCannotForgeAssignmentIdentityVersionScopeOrFrozenEvidenceIdentity'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'runtimeCannotMutateSealedManifestOrFinishedAttempt'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'sameKeyDifferentStepContentIsRejectedWithoutSideEffects'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'setPaginationContinuesBeyondDefaultPageWithStableTimestampTiesAndNoScopeLeak'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'sourceRevisionChangesDoNotRewriteAnExistingRunManifest'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'summarySeparatesUnrunActiveAndLatestCompletedResultsAcrossAuthorizedScopes'),
    ('com.test365alm.server.execution.ManualExecutionDatabaseIT', 'zeroStepRevisionCannotStartAManualRun'),
    ('com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT', 'v13NonEmptyExecutionDatabaseUpgradesToV16'),
    ('com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT', 'v14NonEmptyExecutionDatabaseUpgradesToV16'),
    ('com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT', 'v16NonEmptyExecutionDatabaseUpgradesToV17WithoutLosingHistoryOrReplay'),
    ('com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT', 'v22NonEmptyExecutionDatabaseUpgradesToV24WithUnknownHistoricalClocks'),
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcManualExecutionHttpRunsAndRejectsViewerWrites'),
    ('com.test365alm.server.release.ReleaseMigrationUpgradeIT', 'nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable'),
}
FOCUSED_METHODS = BUSINESS_METHODS | EXECUTION_METHODS


def valid_report(phase: str, report: dict) -> bool:
    # Keep audited baseline totals until the final B+C merge is inventoried by
    # method identity. 93/187 are forecasts, not permission to lower this gate.
    expected = {"scan": (85, 1), "unit": (85, None), "focused": (85, 51), "integration": (85, 167)}[phase]
    if report["unit"]["tests"] != expected[0]:
        return False
    if expected[1] is not None and report["integration"]["tests"] != expected[1]:
        return False
    for kind in ("unit", "integration"):
        if kind not in report:
            continue
        summary = report[kind]
        identities = [(case["class"], case["method"]) for case in summary["cases"]]
        if len(set(identities)) != len(identities) or len(identities) != summary["tests"]:
            return False
        if any(summary[key] for key in ("failures", "errors", "skipped")):
            return False
        if any(case.get(flag, False) for case in summary["cases"] for flag in ("failure", "error", "skipped")):
            return False
    if phase == "scan" and {(case["class"], case["method"]) for case in report["integration"]["cases"]} != {
            ("com.test365alm.server.asset.AssetDatabaseIT", "realClamdCleanAndEicarProduceSafeTerminalStates")}:
        return False
    if phase in ("focused", "integration"):
        actual = {(case["class"], case["method"]) for case in report["integration"]["cases"]}
        if not FOCUSED_METHODS <= actual:
            return False
    return True


def summarize(directory: Path) -> dict:
    cases = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for case in suite.findall("testcase"):
            # Never export system-out/err, exception messages, properties, or stack traces.
            cases.append({"class": case.get("classname"), "method": case.get("name"),
                          "failure": case.find("failure") is not None,
                          "error": case.find("error") is not None,
                          "skipped": case.find("skipped") is not None})
    for case in cases:
        if not all(re.fullmatch(r"[\w.$#()\[\], :\-]+", case[k] or "") for k in ("class", "method")):
            raise ValueError("Unexpected test identity; refuse export")
    if len({(c["class"], c["method"]) for c in cases}) != len(cases):
        raise ValueError("Duplicate test identities")
    return {"tests": len(cases), "failures": sum(c["failure"] for c in cases),
            "errors": sum(c["error"] for c in cases), "skipped": sum(c["skipped"] for c in cases), "cases": cases}


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in PHASES:
        return 2  # No arbitrary selector/count arguments, including inside the runner.
    phase = sys.argv[1]
    args = PHASES[phase]
    evidence = Path("/work/evidence")
    evidence.mkdir(exist_ok=True)
    target = Path("apps/server/target")
    # Clear only reports in this disposable runner, so previous focused results cannot count.
    import shutil
    for report in ("surefire-reports", "failsafe-reports"):
        shutil.rmtree(target / report, ignore_errors=True)
    command = ["mvn", "-B", "-ntp", *args]
    with (evidence / f"{phase}.raw.log").open("w") as output:
        result = subprocess.run(command, cwd="apps/server", stdout=output, stderr=subprocess.STDOUT, check=False)
    report = {"phase": phase, "command": command, "exit_code": result.returncode,
              "unit": summarize(target / "surefire-reports")}
    if phase != "unit":
        report["integration"] = summarize(target / "failsafe-reports")
    valid = valid_report(phase, report)
    report["expected_counts_and_zero_failures"] = valid
    report["exit_code"] = result.returncode or (0 if valid else 2)
    (evidence / f"{phase}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
