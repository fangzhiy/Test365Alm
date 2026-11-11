"""Require the actual F report database methods or real authenticated browser flow."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_r08_core_report import verify as verify_suites

SUITE = 'com.test365alm.server.report.ReportDatabaseIT'
REQUIRED = set(json.loads((Path(__file__).parent / 'linux-test/combined-methods.json').read_text(encoding='utf-8'))['integration'][SUITE])
BROWSER = 'real Keycloak UI reports preserve historical FAIL after related PASS and print three complete Chinese reports'

def verify_browser(path):
    try:
        cases = list(ET.parse(path).getroot().iter('testcase'))
        names = [c.get('name') for c in cases]
        return (len(cases) >= 17 and len(names) == len(set(names)) and names.count(BROWSER) == 1
                and not any(c.find(flag) is not None for c in cases for flag in ('failure', 'error', 'skipped')))
    except (OSError, ET.ParseError):
        return False

if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] not in ('database', 'browser'):
        raise SystemExit('usage: verify_business_reports.py database|browser REPORT_PATH')
    ok = verify_suites(Path(sys.argv[2]), {SUITE: REQUIRED})[0] if sys.argv[1] == 'database' else verify_browser(Path(sys.argv[2]))
    print(f'F report {sys.argv[1]}: {"PASS" if ok else "FAIL / NOT_RUN"}')
    raise SystemExit(0 if ok else 1)
