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
    "focused": ["-Pintegration", "verify", "-Dit.test=BusinessFieldsDatabaseIT,BusinessFieldsMigrationUpgradeIT,ManualExecutionDatabaseIT,ManualExecutionMigrationUpgradeIT,AssetDatabaseIT#stepEvidence*,OidcCallbackSecurityIT#realOidcManualExecutionHttpRunsAndRejectsViewerWrites+realOidcReviewHttpDesignatedIdentityCsrfVersionsReplayAndImmutableRevision+realOidcReviewHttpOldSessionsReauthorizeReviewerAndActorAfterRevocation+realOidcSignatureHttpCrossSessionRevocationAndLogoutRejectPendingOrReplayedWrites+realOidcSignatureHttpRejectsOtherSubjectMissingOrStaleAuthenticationAndInvalidNonce+realOidcSignatureHttpSameSubjectFreshLoginCsrfStrongVersionAndFrozenReplay,SignatureDatabaseIT,SignatureMigrationUpgradeIT,TestReviewDatabaseIT,TestReviewMigrationUpgradeIT,AssetMigrationUpgradeIT,DefectMigrationUpgradeIT,ReleaseMigrationUpgradeIT,AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin"],
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
# D review identities are fixed alongside the original C43+B8 gate.
REVIEW_METHODS = {
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcReviewHttpDesignatedIdentityCsrfVersionsReplayAndImmutableRevision'),
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcReviewHttpOldSessionsReauthorizeReviewerAndActorAfterRevocation'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'auditAndOutboxFailureRollbackEventVersionAndIdempotencyAndSameIntentCanRecover'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'boundedHistoryUsesStableVersionCursorAndReadAuthorizationBeforeRecords'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'concurrentDecisionsAndContentEditsUseOneSharedStrongVersion'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'disabledRevokedExpiredOrViewerReviewerCannotActOrReplay'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'frozenReplayRetainsOriginalVersionAndStateAndReauthorizesItsActor'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'missingReviewerInvalidTransitionsAndForeignRevisionNeverWrite'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'newContentRevisionStartsDraftWhileHistoricalApprovalAndDigestRemainFrozen'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'onlyDesignatedWriterCanDecideAndViewerForeignScopesOrForgedIdentityHaveNoEffects'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'reviewerIdentityUsesUuidValueWhileDigestPreservesOriginalFrozenText'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'runtimeRlsRejectsForgedBindingViewerWritesAndHistoricalMutationOrIncompleteCommit'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'sameIntentConcurrentSubmissionsReturnOneFrozenEventAndNoDuplicateAudit'),
    ('com.test365alm.server.review.TestReviewDatabaseIT', 'submitRejectResubmitApproveBindsExactContentAndPreservesEveryDecision'),
    ('com.test365alm.server.review.TestReviewMigrationUpgradeIT', 'nonemptyPreReviewUpgradePreservesFrozenContentAndRuntimeReviewsExactRevision'),
}
SIGNATURE_METHODS = {
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'approvedRevisionFreezesVerifiedIdentityMeaningContentAndSimulatedEvidenceAtomically'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'concurrentContentRevisionAndSigningSerializeWithoutSigningChangedContent'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'concurrentDistinctChallengesProduceExactlyOneSignatureAndOneConflict'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'concurrentSameIntentReturnsOneRecordAndDistinctIntentCannotDoubleSign'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'draftSubmittedForeignReviewerViewerAndCrossProjectNeverCreateChallenges'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'editsInvalidatePendingChallengeAndNewRevisionNeedsReviewWhileOldSignatureRemainsFrozen'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'forgedCrossSessionOtherSubjectMissingOrStaleProofCannotSign'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'outboxFailureRollsBackSignatureAuditAndIdempotencyAndOriginalIntentRecovers'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'revokedDisabledAndExpiredMembershipCannotSignReplayOrRead'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'runtimeCannotMutateSignedEvidenceTruncateOrWriteAsViewerAndRlsHidesForeignRecords'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'sameIntentReplayIsFrozenAndNewKeyOrReusedKeyCannotCreateSecondSignature'),
    ('com.test365alm.server.signature.SignatureDatabaseIT', 'terminalRunAndClosedDeviationSignExactSnapshotsWithoutMutatingTheirVersions'),
    ('com.test365alm.server.signature.SignatureMigrationUpgradeIT', 'nonemptyV25UpgradePreservesEveryOldColumnFrozenReplayReviewAndExecutionThenSignsThroughRuntime'),
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcSignatureHttpCrossSessionRevocationAndLogoutRejectPendingOrReplayedWrites'),
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcSignatureHttpRejectsOtherSubjectMissingOrStaleAuthenticationAndInvalidNonce'),
    ('com.test365alm.server.identity.OidcCallbackSecurityIT', 'realOidcSignatureHttpSameSubjectFreshLoginCsrfStrongVersionAndFrozenReplay'),
}
FOCUSED_METHODS = BUSINESS_METHODS | EXECUTION_METHODS | REVIEW_METHODS | SIGNATURE_METHODS
INVENTORY = json.loads((Path(__file__).with_name("linux-test") / "combined-methods.json").read_text(encoding="utf-8"))
UNIT_METHODS = {(suite,name) for suite,names in INVENTORY["unit"].items() for name in names}
INTEGRATION_METHODS = {(suite,name) for suite,names in INVENTORY["integration"].items() for name in names}
if len(UNIT_METHODS) != 107 or len(INTEGRATION_METHODS) != 226 or not FOCUSED_METHODS <= INTEGRATION_METHODS:
    raise ValueError("Frozen combined method inventory does not match the reviewed gate")


