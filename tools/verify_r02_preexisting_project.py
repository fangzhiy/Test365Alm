#!/usr/bin/env python3
"""Prove both R02 scripts reject a populated, pre-existing owned control project."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.r02_resource_guard import (
    ResourceManifest, assert_project_available, capture_manifest, cleanup_manifest,
    existing_resources, isolated_environment, manifest_path, new_run_id,
    verify_manifest,
)
from tools.verify_r02_readiness import (
    ROOT, free_ephemeral_port, load_env, run_compose, validate_test_datasource,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=".env.r02-test")
    parser.add_argument("--compose-project", default=new_run_id("test365alm-r02-control"))
    args = parser.parse_args()
    env_file = ROOT / args.env_file
    jar = ROOT / "apps/server/target/server-0.0.1-SNAPSHOT.jar"
    if not env_file.is_file() or not jar.is_file():
        print("FAIL: dedicated test env or built server jar is missing")
        return 2

    manifest: ResourceManifest | None = None
    environment: dict[str, str] = {}
    result = 1
    try:
        values = load_env(env_file)
        port = free_ephemeral_port()
        values["POSTGRES_HOST_PORT"] = str(port)
        values["TEST365ALM_DATASOURCE_URL"] = (
            f"jdbc:postgresql://127.0.0.1:{port}/{values.get('POSTGRES_DB', 'test365alm')}")
        values["TEST365ALM_DATASOURCE_USERNAME"] = values.get("POSTGRES_USER", "")
        values["TEST365ALM_DATASOURCE_PASSWORD"] = values.get("POSTGRES_PASSWORD", "")
        validate_test_datasource(values)
        run_id = new_run_id(args.compose_project)
        evidence_dir = ROOT / "local-evidence/r02-control" / run_id
        evidence_dir.mkdir(parents=True, exist_ok=True)
        discovery = isolated_environment(values, run_id, values.get("TEST365ALM_DOCKER_CONTEXT", ""))
        context, engine = assert_project_available(args.compose_project, discovery)
        environment = isolated_environment(values, run_id, context)
        environment["TEST365ALM_DOCKER_ENGINE"] = engine
        run_compose(args.compose_project, "up", "-d", "--wait", "postgres",
                    env_file=args.env_file, environment=environment)
        manifest = capture_manifest(args.compose_project, run_id, context, environment)
        manifest_path(evidence_dir / "resource-manifest.json", manifest)
        container = manifest.containers[0]

        def query(sql: str) -> str:
            command = ["docker", "--context", context, "exec", container,
                       "psql", "-U", values["POSTGRES_USER"], "-d", values["POSTGRES_DB"],
                       "-At", "-c", sql]
            completed = subprocess.run(command, env=environment, capture_output=True,
                                       text=True, timeout=20, check=True)
            return completed.stdout.strip()

        query("CREATE TABLE r02_control_sentinel (id integer PRIMARY KEY); INSERT INTO r02_control_sentinel VALUES (1), (2);")
        before = query("SELECT count(*) FROM r02_control_sentinel;")
        child_results: dict[str, int] = {}
        for name, script, backend_port in (
            ("readiness", "verify_r02_readiness.py", 18181),
            ("migration", "verify_r02_migration_failure.py", 18182),
        ):
            completed = subprocess.run(
                [sys.executable, str(ROOT / "tools" / script), "--env-file", args.env_file,
                 "--compose-project", args.compose_project, "--server-port", str(backend_port)],
                cwd=ROOT, env=environment, capture_output=True, text=True, timeout=45)
            child_results[name] = completed.returncode
            if completed.returncode == 0 or "already has resources" not in completed.stdout:
                raise RuntimeError(f"{name} did not reject the pre-existing project before mutation")
            if not verify_manifest(manifest, environment):
                raise RuntimeError(f"{name} changed a recorded control resource")
            actual = existing_resources(args.compose_project, environment)
            if (actual["containers"] != manifest.containers or actual["networks"] != manifest.networks
                    or actual["volumes"] != manifest.volumes):
                raise RuntimeError(f"{name} changed the control resource identities")
        after = query("SELECT count(*) FROM r02_control_sentinel;")
        if before != "2" or after != "2":
            raise RuntimeError("control sentinel rows changed")
        report = {"project": args.compose_project, "run_id": run_id,
                  "container": container, "engine": engine,
                  "sentinel_rows_before": before, "sentinel_rows_after": after,
                  "child_exit_codes": child_results}
        (evidence_dir / "control-result.json").write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"PASS: both scripts rejected the existing project; two sentinel rows and resource IDs unchanged; report={evidence_dir / 'control-result.json'}")
        result = 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"FAIL: control project isolation verification: {type(error).__name__}: {error}")
        result = 1
    finally:
        if manifest is not None and not cleanup_manifest(manifest, environment):
            print("FAIL: control resource cleanup could not be confirmed", file=sys.stderr)
            result = 1
    return result


if __name__ == "__main__":
    raise SystemExit(main())
