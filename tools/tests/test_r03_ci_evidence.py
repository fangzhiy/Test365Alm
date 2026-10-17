import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.redact_r03_logs import redact
from tools.verify_r03_report import count_results
from tools.verify_r03_project_browser_report import REQUIRED_CORE_UI_TEST, REQUIRED_DEFECT_UI_TEST, REQUIRED_RELEASE_UI_TEST, REQUIRED_ASSET_UI_TEST
from tools.verify_r03_oidc_http_report import REQUIRED_TESTS, verify
from tools.verify_r03_project_access_report import REQUIRED_PROJECT_TESTS, verify_project_access
from tools.verify_r03_project_browser_report import REQUIRED_MANUAL_EXECUTION_UI_TEST, REQUIRED_MANUAL_TEST_CASE_UI_TEST, REQUIRED_REQUIREMENT_UI_TEST, REQUIRED_TEST as REQUIRED_PROJECT_BROWSER_TEST, REQUIRED_UI_TEST as REQUIRED_PROJECT_UI_TEST, verify as verify_project_browser

TERMINAL_HTTP_TEST = "realOidcAssetHttpTerminalCompleteFreezesRejectionAndReauthorizesReplay"


class R03CiEvidenceTests(unittest.TestCase):
    def _oidc_report(self, directory, names):
        suite = ET.Element("testsuite", name="com.test365alm.server.identity.OidcCallbackSecurityIT",
                           tests=str(len(names)), failures="0", errors="0", skipped="0")
        for name in names:
            ET.SubElement(suite, "testcase", name=name)
        report = Path(directory) / "TEST-com.test365alm.server.identity.OidcCallbackSecurityIT.xml"
        ET.ElementTree(suite).write(report, encoding="unicode")
        return suite, report

    def test_oidc_gate_exact_names_match_real_test_methods(self):
        source = (Path(__file__).resolve().parents[2] /
                  "apps/server/src/test/java/com/test365alm/server/identity/OidcCallbackSecurityIT.java").read_text(encoding="utf-8")
        methods = re.findall(r"@Test\s+void\s+(\w+)\s*\(", source)
        self.assertEqual(24, len(methods))
        self.assertEqual(len(methods), len(set(methods)))
        self.assertIn(TERMINAL_HTTP_TEST, methods)
        self.assertEqual(set(methods), REQUIRED_TESTS)

    def test_oidc_terminal_case_is_required_and_unexpected_names_still_fail(self):
        names = sorted(REQUIRED_TESTS | {TERMINAL_HTTP_TEST})
        with tempfile.TemporaryDirectory() as directory:
            self._oidc_report(directory, names)
            valid, summary = verify(Path(directory))
            self.assertTrue(valid, summary)
            self._oidc_report(directory, [name for name in names if name != TERMINAL_HTTP_TEST])
            self.assertFalse(verify(Path(directory))[0], "omitting terminal HTTP evidence must fail")
            self._oidc_report(directory, names + ["unexpectedSubstitute"])
            self.assertFalse(verify(Path(directory))[0], "the exact-name gate remains closed")

    def test_oidc_duplicate_case_cannot_hide_behind_suite_count(self):
        # Use the closed set itself: duplicate detection must not rely on a truthful tests attribute.
        with tempfile.TemporaryDirectory() as directory:
            for duplicate in (sorted(REQUIRED_TESTS)[0], TERMINAL_HTTP_TEST):
                with self.subTest(duplicate=duplicate):
                    suite, report = self._oidc_report(directory, sorted(REQUIRED_TESTS))
                    ET.SubElement(suite, "testcase", name=duplicate)
                    ET.ElementTree(suite).write(report, encoding="unicode")
                    with self.assertRaisesRegex(ValueError, "duplicate|count"):
                        verify(Path(directory))

    def test_oidc_terminal_failure_error_and_skip_are_rejected_even_with_zero_summary(self):
        names = sorted(REQUIRED_TESTS | {TERMINAL_HTTP_TEST})
        with tempfile.TemporaryDirectory() as directory:
            for tag, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                for declared in ("0", "1"):
                    with self.subTest(tag=tag, declared=declared):
                        suite, report = self._oidc_report(directory, names)
                        suite.set(attribute, declared)
                        case = next(case for case in suite.findall("testcase") if case.get("name") == TERMINAL_HTTP_TEST)
                        ET.SubElement(case, tag)
                        ET.ElementTree(suite).write(report, encoding="unicode")
                        self.assertFalse(verify(Path(directory))[0])

    def test_redacts_both_env_and_realm_passwords(self):
        env = "R03_KEYCLOAK_ADMIN_PASSWORD=admin-secret\nR03_TEST_USER_PASSWORD=user-secret\n"
        result = redact(env, "admin-secret user-secret Authorization: Bearer opaque-token")
        self.assertNotIn("admin-secret", result)
        self.assertNotIn("user-secret", result)
        self.assertNotIn("opaque-token", result)
        self.assertEqual(3, result.count("[REDACTED]"))

    def test_redacts_generated_asset_storage_and_scanner_credentials(self):
        env = ("TEST365ALM_ASSETS_S3_ACCESS_KEY=synthetic-access\n"
               "TEST365ALM_ASSETS_S3_SECRET_KEY=synthetic-secret\n"
               "R11_SCANNER_PASSWORD=synthetic-scanner\n"
               "TEST365ALM_ASSETS_SCANNER_DB_PASSWORD=synthetic-scanner\n"
               "AWS_ACCESS_KEY_ID=synthetic-sdk-access\n")
        result = redact(env, "S3 Access Key: synthetic-access; Secret Key: synthetic-secret; "
                        "scanner password: synthetic-scanner; SDK key: synthetic-sdk-access; "
                        "identity: admin-syntheti; storage failure: connection refused")
        for secret in ("synthetic-access", "synthetic-secret", "synthetic-scanner", "synthetic-sdk-access"):
            self.assertNotIn(secret, result)
        self.assertNotIn("admin-syntheti", result)
        self.assertIn("identity: admin-[REDACTED]", result)
        self.assertIn("storage failure: connection refused", result)
        self.assertEqual(5, result.count("[REDACTED]"))

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
                '<testsuites><testsuite tests="9" failures="0" errors="0" skipped="0">'
                f'<testcase name="{REQUIRED_PROJECT_BROWSER_TEST}" />'
                f'<testcase name="{REQUIRED_PROJECT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_REQUIREMENT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_MANUAL_TEST_CASE_UI_TEST}" />'
                f'<testcase name="{REQUIRED_MANUAL_EXECUTION_UI_TEST}" />'
                f'<testcase name="{REQUIRED_CORE_UI_TEST}" />'
                f'<testcase name="{REQUIRED_DEFECT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_RELEASE_UI_TEST}" />'
                f'<testcase name="{REQUIRED_ASSET_UI_TEST}" />'
                '</testsuite></testsuites>'
            )
            valid, summary = verify_project_browser(report)
            self.assertTrue(valid, summary)

            report.write_text(
                '<testsuites><testsuite tests="4" failures="0" errors="0" skipped="1">'
                f'<testcase name="{REQUIRED_PROJECT_BROWSER_TEST}"><skipped /></testcase>'
                f'<testcase name="{REQUIRED_PROJECT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_REQUIREMENT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_MANUAL_TEST_CASE_UI_TEST}" />'
                f'<testcase name="{REQUIRED_MANUAL_EXECUTION_UI_TEST}" />'
                '</testsuite></testsuites>'
            )
            valid, _ = verify_project_browser(report)
            self.assertFalse(valid)

    def test_requires_m09_manual_execution_browser_case(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.xml"
            report.write_text(
                '<testsuites><testsuite tests="4" failures="0" errors="0" skipped="0">'
                f'<testcase name="{REQUIRED_PROJECT_BROWSER_TEST}" />'
                f'<testcase name="{REQUIRED_PROJECT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_REQUIREMENT_UI_TEST}" />'
                f'<testcase name="{REQUIRED_MANUAL_TEST_CASE_UI_TEST}" />'
                '</testsuite></testsuites>'
            )
            valid, summary = verify_project_browser(report)
            self.assertFalse(valid)
            self.assertIn("manual execution", summary)


if __name__ == "__main__":
    unittest.main()
