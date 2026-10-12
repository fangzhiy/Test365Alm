import tempfile
import unittest
from pathlib import Path

from tools.verify_r05_test_case_report import (
    DATABASE_SUITE,
    HTTP_SUITE,
    MIGRATION_SUITE,
    OIDC_SUITE,
    REQUIRED_DATABASE_TESTS,
    REQUIRED_HTTP_TESTS,
    REQUIRED_MIGRATION_TESTS,
    REQUIRED_OIDC_TESTS,
    verify_test_case_report,
)


def _write(directory: Path, suite: str, names: set[str], *, skipped: int = 0) -> None:
    cases = "".join(f'<testcase name="{name}" />' for name in sorted(names))
    (directory / f"TEST-{suite}.xml").write_text(
        f'<testsuite name="{suite}" tests="{len(names)}" failures="0" errors="0" skipped="{skipped}">{cases}</testsuite>',
        encoding="utf-8",
    )


class R05TestCaseEvidenceTests(unittest.TestCase):
    def test_requires_all_real_suites_and_named_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write(root, DATABASE_SUITE, REQUIRED_DATABASE_TESTS)
            _write(root, HTTP_SUITE, REQUIRED_HTTP_TESTS)
            _write(root, MIGRATION_SUITE, REQUIRED_MIGRATION_TESTS)
            _write(root, OIDC_SUITE, REQUIRED_OIDC_TESTS)
            valid, summary = verify_test_case_report(root)
            self.assertTrue(valid, summary)

            _write(root, HTTP_SUITE, REQUIRED_HTTP_TESTS, skipped=1)
            valid, _ = verify_test_case_report(root)
            self.assertFalse(valid)

    def test_missing_suite_or_case_is_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write(root, DATABASE_SUITE, REQUIRED_DATABASE_TESTS - {next(iter(REQUIRED_DATABASE_TESTS))})
            _write(root, HTTP_SUITE, REQUIRED_HTTP_TESTS)
            _write(root, OIDC_SUITE, REQUIRED_OIDC_TESTS)
            valid, summary = verify_test_case_report(root)
            self.assertFalse(valid)
            self.assertIn("missing=", summary)

    def test_zero_tests_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / f"TEST-{DATABASE_SUITE}.xml").write_text(
                f'<testsuite name="{DATABASE_SUITE}" tests="0" failures="0" errors="0" skipped="0" />',
                encoding="utf-8",
            )
            _write(root, HTTP_SUITE, REQUIRED_HTTP_TESTS)
            _write(root, MIGRATION_SUITE, REQUIRED_MIGRATION_TESTS)
            _write(root, OIDC_SUITE, REQUIRED_OIDC_TESTS)
            valid, _ = verify_test_case_report(root)
            self.assertFalse(valid)


if __name__ == "__main__":
    unittest.main()
