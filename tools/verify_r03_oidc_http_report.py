#!/usr/bin/env python3
"""Fail closed unless the real HTTP OIDC callback integration report is complete."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_TESTS = {
    "legalTokenCompletesHttpCallbackAndPersistsPrincipal",
    "wrongSignatureIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite",
    "wrongIssuerIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite",
    "wrongAudienceIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite",
    "expiredTokenIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite",
    "wrongNonceIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite",
    "failedCallbackDoesNotPoisonClientAndFreshLegalAuthorizationRecovers",
}


def read_report(directory: Path) -> tuple[int, int, int, int, set[str]]:
    reports = sorted(directory.glob("TEST-*OidcCallbackSecurityIT.xml"))
    if len(reports) != 1:
        raise ValueError(f"expected exactly one OidcCallbackSecurityIT report, found {len(reports)}")
    root = ET.parse(reports[0]).getroot()
    if root.tag != "testsuite" or root.get("name") != "com.test365alm.server.identity.OidcCallbackSecurityIT":
        raise ValueError("report is not the expected OidcCallbackSecurityIT suite")
    tests = int(root.get("tests", "0"))
    failures = int(root.get("failures", "0"))
    errors = int(root.get("errors", "0"))
    skipped = int(root.get("skipped", "0"))
    names = {case.get("name", "") for case in root.findall("testcase")}
    return tests, failures, errors, skipped, names


def verify(directory: Path) -> tuple[bool, str]:
    tests, failures, errors, skipped, names = read_report(directory)
    missing = REQUIRED_TESTS - names
    summary = (
        f"oidc HTTP callback tests={tests} failures={failures} "
        f"errors={errors} skipped={skipped} cases={len(names)}"
    )
    if tests != len(REQUIRED_TESTS) or names != REQUIRED_TESTS:
        return False, summary + f" missing_or_unexpected={sorted((REQUIRED_TESTS - names) | (names - REQUIRED_TESTS))}"
    if failures or errors or skipped:
        return False, summary
    return not missing, summary


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_r03_oidc_http_report.py FAILSAFE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    directory = Path(sys.argv[1])
    if not directory.is_dir():
        print("OIDC HTTP integration NOT_RUN: report directory missing", file=sys.stderr)
        return 1
    try:
        valid, summary = verify(directory)
    except (ET.ParseError, ValueError) as exc:
        print(f"OIDC HTTP integration report invalid: {exc}", file=sys.stderr)
        return 1
    print(summary)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
