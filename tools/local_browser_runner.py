"""Explicit one-use local-owned Keycloak acceptance. Never opts into CI identity.

The four required flows use a committed snapshot and fresh localhost services.
Only exact manifest resources and directly created process handles are cleaned.
"""
from __future__ import annotations
import argparse
import contextlib
import io
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools import prepare_r03_dev, local_browser_assets
from tools.linux_test_runner import committed_archive
from tools.r02_resource_guard import (
    ResourceManifest, ResourceOwnershipError, _docker, _inspect, _labels,
    assert_project_available, cleanup_manifest, docker_context, docker_engine,
    existing_resources, isolated_environment, verify_docker_engine, verify_manifest,
)
from tools.redact_r03_logs import redact

SOURCE_LABEL = "test365alm.local.source"
RUN_LABEL = "test365alm.r03.run"
EXPECTED = {
    "real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only",
    "real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run",
    "real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history",
    "real Keycloak UI designated reviewer rejects approves and new revision requires fresh review",
}


def port(used: set[int]) -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        value = sock.getsockname()[1]
    if value in used or value in {5173, 8080, 15173, 18083}:
        return port(used)
    used.add(value)
    return value


def fresh_config(root: Path, run: str, source: str, with_assets: bool = False) -> dict[str, str]:
    env_file, realm_file = root / ".env.r03", root / "local-evidence/r03/realm.json"
    previous = prepare_r03_dev.ENV, prepare_r03_dev.REALM
    try:
        prepare_r03_dev.ENV, prepare_r03_dev.REALM = env_file, realm_file
        with contextlib.redirect_stdout(io.StringIO()):
            if prepare_r03_dev.main() != 0:
                raise ResourceOwnershipError("Refusing pre-existing local credentials")
    finally:
        prepare_r03_dev.ENV, prepare_r03_dev.REALM = previous
    values = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if line)
    used: set[int] = set()
    pg, kc, backend, web = [port(used) for _ in range(4)]
    values.update(R03_RUN_ID=run, R03_POSTGRES_PORT=str(pg), R03_KEYCLOAK_PORT=str(kc),
                  TEST365ALM_SERVER_PORT=str(backend), LOCAL_WEB_PORT=str(web),
                  TEST365ALM_WEB_ORIGIN=f"http://127.0.0.1:{web}",
                  TEST365ALM_OIDC_REDIRECT_URI=f"http://127.0.0.1:{web}/login/oauth2/code/test365alm",
                  TEST365ALM_DEV_BACKEND_URL=f"http://127.0.0.1:{backend}",
                  TEST365ALM_OIDC_ISSUER=f"http://127.0.0.1:{kc}/realms/test365alm",
                  TEST365ALM_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{pg}/test365alm_r03",
                  SPRING_FLYWAY_URL=f"jdbc:postgresql://127.0.0.1:{pg}/test365alm_r03",
                  TEST365ALM_ASSETS_ENABLED="false", R03_LOCAL_SOURCE_SHA=source)
    if with_assets:
        local_browser_assets.configure(values, used, port)
    env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
    realm = json.loads(realm_file.read_text())
    realm["clients"][0]["redirectUris"] = [values["TEST365ALM_OIDC_REDIRECT_URI"]]
    realm["clients"][0]["webOrigins"] = [values["TEST365ALM_WEB_ORIGIN"]]
    realm_file.write_text(json.dumps(realm), encoding="utf-8")
    return values


def capture(project: str, run: str, context: str, source: str, env: dict) -> ResourceManifest:
    # Also supports a partial failed create: each observed ID still needs both labels.
    verify_docker_engine(env)
    if docker_context(env) != context:
        raise ResourceOwnershipError("Docker context changed")
    resources = existing_resources(project, env)
    for kind, plural in (("container", "containers"), ("network", "networks"), ("volume", "volumes")):
        for identity in resources[plural]:
            labels = _labels(kind, _inspect(kind, identity, env))
            if (labels.get("com.docker.compose.project") != project or labels.get(RUN_LABEL) != run
                    or labels.get(SOURCE_LABEL) != source):
                raise ResourceOwnershipError("Refusing unowned local browser resource")
    return ResourceManifest(project, run, context, resources["containers"], resources["networks"], resources["volumes"],
                            docker_engine(env), RUN_LABEL)


