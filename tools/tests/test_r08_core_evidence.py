"""Synthetic reports test only gates, never substitute for application evidence."""
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.verify_r08_core_report import REQUIRED, verify
from tools.verify_r03_project_browser_report import REQUIRED_BROWSER_TESTS, verify as browser_verify


class CoreEvidenceTests(unittest.TestCase):
    def fixture(self, directory):
        for suite, names in REQUIRED.items():
            root = ET.Element('testsuite', name=suite, tests=str(len(names)), failures='0', errors='0', skipped='0')
            for name in names: ET.SubElement(root, 'testcase', name=name, classname=suite)
            ET.ElementTree(root).write(Path(directory, f'TEST-{suite}.xml'))

    def test_accepts_exact_named_successes(self):
        with tempfile.TemporaryDirectory() as d:
            self.fixture(d); self.assertTrue(verify(Path(d))[0])

    def test_rejects_every_missing_required_name_and_report(self):
        for suite, names in REQUIRED.items():
            for name in names:
                with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                    self.fixture(d); path = Path(d, f'TEST-{suite}.xml'); tree = ET.parse(path)
                    root = tree.getroot(); root.remove(next(c for c in root if c.get('name') == name))
                    root.set('tests', str(len(root))); tree.write(path)
                    self.assertFalse(verify(Path(d))[0])
            with tempfile.TemporaryDirectory() as d:
                self.fixture(d); Path(d, f'TEST-{suite}.xml').unlink(); self.assertFalse(verify(Path(d))[0])

    def test_rejects_failure_error_skip_duplicate_and_bad_counts(self):
        for mode in ('failure', 'error', 'skipped', 'duplicate', 'count', 'malformed', 'wrong_class'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as d:
                self.fixture(d); suite = next(iter(REQUIRED)); path = Path(d, f'TEST-{suite}.xml'); tree = ET.parse(path); root = tree.getroot()
                if mode in ('failure', 'error', 'skipped'): ET.SubElement(root[0], mode)
                elif mode == 'duplicate': root[1].set('name', root[0].get('name'))
                elif mode == 'count': root.set('tests', '0')
                elif mode == 'wrong_class': root[0].set('classname', 'other')
                tree.write(path)
                if mode == 'malformed': path.write_text('<broken')
                self.assertFalse(verify(Path(d))[0])

    def test_browser_requires_core_flow_and_rejects_missing_or_failed_case(self):
        for mode in ('ok', 'missing', 'failure', 'error', 'skipped', 'duplicate'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as d:
                root = ET.Element('testsuite', tests=str(len(REQUIRED_BROWSER_TESTS)), failures='0', errors='0', skipped='0')
                for name in REQUIRED_BROWSER_TESTS: ET.SubElement(root, 'testcase', name=name)
                if mode == 'missing': root.remove(root[-1]); root.set('tests', str(len(root)))
                elif mode in ('failure', 'error', 'skipped'): ET.SubElement(root[-1], mode)
                elif mode == 'duplicate': root[-1].set('name', root[0].get('name'))
                path=Path(d,'browser.xml'); ET.ElementTree(root).write(path)
                self.assertEqual(mode=='ok',browser_verify(path)[0])

    def test_ci_calls_core_gate_and_uploads_report(self):
        text=Path(__file__).resolve().parents[2].joinpath('.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('python ../../tools/verify_r08_core_report.py target/failsafe-reports',text)
        self.assertIn('apps/server/core-r08-report.txt',text)
