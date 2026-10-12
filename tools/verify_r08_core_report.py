"""Exact R08 database, real OIDC HTTP and non-empty upgrade evidence gate."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED = {
    'com.test365alm.server.core.CoreMetadataTraceDatabaseIT': {
        'fieldDefinitionsPersistImmutableRevisionsAndValidateExactValues',
        'fieldAdminBoundaryRlsAndForeignScopesRejectWithoutEffects',
        'fieldAuditAndOutboxFailuresRollbackTheWholeCommand',
        'traceLinksHaveRealEndpointsFrozenReplayAndNonPassUnexecutedCoverage',
        'concurrentFieldRevisionsHaveOneExactStaleLoserAndTraceCreatesHaveOneIdentity',
        'traceForeignDeletedEndpointsRlsAndAuditOutboxRollbackHaveNoEffects',
        'traceCoverageRequiresCurrentFrozenRevisionsPassAndNoActiveRerun',
        'fieldAndTraceKeysetPagesContinueWithoutScopeLeakOrDuplicates',
    },
    'com.test365alm.server.identity.OidcCallbackSecurityIT': {
        'realOidcFieldMetadataHttpAdminViewerCsrfTypesVersionsAndScope',
        'realOidcTraceHttpTypedEndpointsViewerCsrfRevokeAndNoIllegalEffects',
    },
    'com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT': {
        'v16NonEmptyExecutionDatabaseUpgradesToV17WithoutLosingHistoryOrReplay',
    },
}


def verify(directory: Path) -> tuple[bool, str]:
    summaries = []
    try:
        for suite, required in REQUIRED.items():
            path = directory / f'TEST-{suite}.xml'
            if not path.is_file():
                return False, f'NOT_RUN: {suite} report missing'
            root = ET.parse(path).getroot()
            counts = {k: int(root.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
            cases = root.findall('testcase')
            names = [c.get('name') for c in cases]
            valid = (root.tag == 'testsuite' and root.get('name') == suite
                     and counts['tests'] == len(cases) and counts['tests'] > 0
                     and len(names) == len(set(names)) and required.issubset(names)
                     and not any(counts[k] for k in ('failures', 'errors', 'skipped'))
                     and all(c.get('classname') == suite for c in cases)
                     and not any(c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped')))
            summaries.append(f'{suite}: {counts}; required={sorted(required)}; valid={valid}')
            if not valid:
                return False, '\n'.join(summaries)
    except (ET.ParseError, ValueError, KeyError, OSError) as error:
        return False, f'invalid R08 report: {type(error).__name__}'
    return True, '\n'.join(summaries)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r08_core_report.py FAILSAFE_REPORT_DIRECTORY')
    ok, summary = verify(Path(sys.argv[1]))
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
