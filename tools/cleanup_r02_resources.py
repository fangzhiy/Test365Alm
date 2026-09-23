#!/usr/bin/env python3
"""Safely clean manifests produced by the R02 verification scripts."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import fields
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.r02_resource_guard import ResourceManifest, cleanup_manifest, isolated_environment


def _load(path: Path) -> ResourceManifest:
    data = json.loads(path.read_text(encoding="utf-8"))
    values = {field.name: data[field.name] for field in fields(ResourceManifest)}
    values["containers"] = tuple(values["containers"])
    values["networks"] = tuple(values["networks"])
    values["volumes"] = tuple(values["volumes"])
    return ResourceManifest(**values)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True,
                        help="Evidence directory containing resource-manifest.json files")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        print(f"FAIL: owned resource evidence directory is missing: {root}", file=sys.stderr)
        return 1
    manifests = sorted(root.rglob("resource-manifest.json"))
    if not manifests:
        print(f"FAIL: no owned resource manifest found under {root}", file=sys.stderr)
        return 1
    result = 0
    for path in manifests:
        try:
            manifest = _load(path)
            environment = isolated_environment({}, manifest.run_id, manifest.docker_context)
            if not cleanup_manifest(manifest, environment):
                print(f"FAIL: resource ownership or cleanup could not be confirmed: {path}", file=sys.stderr)
                result = 1
            else:
                print(f"PASS: cleaned owned resources from {path}")
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
            print(f"FAIL: invalid resource manifest {path}: {type(error).__name__}", file=sys.stderr)
            result = 1
    return result


if __name__ == "__main__":
    raise SystemExit(main())
