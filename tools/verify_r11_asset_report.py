"""Exact M05 real PostgreSQL/S3/clamd, upgrade and OIDC HTTP report gate."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_r08_core_report import verify as verify_suites

REQUIRED = {
    'com.test365alm.server.asset.AssetDatabaseIT': {
        'memberUploadUsesPrivateQuarantineAndServerVerifiedReceipt',
        'contentSizeHashMimeAndStreamBoundsRejectWithoutPublication',
        'realClamdCleanAndEicarProduceSafeTerminalStates',
        'scannerUnavailableAndTimeoutRemainRetryableWithoutPublication',
        'newVersionsPreserveFrozenAttachmentBytesAndImmutableRows',
        'typedLinksRejectForeignMissingAndDeletedTargets',
        'runtimeRlsAndCurrentMembershipProtectEveryScope',
        'concurrentSameIntentCreatesOneReceiptScanAndRevision',
        'concurrentNewVersionsAllocateUniqueRevisionNumbers',
        'quarantinePutThenReceiptFailureHasNoDownloadAndSafelyRetries',
        'publishedObjectThenDatabaseFailureIsInaccessibleAndRecoverable',
        'attachmentAuditAndOutboxFailuresRollbackAndRecover',
        'postCommitCleanupFailureRetainsPublishedAccessAndPendingState',
        'boundedPagesAndSameHashTenantsRemainIndependent',
        'runtimeCannotForgeRevisionFromUnscannedOrRejectedUpload',
    },
    'com.test365alm.server.asset.AssetMigrationUpgradeIT': {
        'v19NonEmptyDatabaseUpgradesToV20WithoutLosingExistingBusinessHistory',
    },
    'com.test365alm.server.identity.OidcCallbackSecurityIT': {
        'realOidcAssetHttpIntegrityCsrfRolesAndIdempotency',
        'realOidcAssetHttpTypedLinksFrozenVersionsAndLiveDownloadRevocation',
    },
}


def verify(directory):
    return verify_suites(Path(directory), REQUIRED)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r11_asset_report.py FAILSAFE_REPORT_DIRECTORY')
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
