"""Offline gate tests use synthetic reports, not fabricated application evidence."""
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.verify_r09_defect_report import REQUIRED, verify
from tools.verify_r03_project_browser_report import REQUIRED_BROWSER_TESTS, verify as browser_verify

DEFECT_UI = 'real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history'

class DefectEvidenceTests(unittest.TestCase):
    def fixture(self, directory):
        for suite, names in REQUIRED.items():
            root=ET.Element('testsuite',name=suite,tests=str(len(names)),failures='0',errors='0',skipped='0')
            for name in names: ET.SubElement(root,'testcase',name=name,classname=suite)
            ET.ElementTree(root).write(Path(directory,f'TEST-{suite}.xml'))

    def test_exact_defect_successes_are_required(self):
        with tempfile.TemporaryDirectory() as d:
            self.fixture(d);self.assertTrue(verify(d)[0])

    def test_every_missing_name_and_missing_report_is_rejected(self):
        for suite,names in REQUIRED.items():
            for name in names:
                with self.subTest(name=name),tempfile.TemporaryDirectory() as d:
                    self.fixture(d);p=Path(d,f'TEST-{suite}.xml');tree=ET.parse(p);root=tree.getroot();root.remove(next(c for c in root if c.get('name')==name));root.set('tests',str(len(root)));tree.write(p);self.assertFalse(verify(d)[0])
            with tempfile.TemporaryDirectory() as d:
                self.fixture(d);Path(d,f'TEST-{suite}.xml').unlink();self.assertFalse(verify(d)[0])

    def test_failure_error_skip_duplicate_and_invalid_count_are_rejected(self):
        for mode in ('failure','error','skipped','duplicate','count','class','malformed'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as d:
                self.fixture(d);suite=next(iter(REQUIRED));p=Path(d,f'TEST-{suite}.xml');tree=ET.parse(p);root=tree.getroot()
                if mode in ('failure','error','skipped'):ET.SubElement(root[0],mode)
                elif mode=='duplicate':root[1].set('name',root[0].get('name'))
                elif mode=='count':root.set('tests','0')
                elif mode=='class':root[0].set('classname','wrong')
                tree.write(p)
                if mode=='malformed':p.write_text('<broken')
                self.assertFalse(verify(d)[0])

    def test_old_browser_suite_without_m10_cannot_satisfy_gate(self):
        self.assertIn(DEFECT_UI,REQUIRED_BROWSER_TESTS)
        for mode in ('ok','missing','failure','error','skipped','duplicate'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as d:
                names=[n for n in REQUIRED_BROWSER_TESTS if n!=DEFECT_UI]
                if mode!='missing':names.append(DEFECT_UI)
                root=ET.Element('testsuite',tests=str(len(names)),failures='0',errors='0',skipped='0')
                for name in names:ET.SubElement(root,'testcase',name=name)
                if mode in ('failure','error','skipped'):ET.SubElement(root[-1],mode)
                if mode=='duplicate':root[-1].set('name',root[0].get('name'))
                p=Path(d,'browser.xml');ET.ElementTree(root).write(p);self.assertEqual(mode=='ok',browser_verify(p)[0])

    def test_ci_calls_and_preserves_defect_report(self):
        text=Path(__file__).resolve().parents[2].joinpath('.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('python ../../tools/verify_r09_defect_report.py target/failsafe-reports',text)
        self.assertIn('apps/server/defect-r09-report.txt',text)
