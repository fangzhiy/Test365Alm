"""Require actual M06 Vitest component/API names and honest complete-suite counters."""
import json
import sys
from collections import Counter
from pathlib import Path

REQUIRED = {
    'src/ReleasesPanel.test.tsx': {
        'release ADMIN writes while MEMBER and VIEWER read frozen metrics with no write entry',
        'project change aborts delayed list success and restores new scope without stale finally',
        'switch release during delayed detail ignores old response',
        'release switch aborts delayed snapshot and late success cannot replace new metrics',
        'release switch aborts delayed snapshot and late failure cannot replace new metrics',
        'release switch aborts delayed snapshot and late abort cannot replace new metrics',
        'all release cycle scope snapshot lists continue explicit cursors without automatic download',
        'locally confirmed snapshot is not duplicated when it also appears in the next server page',
        'cycle edits use exact detail version and preserve server date error draft',
        'CSRF rejection preserves draft while actual scope FORBIDDEN clears every descendant',
        '412 draft keeps original base until explicit adoption and does not auto submit',
        'unknown create locks real inputs and retries original key without list cancellation',
        'direct same-project access loss invalidates write then late success cannot restore old state',
        'direct same-project access loss invalidates write then late failure cannot restore old state',
        'direct same-project access loss invalidates write then late abort cannot restore old state',
        'confirmed evaluate with failed following GET keeps snapshot and uses GET-only recovery',
        'confirmed edit remains visibly confirmed after detail GET failure and recovery never repeats PATCH',
        'StrictMode timeout and unmount cancel timers while preserving same evaluate intent',
    },
    'src/releasesApi.test.ts': {
        'release HTTP validates dates states fields ETag and preserves business denial',
        'release pages use explicit cursors and snapshot metrics reject contradictory counts',
        'release HTTP serializes only editable fields and preserves original key and version',
    },
}


def verify(path):
    try:
        report = json.loads(Path(path).read_text(encoding='utf-8-sig'))
        if not isinstance(report, dict) or report.get('success') is not True:
            return False, 'FAIL: absent, malformed, or unsuccessful Vitest report'
        numeric = ('numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests',
                   'numTodoTests', 'numTotalTestSuites', 'numPassedTestSuites',
                   'numFailedTestSuites', 'numPendingTestSuites')
        if any(type(report.get(key)) is not int or report[key] < 0 for key in numeric):
            return False, 'FAIL: missing or invalid Vitest counters'
        if any(report[key] != 0 for key in ('numFailedTests', 'numPendingTests', 'numTodoTests',
                                            'numFailedTestSuites', 'numPendingTestSuites')):
            return False, 'FAIL: failed, pending, skipped, or todo results are not acceptance'
        if report.get('unhandledErrors') or report.get('numRuntimeErrorTestSuites', 0):
            return False, 'FAIL: unhandled or runtime errors'
        suites = report.get('testResults')
        if not isinstance(suites, list) or not suites:
            return False, 'FAIL: no actual test files'
        # Vitest counts nested describe suites, so this is not a file count.
        if report['numTotalTestSuites'] != report['numPassedTestSuites'] or report['numTotalTestSuites'] < len(suites):
            return False, 'FAIL: suite counters are inconsistent'
        actual = 0
        required_seen = {filename: Counter() for filename in REQUIRED}
        seen_files = set()
        for suite in suites:
            if not isinstance(suite, dict) or suite.get('status') != 'passed' or suite.get('message'):
                return False, 'FAIL: test file failed or has an error message'
            filename = suite.get('name')
            if not isinstance(filename, str) or not filename:
                return False, 'FAIL: missing test file identity'
            filename = filename.replace('\\', '/')
            if filename in seen_files:
                return False, 'FAIL: duplicate test file identity'
            seen_files.add(filename)
            cases = suite.get('assertionResults')
            if not isinstance(cases, list) or not cases:
                return False, 'FAIL: zero tests or malformed assertions in a test file'
            matches = [required for required in REQUIRED
                       if filename == required or filename.endswith('/' + required)]
            for case in cases:
                if not isinstance(case, dict) or case.get('status') != 'passed' or case.get('failureMessages') != []:
                    return False, 'FAIL: failed, errored, skipped, pending, or todo assertion'
                name = case.get('fullName')
                if not isinstance(name, str) or not name or not isinstance(case.get('title'), str):
                    return False, 'FAIL: missing assertion identity'
                actual += 1
                for required in matches:
                    required_seen[required][name] += 1
        if actual == 0 or actual != report['numTotalTests'] or actual != report['numPassedTests']:
            return False, 'FAIL: actual assertions disagree with global test counters'
        for filename, names in REQUIRED.items():
            if any(required_seen[filename][name] != 1 for name in names):
                return False, f'FAIL: missing or duplicate exact M06 name in {filename}'
        return True, f'PASS: {actual} actual Vitest assertions; M06 component 18 + API 3 exact names; zero failures/errors/skips'
    except (OSError, ValueError, TypeError):
        return False, 'FAIL: cannot read a valid Vitest JSON report'


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r10_release_web_report.py VITEST_JSON_REPORT')
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
