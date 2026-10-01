import tempfile
import unittest
from pathlib import Path

from tools.verify_r04_requirement_report import REQUIRED_REQUIREMENT_TESTS, verify_requirement_report


class R04RequirementEvidenceTests(unittest.TestCase):
    def test_requires_all_real_requirement_cases_and_zero_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-com.test365alm.server.requirement.RequirementDatabaseIT.xml"
            cases = "".join(f'<testcase name="{name}" />' for name in sorted(REQUIRED_REQUIREMENT_TESTS))
            report.write_text(
                '<testsuite name="com.test365alm.server.requirement.RequirementDatabaseIT" '
                f'tests="{len(REQUIRED_REQUIREMENT_TESTS)}" failures="0" errors="0" skipped="0">{cases}</testsuite>',
                encoding="utf-8",
            )
            valid, summary = verify_requirement_report(Path(directory))
            self.assertTrue(valid, summary)

            report.write_text(
                '<testsuite name="com.test365alm.server.requirement.RequirementDatabaseIT" '
                f'tests="{len(REQUIRED_REQUIREMENT_TESTS)}" failures="0" errors="0" skipped="1">{cases}</testsuite>',
                encoding="utf-8",
            )
            valid, _ = verify_requirement_report(Path(directory))
            self.assertFalse(valid)

    def test_missing_report_is_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            valid, summary = verify_requirement_report(Path(directory))
            self.assertFalse(valid)
            self.assertIn("found 0", summary)


if __name__ == "__main__":
    unittest.main()