def verify(resources: ResourceManifest, source: str, identities: dict, env: dict) -> None:
    if not verify_manifest(resources, env):
        raise ResourceOwnershipError("Local browser manifest changed")
    for kind, ids in (("container", resources.containers), ("network", resources.networks), ("volume", resources.volumes)):
        for identity in ids:
            actual = _inspect(kind, identity, env)
            if _labels(kind, actual,).get(SOURCE_LABEL) != source:
                raise ResourceOwnershipError("Local browser source provenance changed")
            if kind == "container":
                expected = identities[identity]
                if any(actual[k] != expected[k] for k in ("Id", "Created", "Image")):
                    raise ResourceOwnershipError("Local browser container replaced")


def wait_http(url: str, processes: list, seconds: int = 180) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if any(p.poll() is not None for p in processes):
            raise RuntimeError("Owned application process exited before readiness")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError("Owned local service readiness timed out")


def validate_results(report: dict) -> bool:
    cases = report.get("cases", [])
    return (report.get("status") == "passed" and report.get("workers") == 1 and report.get("retries") == 0
            and len(cases) == 4 and {c["name"] for c in cases} == EXPECTED
            and all(c["status"] == "passed" and c["expected"] == "passed" and c["retry"] == 0 for c in cases))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--with-assets", action="store_true", help="Create owned real S3/ClamAV and provision a dedicated scanner LOGIN")
    args = parser.parse_args()
    suffix = uuid.uuid4().hex
    run, project = f"r03-local-{suffix}", f"test365alm-r03-local-{suffix}"
    evidence = ROOT / "local-evidence/local-browser" / suffix
    evidence.mkdir(parents=True)
    archive = evidence / "snapshot.tar"
    committed_archive(args.source, archive)
    root = evidence / "repo"
    root.mkdir()
    with tarfile.open(archive) as snapshot:
        # Git archive creates no traversal paths; reject them explicitly on Python 3.10 too.
        for member in snapshot.getmembers():
            if member.issym() or member.islnk() or not (root / member.name).resolve().is_relative_to(root.resolve()):
                raise ValueError("Unsafe archive entry")
        snapshot.extractall(root)
    values: dict[str, str] = {}
    env = isolated_environment({}, run, "")
    context = engine = ""
    compose_file = root / "local-evidence/local-browser-compose.json"
    secret_paths = (root / ".env.r03", root / "local-evidence/r03/realm.json", compose_file)
    preexisting_secrets = {path for path in secret_paths if path.exists()}
    creation_started = False
    resources = None
    identities: dict = {}
    processes, handles = [], []
    result, cleanup = 1, False
    summary = {"run": run, "source": args.source, "project": project, "context": context, "engine": engine,
               "assets": "real-owned" if args.with_assets else "disabled", "asset_preflight": "NOT_RUN", "scanner_provision": "NOT_RUN"}

    def command(command: list, cwd: Path, name: str, timeout=300):
        with (evidence / f"{name}.private.log").open("w", encoding="utf-8") as output:
            subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=timeout)

    def compose(*arguments):
        verify_docker_engine(env)
        if resources is not None:
            verify(resources, args.source, identities, env)
        return _docker(["compose", "--env-file", str(root / ".env.r03"), "-f", str(compose_file), "-p", project, *arguments], env, timeout=120)

    try:
        if preexisting_secrets:
            raise ResourceOwnershipError("Refusing pre-existing local secret files")
        values = fresh_config(root, run, args.source, with_assets=True) if args.with_assets else fresh_config(root, run, args.source)
        context, engine = assert_project_available(project, env)
        env.update(TEST365ALM_DOCKER_CONTEXT=context, TEST365ALM_DOCKER_ENGINE=engine)
        env.update(values)
        npm_config = root / "local-evidence/isolated.npmrc"
        npm_config.write_text("registry=https://registry.npmjs.org/\n", encoding="utf-8")
        npm_global = root / "local-evidence/empty-global.npmrc"
        npm_global.write_text("", encoding="utf-8")
        env.update(NPM_CONFIG_USERCONFIG=str(npm_config), NPM_CONFIG_GLOBALCONFIG=str(npm_global))
        if Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe").is_file():
            env["R03_E2E_BROWSER_CHANNEL"] = "msedge"
        java_home = Path("D:/code/ProOrca/Wetest365ALM/.tools/jdk-21")
        env.update(JAVA_HOME=str(java_home), PATH=str(java_home / "bin") + os.pathsep + env["PATH"])
        summary.update(context=context, engine=engine)
        asset_files = ["-f", str(root / "compose.r11.yaml")] if args.with_assets else []
        raw = _docker(["compose", "--env-file", str(root / ".env.r03"), "-f", str(root / "compose.r03.yaml"), *asset_files,
                       "-p", project, "config", "--format", "json"], env)
        document = json.loads(raw)
        init = root / "local-evidence/init-db.sh"
        init.write_bytes((root / "tools/r03_init_db.sh").read_bytes().replace(b"\r\n", b"\n"))
        for mount in document["services"]["postgres"]["volumes"]:
            if mount["target"].startswith("/docker-entrypoint-initdb.d/"):
                mount["source"] = str(init)
        if args.with_assets:
            local_browser_assets.prepare_document(document)
        else:
            document["services"]["postgres"]["environment"]["POSTGRES_SCANNER_PASSWORD"] = values["R11_SCANNER_PASSWORD"]
        kc = document["services"]["keycloak"]
        kc.update(volumes=[], entrypoint=["/bin/sh", "-c"], command=["sleep infinity"])
        for category in ("services", "volumes", "networks"):
            for item in document[category].values():
                item.setdefault("labels", {}).update({RUN_LABEL: run, SOURCE_LABEL: args.source})
        compose_file.write_text(json.dumps(document), encoding="utf-8")
        # Check again immediately before first create, not only before read-only configuration.
        verify_docker_engine(env)
        assert_project_available(project, env)
        creation_started = True
        compose("create")
        resources = capture(project, run, context, args.source, env)
        services = {}
        for identity in resources.containers:
            actual = _inspect("container", identity, env)
            identities[identity] = {k: actual[k] for k in ("Id", "Created", "Image")}
            services[_labels("container", actual)["com.docker.compose.service"]] = {
                "id": actual["Id"], "created": actual["Created"], "image": actual["Image"]}
        manifest = {"mode": "local-owned", **summary, "root": str(root), "envFile": str(root / ".env.r03"),
                    "composeFile": str(compose_file), "services": services, "resources": resources.as_dict()}
        manifest_path = evidence / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        verify(resources, args.source, identities, env)
        compose("start")
        def asset_guard():
            local_browser_assets.assert_owned(values, services, env, lambda: verify(resources, args.source, identities, env))
        if args.with_assets:
            summary["asset_preflight"] = "RUNNING"
            local_browser_assets.wait_ready(values, asset_guard)
            summary["asset_preflight"] = "PASSED"
        keycloak = services["keycloak"]["id"]
        verify(resources, args.source, identities, env)
        _docker(["exec", keycloak, "mkdir", "-p", "/opt/keycloak/data/import"], env)
        verify(resources, args.source, identities, env)
        _docker(["cp", str(root / "local-evidence/r03/realm.json"), f"{keycloak}:/opt/keycloak/data/import/realm.json"], env)
        for action in (["chown", "1000:1000"], ["chmod", "0400"]):
            verify(resources, args.source, identities, env)
            _docker(["exec", "--user", "0", keycloak, *action, "/opt/keycloak/data/import/realm.json"], env)
        verify(resources, args.source, identities, env)
        _docker(["exec", "-d", keycloak, "/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"], env)
        wait_http(values["TEST365ALM_OIDC_ISSUER"] + "/.well-known/openid-configuration", [])
        command(["mvn.cmd" if os.name == "nt" else "mvn", "-B", "-ntp", "package", f"-Dbuild.commit={args.source}"], root / "apps/server", "server-build")
        command(["cmd.exe", "/d", "/c", "npm ci"] if os.name == "nt" else ["npm", "ci"], root / "apps/web", "web-install")
        env.update(R03_LOCAL_MANIFEST=str(manifest_path), R03_COMPOSE_PROJECT=project,
                   R03_LOCAL_WEB_ORIGIN=values["TEST365ALM_WEB_ORIGIN"], R03_LOCAL_RESULTS_PATH=str(evidence / "results.json"))
        jar = root / "apps/server/target/server-0.0.1-SNAPSHOT.jar"
        for role, cmd, cwd in (("server", [str(java_home / "bin/java.exe"), "-jar", str(jar)], root / "apps/server"),
                               ("web", ["node", str(root / "apps/web/node_modules/vite/bin/vite.js"), "--host", "127.0.0.1", "--port", values["LOCAL_WEB_PORT"], "--strictPort"], root / "apps/web")):
            handle = (evidence / f"{role}.private.log").open("w", encoding="utf-8")
            handles.append(handle)
            processes.append(subprocess.Popen(cmd, cwd=cwd, env=env, stdout=handle, stderr=subprocess.STDOUT))
        wait_http(values["TEST365ALM_DEV_BACKEND_URL"] + "/health/ready", processes)
        wait_http(values["TEST365ALM_WEB_ORIGIN"], processes)
        if args.with_assets:
            summary["scanner_provision"] = "RUNNING"
            local_browser_assets.provision(values, services, env, asset_guard)
            summary["scanner_provision"] = "PASSED"
        verify(resources, args.source, identities, env)
        command(["node", "node_modules/@playwright/test/cli.js", "test", "e2e/project-access.spec.ts", "e2e/manual-execution.spec.ts", "e2e/defects.spec.ts",
                 "--config", "playwright.local.config.ts", "--grep", "real Keycloak UI (manual test case flow|manual execution creates|member creates defect|designated reviewer rejects approves)",
                 "--workers=1", "--retries=0"], root / "apps/web", "browser", timeout=720)
        report = json.loads((evidence / "results.json").read_text())
        result = 0 if validate_results(report) else 1
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        for stage in ("asset_preflight", "scanner_provision"):
            if summary[stage] == "RUNNING":
                summary[stage] = "FAILED"
        summary["failure_type"] = type(error).__name__
        print(f"FAIL: {type(error).__name__} (private diagnostics kept locally)", flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        for handle in handles:
            handle.close()
        try:
            if not creation_started:
                cleanup = True  # No create attempted: never inspect or touch a refused project.
            else:
                if resources is None:
                    resources = capture(project, run, context, args.source, env)
                else:
                    verify(resources, args.source, identities, env)
                cleanup = cleanup_manifest(resources, env)
        except (OSError, RuntimeError, subprocess.SubprocessError):
            cleanup = False
        # Redact every generated credential from retained text diagnostics, then remove secret files.
        env_text = "".join(f"{k}={v}\n" for k, v in values.items())
        for path in evidence.glob("*.private.log"):
            data = redact(env_text, path.read_text(encoding="utf-8", errors="replace"))
            data = re.sub(r"(?im)^.*(?:set-cookie|authorization|cookie:).*$", "[REDACTED HEADER]", data)
            data = re.sub(r"(https?://[^\s?]+)\?[^\s]+", r"\1?[REDACTED]", data)
            path.with_suffix(".redacted.log").write_text(data, encoding="utf-8")
            path.unlink()
        for path in secret_paths:
            if path not in preexisting_secrets:
                path.unlink(missing_ok=True)
        summary.update(exit_code=result, cleanup=cleanup, processes_stopped=all(p.poll() is not None for p in processes))
        (evidence / "run.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        print(json.dumps(summary), flush=True)
    return result if cleanup else 1


if __name__ == "__main__":
    raise SystemExit(main())
