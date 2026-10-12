"""Synthetic XML here tests the gate, never stands in for database evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / "verify_r07_execution_upgrade_report.py"
SPEC = importlib.util.spec_from_file_location("r07_upgrade_gate", TOOL)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class UpgradeEvidenceTests(unittest.TestCase):
    def verify(self, names=None, *, tests=None, failures=0, errors=0, skipped=0,
               classname=None, suite=None, malformed=False, absent=False):
        names = list(GATE.REQUIRED) if names is None else names
        with tempfile.TemporaryDirectory() as directory:
            if not absent:
                cases = ''.join(f'<testcase name="{name}" classname="{classname or GATE.SUITE}"/>' for name in names)
                xml = f'<testsuite name="{suite or GATE.SUITE}" tests="{len(names) if tests is None else tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{cases}</testsuite>'
                Path(directory, f'TEST-{GATE.SUITE}.xml').write_text('<broken' if malformed else xml, encoding='utf-8')
            return GATE.verify_upgrade_report(Path(directory))[0]

    def test_accepts_two_named_successes(self): self.assertTrue(self.verify())
    def test_rejects_missing_report(self): self.assertFalse(self.verify(absent=True))
    def test_rejects_missing_v13_or_v14(self):
        for name in GATE.REQUIRED:
            with self.subTest(name=name): self.assertFalse(self.verify(names=[name]))
    def test_rejects_duplicate(self): self.assertFalse(self.verify(names=list(GATE.REQUIRED) * 2))
    def test_rejects_zero(self): self.assertFalse(self.verify(names=[]))
    def test_rejects_failed_errored_or_skipped(self):
        for key in ('failures', 'errors', 'skipped'):
            with self.subTest(key=key): self.assertFalse(self.verify(**{key: 1}))
    def test_rejects_wrong_suite_or_class(self):
        self.assertFalse(self.verify(suite='other'))
        self.assertFalse(self.verify(classname='other'))
    def test_rejects_inconsistent_count(self): self.assertFalse(self.verify(tests=99))
    def test_rejects_malformed(self): self.assertFalse(self.verify(malformed=True))


if __name__ == '__main__': unittest.main()