def valid_report(phase: str, report: dict) -> bool:
    # The merged source inventory is frozen by identity, not a caller-selected total.
    expected = {"scan": (107, 1), "unit": (107, None), "focused": (107, 82), "integration": (107, 226)}[phase]
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
        required = UNIT_METHODS if kind == "unit" else INTEGRATION_METHODS if phase == "integration" else None
        if required is not None and set(identities) != required:
            return False
    if phase == "scan" and {(case["class"], case["method"]) for case in report["integration"]["cases"]} != {
            ("com.test365alm.server.asset.AssetDatabaseIT", "realClamdCleanAndEicarProduceSafeTerminalStates")}:
        return False
    if phase in ("focused", "integration"):
        actual = {(case["class"], case["method"]) for case in report["integration"]["cases"]}
        if not FOCUSED_METHODS <= actual:
            return False
    return True


# Failure diagnostics are lossy classifications, never raw exception/log exports.
DIAGNOSTIC_TYPES = frozenset((
    "org.opentest4j.AssertionFailedError", "java.lang.AssertionError",
    "org.springframework.jdbc.BadSqlGrammarException",
    "org.springframework.dao.DataIntegrityViolationException",
    "org.springframework.dao.InvalidDataAccessApiUsageException",
    "org.postgresql.util.PSQLException", "java.lang.IllegalStateException",
    "com.test365alm.server.project.ProjectAccessException",
    "org.flywaydb.core.internal.command.DbMigrate$FlywayMigrateException",
    "org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException",
))
DIAGNOSTIC_SQLSTATES = frozenset(("42601", "42703", "42704", "42883", "42P01", "42501", "23502", "23503", "23505", "23514", "40001", "40P01"))
DIAGNOSTIC_MIGRATIONS = {
    path.name: (int(path.name.split('__', 1)[0][1:]), len(path.read_text(encoding='utf-8').splitlines()))
    for path in (Path(__file__).resolve().parents[1] / 'apps/server/src/main/resources/db/migration').glob('V*__*.sql')
    if re.fullmatch(r'V[1-9][0-9]*__[a-z0-9_]+\.sql', path.name)
}
DIAGNOSTIC_CLASSES = {suite for suite, _ in UNIT_METHODS | INTEGRATION_METHODS} | {
    "com.test365alm.server.execution.ExecutionService",
    "com.test365alm.server.fixture.HistoricalExecutionService61711294",
    "com.test365alm.server.fixture.HistoricalExecutionUpgradeSupport",
    "com.test365alm.server.defect.DefectEvidence",
    "com.test365alm.server.defect.LegacyDefectService",
    "com.test365alm.server.defect.DefectService",
}


def failure_diagnostics(node: ET.Element) -> dict:
    # Bound both input work and output size; ignore attributes other than type.
    body = (node.text or "")[:262144]
    types = []
    for candidate in [node.get("type", ""), *re.findall(r"(?m)^(?:Caused by: )?([\w.$]+)(?=:|$)", body)]:
        if candidate in DIAGNOSTIC_TYPES and candidate not in types:
            types.append(candidate)
    states = sorted(set(re.findall(r"\b(?:SQLState|SQL[ \t]+[Ss]tate|SQLSTATE)[ \t]*:?[ \t]*([A-Z0-9]{5})\b", body)) & DIAGNOSTIC_SQLSTATES)
    frames = []
    for cls, filename, line in re.findall(r"(?m)^\s*at ([\w.$]+)\.[\w$<>]+\(([\w$]+\.java):(\d{1,5})\)\s*$", body):
        if cls not in DIAGNOSTIC_CLASSES or filename != cls.rsplit(".", 1)[-1] + ".java" or int(line) < 1:
            continue
        frame = {"class": cls, "file": filename, "line": int(line)}
        if frame not in frames:
            frames.append(frame)
        if len(frames) == 8:
            break
    migrations = []
    locations = list(re.finditer(r"(?m)(?:^Migration |: Migration )([^\r\n]+) failed[ \t]*$", body))
    for index, location in enumerate(locations):
        known = DIAGNOSTIC_MIGRATIONS.get(location.group(1))
        if known is None:
            continue
        end = locations[index + 1].start() if index + 1 < len(locations) else len(body)
        for match in re.finditer(r"(?m)^Line[ \t]*:[ \t]*([1-9][0-9]{0,4})[ \t]*$", body[location.end():end]):
            line = int(match.group(1))
            item = {"version": known[0], "line": line}
            if line <= known[1] and item not in migrations:
                migrations.append(item)
            if len(migrations) == 4:
                break
        if len(migrations) == 4:
            break
    return {"types": types, "sqlstates": states, "frames": frames, "migrations": migrations}


def summarize(directory: Path) -> dict:
    cases = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for case in suite.findall("testcase"):
            # Never export system-out/err, messages, properties, SQL, or raw stack traces.
            item = {"class": case.get("classname"), "method": case.get("name"),
                          "failure": case.find("failure") is not None,
                          "error": case.find("error") is not None,
                          "skipped": case.find("skipped") is not None}
            failed = case.find("failure")
            if failed is None:
                failed = case.find("error")
            if failed is not None:
                item["diagnostics"] = failure_diagnostics(failed)
            cases.append(item)
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
