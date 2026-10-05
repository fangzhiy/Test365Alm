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
    # FIX02 closes the M09 evidence gap with deterministic, real-database
    # concurrency, rollback, and frozen-idempotency scenarios.  Keep these
    # names explicit: a green Failsafe job with only the two original smoke
    # cases is not sufficient evidence for K07/K08.
    "concurrentStepWritesRejectExactlyOneStaleVersion",
    "concurrentRerunsAllowOnlyOneActiveAttempt",
    "concurrentStepWriteAndFinishHaveOneVersionedWinner",
    "concurrentSameKeyRerunReplaysTheFrozenResponse",
    "concurrentSameKeyRunCreationReplaysTheFrozenResponse",
    "concurrentSameKeyStepSaveReplaysTheFrozenResponse",
    "sameKeyDifferentStepContentIsRejectedWithoutSideEffects",
    "runtimeCannotMutateSealedManifestOrFinishedAttempt",
    "auditInsertFailureRollsBackRunCreation",
    "auditInsertFailureRollsBackStepSave",
    "auditInsertFailureRollsBackFinish",
    "outboxInsertFailureRollsBackRunCreation",
    "outboxInsertFailureRollsBackStepAndAttemptVersion",
    "outboxInsertFailureRollsBackFinish",
    "pauseReplayReturnsTheOriginalFrozenResponseAfterResume",
    "sourceRevisionChangesDoNotRewriteAnExistingRunManifest",
    "executionBuildingStillRejectsHistoricalManifestAndTerminalAttemptWrites",
    "pagedExecutionReadsAndSummaryUseStableProjectScopedCounts",
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
        cases = root.findall("testcase")
        names = [case.get("name", "") for case in cases]
    except (ET.ParseError, ValueError) as exc:
        return False, f"manual execution report invalid: {exc}"
    unique_names = set(names)
    missing = REQUIRED - unique_names
    summary = f"ManualExecutionDatabaseIT tests={tests} failures={failures} errors={errors} skipped={skipped} cases={len(names)}"
    if missing:
        summary += f" missing={sorted(missing)}"
    if tests != len(names):
        summary += " inconsistent-test-count"
    if len(unique_names) != len(names):
        summary += " duplicate-testcase-name"
    unexpected_classname = any(case.get("classname") != SUITE for case in cases)
    if unexpected_classname:
        summary += " unexpected-testcase-classname"
    if failures or errors or skipped or missing or tests != len(names) or not names or len(unique_names) != len(names) or unexpected_classname:
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
