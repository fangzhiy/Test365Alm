#!/usr/bin/env python3
"""Fail closed when the real-browser JUnit report is absent or empty."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def count_results(path: Path) -> tuple[int, int, int, int]:
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    if not suites:
        raise ValueError("No test suite in browser report")
    return tuple(sum(int(suite.get(key, "0")) for suite in suites)
                 for key in ("tests", "failures", "errors", "skipped"))


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r03_report.py REPORT", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print("Browser tests NOT_RUN: report missing", file=sys.stderr)
        return 1
    try:
        tests, failures, errors, skipped = count_results(path)
    except (ET.ParseError, ValueError) as exc:
        print(f"Browser report invalid: {exc}", file=sys.stderr)
        return 1
    print(f"browser tests={tests} failures={failures} errors={errors} skipped={skipped}")
    return 0 if tests >= 5 and failures == 0 and errors == 0 and skipped == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
