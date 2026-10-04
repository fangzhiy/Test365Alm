import tempfile
import unittest
from pathlib import Path

from tools.verify_r06_manual_execution_report import SUITE, verify_manual_execution_report


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
        valid, _ = verify_manual_execution_report(self.write(report([
            "createsImmutableManifestExecutesAndRerunsWithoutChangingSource",
            "zeroStepRevisionCannotStartAManualRun",
        ])))
        self.assertTrue(valid)

    def test_rejects_failure_even_when_cases_are_present(self) -> None:
        valid, _ = verify_manual_execution_report(self.write(report([
            "createsImmutableManifestExecutesAndRerunsWithoutChangingSource",
            "zeroStepRevisionCannotStartAManualRun",
        ], failures=1)))
        self.assertFalse(valid)
