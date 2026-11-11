import tempfile
import unittest
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.verify_signature_report import REQUIRED, verify


class SignatureReportTests(unittest.TestCase):
    def test_required_methods_match_real_signature_sources(self):
        root = Path(__file__).resolve().parents[2] / "apps/server/src/test/java"
        for suite, required in REQUIRED.items():
            source = (root / (suite.replace(".", "/") + ".java")).read_text(encoding="utf-8")
            names = re.findall(r"@Test\s+void\s+(\w+)\s*\(", source)
            self.assertEqual(len(names), len(set(names)))
            self.assertTrue(required.issubset(names))
            if ".signature." in suite:
                self.assertEqual(required, set(names))

    def write(self, directory):
        for suite, names in REQUIRED.items():
            root = ET.Element("testsuite", name=suite, tests=str(len(names)), failures="0", errors="0", skipped="0")
            for name in sorted(names):
                ET.SubElement(root, "testcase", name=name, classname=suite)
            ET.ElementTree(root).write(directory / f"TEST-{suite}.xml")

    def test_exact_signature_method_inventory_passes(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            self.write(directory)
            self.assertTrue(verify(directory)[0])
            self.assertEqual(16, sum(map(len, REQUIRED.values())))

    def test_missing_reports_and_malformed_xml_fail_closed(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            self.assertFalse(verify(directory)[0])
            self.write(directory)
            next(directory.glob("*.xml")).write_text("<broken", encoding="utf-8")
            self.assertFalse(verify(directory)[0])

    def test_every_required_method_missing_replaced_or_duplicated_is_rejected(self):
        for suite, names in REQUIRED.items():
            for name in names:
                for mutation in ("remove", "replace", "duplicate"):
                    with self.subTest(suite=suite, name=name, mutation=mutation), tempfile.TemporaryDirectory() as raw:
                        directory = Path(raw)
                        self.write(directory)
                        path = directory / f"TEST-{suite}.xml"
                        root = ET.parse(path).getroot()
                        case = next(c for c in root.findall("testcase") if c.get("name") == name)
                        if mutation == "remove":
                            root.remove(case)
                            root.set("tests", str(len(root.findall("testcase"))))
                        else:
                            case.set("name", "unrelated" if mutation == "replace" else next(n for n in names if n != name) if len(names) > 1 else name)
                            if mutation == "duplicate" and len(names) == 1:
                                ET.SubElement(root, "testcase", name=name, classname=suite)
                                root.set("tests", "2")
                        ET.ElementTree(root).write(path)
                        self.assertFalse(verify(directory)[0])

    def test_failure_error_skip_and_disguised_nonpassing_cases_are_rejected(self):
        for suite in REQUIRED:
            for kind in ("failure", "error", "skipped"):
                with self.subTest(suite=suite, kind=kind), tempfile.TemporaryDirectory() as raw:
                    directory = Path(raw)
                    self.write(directory)
                    path = directory / f"TEST-{suite}.xml"
                    root = ET.parse(path).getroot()
                    ET.SubElement(root.find("testcase"), kind)
                    ET.ElementTree(root).write(path)
                    self.assertFalse(verify(directory)[0])
                    root.set({"failure":"failures", "error":"errors", "skipped":"skipped"}[kind], "1")
                    ET.ElementTree(root).write(path)
                    self.assertFalse(verify(directory)[0])

    def test_forged_class_or_count_is_rejected(self):
        for mutation in ("class", "count", "suite"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as raw:
                directory = Path(raw)
                self.write(directory)
                path = next(directory.glob("*.xml"))
                root = ET.parse(path).getroot()
                if mutation == "class": root.find("testcase").set("classname", "foreign")
                elif mutation == "count": root.set("tests", "999")
                else: root.set("name", "foreign")
                ET.ElementTree(root).write(path)
                self.assertFalse(verify(directory)[0])


if __name__ == "__main__":
    unittest.main()
