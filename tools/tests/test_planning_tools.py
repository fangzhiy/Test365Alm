"""Offline tests only; no GitHub remote is created by this suite."""
import json
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from calculate_budget import calculate
from validate_package import validate
from publish_github import publish, validate_names, inspect_package, PublishError
ROOT=Path(__file__).resolve().parents[2]

class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.data=json.loads((ROOT/'planning/budget.json').read_text(encoding='utf-8'))
    def test_baseline(self):
        result=calculate(self.data)
        self.assertEqual(result['totals']['person_months'],Decimal('618'))
        self.assertEqual(result['totals']['total'],Decimal('3354'))
    def test_phase_totals(self):
        self.assertEqual([x['total'] for x in calculate(self.data)['phases']],list(map(Decimal,['54','242.4','1000.8','1459.2','597.6'])))
    def test_sensitivity(self):
        self.assertEqual(calculate(self.data,Decimal('3'))['totals']['total'],Decimal('2612.4'))
        self.assertEqual(calculate(self.data,Decimal('5'))['totals']['total'],Decimal('4095.6'))
    def test_invalid_cost(self):
        with self.assertRaises(ValueError):calculate(self.data,Decimal('-1'))

class PublisherTests(unittest.TestCase):
    def test_preview_never_calls_subprocess(self):
        with patch('publish_github.run',side_effect=AssertionError('network or subprocess forbidden')):
            result=publish('fangzhiy','Test365Alm')
        self.assertEqual(result['mode'],'DRY_RUN')
        self.assertEqual(result['network_calls'],0)
    def test_invalid_owner(self):
        for name in ['../other','--flag','a/b','']:
            with self.assertRaises(PublishError):validate_names(name,'Test365Alm')
    def test_invalid_repo(self):
        for name in ['../other','--flag','a/b','.git','foo.git']:
            with self.assertRaises(PublishError):validate_names('fangzhiy',name)
    def test_existing_git_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'.git').mkdir()
            with self.assertRaises(PublishError):inspect_package(root)
    def test_incomplete_package_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(PublishError):inspect_package(Path(tmp))
    def test_owner_mismatch_refused_before_writes(self):
        from subprocess import CompletedProcess
        def fake_run(cmd,**kwargs):
            if cmd[:3]==['gh','api','user']:
                return CompletedProcess(cmd,0,'{"login":"someone-else"}','')
            if cmd[0]=='git':
                raise AssertionError('No git writes allowed')
            return CompletedProcess(cmd,0,'{}','')
        with patch('publish_github.inspect_package'),patch('publish_github.shutil.which',return_value='/bin/tool'),patch('publish_github.run',side_effect=fake_run):
            with self.assertRaises(PublishError):publish('fangzhiy','Test365Alm',True)

class PackageTests(unittest.TestCase):
    def test_package_and_honest_status(self):
        result=validate(ROOT)
        self.assertTrue(result['ok'],result.get('errors'))
        self.assertEqual(result['not_passed_product_cases'],184)
        self.assertEqual(result['product_acceptance'],'NOT_EXECUTED')
    def test_missing_files_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(validate(Path(tmp))['ok'])

if __name__=='__main__':
    unittest.main()
