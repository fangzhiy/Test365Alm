import tempfile
import unittest
from pathlib import Path

from tools.redact_r03_logs import redact
from tools.verify_r03_report import count_results
from tools.verify_r03_oidc_http_report import REQUIRED_TESTS, verify
from tools.verify_r03_project_access_report import REQUIRED_PROJECT_TESTS, verify_project_access
from tools.verify_r03_project_browser_report import REQUIRED_TEST as REQUIRED_PROJECT_BROWSER_TEST, REQUIRED_UI_TEST as REQUIRED_PROJECT_UI_TEST, verify as verify_project_browser


class R03CiEvidenceTests(unittest.TestCase):
    def test_redacts_both_env_and_realm_passwords(self):
        env = "R03_KEYCLOAK_ADMIN_PASSWORD=admin-secret\nR03_TEST_USER_PASSWORD=user-secret\n"
        result = redact(env, "admin-secret user-secret Authorization: Bearer opaque-token")
        self.assertNotIn("admin-secret", result)
        self.assertNotIn("user-secret", result)
        self.assertNotIn("opaque-token", result)
        self.assertEqual(3, result.count("[REDACTED]"))

    def test_counts_real_executed_tests_and_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.xml"
            report.write_text('<testsuites><testsuite tests="3" failures="0" errors="0" skipped="0" /></testsuites>')
            self.assertEqual((3, 0, 0, 0), count_results(report))
            report.write_text('<testsuite tests="0" failures="0" errors="0" skipped="0" />')
            self.assertEqual((0, 0, 0, 0), count_results(report))

    def test_requires_all_real_http_callback_cases(self):
        self.assertIn("realHttpApplicationUsesRestrictedRuntimeRole", REQUIRED_TESTS)
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-com.test365alm.server.identity.OidcCallbackSecurityIT.xml"
            cases = "".join(f'<testcase name="{name}" />' for name in sorted(REQUIRED_TESTS))
            report.write_text(
                '<testsuite name="com.test365alm.server.identity.OidcCallbackSecurityIT" '
                f'tests="{len(REQUIRED_TESTS)}" failures="0" errors="0" skipped="0">{cases}</testsuite>'
            )
            valid, summary = verify(Path(directory))
            self.assertTrue(valid, summary)

            report.write_text(
                '<testsuite name="com.test365alm.server.identity.OidcCallbackSecurityIT" '
                'tests="1" failures="0" errors="0" skipped="0"><testcase name="legal" /></testsuite>'
            )
            valid, _ = verify(Path(directory))
            self.assertFalse(valid)

    def test_requires_real_project_access_rls_case(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-com.test365alm.server.PlatformDatabaseIT.xml"
            cases = "".join(f'<testcase name="{name}" />' for name in sorted(REQUIRED_PROJECT_TESTS))
            report.write_text(
                '<testsuite name="com.test365alm.server.PlatformDatabaseIT" '
                f'tests="{len(REQUIRED_PROJECT_TESTS)}" failures="0" errors="0" skipped="0">'
                f'{cases}'
                '</testsuite>'
            )
            valid, summary = verify_project_access(Path(directory))
            self.assertTrue(valid, summary)
            self.assertEqual(len(REQUIRED_PROJECT_TESTS), len(set(REQUIRED_PROJECT_TESTS)))

            report.write_text(
                '<testsuite name="com.test365alm.server.PlatformDatabaseIT" '
                'tests="1" failures="1" errors="0" skipped="0">'
                '<testcase name="runtimeRoleSeesOnlyTheCurrentTenantThroughRls"><failure /></testcase>'
                '</testsuite>'
            )
            valid, _ = verify_project_access(Path(directory))
            self.assertFalse(valid)

    def test_requires_real_dual_user_project_browser_case(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.xml"
            report.write_text(
                '<testsuites><testsuite tests="1" failures="0" errors="0" skipped="0">'
                f'<testcase name="{REQUIRED_PROJECT_BROWSER_TEST}" />'
                f'<testcase name="{REQUIRED_PROJECT_UI_TEST}" />'
                '</testsuite></testsuites>'
            )
            valid, summary = verify_project_browser(report)
            self.assertTrue(valid, summary)

            report.write_text(
                '<testsuites><testsuite tests="2" failures="0" errors="0" skipped="1">'
                f'<testcase name="{REQUIRED_PROJECT_BROWSER_TEST}"><skipped /></testcase>'
                f'<testcase name="{REQUIRED_PROJECT_UI_TEST}" />'
                '</testsuite></testsuites>'
            )
            valid, _ = verify_project_browser(report)
            self.assertFalse(valid)


if __name__ == "__main__":
    unittest.main()
