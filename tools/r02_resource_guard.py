"""Ownership guard for destructive R02 Docker verification runs.

The guard deliberately refuses project-level ``down --volumes``.  A project
label only tells us that Docker Compose selected a project; the run label and
the resource ids captured after creation are required before a resource can
be stopped, started, or removed.
"""

from __future__ import annotations

import json
import os
import subprocess
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlparse

PROJECT_LABEL = "com.docker.compose.project"
RUN_LABEL = "com.test365alm.r02.run-id"


class ResourceOwnershipError(RuntimeError):
    """The requested Docker resource cannot be proven to belong to this run."""


@dataclass(frozen=True)
class ResourceManifest:
    project: str
    run_id: str
    docker_context: str
    containers: tuple[str, ...]
    networks: tuple[str, ...]
    volumes: tuple[str, ...]
    docker_engine: str = ""

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


def new_run_id(prefix: str = "r02") -> str:
    """Return a non-reusable run id suitable for a Compose label."""
    return f"{prefix}-{uuid.uuid4().hex}"


def _docker(args: list[str], environment: Mapping[str, str], timeout: float = 15.0) -> str:
    context = environment.get("TEST365ALM_DOCKER_CONTEXT", "").strip()
    command = ["docker"]
    if context:
        command.extend(["--context", context])
    command.extend(args)
    try:
        result = subprocess.run(
            command, cwd=None, env=dict(environment), capture_output=True,
            text=True, check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ResourceOwnershipError(f"Docker command unavailable: {type(error).__name__}") from error
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()[-500:]
        raise ResourceOwnershipError(f"Docker command failed: {' '.join(args)}: {detail}")
    return result.stdout


def docker_context(environment: Mapping[str, str]) -> str:
    # The inherited DOCKER_CONTEXT is intentionally ignored until this check
    # has established the current engine; callers then pin it in the child env.
    if any(environment.get(key, "").strip() for key in ("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")):
        raise ResourceOwnershipError("explicit remote Docker environment is not allowed for destructive checks")
    discovery_env = {key: value for key, value in environment.items()
                     if key not in {"TEST365ALM_DOCKER_CONTEXT", "DOCKER_CONTEXT"}}
    context = _docker(["context", "show"], discovery_env).strip()
    expected = environment.get("TEST365ALM_DOCKER_CONTEXT", "").strip()
    if not context or (expected and context != expected):
        raise ResourceOwnershipError("Docker context is missing or differs from the expected local context")
    try:
        details = json.loads(_docker(["context", "inspect", context], discovery_env))
        host = (((details[0].get("Endpoints") or {}).get("docker") or {}).get("Host") or "").strip()
        parsed = urlparse(host) if host else None
        if parsed and parsed.scheme in {"tcp", "ssh", "http", "https"}:
            raise ResourceOwnershipError("remote Docker context is not authorized for destructive verification")
    except (json.JSONDecodeError, IndexError, AttributeError, TypeError) as error:
        raise ResourceOwnershipError("could not verify Docker engine for this run") from error
    return context


def docker_engine(environment: Mapping[str, str]) -> str:
    """Pin the daemon identity as well as the context name."""
    identity = _docker(["info", "--format", "{{.ID}}"], environment).strip()
    if not identity:
        raise ResourceOwnershipError("Docker Engine identity is unavailable")
    return identity


def _ids(kind: str, project: str, environment: Mapping[str, str]) -> tuple[str, ...]:
    label_command = ["ps", "-aq"] if kind == "ps" else [kind, "ls", "-q"]
    output = _docker(label_command + ["--filter", f"label=com.docker.compose.project={project}"], environment)
    ids = {line.strip() for line in output.splitlines() if line.strip()}
    if kind == "ps":
        listed = _docker(["ps", "-aq", "--format", "{{.ID}}\t{{.Names}}"], environment)
    elif kind == "volume":
        listed = _docker([kind, "ls", "--format", "{{.Name}}\t{{.Name}}"], environment)
    else:
        listed = _docker([kind, "ls", "--format", "{{.ID}}\t{{.Name}}"], environment)
    prefixes = (f"{project}-", f"{project}_")
    for line in listed.splitlines():
        resource_id, _, name = line.partition("\t")
        if resource_id and name.startswith(prefixes):
            ids.add(resource_id.strip())
    return tuple(sorted(ids))


def existing_resources(project: str, environment: Mapping[str, str]) -> dict[str, tuple[str, ...]]:
    """List *all* Compose resources for project, including stopped containers."""
    return {
        "containers": _ids("ps", project, environment),
        "networks": _ids("network", project, environment),
        "volumes": _ids("volume", project, environment),
    }


def assert_project_available(project: str, environment: Mapping[str, str]) -> tuple[str, str]:
    """Check ownership before the first ``up``; existing resources are unsafe."""
    context = docker_context(environment)
    engine = docker_engine({**environment, "TEST365ALM_DOCKER_CONTEXT": context})
    resources = existing_resources(project, environment)
    if any(resources.values()):
        details = ", ".join(f"{key}={list(value)!r}" for key, value in resources.items() if value)
        raise ResourceOwnershipError(f"Compose project already has resources; refusing first up: {details}")
    return context, engine


def verify_docker_engine(environment: Mapping[str, str]) -> None:
    """Refuse mutation if a context or daemon changed after preflight."""
    expected_context = environment.get("TEST365ALM_DOCKER_CONTEXT", "")
    expected_engine = environment.get("TEST365ALM_DOCKER_ENGINE", "")
    if not expected_context or not expected_engine:
        raise ResourceOwnershipError("Docker context/engine identity was not pinned before mutation")
    if docker_context(environment) != expected_context or docker_engine(environment) != expected_engine:
        raise ResourceOwnershipError("Docker context/engine changed after preflight")


def _inspect(kind: str, resource_id: str, environment: Mapping[str, str]) -> dict:
    try:
        output = _docker([kind, "inspect", resource_id], environment)
    except ResourceOwnershipError as error:
        if any(marker in str(error).lower() for marker in ("no such", "not found", "does not exist")):
            raise ResourceOwnershipError(f"Docker resource disappeared: {resource_id}") from error
        raise
    try:
        items = json.loads(output)
    except json.JSONDecodeError as error:
        raise ResourceOwnershipError(f"invalid Docker inspect response for {resource_id}") from error
    if not items or not isinstance(items[0], dict):
        raise ResourceOwnershipError(f"Docker resource disappeared: {resource_id}")
    return items[0]


def _labels(kind: str, inspected: dict) -> Mapping[str, str]:
    if kind == "container":
        return inspected.get("Config", {}).get("Labels") or {}
    return inspected.get("Labels") or {}


def capture_manifest(project: str, run_id: str, context: str,
                     environment: Mapping[str, str]) -> ResourceManifest:
    """Capture and validate ids immediately after Compose creates resources."""
    verify_docker_engine(environment)
    resources = existing_resources(project, environment)
    if not resources["containers"] or not resources["networks"] or not resources["volumes"]:
        raise ResourceOwnershipError("Compose did not create the expected container, network, and volume")
    for kind, ids in (("container", resources["containers"]), ("network", resources["networks"]),
                      ("volume", resources["volumes"])):
        for resource_id in ids:
            labels = _labels(kind, _inspect(kind, resource_id, environment))
            if labels.get("com.docker.compose.project") != project or labels.get("com.test365alm.r02.run-id") != run_id:
                raise ResourceOwnershipError(f"resource {resource_id} has unverified project/run ownership")
    return ResourceManifest(project, run_id, context, resources["containers"], resources["networks"], resources["volumes"], docker_engine(environment))


def verify_manifest(manifest: ResourceManifest, environment: Mapping[str, str]) -> bool:
    """Verify every recorded id, label, and Docker context before mutation."""
    try:
        if (docker_context(environment) != manifest.docker_context or not manifest.docker_engine
                or docker_engine(environment) != manifest.docker_engine):
            return False
        for kind, ids in (("container", manifest.containers), ("network", manifest.networks),
                          ("volume", manifest.volumes)):
            for resource_id in ids:
                labels = _labels(kind, _inspect(kind, resource_id, environment))
                if (labels.get("com.docker.compose.project") != manifest.project or
                        labels.get("com.test365alm.r02.run-id") != manifest.run_id):
                    return False
        return True
    except ResourceOwnershipError:
        return False


def _mutate(action: str, resource_ids: tuple[str, ...], manifest: ResourceManifest,
            environment: Mapping[str, str]) -> bool:
    if not resource_ids or not verify_manifest(manifest, environment):
        return False
    try:
        _docker([action, *resource_ids], environment)
        return True
    except ResourceOwnershipError:
        return False


def stop_containers(manifest: ResourceManifest, environment: Mapping[str, str]) -> bool:
    return _mutate("stop", manifest.containers, manifest, environment)


def start_containers(manifest: ResourceManifest, environment: Mapping[str, str]) -> bool:
    return _mutate("start", manifest.containers, manifest, environment)


def cleanup_manifest(manifest: ResourceManifest | None, environment: Mapping[str, str]) -> bool:
    """Remove only recorded owned ids; never invoke project-level down."""
    if manifest is None:
        return False
    try:
        if (docker_context(environment) != manifest.docker_context or not manifest.docker_engine
                or docker_engine(environment) != manifest.docker_engine):
            return False
    except ResourceOwnershipError:
        return False
    present_ids: dict[str, list[str]] = {"container": [], "network": [], "volume": []}
    missing = 0
    for kind, ids in (("container", manifest.containers), ("network", manifest.networks),
                      ("volume", manifest.volumes)):
        for resource_id in ids:
            try:
                labels = _labels(kind, _inspect(kind, resource_id, environment))
                if labels.get(PROJECT_LABEL) != manifest.project or labels.get(RUN_LABEL) != manifest.run_id:
                    return False
                present_ids[kind].append(resource_id)
            except ResourceOwnershipError as error:
                if "disappeared" in str(error):
                    missing += 1
                else:
                    return False
    if not any(present_ids.values()):
        return True
    success = True
    try:
        for kind, action in (("container", "rm"), ("network", "rm"), ("volume", "rm")):
            ids = tuple(present_ids[kind])
            if ids:
                try:
                    command = [kind, action, "-f", *ids] if kind == "container" else [kind, action, *ids]
                    _docker(command, environment)
                except ResourceOwnershipError:
                    success = False
    except (OSError, subprocess.TimeoutExpired):
        success = False
    return success


def isolated_environment(file_values: Mapping[str, str], run_id: str, context: str) -> dict[str, str]:
    """Build a minimal Compose/JVM environment without inherited Spring config."""
    allowed = {
        "PATH", "PATHEXT", "SystemRoot", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE",
        "HOMEDRIVE", "HOMEPATH", "COMSPEC", "OS", "JAVA_HOME", "CLASSPATH",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER",
        "ProgramData", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432",
        "CommonProgramFiles", "CommonProgramFiles(x86)", "CommonProgramW6432",
        "APPDATA", "LOCALAPPDATA",
    }
    # Windows commonly exposes these names in uppercase. Retain their actual
    # spelling; dropping SYSTEMROOT breaks Winsock initialization in the JVM.
    allowed_upper = {key.upper() for key in allowed}
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() in allowed_upper}
    for key, value in file_values.items():
        if key in {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_HOST_PORT",
                   "TEST365ALM_DATASOURCE_URL", "TEST365ALM_DATASOURCE_USERNAME",
                   "TEST365ALM_DATASOURCE_PASSWORD", "TEST365ALM_DB_CONNECTION_TIMEOUT_MS",
                   "TEST365ALM_READINESS_TIMEOUT_MS"}:
            environment[key] = value
    environment["TEST365ALM_RUN_ID"] = run_id
    environment["TEST365ALM_DOCKER_CONTEXT"] = context
    # Explicitly prevent inherited Spring/Flyway/Compose/JVM overrides.
    for key in ("SPRING_DATASOURCE_URL", "SPRING_FLYWAY_URL", "SPRING_APPLICATION_JSON",
                "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION", "COMPOSE_FILE",
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    return environment


def java_environment(environment: Mapping[str, str], *, datasource_url: str,
                     datasource_username: str, datasource_password: str,
                     server_port: int, migration_locations: str | None = None) -> dict[str, str]:
    """Add only explicit application settings to an already isolated env."""
    result = dict(environment)
    result.update({
        "TEST365ALM_DATASOURCE_URL": datasource_url,
        "TEST365ALM_DATASOURCE_USERNAME": datasource_username,
        "TEST365ALM_DATASOURCE_PASSWORD": datasource_password,
        "TEST365ALM_SERVER_ADDRESS": "127.0.0.1",
        "TEST365ALM_SERVER_PORT": str(server_port),
        "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_PORT": str(server_port),
        "SPRING_CONFIG_LOCATION": "classpath:/application.properties",
        "SPRING_FLYWAY_URL": datasource_url,
        "SPRING_FLYWAY_USER": datasource_username,
        "SPRING_FLYWAY_PASSWORD": datasource_password,
    })
    if migration_locations:
        result["SPRING_FLYWAY_LOCATIONS"] = migration_locations
    return result


def manifest_path(path: Path, manifest: ResourceManifest) -> None:
    path.write_text(json.dumps(manifest.as_dict(), indent=2) + "\n", encoding="utf-8")
