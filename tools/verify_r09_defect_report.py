"""Fail closed on missing exact M10 database, HTTP and nonempty upgrade evidence."""
import sys
from pathlib import Path
try:
    from tools.verify_r08_core_report import verify as verify_suites
except ModuleNotFoundError:
    from verify_r08_core_report import verify as verify_suites

REQUIRED = {
    'com.test365alm.server.defect.DefectDatabaseIT': {
        'memberCreatesImmutableDefectAndFrozenReplayWithAtomicEvidence',
        'concurrentEditsAndTransitionsHaveOneExactStaleLoser',
        'lifecycleRejectsIllegalTransitionsAndClosedHistoryCannotBeRewritten',
        'failingStepSourceAndExplicitRetestsPreserveFailureAndRequireRelatedPass',
        'invalidSourceForeignEndpointsAndViewerSqlWritesHaveNoEffects',
        'auditAndOutboxFailuresRollbackCreateEditAndTransitionExactly',
        'concurrentSameKeyCreationAndBoundedPagesHaveNoDuplicateNumbers',
        'unrelatedPassActiveRetestAndPreviousResolutionCannotVerifyCurrentCycle',
        'legitimateRuntimeWriterCannotForgeInitialStateAppendCommittedSourceOrAlterSnapshot',
    },
    'com.test365alm.server.defect.DefectMigrationUpgradeIT': {
        'nonemptyV17RequirementsTestsRunsAndTraceSurviveV18AndRemainUsable',
    },
    'com.test365alm.server.identity.OidcCallbackSecurityIT': {
        'realOidcDefectHttpMemberEtagsCsrfStrictInputAndViewerBoundary',
        'realOidcDefectHttpFailedSourceRetestAndClosePreserveOriginalEvidence',
    },
}

def verify(directory):
    return verify_suites(Path(directory), REQUIRED)

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r09_defect_report.py FAILSAFE_REPORT_DIRECTORY')
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
