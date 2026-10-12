#!/usr/bin/env python3
"""Fail closed unless the real PostgreSQL requirement slice executed."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SUITE_NAME = "com.test365alm.server.requirement.RequirementDatabaseIT"
UPGRADE_SUITE_NAME = "com.test365alm.server.requirement.RequirementMigrationUpgradeIT"
REQUIRED_REQUIREMENT_TESTS = {
    "memberCanCreateEditAndReadImmutableHistory",
    "viewerReadsButCannotWriteAndStaleVersionDoesNotCreateRevision",
    "sameCreateKeyIsIdempotentAndDifferentPayloadConflicts",
    "concurrentCreatesWithSameKeyProduceOneFrozenResult",
    "concurrentCreatesWithDifferentKeysAllocateUniqueNumbers",
    "idempotencyReplayIsFrozenAndExpiryStartsANewIntent",
    "migratedV7ReplayIsRejectedWithoutLeakingHistoryAndRejectionSurvivesRevocation",
    "runtimeViewerCannotWriteRequirementSliceAndRevisionIsImmutable",
    "compositeScopeForeignKeysRejectCrossRequirementReferencesWithSqlState",
    "patchHashDistinguishesOmittedFieldFromLiteralNullText",
    "auditAndOutboxFailuresRollBackTheWholeRequirementTransaction",
    "auditAndOutboxUpdateFailuresRollBackExistingRequirementState",
    "concurrentUpdatesWithTheSameEtagProduceOneRevisionAndOnePreconditionFailure",
}
REQUIRED_UPGRADE_TESTS = {
    "v7RowsUpgradeRejectsUnsafeLegacyReplayThroughRuntimeService",
}


def verify_requirement_report(directory: Path) -> tuple[bool, str]:
    reports = sorted(directory.glob("TEST-com.test365alm.server.requirement.RequirementDatabaseIT.xml"))
    if len(reports) != 1:
        return False, f"expected one {SUITE_NAME} report, found {len(reports)}"
    try:
        root = ET.parse(reports[0]).getroot()
        if root.tag != "testsuite" or root.get("name") != SUITE_NAME:
            return False, "requirement report has an unexpected suite name"
        tests = int(root.get("tests", "0"))
        failures = int(root.get("failures", "0"))
        errors = int(root.get("errors", "0"))
        skipped = int(root.get("skipped", "0"))
        names = {case.get("name", "") for case in root.findall("testcase")}
    except (ET.ParseError, ValueError) as exc:
        return False, f"requirement report invalid: {exc}"
    missing = REQUIRED_REQUIREMENT_TESTS - names
    summary = f"requirement tests={tests} failures={failures} errors={errors} skipped={skipped} cases={len(names)}"
    if missing:
        return False, summary + f" missing={sorted(missing)}"
    if tests < len(REQUIRED_REQUIREMENT_TESTS) or failures or errors or skipped:
        return False, summary
    upgrade_reports = sorted(directory.glob("TEST-com.test365alm.server.requirement.RequirementMigrationUpgradeIT.xml"))
    if len(upgrade_reports) != 1:
        return False, summary + f" upgrade reports={len(upgrade_reports)} (expected one {UPGRADE_SUITE_NAME} report)"
    try:
        upgrade_root = ET.parse(upgrade_reports[0]).getroot()
        if upgrade_root.tag != "testsuite" or upgrade_root.get("name") != UPGRADE_SUITE_NAME:
            return False, summary + " upgrade report has an unexpected suite name"
        upgrade_tests = int(upgrade_root.get("tests", "0"))
        upgrade_failures = int(upgrade_root.get("failures", "0"))
        upgrade_errors = int(upgrade_root.get("errors", "0"))
        upgrade_skipped = int(upgrade_root.get("skipped", "0"))
        upgrade_names = {case.get("name", "") for case in upgrade_root.findall("testcase")}
    except (ET.ParseError, ValueError) as exc:
        return False, summary + f" upgrade report invalid: {exc}"
    upgrade_missing = REQUIRED_UPGRADE_TESTS - upgrade_names
    upgrade_summary = f"upgrade tests={upgrade_tests} failures={upgrade_failures} errors={upgrade_errors} skipped={upgrade_skipped} cases={len(upgrade_names)}"
    if upgrade_missing:
        return False, summary + " " + upgrade_summary + f" missing={sorted(upgrade_missing)}"
    if upgrade_tests < len(REQUIRED_UPGRADE_TESTS) or upgrade_failures or upgrade_errors or upgrade_skipped:
        return False, summary + " " + upgrade_summary
    return True, summary + " " + upgrade_summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r04_requirement_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    valid, summary = verify_requirement_report(Path(sys.argv[1]))
    print(summary, file=sys.stderr if not valid else sys.stdout)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
