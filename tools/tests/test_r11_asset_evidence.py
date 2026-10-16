"""Offline report adversaries are gate tests, not M05 acceptance evidence."""
import copy
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.verify_r11_asset_report import REQUIRED, verify
from tools.verify_r11_asset_web_report import REQUIRED as WEB_REQUIRED, verify as web_verify
from tools.verify_r03_project_browser_report import REQUIRED_BROWSER_TESTS, verify as browser_verify

ASSET_UI = 'real Keycloak UI member uploads scans attaches and preserves old asset revision'


class AssetEvidenceTests(unittest.TestCase):
    def xml_fixture(self, directory):
        for suite, names in REQUIRED.items():
            node = ET.Element('testsuite', name=suite, tests=str(len(names)), failures='0', errors='0', skipped='0')
            for name in names:
                ET.SubElement(node, 'testcase', name=name, classname=suite)
            ET.ElementTree(node).write(Path(directory, f'TEST-{suite}.xml'))

    def test_exact_real_storage_scanner_database_upgrade_http_names_required(self):
        with tempfile.TemporaryDirectory() as directory:
            self.xml_fixture(directory)
            self.assertTrue(verify(directory)[0])
        for suite, names in REQUIRED.items():
            for missing in [None, *names]:
                with self.subTest(suite=suite, missing=missing), tempfile.TemporaryDirectory() as directory:
                    self.xml_fixture(directory)
                    path = Path(directory, f'TEST-{suite}.xml')
                    if missing is None:
                        path.unlink()
                    else:
                        tree = ET.parse(path)
                        node = tree.getroot()
                        node.remove(next(c for c in node if c.get('name') == missing))
                        node.set('tests', str(len(node)))
                        tree.write(path)
                    self.assertFalse(verify(directory)[0])

    def test_backend_fail_error_skip_duplicate_count_and_wrong_identity_rejected(self):
        for suite in REQUIRED:
            for mode in ('failure', 'error', 'skipped', 'duplicate', 'count', 'class'):
                with self.subTest(suite=suite, mode=mode), tempfile.TemporaryDirectory() as directory:
                    self.xml_fixture(directory)
                    path = Path(directory, f'TEST-{suite}.xml')
                    tree = ET.parse(path)
                    node = tree.getroot()
                    if mode in ('failure', 'error', 'skipped'):
                        ET.SubElement(node[0], mode)
                    elif mode == 'duplicate':
                        node.append(copy.deepcopy(node[0]))
                        node.set('tests', str(len(node)))
                    elif mode == 'count':
                        node.set('tests', '0')
                    else:
                        node[0].set('classname', 'wrong')
                    tree.write(path)
                    self.assertFalse(verify(directory)[0])

    def web_fixture(self):
        files = [{'name': '/repo/apps/web/' + filename, 'status': 'passed', 'message': '',
                  'assertionResults': [{'title': name, 'fullName': name, 'status': 'passed', 'failureMessages': []}
                                       for name in sorted(names)]} for filename, names in WEB_REQUIRED.items()]
        total = sum(len(f['assertionResults']) for f in files)
        return dict(success=True, numTotalTests=total, numPassedTests=total, numFailedTests=0,
                    numPendingTests=0, numTodoTests=0, numTotalTestSuites=len(files),
                    numPassedTestSuites=len(files), numFailedTestSuites=0, numPendingTestSuites=0,
                    testResults=files)

    def check_web(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'report.json')
            path.write_text(json.dumps(data), encoding='utf-8')
            return web_verify(path)[0]

    def test_all_component_and_http_names_are_mandatory(self):
        self.assertTrue(self.check_web(self.web_fixture()))
        for filename, names in WEB_REQUIRED.items():
            for missing in names:
                with self.subTest(missing=missing):
                    data = self.web_fixture()
                    file = next(f for f in data['testResults'] if f['name'].endswith(filename))
                    file['assertionResults'] = [c for c in file['assertionResults'] if c['fullName'] != missing]
                    data['numTotalTests'] -= 1
                    data['numPassedTests'] -= 1
                    self.assertFalse(self.check_web(data))

    def test_web_failure_error_skip_duplicate_and_unhandled_cannot_pass(self):
        for mode in ('failed', 'error', 'skipped', 'pending', 'todo', 'duplicate', 'unhandled', 'count'):
            with self.subTest(mode=mode):
                data = self.web_fixture()
                cases = data['testResults'][0]['assertionResults']
                if mode == 'duplicate':
                    cases.append(copy.deepcopy(cases[0]))
                    data['numTotalTests'] += 1
                    data['numPassedTests'] += 1
                elif mode == 'unhandled':
                    data['unhandledErrors'] = ['synthetic failure']
                elif mode == 'count':
                    data['numTotalTests'] = 0
                else:
                    cases[0]['status'] = mode
                self.assertFalse(self.check_web(data))

    def test_previous_browser_suite_without_m05_cannot_pass(self):
        self.assertIn(ASSET_UI, REQUIRED_BROWSER_TESTS)
        for mode in ('ok', 'missing', 'failure', 'error', 'skipped', 'duplicate'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                names = [n for n in REQUIRED_BROWSER_TESTS if n != ASSET_UI]
                if mode != 'missing':
                    names.append(ASSET_UI)
                node = ET.Element('testsuite', tests=str(len(names)), failures='0', errors='0', skipped='0')
                for name in names:
                    ET.SubElement(node, 'testcase', name=name)
                if mode in ('failure', 'error', 'skipped'):
                    ET.SubElement(node[-1], mode)
                if mode == 'duplicate':
                    node.append(copy.deepcopy(node[-1]))
                    node.set('tests', str(len(node)))
                path = Path(directory, 'browser.xml')
                ET.ElementTree(node).write(path)
                self.assertEqual(mode == 'ok', browser_verify(path)[0])

    def test_ci_requires_reports_real_services_and_owned_cleanup(self):
        root = Path(__file__).resolve().parents[2]
        ci = (root / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('python ../../tools/verify_r11_asset_report.py target/failsafe-reports', ci)
        self.assertIn('python tools/verify_r11_asset_web_report.py', ci)
        self.assertIn('apps/server/asset-r11-report.txt', ci)
        self.assertIn('local-evidence/r03/asset-ui-context-full.json', ci)
        self.assertIn('-f compose.r03.yaml -f compose.r11.yaml', ci)
        self.assertIn('tools/cleanup_r02_resources.py --root local-evidence/r03', ci)
        self.assertNotIn('down --volumes', ci)
        compose = (root / 'compose.r11.yaml').read_text(encoding='utf-8')
        self.assertIn('seaweedfs:4.48@sha256:', compose)
        self.assertIn('clamav:1.5.4-debian13-slim@sha256:', compose)
        self.assertEqual(4, compose.count('test365alm.r03.run:'))
        self.assertNotIn('0.0.0.0:', compose)
