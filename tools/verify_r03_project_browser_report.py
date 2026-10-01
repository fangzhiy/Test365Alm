#!/usr/bin/env python3
"""Require the real dual-user project authorization browser case in CI."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_TEST = (
    "real Keycloak dual-user project access grants viewer read, rejects write, "
    "and revokes the live session"
)


def read_report(path: Path) -> tuple[int, int, int, int, set[str]]:
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    if not suites:
        raise ValueError("No test suites in browser report")
    values = {key: sum(int(suite.get(key, "0")) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    names = {case.get("name", "") for suite in suites for case in suite.findall("testcase")}
    return values["tests"], values["failures"], values["errors"], values["skipped"], names


def verify(path: Path) -> tuple[bool, str]:
    tests, failures, errors, skipped, names = read_report(path)
    summary = f"project browser tests={tests} failures={failures} errors={errors} skipped={skipped}"
    if REQUIRED_TEST not in names:
        return False, summary + " missing=dual-user-project-access"
    if failures or errors or skipped:
        return False, summary
    return True, summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r03_project_browser_report.py REPORT", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print("Project browser tests NOT_RUN: report missing", file=sys.stderr)
        return 1
    try:
        valid, summary = verify(path)
    except (ET.ParseError, ValueError) as exc:
        print(f"Project browser report invalid: {exc}", file=sys.stderr)
        return 1
    print(summary)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
