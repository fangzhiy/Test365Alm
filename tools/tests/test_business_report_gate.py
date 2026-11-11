import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from tools.verify_business_reports import BROWSER, REQUIRED, verify_browser
from tools.verify_r08_core_report import verify

class BusinessReportGateTests(unittest.TestCase):
    def test_missing_duplicate_failed_and_skipped_report_flow_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'browser.xml'
            names=[BROWSER,*[f'baseline-{i}' for i in range(16)]]
            for change in ('good','missing','duplicate','failure','error','skipped'):
                root=ET.Element('testsuite')
                for name in names:
                    if change=='missing' and name==BROWSER:continue
                    case=ET.SubElement(root,'testcase',name=name)
                    if change in ('failure','error','skipped') and name==BROWSER:ET.SubElement(case,change)
                if change=='duplicate':ET.SubElement(root,'testcase',name=BROWSER)
                ET.ElementTree(root).write(path)
                self.assertEqual(change=='good',verify_browser(path),change)

    def test_exact_database_inventory_is_required(self):
        self.assertEqual(7,len(REQUIRED))
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(verify(Path(directory),{'com.test365alm.server.report.ReportDatabaseIT':REQUIRED})[0])
