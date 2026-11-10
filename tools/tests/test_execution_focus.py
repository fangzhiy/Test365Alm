"""Offline combined gate regression; no Docker, Maven, database or scan runs."""
import copy
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from tools import linux_test_inside as inside, linux_test_runner as runner


class ExecutionFocusTests(unittest.TestCase):
    def report(self):
        def summary(cases):
            return {"tests": len(cases), "cases": cases, "failures": 0, "errors": 0, "skipped": 0}
        return {"unit": summary([{"class": "Unit", "method": str(i)} for i in range(85)]),
                "integration": summary([{"class": c, "method": m} for c, m in sorted(inside.FOCUSED_METHODS)])}

    def test_fixed_fifty_one_names_and_no_extra_asset_provision_method(self):
        self.assertEqual(43, len(inside.EXECUTION_METHODS))
        self.assertEqual(8, len(inside.BUSINESS_METHODS))
        self.assertEqual(51, len(inside.FOCUSED_METHODS))
        self.assertTrue(inside.valid_report("focused", self.report()))
        selected = inside.PHASES["focused"]
        self.assertEqual(["-Pintegration", "verify"], selected[:2])
        self.assertIn("AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin", selected[2])
        self.assertNotIn(",AssetProvisioningUpgradeIT,", selected[2])
        self.assertEqual(1, selected[2].count("-Dit.test="))

    def test_each_missing_or_replaced_method_is_rejected_even_with_same_total(self):
        for index in range(51):
            bad = self.report()
            bad["integration"]["cases"][index]["method"] = "unapprovedReplacement"
            self.assertFalse(inside.valid_report("focused", bad))
        for change in ("duplicate", "missing", "extra", "failures", "errors", "skipped", "unit-count"):
            bad = self.report()
            if change == "duplicate":
                bad["integration"]["cases"][0] = copy.deepcopy(bad["integration"]["cases"][1])
            elif change == "missing":
                bad["integration"]["cases"].pop()
            elif change == "extra":
                bad["integration"]["cases"].append({"class": "Extra", "method": "extra"})
                bad["integration"]["tests"] += 1
            elif change == "unit-count":
                bad["unit"]["tests"] = 93  # A forecast cannot bypass the reviewed inventory.
            else:
                bad["integration"][change] = 1
            with self.subTest(change=change):
                self.assertFalse(inside.valid_report("focused", bad))

    def test_per_case_failures_cannot_hide_behind_zero_aggregate_counts(self):
        for field in ("failure", "error", "skipped"):
            bad = self.report()
            bad["integration"]["cases"][0][field] = True
            self.assertFalse(inside.valid_report("focused", bad))

    def test_combined_full_gate_retains_every_focused_method(self):
        good = self.report()
        cases = good["integration"]["cases"]
        cases.extend({"class": "Other", "method": str(i)} for i in range(167-len(cases)))
        good["integration"]["tests"] = 167
        self.assertTrue(inside.valid_report("integration", good))
        for index in range(51):
            bad = copy.deepcopy(good)
            bad["integration"]["cases"][index]["method"] = "unapprovedReplacement"
            self.assertFalse(inside.valid_report("integration", bad))

    def test_stage_order_and_each_failure_short_circuits_without_retry(self):
        for failure_index in range(5):
            outcomes = [0] * 4 if failure_index == 4 else [0] * failure_index + [7]
            with patch.object(runner, "phase", side_effect=outcomes) as phase:
                self.assertEqual(0 if failure_index == 4 else 7, runner.run_phases(Mock(), "runner", Path("evidence")))
            expected = ["scan", "unit", "focused", "integration"][:min(failure_index+1, 4)]
            self.assertEqual(expected, [call.args[2] for call in phase.call_args_list])

    def test_arbitrary_selectors_counts_or_phases_cannot_invoke_maven(self):
        for args in (["inside"], ["inside", "unapproved"], ["inside", "focused", "-Dit.test=Other"],
                     ["inside", "focused", "--expected-tests=1"]):
            with patch.object(inside.sys, "argv", args), patch.object(inside.subprocess, "run") as process:
                self.assertEqual(2, inside.main())
                process.assert_not_called()

    def test_scan_gate_requires_the_real_named_clean_and_eicar_method(self):
        report = self.report()
        report["integration"]["cases"] = [{"class": "com.test365alm.server.asset.AssetDatabaseIT", "method": "realClamdCleanAndEicarProduceSafeTerminalStates"}]
        report["integration"]["tests"] = 1
        self.assertTrue(inside.valid_report("scan", report))
        report["integration"]["cases"][0]["method"] = "someMockScan"
        self.assertFalse(inside.valid_report("scan", report))


if __name__ == "__main__":
    unittest.main()
