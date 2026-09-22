#!/usr/bin/env python3
"""Run a repeatable PostgreSQL outage/recovery check against a local dev stack.

This intentionally uses only the isolated ``test365alm-r02`` Compose project.
It never removes a volume and never prints datasource credentials. Build the
server jar first, then run this script from the repository root.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]


def compose_args(project: str, *args: str) -> list[str]:
    return ["docker", "compose", "--env-file", ".env", "-p", project, *args]


def http_status(base_url: str, path: str, timeout: float = 5.0) -> tuple[int | None, str]:
    try:
        with urlopen(f"{base_url}{path}", timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")
    except (URLError, TimeoutError, OSError):
        return None, ""


def wait_for(base_url: str, path: str, expected: set[int], deadline: float) -> tuple[int | None, str]:
    latest: tuple[int | None, str] = (None, "")
    while time.monotonic() < deadline:
        latest = http_status(base_url, path)
        if latest[0] in expected:
            return latest
        time.sleep(1)
    return latest


def run_compose(project: str, *args: str) -> None:
    subprocess.run(compose_args(project, *args), cwd=ROOT, check=True, stdout=subprocess.DEVNULL)


def listening_addresses(port: int) -> list[str]:
    command = ["netstat", "-ano"] if os.name == "nt" else ["ss", "-ltn"]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    addresses: list[str] = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if os.name == "nt":
            if len(fields) < 4 or fields[0].upper() != "TCP" or fields[3].upper() != "LISTENING":
                continue
            local = fields[1]
        else:
            if len(fields) < 4 or fields[0] != "LISTEN":
                continue
            local = fields[3]
        if local.rsplit(":", 1)[-1] == str(port):
            addresses.append(local.rsplit(":", 1)[0].strip("[]"))
    return addresses


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key.strip()):
            raise ValueError(f"invalid .env entry: {raw_line}")
        value = value.strip().strip('"').strip("'")
        def expand(match: re.Match[str]) -> str:
            referenced = match.group(1)
            if referenced not in values and referenced not in os.environ:
                raise ValueError(f"{referenced} is not defined before {key.strip()}")
            return values.get(referenced, os.environ[referenced])

        value = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", expand, value)
        values[key.strip()] = value
    # Mirror the development Compose settings for the host-side Spring
    # process. Explicit non-local JDBC URLs remain untouched.
    jdbc_url = values.get("TEST365ALM_DATASOURCE_URL", "")
    host_port = values.get("POSTGRES_HOST_PORT")
    if host_port and jdbc_url.startswith("jdbc:postgresql://127.0.0.1:"):
        jdbc_url = re.sub(r"^jdbc:postgresql://127\.0\.0\.1:\d+", f"jdbc:postgresql://127.0.0.1:{host_port}", jdbc_url)
        values["TEST365ALM_DATASOURCE_URL"] = jdbc_url
    if values.get("POSTGRES_USER"):
        values["TEST365ALM_DATASOURCE_USERNAME"] = values["POSTGRES_USER"]
    if values.get("POSTGRES_PASSWORD"):
        values["TEST365ALM_DATASOURCE_PASSWORD"] = values["POSTGRES_PASSWORD"]
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-project", default="test365alm-r02")
    parser.add_argument("--server-port", type=int, default=18080)
    parser.add_argument(
        "--jar",
        type=Path,
        default=ROOT / "apps" / "server" / "target" / "server-0.0.1-SNAPSHOT.jar",
    )
    parser.add_argument("--wait-seconds", type=float, default=90.0)
    args = parser.parse_args()

    if not (ROOT / ".env").is_file():
        print("FAIL: .env is missing; copy .env.example to .env without overwriting an existing file.")
        return 2
    if not args.jar.is_file():
        print(f"FAIL: server jar not found: {args.jar}; build it before this check.")
        return 2

    base_url = f"http://127.0.0.1:{args.server_port}"
    env = os.environ.copy()
    env.update(load_env(ROOT / ".env"))
    env.update(
        {
            "TEST365ALM_DATASOURCE_URL": env.get(
                "TEST365ALM_DATASOURCE_URL",
                f"jdbc:postgresql://127.0.0.1:{env.get('POSTGRES_HOST_PORT', '54329')}/test365alm",
            ),
            "TEST365ALM_DATASOURCE_USERNAME": env.get("TEST365ALM_DATASOURCE_USERNAME", "test365alm"),
            "TEST365ALM_DATASOURCE_PASSWORD": env.get("TEST365ALM_DATASOURCE_PASSWORD", "test365alm_dev_password"),
            "TEST365ALM_SERVER_ADDRESS": "127.0.0.1",
            "TEST365ALM_SERVER_PORT": str(args.server_port),
            # Spring's conventional names keep this check compatible with
            # jars built before the explicit TEST365ALM_* server settings.
            "SERVER_ADDRESS": "127.0.0.1",
            "SERVER_PORT": str(args.server_port),
        }
    )

    server: subprocess.Popen[bytes] | None = None
    stopped = False
    try:
        run_compose(args.compose_project, "up", "-d", "--wait", "postgres")
        server = subprocess.Popen(
            ["java", "-jar", str(args.jar)],
            cwd=ROOT,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + args.wait_seconds
        live, _ = wait_for(base_url, "/health/live", {200}, deadline)
        ready, _ = wait_for(base_url, "/health/ready", {200}, deadline)
        if live != 200 or ready != 200:
            print(f"FAIL: startup live={live!r} ready={ready!r}")
            return 1
        listeners = listening_addresses(args.server_port)
        if not listeners or any(address not in {"127.0.0.1", "::1"} for address in listeners):
            print(f"FAIL: backend listener addresses={listeners!r}")
            return 1
        print(f"PASS: backend listener loopback-only addresses={listeners!r}")
        print("PASS: startup live=200 ready=200")

        run_compose(args.compose_project, "stop", "postgres")
        stopped = True
        live_down, _ = wait_for(base_url, "/health/live", {200}, time.monotonic() + args.wait_seconds)
        ready_down, _ = wait_for(base_url, "/health/ready", {503}, time.monotonic() + args.wait_seconds)
        if live_down != 200 or ready_down != 503:
            print(f"FAIL: outage live={live_down!r} ready={ready_down!r}")
            return 1
        print("PASS: outage live=200 ready=503 (backend process unchanged)")

        run_compose(args.compose_project, "start", "postgres")
        stopped = False
        ready_up, _ = wait_for(base_url, "/health/ready", {200}, time.monotonic() + args.wait_seconds)
        if ready_up != 200:
            print(f"FAIL: recovery ready={ready_up!r}")
            return 1
        print("PASS: recovery ready=200 without backend restart")
        return 0
    finally:
        if stopped:
            try:
                run_compose(args.compose_project, "start", "postgres")
            except (OSError, subprocess.CalledProcessError):
                print("WARNING: could not restore the isolated postgres service", file=sys.stderr)
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()


if __name__ == "__main__":
    raise SystemExit(main())
