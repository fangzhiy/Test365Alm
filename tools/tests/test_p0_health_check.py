"""Tests for the read-only P0 planning/contract health check."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from p0_health_check import ROOT, check_health, main


class P0HealthCheckTests(unittest.TestCase):
    def test_baseline_reports_assets_without_claiming_business_acceptance(self):
        result = check_health(ROOT)

        self.assertTrue(result["ok"], result)
        self.assertTrue(result["package_check"]["ok"], result)
        self.assertTrue(result["contract_check"]["ok"], result)
        self.assertIsInstance(result["inputs_missing"], list)
        self.assertIn("B01", result["inputs_missing"])
        self.assertEqual(result["input_status"], "INPUTS_MISSING")
        self.assertFalse(result["p0_input_check"]["ok"])
        self.assertTrue(result["planning_only"])
        self.assertEqual(result["business_acceptance"], "NOT_EXECUTED")
        self.assertEqual(result["product_acceptance"], "NOT_EXECUTED")
        self.assertEqual(result["application_status"], "NOT_IMPLEMENTED")

    def test_missing_asset_is_reported_and_fails_asset_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = check_health(root)

        self.assertFalse(result["ok"])
        self.assertFalse(result["package_check"]["ok"])
        self.assertFalse(result["contract_check"]["ok"])
        self.assertIn("planning/module_catalogue.json", result["inputs_missing"])
        self.assertIn("contracts/openapi-core.json", result["inputs_missing"])
        self.assertIn("tools/validate_package.py", result["inputs_missing"])
        self.assertIn("B01", result["inputs_missing"])
        self.assertEqual(result["business_acceptance"], "NOT_EXECUTED")

    def test_main_emits_only_json_and_returns_nonzero_for_missing_root(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            with contextlib.redirect_stdout(output):
                exit_code = main(["--root", directory])

        payload = json.loads(output.getvalue())
        self.assertEqual(exit_code, 1)
        self.assertIn("package_check", payload)
        self.assertEqual(payload["business_acceptance"], "NOT_EXECUTED")
        self.assertEqual(payload["product_acceptance"], "NOT_EXECUTED")


if __name__ == "__main__":
    unittest.main()
