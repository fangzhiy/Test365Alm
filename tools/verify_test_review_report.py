"""Require exact real PostgreSQL, nonempty upgrade and OIDC review methods."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_r08_core_report import verify as verify_suites

REQUIRED = {
    "com.test365alm.server.review.TestReviewDatabaseIT": {
        "submitRejectResubmitApproveBindsExactContentAndPreservesEveryDecision",
        "newContentRevisionStartsDraftWhileHistoricalApprovalAndDigestRemainFrozen",
        "reviewerIdentityUsesUuidValueWhileDigestPreservesOriginalFrozenText",
        "onlyDesignatedWriterCanDecideAndViewerForeignScopesOrForgedIdentityHaveNoEffects",
        "disabledRevokedExpiredOrViewerReviewerCannotActOrReplay",
        "frozenReplayRetainsOriginalVersionAndStateAndReauthorizesItsActor",
        "concurrentDecisionsAndContentEditsUseOneSharedStrongVersion",
        "sameIntentConcurrentSubmissionsReturnOneFrozenEventAndNoDuplicateAudit",
        "missingReviewerInvalidTransitionsAndForeignRevisionNeverWrite",
        "auditAndOutboxFailureRollbackEventVersionAndIdempotencyAndSameIntentCanRecover",
        "runtimeRlsRejectsForgedBindingViewerWritesAndHistoricalMutationOrIncompleteCommit",
        "boundedHistoryUsesStableVersionCursorAndReadAuthorizationBeforeRecords",
    },
    "com.test365alm.server.review.TestReviewMigrationUpgradeIT": {
        "nonemptyPreReviewUpgradePreservesFrozenContentAndRuntimeReviewsExactRevision",
    },
    "com.test365alm.server.identity.OidcCallbackSecurityIT": {
        "realOidcReviewHttpDesignatedIdentityCsrfVersionsReplayAndImmutableRevision",
        "realOidcReviewHttpOldSessionsReauthorizeReviewerAndActorAfterRevocation",
    },
}


def verify(directory):
    return verify_suites(Path(directory), REQUIRED)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_test_review_report.py FAILSAFE_REPORT_DIRECTORY")
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
