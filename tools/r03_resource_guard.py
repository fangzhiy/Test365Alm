"""Ownership guard for the disposable R03 Compose browser environment.

This module deliberately reuses the strict manifest implementation used by
the R02 verification scripts.  R03 resources use a different run-label key,
so a resource from another run can never be mistaken for one owned here.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.r02_resource_guard import (
    ResourceManifest,
    ResourceOwnershipError,
    assert_project_available as _assert_project_available,
    capture_manifest as _capture_manifest,
    cleanup_manifest,
    isolated_environment,
    manifest_path,
    new_run_id as _new_run_id,
    verify_manifest,
)

R03_RUN_LABEL = "test365alm.r03.run"


def new_run_id(prefix: str = "r03") -> str:
    return _new_run_id(prefix)


def assert_project_available(project: str, environment: dict[str, str]) -> tuple[str, str]:
    """Refuse any pre-existing project resource before the first ``up``."""
    return _assert_project_available(project, environment)


def capture_manifest(project: str, run_id: str, context: str,
                     environment: dict[str, str]) -> ResourceManifest:
    return _capture_manifest(project, run_id, context, environment, R03_RUN_LABEL)


def _main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    preflight = sub.add_parser("preflight")
    preflight.add_argument("--project", required=True)
    capture = sub.add_parser("capture")
    capture.add_argument("--project", required=True)
    capture.add_argument("--run-id", required=True)
    capture.add_argument("--context", required=True)
    capture.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    environment = dict(os.environ)
    try:
        if args.command == "preflight":
            context, engine = assert_project_available(args.project, environment)
            print(f"PASS: R03 project available context={context} engine={engine}")
            return 0
        manifest = capture_manifest(args.project, args.run_id, args.context, environment)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        manifest_path(args.output, manifest)
        print(f"PASS: captured R03 resource manifest {args.output}")
        return 0
    except ResourceOwnershipError as error:
        print(f"FAIL: R03 resource ownership: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(_main())
