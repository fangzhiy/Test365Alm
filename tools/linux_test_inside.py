"""Inside-runner entrypoint. Raw Maven output and upload temp files never leave Linux."""
from __future__ import annotations
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PHASES = {
    "scan": ["-Pintegration", "verify", "-Dit.test=AssetDatabaseIT#realClamdCleanAndEicarProduceSafeTerminalStates"],
    "unit": ["test"],
    "integration": ["-Pintegration", "verify"],
}


def summarize(directory: Path) -> dict:
    cases = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for case in suite.findall("testcase"):
            # Never export system-out/err, exception messages, properties, or stack traces.
            cases.append({"class": case.get("classname"), "method": case.get("name"),
                          "failure": case.find("failure") is not None,
                          "error": case.find("error") is not None,
                          "skipped": case.find("skipped") is not None})
    for case in cases:
        if not all(re.fullmatch(r"[\w.$#()\[\], :\-]+", case[k] or "") for k in ("class", "method")):
            raise ValueError("Unexpected test identity; refuse export")
    if len({(c["class"], c["method"]) for c in cases}) != len(cases):
        raise ValueError("Duplicate test identities")
    return {"tests": len(cases), "failures": sum(c["failure"] for c in cases),
            "errors": sum(c["error"] for c in cases), "skipped": sum(c["skipped"] for c in cases), "cases": cases}


def main() -> int:
    phase = sys.argv[1]
    args = PHASES[phase]
    evidence = Path("/work/evidence")
    evidence.mkdir(exist_ok=True)
    target = Path("apps/server/target")
    # Clear only reports in this disposable runner, so previous focused results cannot count.
    import shutil
    for report in ("surefire-reports", "failsafe-reports"):
        shutil.rmtree(target / report, ignore_errors=True)
    command = ["mvn", "-B", "-ntp", *args]
    with (evidence / f"{phase}.raw.log").open("w") as output:
        result = subprocess.run(command, cwd="apps/server", stdout=output, stderr=subprocess.STDOUT, check=False)
    report = {"phase": phase, "command": command, "exit_code": result.returncode,
              "unit": summarize(target / "surefire-reports")}
    if phase != "unit":
        report["integration"] = summarize(target / "failsafe-reports")
    expected = {"scan": (89, 1), "unit": (89, None), "integration": (89, 174)}[phase]
    valid = report["unit"]["tests"] == expected[0]
    if expected[1] is not None:
        valid = valid and report["integration"]["tests"] == expected[1]
    valid = valid and all(not report[k][s] for k in ("unit", "integration") if k in report
                          for s in ("failures", "errors", "skipped"))
    report["expected_counts_and_zero_failures"] = valid
    report["exit_code"] = result.returncode or (0 if valid else 2)
    (evidence / f"{phase}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
