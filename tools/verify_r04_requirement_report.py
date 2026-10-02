#!/usr/bin/env python3
"""Fail closed unless the real PostgreSQL requirement slice executed."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SUITE_NAME = "com.test365alm.server.requirement.RequirementDatabaseIT"
REQUIRED_REQUIREMENT_TESTS = {
    "memberCanCreateEditAndReadImmutableHistory",
    "viewerReadsButCannotWriteAndStaleVersionDoesNotCreateRevision",
    "sameCreateKeyIsIdempotentAndDifferentPayloadConflicts",
    "idempotencyReplayIsFrozenAndExpiryStartsANewIntent",
    "runtimeViewerCannotWriteRequirementSliceAndRevisionIsImmutable",
    "compositeScopeForeignKeysRejectCrossRequirementReferencesWithSqlState",
    "patchHashDistinguishesOmittedFieldFromLiteralNullText",
    "auditAndOutboxFailuresRollBackTheWholeRequirementTransaction",
    "concurrentUpdatesWithTheSameEtagProduceOneRevisionAndOnePreconditionFailure",
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
    return True, summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r04_requirement_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    valid, summary = verify_requirement_report(Path(sys.argv[1]))
    print(summary, file=sys.stderr if not valid else sys.stdout)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
