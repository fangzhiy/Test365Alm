"""Synthetic offline reports test the gate, not the application's acceptance result."""
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.verify_r10_release_report import REQUIRED, verify
from tools.verify_r03_project_browser_report import REQUIRED_BROWSER_TESTS, verify as browser_verify

RELEASE_UI = 'real Keycloak UI admin plans release cycle scope and freezes quality snapshots'


class ReleaseEvidenceTests(unittest.TestCase):
    def fixture(self, directory):
        for suite, names in REQUIRED.items():
            root = ET.Element('testsuite', name=suite, tests=str(len(names)), failures='0', errors='0', skipped='0')
            for name in names:
                ET.SubElement(root, 'testcase', name=name, classname=suite)
            ET.ElementTree(root).write(Path(directory, f'TEST-{suite}.xml'))

    def test_exact_release_db_upgrade_http_names_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            self.assertTrue(verify(directory)[0])

    def test_each_missing_required_name_or_report_is_rejected(self):
        for suite, names in REQUIRED.items():
            for name in names:
                with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                    self.fixture(directory)
                    path = Path(directory, f'TEST-{suite}.xml')
                    tree = ET.parse(path)
                    root = tree.getroot()
                    root.remove(next(case for case in root if case.get('name') == name))
                    root.set('tests', str(len(root)))
                    tree.write(path)
                    self.assertFalse(verify(directory)[0])
            with tempfile.TemporaryDirectory() as directory:
                self.fixture(directory)
                Path(directory, f'TEST-{suite}.xml').unlink()
                self.assertFalse(verify(directory)[0])

    def test_failure_error_skip_duplicate_count_and_class_cannot_pass(self):
        for suite in REQUIRED:
            for mode in ('failure', 'error', 'skipped', 'duplicate', 'count', 'class', 'malformed'):
                with self.subTest(suite=suite, mode=mode), tempfile.TemporaryDirectory() as directory:
                    self.fixture(directory)
                    path = Path(directory, f'TEST-{suite}.xml')
                    tree = ET.parse(path)
                    root = tree.getroot()
                    if mode in ('failure', 'error', 'skipped'):
                        ET.SubElement(root[0], mode)
                    elif mode == 'duplicate':
                        ET.SubElement(root, 'testcase', name=root[0].get('name'), classname=suite)
                        root.set('tests', str(len(root)))
                    elif mode == 'count':
                        root.set('tests', '0')
                    elif mode == 'class':
                        root[0].set('classname', 'wrong')
                    tree.write(path)
                    if mode == 'malformed':
                        path.write_text('<broken')
                    self.assertFalse(verify(directory)[0])

    def test_previous_browser_suite_without_m06_is_rejected(self):
        self.assertIn(RELEASE_UI, REQUIRED_BROWSER_TESTS)
        for mode in ('ok', 'missing', 'failure', 'error', 'skipped', 'duplicate'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                names = [name for name in REQUIRED_BROWSER_TESTS if name != RELEASE_UI]
                if mode != 'missing':
                    names.append(RELEASE_UI)
                root = ET.Element('testsuite', tests=str(len(names)), failures='0', errors='0', skipped='0')
                for name in names:
                    ET.SubElement(root, 'testcase', name=name)
                if mode in ('failure', 'error', 'skipped'):
                    ET.SubElement(root[-1], mode)
                if mode == 'duplicate':
                    ET.SubElement(root, 'testcase', name=RELEASE_UI)
                    root.set('tests', str(len(root)))
                path = Path(directory, 'browser.xml')
                ET.ElementTree(root).write(path)
                self.assertEqual(mode == 'ok', browser_verify(path)[0])

    def test_ci_requires_and_retains_release_evidence(self):
        workflow = Path(__file__).resolve().parents[2].joinpath('.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('python ../../tools/verify_r10_release_report.py target/failsafe-reports', workflow)
        self.assertIn('apps/server/release-r10-report.txt', workflow)
        self.assertIn('local-evidence/r03/release-ui-context-full.json', workflow)
