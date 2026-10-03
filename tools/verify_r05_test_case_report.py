#!/usr/bin/env python3
"""Fail closed unless the real PostgreSQL M08 manual-test-case suites ran."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


DATABASE_SUITE = "com.test365alm.server.testcase.TestCaseDatabaseIT"
HTTP_SUITE = "com.test365alm.server.testcase.TestCaseHttpSecurityIT"
MIGRATION_SUITE = "com.test365alm.server.testcase.TestCaseMigrationUpgradeIT"

REQUIRED_DATABASE_TESTS = {
    "memberCreatesAndReadsInitialSteps",
    "stepEditsCreateImmutableRevision",
    "crossScopeReferencesRejectedAndHistoryImmutable",
    "concurrentRevisionSavesOneWins",
    "idempotentCreateAndRevisionReplay",
    "sameKeyConcurrentCreatesProduceOneCaseAndDifferentKeysKeepNumbersUnique",
    "viewerCannotModifyHistoryOrWriteOutboxThroughRuntimeRls",
    "auditAndOutboxFailuresRollBack",
}
REQUIRED_HTTP_TESTS = {
    "httpMatrixRejectsUnauthorizedAndInvalidInput",
}
REQUIRED_MIGRATION_TESTS = {
    "v9DataSurvivesV10Upgrade",
}


def _read_suite(directory: Path, suite_name: str) -> tuple[bool, str, set[str]]:
    simple_name = suite_name.rsplit(".", 1)[-1]
    reports = sorted(directory.glob(f"TEST-{suite_name}.xml"))
    if len(reports) != 1:
        return False, f"expected one {suite_name} report, found {len(reports)}", set()
    try:
        root = ET.parse(reports[0]).getroot()
        if root.tag != "testsuite" or root.get("name") != suite_name:
            return False, f"{simple_name} report has an unexpected suite name", set()
        tests = int(root.get("tests", "0"))
        failures = int(root.get("failures", "0"))
        errors = int(root.get("errors", "0"))
        skipped = int(root.get("skipped", "0"))
        names = {case.get("name", "") for case in root.findall("testcase")}
    except (ET.ParseError, ValueError) as exc:
        return False, f"{simple_name} report invalid: {exc}", set()
    summary = f"{simple_name} tests={tests} failures={failures} errors={errors} skipped={skipped} cases={len(names)}"
    if failures or errors or skipped:
        return False, summary, names
    if tests < len(names) or tests == 0:
        return False, summary + " (zero or inconsistent test count)", names
    return True, summary, names


def verify_test_case_report(directory: Path) -> tuple[bool, str]:
    checks = (
        (DATABASE_SUITE, REQUIRED_DATABASE_TESTS),
        (HTTP_SUITE, REQUIRED_HTTP_TESTS),
        (MIGRATION_SUITE, REQUIRED_MIGRATION_TESTS),
    )
    summaries: list[str] = []
    valid = True
    for suite_name, required in checks:
        suite_valid, summary, names = _read_suite(directory, suite_name)
        missing = required - names
        if missing:
            suite_valid = False
            summary += f" missing={sorted(missing)}"
        valid = valid and suite_valid
        summaries.append(summary)
    return valid, " ".join(summaries)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r05_test_case_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    valid, summary = verify_test_case_report(Path(sys.argv[1]))
    print(summary, file=sys.stdout if valid else sys.stderr)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())

