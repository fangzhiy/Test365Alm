import tempfile
import unittest
from pathlib import Path

from tools.redact_r03_logs import redact
from tools.verify_r03_report import count_results


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


if __name__ == "__main__":
    unittest.main()
