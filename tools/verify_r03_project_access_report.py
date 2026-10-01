#!/usr/bin/env python3
"""Fail closed unless the Testcontainers project-access/RLS evidence ran."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_PROJECT_TESTS = {
    "runtimeRoleHasDmlButNoDdlOrBypassRls",
    "runtimeRoleSeesOnlyTheCurrentTenantThroughRls",
    "projectSliceCreatesAuditsGrantsViewerAndRevokesWithoutCrossTenantLeak",
    "tenantAdminOnlySeesProjectsWhereTheyAreExplicitlyJoined",
    "revokedTenantMembershipInvalidatesExistingProjectMembership",
    "authorizationVersionLastAdminAndAuditAreTransactional",
    "concurrentAdminRevokesRetainOneAdministrator",
}
SUITE_NAME = "com.test365alm.server.PlatformDatabaseIT"


def read_report(directory: Path) -> tuple[int, int, int, int, set[str]]:
    reports = sorted(directory.glob("TEST-com.test365alm.server.PlatformDatabaseIT.xml"))
    if len(reports) != 1:
        raise ValueError(f"expected exactly one PlatformDatabaseIT report, found {len(reports)}")
    root = ET.parse(reports[0]).getroot()
    if root.tag != "testsuite" or root.get("name") != SUITE_NAME:
        raise ValueError("report is not the expected PlatformDatabaseIT suite")
    tests = int(root.get("tests", "0"))
    failures = int(root.get("failures", "0"))
    errors = int(root.get("errors", "0"))
    skipped = int(root.get("skipped", "0"))
    names = {case.get("name", "") for case in root.findall("testcase")}
    return tests, failures, errors, skipped, names


def verify_project_access(directory: Path) -> tuple[bool, str]:
    tests, failures, errors, skipped, names = read_report(directory)
    missing = REQUIRED_PROJECT_TESTS - names
    summary = (
        f"project access/RLS tests={tests} failures={failures} "
        f"errors={errors} skipped={skipped} cases={len(names)}"
    )
    if missing:
        return False, summary + f" missing={sorted(missing)}"
    if failures or errors or skipped:
        return False, summary
    return True, summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r03_project_access_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    directory = Path(sys.argv[1])
    if not directory.is_dir():
        print("project access/RLS integration NOT_RUN: report directory missing", file=sys.stderr)
        return 1
    try:
        valid, summary = verify_project_access(directory)
    except (ET.ParseError, ValueError) as exc:
        print(f"project access/RLS integration report invalid: {exc}", file=sys.stderr)
        return 1
    print(summary)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
