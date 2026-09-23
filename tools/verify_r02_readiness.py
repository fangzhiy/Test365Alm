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
import shutil
from pathlib import Path
from typing import Mapping
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.r02_resource_guard import (
    ResourceManifest,
    ResourceOwnershipError,
    assert_project_available,
    capture_manifest,
    cleanup_manifest,
    isolated_environment,
    java_environment,
    manifest_path,
    new_run_id,
    start_containers,
    stop_containers,
    verify_docker_engine,
)

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROJECT = f"test365alm-r02-{new_run_id('project').split('-', 1)[1][:16]}"
DEFAULT_ENV_FILE = ".env.r02-test"
DEFAULT_TIMEOUT = 60.0


class VerificationError(RuntimeError):
    """A bounded verification step failed and must not be reported as PASS."""


def compose_args(project: str, *args: str, env_file: str = DEFAULT_ENV_FILE,
                 context: str = "") -> list[str]:
    standalone = bool(shutil.which("docker-compose"))
    command = ["docker-compose"] if standalone else ["docker"]
    if context:
        command.extend(["--context", context])
    if not standalone:
        command.append("compose")
    return command + ["--env-file", str(ROOT / env_file), "-f", str(ROOT / "compose.yaml"),
                      "--project-directory", str(ROOT), "-p", project, *args]


def _compose_environment(file_values: Mapping[str, str], run_id: str = "development",
                         context: str = "") -> dict[str, str]:
    """Build an isolated environment; no parent Spring/Compose overrides survive."""
    return isolated_environment(file_values, run_id, context)


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
    verify_docker_engine(environment)
    command = compose_args(project, *args, env_file=env_file,
                           context=environment.get("TEST365ALM_DOCKER_CONTEXT", ""))
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
    allowed_keys = {
        "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_HOST_PORT",
        "TEST365ALM_DATASOURCE_URL", "TEST365ALM_DATASOURCE_USERNAME",
        "TEST365ALM_DATASOURCE_PASSWORD", "TEST365ALM_DB_CONNECTION_TIMEOUT_MS",
        "TEST365ALM_READINESS_TIMEOUT_MS", "TEST365ALM_DOCKER_CONTEXT",
    }
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"invalid env entry: {raw_line}")
        if key not in allowed_keys:
            raise ValueError(f"unsupported test configuration key: {key}")
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

    run_id = new_run_id(args.compose_project)
    log_dir = ROOT / "local-evidence" / "r02-readiness" / run_id
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / "backend-startup.log"
    server: subprocess.Popen[bytes] | None = None
    manifest: ResourceManifest | None = None
    environment: dict[str, str] = {}
    result = 1
    base_url = f"http://127.0.0.1:{args.server_port}"
    build_commit = jar_build_commit(args.jar)
    stopped = False
    try:
        # This check must precede the first mutating Compose command. It
        # rejects collisions even when the existing container is stopped.
        run_environment = _compose_environment(
            file_values, run_id, file_values.get("TEST365ALM_DOCKER_CONTEXT", ""))
        context, engine = assert_project_available(args.compose_project, run_environment)
        environment = isolated_environment(file_values, run_id, context)
        environment["TEST365ALM_DOCKER_ENGINE"] = engine
        run_compose(args.compose_project, "up", "-d", "--wait", "postgres", env_file=args.env_file, environment=environment)
        manifest = capture_manifest(args.compose_project, run_id, context, environment)
        manifest_path(log_dir / "resource-manifest.json", manifest)
        with log_path.open("wb") as output:
            process_environment = java_environment(
                environment,
                datasource_url=file_values["TEST365ALM_DATASOURCE_URL"],
                datasource_username=file_values["TEST365ALM_DATASOURCE_USERNAME"],
                datasource_password=file_values["TEST365ALM_DATASOURCE_PASSWORD"],
                server_port=args.server_port,
            )
            server = subprocess.Popen(
                ["java", "-jar", str(args.jar)], cwd=log_dir, env=process_environment,
                stdout=output, stderr=subprocess.STDOUT)
        print(f"INFO: run={run_id} pid={server.pid} build={build_commit} compose_project={manifest.project} postgres={manifest.containers[0]} context={manifest.docker_context}")
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
        if not stop_containers(manifest, environment):
            raise VerificationError("could not verify ownership before stopping postgres")
        stopped = True
        live_down, _ = wait_for(base_url, "/health/live", {200}, time.monotonic() + args.wait_seconds, server, log_path)
        ready_down, _ = wait_for(base_url, "/health/ready", {503}, time.monotonic() + args.wait_seconds, server, log_path)
        ensure_process_alive(server, "database outage", log_path)
        if live_down != 200 or ready_down != 503:
            raise VerificationError(f"outage live={live_down!r} ready={ready_down!r}")
        print("PASS: outage live=200 ready=503 (same backend PID)")

        if not start_containers(manifest, environment):
            raise VerificationError("could not verify ownership before starting postgres")
        stopped = False
        ready_up, _ = wait_for(base_url, "/health/ready", {200}, time.monotonic() + args.wait_seconds, server, log_path)
        ensure_process_alive(server, "database recovery", log_path)
        if ready_up != 200:
            raise VerificationError(f"recovery ready={ready_up!r}")
        print("PASS: recovery ready=200 without backend restart")
        result = 0
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired,
            VerificationError, ResourceOwnershipError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}")
        if log_path.exists():
            print("--- sanitized backend startup log ---", file=sys.stderr)
            print(tail_log(log_path), file=sys.stderr)
        result = 1
    finally:
        if stopped:
            if not start_containers(manifest, environment):
                print("FAIL: could not restore the owned postgres service", file=sys.stderr)
                result = 1
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
        if manifest is None or not cleanup_manifest(manifest, environment):
            print("FAIL: owned Compose resource cleanup was not confirmed", file=sys.stderr)
            result = 1
    return result


if __name__ == "__main__":
    raise SystemExit(main())
