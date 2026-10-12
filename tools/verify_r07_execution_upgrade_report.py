#!/usr/bin/env python3
"""Require independently executed V13 and V14 non-empty PostgreSQL upgrades."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SUITE = 'com.test365alm.server.execution.ManualExecutionMigrationUpgradeIT'
REQUIRED = {'v13NonEmptyExecutionDatabaseUpgradesToV16', 'v14NonEmptyExecutionDatabaseUpgradesToV16'}


def verify_upgrade_report(directory: Path) -> tuple[bool, str]:
    reports = list(directory.glob(f'TEST-{SUITE}.xml'))
    if len(reports) != 1:
        return False, f'expected one {SUITE} report, found {len(reports)}'
    try:
        root = ET.parse(reports[0]).getroot()
        counts = {key: int(root.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        cases = root.findall('testcase')
        names = [case.get('name', '') for case in cases]
        valid = (root.tag == 'testsuite' and root.get('name') == SUITE
                 and counts['tests'] == len(cases) and counts['tests'] >= 2
                 and all(counts[key] == 0 for key in ('failures', 'errors', 'skipped'))
                 and len(names) == len(set(names)) and REQUIRED.issubset(names)
                 and all(case.get('classname') == SUITE for case in cases)
                 and not any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')))
        return valid, f'{SUITE}: {counts}; names={names}; valid={valid}'
    except (OSError, ET.ParseError, KeyError, ValueError) as error:
        return False, f'invalid upgrade report: {type(error).__name__}'


def main() -> int:
    if len(sys.argv) != 2:
        print('usage: verify_r07_execution_upgrade_report.py FAILSAFE_REPORT_DIRECTORY', file=sys.stderr)
        return 2
    valid, summary = verify_upgrade_report(Path(sys.argv[1]))
    print(summary, file=sys.stdout if valid else sys.stderr)
    return 0 if valid else 1


if __name__ == '__main__':
    raise SystemExit(main())
