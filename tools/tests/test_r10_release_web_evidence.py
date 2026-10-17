"""Offline adversarial fixtures exercise the gate, not M06 acceptance itself."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from tools.verify_r10_release_web_report import REQUIRED, verify


class ReleaseWebEvidenceTests(unittest.TestCase):
    def fixture(self):
        suites = []
        for filename, names in REQUIRED.items():
            suites.append({
                'name': '/workspace/apps/web/' + filename,
                'status': 'passed', 'message': '',
                'assertionResults': [
                    {'title': name, 'fullName': name, 'ancestorTitles': [],
                     'status': 'passed', 'failureMessages': []}
                    for name in sorted(names)
                ],
            })
        total = sum(len(suite['assertionResults']) for suite in suites)
        return {'success': True, 'numTotalTests': total, 'numPassedTests': total,
                'numFailedTests': 0, 'numPendingTests': 0, 'numTodoTests': 0,
                'numFailedTestSuites': 0, 'numPendingTestSuites': 0,
                'numTotalTestSuites': len(suites), 'numPassedTestSuites': len(suites),
                'testResults': suites}

    def check(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'vitest.json')
            path.write_text(json.dumps(data), encoding='utf-8')
            return verify(path)

    def test_exact_component_and_public_api_names_pass(self):
        self.assertEqual(21, sum(map(len, REQUIRED.values())))
        self.assertTrue(self.check(self.fixture())[0])
        windows = self.fixture()
        for suite in windows['testResults']:
            suite['name'] = suite['name'].replace('/', '\\')
        self.assertTrue(self.check(windows)[0])

    def test_missing_required_case_or_file_cannot_pass_with_adjusted_counts(self):
        for filename, names in REQUIRED.items():
            for name in names:
                with self.subTest(filename=filename, name=name):
                    data = self.fixture()
                    suite = next(s for s in data['testResults'] if s['name'].endswith(filename))
                    suite['assertionResults'] = [c for c in suite['assertionResults'] if c['title'] != name]
                    data['numTotalTests'] -= 1
                    data['numPassedTests'] -= 1
                    self.assertFalse(self.check(data)[0])
            data = self.fixture()
            suite = next(s for s in data['testResults'] if s['name'].endswith(filename))
            data['testResults'].remove(suite)
            data['numTotalTests'] -= len(suite['assertionResults'])
            data['numPassedTests'] = data['numTotalTests']
            data['numTotalTestSuites'] -= 1
            data['numPassedTestSuites'] -= 1
            self.assertFalse(self.check(data)[0])

    def test_failure_error_skip_todo_and_duplicate_are_rejected(self):
        for mode in ('failed', 'error', 'skipped', 'pending', 'todo', 'duplicate', 'failure-message', 'suite-error'):
            with self.subTest(mode=mode):
                data = self.fixture()
                suite = data['testResults'][0]
                case = suite['assertionResults'][0]
                if mode == 'duplicate':
                    suite['assertionResults'].append(copy.deepcopy(case))
                    data['numTotalTests'] += 1
                    data['numPassedTests'] += 1
                elif mode == 'failure-message':
                    case['failureMessages'] = ['synthetic failure']
                elif mode == 'suite-error':
                    suite['message'] = 'synthetic import error'
                else:
                    case['status'] = mode
                self.assertFalse(self.check(data)[0])

    def test_global_counts_success_shape_and_filename_are_checked(self):
        for mode in ('success', 'count', 'passed', 'failures', 'skips', 'todo', 'suite-count', 'suite-status', 'filename', 'empty', 'shape', 'unhandled'):
            with self.subTest(mode=mode):
                data = self.fixture()
                if mode == 'success': data['success'] = False
                elif mode == 'count': data['numTotalTests'] += 1
                elif mode == 'passed': data['numPassedTests'] -= 1
                elif mode == 'failures': data['numFailedTests'] = 1
                elif mode == 'skips': data['numPendingTests'] = 1
                elif mode == 'todo': data['numTodoTests'] = 1
                elif mode == 'suite-count': data['numPassedTestSuites'] -= 1
                elif mode == 'suite-status': data['testResults'][0]['status'] = 'failed'
                elif mode == 'filename': data['testResults'][0]['name'] = '/other/fake.test.tsx'
                elif mode == 'empty': data['testResults'] = []
                elif mode == 'shape': data['testResults'][0]['assertionResults'] = None
                elif mode == 'unhandled': data['unhandledErrors'] = ['synthetic async error']
                self.assertFalse(self.check(data)[0])

    def test_missing_malformed_and_nonobject_report_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'missing.json')
            self.assertFalse(verify(path)[0])
            for content in ('{broken', 'null', '[]', '{}'):
                path.write_text(content, encoding='utf-8')
                self.assertFalse(verify(path)[0])
