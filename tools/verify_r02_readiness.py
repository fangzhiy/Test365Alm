#!/usr/bin/env python3
"""Verify an owned PostgreSQL outage/recovery run without trusting a port alone."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import Mapping
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROJECT = f"test365alm-r02-{os.getpid()}"
DEFAULT_ENV_FILE = ".env.r02-test"
DEFAULT_TIMEOUT = 60.0


class VerificationError(RuntimeError):
    """A bounded verification step failed and must not be reported as PASS."""


def compose_args(project: str, *args: str, env_file: str = DEFAULT_ENV_FILE) -> list[str]:
    return ["docker", "compose", "--env-file", env_file, "-p", project, *args]


def _compose_environment(file_values: Mapping[str, str]) -> dict[str, str]:
    """Make the dedicated file authoritative over inherited DB variables."""
    environment = os.environ.copy()
    for key in ("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_HOST_PORT",
                "TEST365ALM_DATASOURCE_URL", "TEST365ALM_DATASOURCE_USERNAME",
                "TEST365ALM_DATASOURCE_PASSWORD"):
        environment.pop(key, None)
    environment.update(file_values)
    return environment


def http_status(base_url: str, path: str, timeout: float = 5.0) -> tuple[int | None, str]:
    try:
        with urlopen(f"{base_url}{path}", timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")
    except (URLError, TimeoutError, OSError):
        return None, ""


def ensure_process_alive(process: subprocess.Popen[bytes], phase: str, log_path: Path) -> None:
    code = process.poll()
    if code is not None:
        raise VerificationError(f"backend exited during {phase} with code {code}; log={log_path}")


def wait_for(base_url: str, path: str, expected: set[int], deadline: float,
             process: subprocess.Popen[bytes] | None = None,
             log_path: Path | None = None) -> tuple[int | None, str]:
    latest: tuple[int | None, str] = (None, "")
    while time.monotonic() < deadline:
        if process is not None and log_path is not None:
            ensure_process_alive(process, f"HTTP wait {path}", log_path)
        latest = http_status(base_url, path)
        if latest[0] in expected:
            return latest
        time.sleep(1)
    return latest


def run_compose(project: str, *args: str, env_file: str, environment: Mapping[str, str],
                timeout: float = DEFAULT_TIMEOUT) -> subprocess.CompletedProcess[str]:
    command = compose_args(project, *args, env_file=env_file)
    try:
        return subprocess.run(
            command, cwd=ROOT, env=dict(environment), check=True,
            capture_output=True, text=True, timeout=timeout)
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()[-1000:]
        raise VerificationError(f"Compose command failed with exit {error.returncode}: {detail}") from error


def port_is_free(port: int, host: str = "127.0.0.1") -> bool:
    """Probe without terminating or reusing an existing listener."""
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as probe:
        try:
            probe.bind((host, port))
        except OSError:
            return False
    return True


def free_ephemeral_port(host: str = "127.0.0.1") -> int:
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as probe:
        probe.bind((host, 0))
        return int(probe.getsockname()[1])


def _endpoint_address(local: str) -> str:
    local = local.strip()
    if local.startswith("[") and "]" in local:
        return local[1:local.index("]")]
    if local.count(":") == 1:
        return local.rsplit(":", 1)[0]
    if local.count(":") > 1:
        return local.rsplit(":", 1)[0]
    return local


def parse_listener_output(output: str, port: int, windows: bool = False) -> list[str]:
    addresses: list[str] = []
    for line in output.splitlines():
        fields = line.split()
        if windows:
            if len(fields) < 4 or fields[0].upper() != "TCP" or fields[3].upper() != "LISTENING":
                continue
            local = fields[1]
        else:
            if len(fields) < 4 or fields[0] != "LISTEN":
                continue
            local = fields[3]
        if local.rsplit(":", 1)[-1].rstrip("]") == str(port):
            addresses.append(_endpoint_address(local).strip("[]"))
    return addresses


def is_loopback_address(address: str) -> bool:
    """Accept IPv4, IPv6 and IPv4-mapped IPv6 loopback addresses only."""
    try:
        value = ipaddress.ip_address(address.strip("[]").split("%", 1)[0])
        mapped = getattr(value, "ipv4_mapped", None)
        return (mapped or value).is_loopback
    except ValueError:
        return False


def listening_addresses(port: int) -> list[str] | None:
    command = ["netstat", "-ano"] if os.name == "nt" else ["ss", "-ltn"]
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=10)
    except (FileNotFoundError, OSError, subprocess.TimeoutExpired):
        result = None
    if result is not None and result.returncode == 0:
        return parse_listener_output(result.stdout, port, windows=os.name == "nt")

    found: list[str] = []
    scanned = False
    for proc_path, family in (("/proc/net/tcp", socket.AF_INET), ("/proc/net/tcp6", socket.AF_INET6)):
        try:
            lines = Path(proc_path).read_text(encoding="ascii").splitlines()[1:]
            scanned = True
        except OSError:
            continue
        for line in lines:
            try:
                fields = line.split()
                if len(fields) < 4 or fields[3] != "0A":
                    continue
                local_hex, port_hex = fields[1].split(":", 1)
                if int(port_hex, 16) != port:
                    continue
                raw = bytes.fromhex(local_hex)
                if family == socket.AF_INET:
                    found.append(socket.inet_ntoa(raw[::-1]))
                else:
                    found.append(socket.inet_ntop(family, b"".join(
                        raw[index:index + 4][::-1] for index in range(0, 16, 4))))
            except (ValueError, OSError):
                return None
    return found if scanned else None


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"invalid env entry: {raw_line}")
        value = value.strip().strip('"').strip("'")

        def expand(match: re.Match[str]) -> str:
            referenced = match.group(1)
            if referenced in values:
                return values[referenced]
            raise ValueError(f"{referenced} is not defined before {key}")

        values[key] = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", expand, value)
    return values


def validate_test_datasource(values: Mapping[str, str]) -> None:
    url = values.get("TEST365ALM_DATASOURCE_URL", "")
    expected_port = values.get("POSTGRES_HOST_PORT", "")
    expected_db = values.get("POSTGRES_DB", "")
    match = re.fullmatch(r"jdbc:postgresql://(127\.0\.0\.1|\[?::1\]?):(\d+)/([^?]+)", url)
    if not match or (expected_port and match.group(2) != expected_port) or (expected_db and match.group(3) != expected_db):
        raise ValueError("refusing non-local or mismatched test datasource")
    if not values.get("POSTGRES_USER") or not values.get("POSTGRES_PASSWORD"):
        raise ValueError("test datasource credentials are incomplete")


def jar_build_commit(jar: Path) -> str:
    try:
        with zipfile.ZipFile(jar) as archive:
            for name in ("BOOT-INF/classes/META-INF/build-info.properties", "META-INF/build-info.properties"):
                try:
                    content = archive.read(name).decode("utf-8")
                except KeyError:
                    continue
                for line in content.splitlines():
                    if line.startswith("build.buildCommit="):
                        return line.partition("=")[2].strip() or "unknown"
    except (OSError, zipfile.BadZipFile):
        pass
    return "unknown"


def compose_identity(project: str, env_file: str, environment: Mapping[str, str]) -> tuple[str, str] | None:
    result = subprocess.run(compose_args(project, "ps", "-q", "postgres", env_file=env_file), cwd=ROOT,
                            env=dict(environment), capture_output=True, text=True, check=False, timeout=15)
    container_id = result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
    if result.returncode != 0 or not container_id:
        return None
    inspect = subprocess.run(
        ["docker", "inspect", "--format", "{{.Id}} {{index .Config.Labels \"com.docker.compose.project\"}}", container_id],
        capture_output=True, text=True, check=False, timeout=15)
    fields = inspect.stdout.strip().split(maxsplit=1)
    if inspect.returncode != 0 or len(fields) != 2 or fields[0] != container_id or fields[1] != project:
        return None
    return container_id, project


def cleanup_compose(project: str, env_file: str, environment: Mapping[str, str], identity: tuple[str, str] | None) -> bool:
    if identity is None or identity[1] != project:
        print("BLOCKED: Compose ownership was not confirmed; refusing cleanup", file=sys.stderr)
        return False
    try:
        run_compose(project, "down", "--volumes", "--remove-orphans", env_file=env_file, environment=environment)
        return True
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"WARNING: owned Compose cleanup failed: {type(error).__name__}", file=sys.stderr)
        return False


def tail_log(path: Path, limit: int = 80) -> str:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return "<log unavailable>"
    return "\n".join(lines[-limit:])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-project", default=DEFAULT_PROJECT)
    parser.add_argument("--env-file", default=DEFAULT_ENV_FILE)
    parser.add_argument("--server-port", type=int, default=18080)
    parser.add_argument("--jar", type=Path, default=ROOT / "apps" / "server" / "target" / "server-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--wait-seconds", type=float, default=90.0)
    parser.add_argument("--http-timeout", type=float, default=5.0)
    parser.add_argument("--expected-commit", default=os.environ.get("TEST365ALM_EXPECTED_COMMIT", ""))
    args = parser.parse_args()

    env_path = ROOT / args.env_file
    if not env_path.is_file():
        print(f"FAIL: dedicated test env is missing: {env_path}")
        return 2
    if not args.jar.is_file():
        print(f"FAIL: server jar not found: {args.jar}; build it before this check.")
        return 2
    if not port_is_free(args.server_port):
        print(f"FAIL: target backend port {args.server_port} is already occupied; refusing to reuse it")
        return 2
    try:
        file_values = load_env(env_path)
        if "TEST365ALM_DATASOURCE_URL" not in file_values:
            port = file_values.get("POSTGRES_HOST_PORT", "54329")
            db = file_values.get("POSTGRES_DB", "test365alm")
            file_values["TEST365ALM_DATASOURCE_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/{db}"
        db_port = int(file_values.get("POSTGRES_HOST_PORT", "54329"))
        if not port_is_free(db_port):
            replacement = free_ephemeral_port()
            file_values["POSTGRES_HOST_PORT"] = str(replacement)
            file_values["TEST365ALM_DATASOURCE_URL"] = re.sub(
                r"(?<=jdbc:postgresql://127\.0\.0\.1:)\d+",
                str(replacement), file_values["TEST365ALM_DATASOURCE_URL"], count=1)
            print(f"INFO: configured database port was occupied; using owned ephemeral port {replacement}")
        file_values.setdefault("TEST365ALM_DATASOURCE_USERNAME", file_values.get("POSTGRES_USER", ""))
        file_values.setdefault("TEST365ALM_DATASOURCE_PASSWORD", file_values.get("POSTGRES_PASSWORD", ""))
        validate_test_datasource(file_values)
    except (OSError, ValueError) as error:
        print(f"FAIL: dedicated test datasource rejected: {error}")
        return 2

    environment = _compose_environment(file_values)
    run_id = f"{args.compose_project}-{int(time.time())}"
    log_dir = ROOT / "local-evidence" / "r02-readiness" / run_id
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / "backend-startup.log"
    server: subprocess.Popen[bytes] | None = None
    identity: tuple[str, str] | None = None
    base_url = f"http://127.0.0.1:{args.server_port}"
    build_commit = jar_build_commit(args.jar)
    stopped = False
    try:
        run_compose(args.compose_project, "up", "-d", "--wait", "postgres", env_file=args.env_file, environment=environment)
        identity = compose_identity(args.compose_project, args.env_file, environment)
        if identity is None:
            raise VerificationError("could not confirm owned Compose postgres identity")
        with log_path.open("wb") as output:
            server = subprocess.Popen(
                ["java", "-jar", str(args.jar)], cwd=ROOT,
                env={**environment, "TEST365ALM_SERVER_ADDRESS": "127.0.0.1", "TEST365ALM_SERVER_PORT": str(args.server_port),
                     "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(args.server_port)},
                stdout=output, stderr=subprocess.STDOUT)
        print(f"INFO: run={run_id} pid={server.pid} build={build_commit} compose_project={identity[1]} postgres={identity[0]}")
        deadline = time.monotonic() + args.wait_seconds
        live, _ = wait_for(base_url, "/health/live", {200}, deadline, server, log_path)
        ready, _ = wait_for(base_url, "/health/ready", {200}, deadline, server, log_path)
        ensure_process_alive(server, "startup", log_path)
        if live != 200 or ready != 200:
            raise VerificationError(f"startup live={live!r} ready={ready!r}")
        version_status, version_body = http_status(base_url, "/api/v1/version", args.http_timeout)
        version = json.loads(version_body) if version_status == 200 else {}
        response_commit = version.get("commit") if isinstance(version, dict) else None
        expected_commit = args.expected_commit or build_commit
        if version_status != 200 or not isinstance(response_commit, str) or not response_commit:
            raise VerificationError(f"version identity unavailable: status={version_status!r}")
        if expected_commit not in {"", "unknown"} and response_commit != expected_commit:
            raise VerificationError(f"version commit {response_commit!r} does not match expected {expected_commit!r}")
        listeners = listening_addresses(args.server_port)
        if listeners is None:
            raise VerificationError("listener enumeration unavailable (NOT_RUN/BLOCKED)")
        if not listeners or any(not is_loopback_address(address) for address in listeners):
            raise VerificationError(f"backend listener is not loopback-only: {listeners!r}")
        print(f"PASS: startup live=200 ready=200 version={response_commit} listener={listeners!r}")

        ensure_process_alive(server, "before database outage", log_path)
        run_compose(args.compose_project, "stop", "postgres", env_file=args.env_file, environment=environment)
        stopped = True
        live_down, _ = wait_for(base_url, "/health/live", {200}, time.monotonic() + args.wait_seconds, server, log_path)
        ready_down, _ = wait_for(base_url, "/health/ready", {503}, time.monotonic() + args.wait_seconds, server, log_path)
        ensure_process_alive(server, "database outage", log_path)
        if live_down != 200 or ready_down != 503:
            raise VerificationError(f"outage live={live_down!r} ready={ready_down!r}")
        print("PASS: outage live=200 ready=503 (same backend PID)")

        run_compose(args.compose_project, "start", "postgres", env_file=args.env_file, environment=environment)
        stopped = False
        ready_up, _ = wait_for(base_url, "/health/ready", {200}, time.monotonic() + args.wait_seconds, server, log_path)
        ensure_process_alive(server, "database recovery", log_path)
        if ready_up != 200:
            raise VerificationError(f"recovery ready={ready_up!r}")
        print("PASS: recovery ready=200 without backend restart")
        return 0
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, VerificationError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}")
        if log_path.exists():
            print("--- sanitized backend startup log ---", file=sys.stderr)
            print(tail_log(log_path), file=sys.stderr)
        return 1
    finally:
        if stopped:
            try:
                run_compose(args.compose_project, "start", "postgres", env_file=args.env_file, environment=environment)
            except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
                print("WARNING: could not restore the owned postgres service", file=sys.stderr)
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
        cleanup_compose(args.compose_project, args.env_file, environment, identity)


if __name__ == "__main__":
    raise SystemExit(main())
