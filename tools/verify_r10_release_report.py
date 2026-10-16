"""Require exact M06 runtime DB, nonempty upgrade and real OIDC HTTP evidence."""
import sys
from pathlib import Path
try:
    from tools.verify_r08_core_report import verify as verify_suites
except ModuleNotFoundError:
    from verify_r08_core_report import verify as verify_suites

REQUIRED = {
    'com.test365alm.server.release.ReleaseCycleDatabaseIT': {
        'adminCreatesReleaseCycleAndFrozenIdempotentResultsWithAtomicEvents',
        'datesRejectInvalidCyclesAndParentShrinkWithoutSideEffects',
        'archivedReleaseAndClosedCycleRejectIllegalWritesAndTransitions',
        'typedScopeFreezesCurrentRevisionsAndRejectsForeignOrWrongTargets',
        'snapshotsStayImmutableWhileTraceExecutionDefectAndScopeFactsChange',
        'metricsExcludeInactiveTraceActiveRerunsAndUnrelatedRevisionPass',
        'adminOnlyRuntimeRlsRejectsMembersForeignScopesAndIdentityLoss',
        'twoAdminsUpdatingReleaseOrCycleHaveOneExactStaleLoser',
        'sameKeyConcurrentCreatesEvaluateAndScopeHaveOneFrozenResult',
        'auditAndOutboxFailuresRollbackCreateEditScopeAndEvaluateExactly',
        'boundedListsContinueWithoutDuplicatesOrForeignProjects',
        'runtimeCannotRewriteSnapshotsOrForgeCrossScopeRelations',
    },
    'com.test365alm.server.release.ReleaseMigrationUpgradeIT': {
        'nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable',
    },
    'com.test365alm.server.identity.OidcCallbackSecurityIT': {
        'realOidcReleaseHttpAdminDatesEtagsCsrfAndRoleBoundary',
        'realOidcReleaseHttpTypedScopeSnapshotsAndOldSessionRevocation',
    },
}


def verify(directory):
    return verify_suites(Path(directory), REQUIRED)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r10_release_report.py FAILSAFE_REPORT_DIRECTORY')
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
