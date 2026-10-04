#!/usr/bin/env python3
"""Fail closed unless the R06 manual execution integration suite ran."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


SUITE = "com.test365alm.server.execution.ManualExecutionDatabaseIT"
REQUIRED = {
    "createsImmutableManifestExecutesAndRerunsWithoutChangingSource",
    "zeroStepRevisionCannotStartAManualRun",
}


def verify_manual_execution_report(directory: Path) -> tuple[bool, str]:
    reports = sorted(directory.glob(f"TEST-{SUITE}.xml"))
    if len(reports) != 1:
        return False, f"expected one {SUITE} report, found {len(reports)}"
    try:
        root = ET.parse(reports[0]).getroot()
        if root.tag != "testsuite" or root.get("name") != SUITE:
            return False, "manual execution report has an unexpected suite name"
        tests = int(root.get("tests", "0"))
        failures = int(root.get("failures", "0"))
        errors = int(root.get("errors", "0"))
        skipped = int(root.get("skipped", "0"))
        names = {case.get("name", "") for case in root.findall("testcase")}
    except (ET.ParseError, ValueError) as exc:
        return False, f"manual execution report invalid: {exc}"
    missing = REQUIRED - names
    summary = f"ManualExecutionDatabaseIT tests={tests} failures={failures} errors={errors} skipped={skipped} cases={len(names)}"
    if missing:
        summary += f" missing={sorted(missing)}"
    if failures or errors or skipped or missing or tests < len(names) or tests == 0:
        return False, summary
    return True, summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r06_manual_execution_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    valid, summary = verify_manual_execution_report(Path(sys.argv[1]))
    print(summary, file=sys.stdout if valid else sys.stderr)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
