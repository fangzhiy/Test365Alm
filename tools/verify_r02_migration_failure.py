#!/usr/bin/env python3
"""Verify a deliberate Flyway startup failure in owned temporary resources."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.verify_r02_readiness import (
    ROOT,
    VerificationError,
    _compose_environment,
    cleanup_compose,
    compose_identity,
    free_ephemeral_port,
    http_status,
    jar_build_commit,
    load_env,
    port_is_free,
    run_compose,
    tail_log,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-project", default=f"test365alm-r02-migration-{os.getpid()}")
    parser.add_argument("--env-file", default=".env.r02-test")
    parser.add_argument("--server-port", type=int, default=18082)
    parser.add_argument("--jar", type=Path, default=ROOT / "apps" / "server" / "target" / "server-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--wait-seconds", type=float, default=45.0)
    args = parser.parse_args()

    env_path = ROOT / args.env_file
    if not env_path.is_file() or not args.jar.is_file():
        print("FAIL: dedicated test env or built server jar is missing")
        return 2
    if not port_is_free(args.server_port):
        print(f"FAIL: migration-failure backend port {args.server_port} is occupied")
        return 2

    try:
        values = load_env(env_path)
        db_port = int(values.get("POSTGRES_HOST_PORT", "54329"))
        if not port_is_free(db_port):
            db_port = free_ephemeral_port()
            values["POSTGRES_HOST_PORT"] = str(db_port)
        values["TEST365ALM_DATASOURCE_URL"] = (
            f"jdbc:postgresql://127.0.0.1:{db_port}/{values.get('POSTGRES_DB', 'test365alm')}"
        )
        values.setdefault("TEST365ALM_DATASOURCE_USERNAME", values.get("POSTGRES_USER", ""))
        values.setdefault("TEST365ALM_DATASOURCE_PASSWORD", values.get("POSTGRES_PASSWORD", ""))
        environment = _compose_environment(values)
    except (OSError, ValueError) as error:
        print(f"FAIL: dedicated migration datasource rejected: {error}")
        return 2

    identity: tuple[str, str] | None = None
    process: subprocess.Popen[bytes] | None = None
    run_id = f"{args.compose_project}-{int(time.time())}"
    evidence_dir = ROOT / "local-evidence" / "r02-migration-failure" / run_id
    evidence_dir.mkdir(parents=True, exist_ok=True)
    log_path = evidence_dir / "startup.log"
    base_url = f"http://127.0.0.1:{args.server_port}"

    with tempfile.TemporaryDirectory(prefix="test365alm-flyway-failure-") as migration_root:
        migration_dir = Path(migration_root)
        failure_migration = migration_dir / "V2__intentional_failure.sql"
        failure_migration.write_text(
            "SELECT CAST('intentional migration failure' AS integer);\n", encoding="utf-8"
        )
        try:
            run_compose(args.compose_project, "up", "-d", "--wait", "postgres",
                        env_file=args.env_file, environment=environment)
            identity = compose_identity(args.compose_project, args.env_file, environment)
            if identity is None:
                raise VerificationError("could not confirm owned migration-test Compose identity")
            process_env = {
                **environment,
                "TEST365ALM_SERVER_ADDRESS": "127.0.0.1",
                "TEST365ALM_SERVER_PORT": str(args.server_port),
                "SERVER_ADDRESS": "127.0.0.1",
                "SERVER_PORT": str(args.server_port),
                "SPRING_FLYWAY_LOCATIONS": f"classpath:db/migration,filesystem:{migration_dir.as_posix()}",
            }
            with log_path.open("wb") as output:
                process = subprocess.Popen(
                    ["java", "-jar", str(args.jar)], cwd=ROOT, env=process_env,
                    stdout=output, stderr=subprocess.STDOUT)
            print(f"INFO: pid={process.pid} build={jar_build_commit(args.jar)} compose_project={identity[1]} postgres={identity[0]}")
            deadline = time.monotonic() + args.wait_seconds
            while time.monotonic() < deadline and process.poll() is None:
                status, _ = http_status(base_url, "/health/ready", timeout=2.0)
                if status == 200:
                    raise VerificationError("application reached ready=200 despite failing migration")
                time.sleep(0.5)
            exit_code = process.poll()
            if exit_code is None:
                raise VerificationError("application did not exit after intentional migration failure")
            log_text = log_path.read_text(encoding="utf-8", errors="replace")
            if exit_code == 0 or "Flyway" not in log_text or "V2__intentional_failure" not in log_text:
                raise VerificationError(
                    f"startup failure was not attributable to Flyway V2 migration (exit={exit_code})"
                )
            print(f"PASS: startup failed with Flyway V2 migration error; exit={exit_code}; log={log_path}")
            return 0
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, VerificationError) as error:
            print(f"FAIL: {error}")
            if log_path.exists():
                print("--- sanitized migration startup log ---", file=sys.stderr)
                print(tail_log(log_path), file=sys.stderr)
            return 1
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
            cleanup_compose(args.compose_project, args.env_file, environment, identity)


if __name__ == "__main__":
    raise SystemExit(main())
