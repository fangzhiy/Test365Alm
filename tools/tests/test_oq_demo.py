"""Offline contract, ownership and actual-state inspection regressions."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo_up
import oq_demo
from demo_seed import all_pages, cases


class OqDemoTests(unittest.TestCase):
    def test_versioned_scenario_preserves_fourteen_original_business_scenarios(self):
        doc = json.loads((demo_up.ROOT / 'tools/scenarios/oq-demo-01-v1.1.json').read_text(encoding='utf-8'))
        self.assertEqual(doc['classification'], 'SYNTHETIC')
        self.assertEqual(doc['version'], '1.1')
        self.assertEqual([c['id'] for c in doc['cases']], [f'OQD-{i:02}' for i in range(1, 15)])
        for original, case in zip(cases(), doc['cases']):
            self.assertEqual(case['steps'], original['steps'])
            self.assertEqual(len(case['observations']), 3)
            self.assertIn('合成', case['purpose'])
        self.assertEqual([c['id'] for c in doc['cases'] if c['initialConclusion'] == 'FAIL'], ['OQD-12', 'OQD-13'])

    def test_explicit_profile_does_not_select_or_reconfigure_legacy_paths(self):
        before = {k: getattr(demo_up, k) for k in ('LOCAL','ENV','REALM','COMPOSE','STATE','PROJECT')}
        with patch.multiple(demo_up, **before), patch.object(sys, 'argv', ['demo_up.py', '--profile', 'oq-demo-01']):
            demo_up.select_profile_args()
            self.assertEqual(demo_up.PROJECT, 'test365alm-demo-oq-demo-01')
            self.assertNotEqual(demo_up.ENV, before['ENV'])
            self.assertNotEqual(demo_up.STATE, before['STATE'])

    def test_inspection_uses_recorded_ids_and_reports_legitimate_new_attempt_without_writes(self):
        saved = {'scenario':'OQ-DEMO-01','version':'1.1','projectId':'p','stages':['finished'],
                 'cases':{'OQD-01':{'testId':'t','revisionId':'r','runId':'run'}}}
        original = copy.deepcopy(saved)
        class Api:
            def call(self, path):
                return {'/api/v1/projects/p':{'code':'SYNTHETIC-OQ-DEMO-01-V1-1'},
                        '/api/v1/projects/p/tests/t/revisions/r':{'title':'SYNTHETIC OQD-01 · 冻结'},
                        '/api/v1/projects/p/runs/run':{'currentAttempt':{'id':'new','status':'RUNNING','conclusion':None}}}[path]
        result = oq_demo.inspect(Api(), saved)
        self.assertTrue(result['readOnly'])
        self.assertEqual(result['actual'][0]['currentAttempt'], 'new')
        self.assertEqual(saved, original)

    def test_inspection_refuses_foreign_project_and_unknown_version(self):
        class Api:
            def call(self, path):
                return {'code':'other'}
        with self.assertRaisesRegex(RuntimeError, 'ownership'):
            oq_demo.inspect(Api(), {'scenario':'OQ-DEMO-01','version':'1.1','projectId':'p'})
        with self.assertRaisesRegex(RuntimeError, 'Unknown'):
            oq_demo.inspect(Api(), {'scenario':'OQ-DEMO-01','version':'unknown'})

    def test_pages_include_later_ids_and_reject_cursor_loop(self):
        class Api:
            def call(self, path):
                return {'items':[{'id':'later' if 'cursor=' in path else 'first'}], 'nextCursor':None if 'cursor=' in path else 'opaque+value'}
        self.assertEqual([r['id'] for r in all_pages(Api(), '/tests')], ['first','later'])
        class Loop:
            def call(self, path):
                return {'items':[], 'nextCursor':'same'}
        with self.assertRaisesRegex(RuntimeError, 'Repeated'):
            all_pages(Loop(), '/tests')


if __name__ == '__main__':
    unittest.main()
