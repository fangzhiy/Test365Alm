"""Require exact real PostgreSQL, nonempty upgrade and OIDC simulated signature methods."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_r08_core_report import verify as verify_suites

REQUIRED = {
    'com.test365alm.server.signature.SignatureDatabaseIT': {
        'approvedRevisionFreezesVerifiedIdentityMeaningContentAndSimulatedEvidenceAtomically',
        'concurrentContentRevisionAndSigningSerializeWithoutSigningChangedContent',
        'concurrentDistinctChallengesProduceExactlyOneSignatureAndOneConflict',
        'concurrentSameIntentReturnsOneRecordAndDistinctIntentCannotDoubleSign',
        'draftSubmittedForeignReviewerViewerAndCrossProjectNeverCreateChallenges',
        'editsInvalidatePendingChallengeAndNewRevisionNeedsReviewWhileOldSignatureRemainsFrozen',
        'forgedCrossSessionOtherSubjectMissingOrStaleProofCannotSign',
        'outboxFailureRollsBackSignatureAuditAndIdempotencyAndOriginalIntentRecovers',
        'revokedDisabledAndExpiredMembershipCannotSignReplayOrRead',
        'runtimeCannotMutateSignedEvidenceTruncateOrWriteAsViewerAndRlsHidesForeignRecords',
        'sameIntentReplayIsFrozenAndNewKeyOrReusedKeyCannotCreateSecondSignature',
        'terminalRunAndClosedDeviationSignExactSnapshotsWithoutMutatingTheirVersions',
    },
    'com.test365alm.server.signature.SignatureMigrationUpgradeIT': {
        'nonemptyV25UpgradePreservesEveryOldColumnFrozenReplayReviewAndExecutionThenSignsThroughRuntime',
    },
    'com.test365alm.server.identity.OidcCallbackSecurityIT': {
        'realOidcSignatureHttpCrossSessionRevocationAndLogoutRejectPendingOrReplayedWrites',
        'realOidcSignatureHttpRejectsOtherSubjectMissingOrStaleAuthenticationAndInvalidNonce',
        'realOidcSignatureHttpSameSubjectFreshLoginCsrfStrongVersionAndFrozenReplay',
    },
}


def verify(directory):
    return verify_suites(Path(directory), REQUIRED)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_signature_report.py FAILSAFE_REPORT_DIRECTORY")
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
