import tempfile
import unittest
from pathlib import Path

from tools.verify_r06_manual_execution_report import SUITE, verify_manual_execution_report


REQUIRED_CASES = [
    "createsImmutableManifestExecutesAndRerunsWithoutChangingSource",
    "zeroStepRevisionCannotStartAManualRun",
    "concurrentStepWritesRejectExactlyOneStaleVersion",
    "concurrentSameKeyRunCreationReplaysTheFrozenResponse",
    "concurrentSameKeyStepSaveReplaysTheFrozenResponse",
    "sameKeyDifferentStepContentIsRejectedWithoutSideEffects",
    "runtimeCannotMutateSealedManifestOrFinishedAttempt",
    "concurrentRerunsAllowOnlyOneActiveAttempt",
    "concurrentStepWriteAndFinishHaveOneVersionedWinner",
    "concurrentSameKeyRerunReplaysTheFrozenResponse",
    "auditInsertFailureRollsBackRunCreation",
    "auditInsertFailureRollsBackStepSave",
    "auditInsertFailureRollsBackFinish",
    "outboxInsertFailureRollsBackRunCreation",
    "outboxInsertFailureRollsBackStepAndAttemptVersion",
    "outboxInsertFailureRollsBackFinish",
    "pauseReplayReturnsTheOriginalFrozenResponseAfterResume",
]


def report(cases: list[str], failures: int = 0, errors: int = 0, skipped: int = 0) -> str:
    rows = "".join(f'<testcase classname="{SUITE}" name="{name}" />' for name in cases)
    return f'<testsuite name="{SUITE}" tests="{len(cases)}" failures="{failures}" errors="{errors}" skipped="{skipped}">{rows}</testsuite>'


class ManualExecutionEvidenceTests(unittest.TestCase):
    def write(self, xml: str) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / f"TEST-{SUITE}.xml").write_text(xml, encoding="utf-8")
        return root

    def test_requires_both_real_cases(self) -> None:
        valid, summary = verify_manual_execution_report(self.write(report([
            "createsImmutableManifestExecutesAndRerunsWithoutChangingSource",
        ])))
        self.assertFalse(valid)
        self.assertIn("missing", summary)

    def test_accepts_complete_clean_report(self) -> None:
        valid, _ = verify_manual_execution_report(self.write(report(REQUIRED_CASES)))
        self.assertTrue(valid)

    def test_rejects_failure_even_when_cases_are_present(self) -> None:
        valid, _ = verify_manual_execution_report(self.write(report(REQUIRED_CASES, failures=1)))
        self.assertFalse(valid)

    def test_rejects_inflated_test_count(self) -> None:
        expected_count = len(REQUIRED_CASES)
        xml = report(REQUIRED_CASES).replace(f'tests="{expected_count}"', f'tests="{expected_count + 1}"')
        valid, summary = verify_manual_execution_report(self.write(xml))
        self.assertFalse(valid)
        self.assertIn("inconsistent", summary)

    def test_rejects_duplicate_testcase_names(self) -> None:
        xml = report(REQUIRED_CASES + [REQUIRED_CASES[-1]])
        valid, summary = verify_manual_execution_report(self.write(xml))
        self.assertFalse(valid)
        self.assertIn("duplicate", summary)

    def test_rejects_case_from_unexpected_suite(self) -> None:
        xml = report(REQUIRED_CASES).replace(f'classname="{SUITE}"', 'classname="other.Suite"', 1)
        valid, summary = verify_manual_execution_report(self.write(xml))
        self.assertFalse(valid)
        self.assertIn("unexpected-testcase-classname", summary)
