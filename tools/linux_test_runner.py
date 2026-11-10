"""Opt-in local Linux Maven verification. Docker socket access is full daemon authority.

Only a committed archive is copied in. No Windows source/tmp/cache binds, .env,
credentials, raw logs or EICAR bytes are exported. Never targets remote Docker.
"""
from __future__ import annotations
import argparse
import dataclasses
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.r02_resource_guard import ResourceOwnershipError, docker_context, docker_engine

RUN = "com.test365alm.linux.run"
SOURCE = "com.test365alm.linux.source"


@dataclasses.dataclass(frozen=True)
class Identity:
    id: str
    created: str
    image: str


def validate(actual: dict, run: str, source: str, expected: Identity | None = None) -> Identity:
    labels = actual.get("Config", {}).get("Labels") or {}
    identity = Identity(actual["Id"], actual["Created"], actual["Image"])
    if labels.get(RUN) != run or labels.get(SOURCE) != source or (expected and identity != expected):
        raise ResourceOwnershipError("Container ownership or immutable identity changed")
    host = actual.get("HostConfig") or {}
    if host.get("Privileged") or host.get("PidMode") == "host" or host.get("NetworkMode") == "host":
        raise ResourceOwnershipError("Unexpected privileged or host namespace access")
    for mount in actual.get("Mounts", []):
        if mount.get("Type") == "bind" and not (
                mount.get("Source") == "/var/run/docker.sock" and mount.get("Destination") == "/var/run/docker.sock"):
            raise ResourceOwnershipError("Unexpected host bind mount")
    return identity


class Guard:
    def __init__(self, run: str, source: str, environment: dict[str, str]):
        self.run, self.source, self.env = run, source, environment
        self.context = docker_context(environment)
        self.env["TEST365ALM_DOCKER_CONTEXT"] = self.context
        self.engine = docker_engine(self.env)
        self.resources: dict[str, Identity] = {}

    def verify_engine(self) -> None:
        if docker_context(self.env) != self.context or docker_engine(self.env) != self.engine:
            raise ResourceOwnershipError("Docker context or daemon changed")

    def command(self, *args: str) -> list[str]:
        return ["docker", "--context", self.context, *args]

    def docker(self, *args: str, timeout: int = 30) -> str:
        result = subprocess.run(self.command(*args), env=self.env, capture_output=True, timeout=timeout, check=False)
        if result.returncode:
            raise ResourceOwnershipError(f"Docker {args[0]} failed (exit {result.returncode}); raw diagnostics withheld")
        return result.stdout.decode("utf-8").strip()

    def inspect(self, container: str) -> dict | None:
        result = subprocess.run(self.command("container", "inspect", container), env=self.env,
                                capture_output=True, timeout=20, check=False)
        if result.returncode:
            if b"No such container" in result.stderr or b"No such object" in result.stderr:
                return None
            raise ResourceOwnershipError("Container inspection failed")
        return json.loads(result.stdout)[0]

    def capture(self, container: str) -> None:
        self.verify_engine()
        actual = self.inspect(container)
        if actual is not None:
            if actual["Id"] != container:
                raise ResourceOwnershipError("Container ID was replaced")
            self.resources[container] = validate(actual, self.run, self.source, self.resources.get(container))

    def require(self, container: str) -> None:
        self.verify_engine()
        if container not in self.resources:
            raise ResourceOwnershipError("Unrecorded container")
        actual = self.inspect(container)
        if actual is None:
            raise ResourceOwnershipError("Required container disappeared")
        validate(actual, self.run, self.source, self.resources[container])

    def discover(self) -> None:
        self.verify_engine()
        for container in self.docker("ps", "-aq", "--no-trunc", "--filter", f"label={RUN}={self.run}").split():
            self.capture(container)

    def remove(self, container: str) -> None:
        self.require(container)
        # -v removes only anonymous volumes attached to this exact owned container, never named volumes.
        self.docker("container", "rm", "-f", "-v", container)

    def cleanup(self) -> bool:
        try:
            self.discover()
            for container in list(self.resources):
                self.verify_engine()
                if self.inspect(container) is not None:
                    self.remove(container)
            return True
        except (ResourceOwnershipError, OSError, subprocess.SubprocessError):
            return False

    def labels(self) -> list[str]:
        return ["--label", f"{RUN}={self.run}", "--label", f"{SOURCE}={self.source}"]


def committed_archive(source: str, destination: Path) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("An exact 40-character commit is required")
    subprocess.run(["git", "cat-file", "-e", f"{source}^{{commit}}"], cwd=ROOT, check=True, capture_output=True)
    paths = subprocess.run(["git", "ls-tree", "-r", "--name-only", source], cwd=ROOT,
                           capture_output=True, text=True, check=True).stdout.splitlines()
    forbidden = {".env", ".env.demo", ".env.r03", ".env.r02-test", ".git"}
    if any(any(part in forbidden for part in Path(path).parts) for path in paths):
        raise ValueError("Snapshot contains forbidden environment or git paths")
    subprocess.run(["git", "archive", "--format=tar", f"--output={destination}", source], cwd=ROOT, check=True)


def phase(guard: Guard, runner: str, name: str, evidence: Path) -> int:
    guard.require(runner)
    child = subprocess.Popen(guard.command("exec", runner, "python3", "tools/linux_test_inside.py", name),
                             env=guard.env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + 1800
    try:
        while child.poll() is None:
            guard.discover()
            if time.monotonic() > deadline:
                raise ResourceOwnershipError("Bounded Linux test phase timed out")
            time.sleep(3)
    finally:
        if child.poll() is None:
            # Stops only the local CLI; finally cleanup removes the verified runner/test resources.
            child.terminate()
            child.wait(timeout=15)
    guard.discover()
    guard.require(runner)
    guard.docker("cp", f"{runner}:/work/evidence/{name}.json", str(evidence / f"{name}.json"))
    report = json.loads((evidence / f"{name}.json").read_text(encoding="utf-8"))
    print(json.dumps({"phase": name, "exit_code": child.returncode,
                      **{key: {k: v for k, v in report[key].items() if k != "cases"}
                         for key in ("unit", "integration") if key in report}}), flush=True)
    return child.returncode


def execute(guard: Guard, source: str, image: str, archive: Path, evidence: Path) -> int:
    guard.verify_engine()
    runner = guard.docker("create", *guard.labels(), "--mount", "type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock",
                          "--tmpfs", "/root/.m2:rw,size=1073741824", "--tmpfs", "/tmp:rw,size=536870912",
                          "--env", f"TEST365ALM_LINUX_RUN_ID={guard.run}",
                          "--env", f"TEST365ALM_LINUX_SOURCE_SHA={source}",
                          "--env", "DOCKER_HOST=unix:///var/run/docker.sock",
                          "--env", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
                          "--env", "TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false", image)
    guard.capture(runner)
    guard.require(runner)
    guard.docker("start", runner)
    guard.require(runner)
    guard.docker("cp", str(archive), f"{runner}:/snapshot.tar")
    guard.require(runner)
    guard.docker("exec", runner, "tar", "-xf", "/snapshot.tar", "-C", "/work")
    guard.require(runner)
    versions = guard.docker("exec", runner, "python3", "-c",
                           "import json,subprocess; print(json.dumps({k:subprocess.run(v,capture_output=True,text=True).stdout for k,v in "
                           "{'java':['java','--version'],'maven':['mvn','--version'],'python':['python3','--version'],"
                           "'docker':['docker','--version'],'packages':['cat','/test-tool-packages.txt']}.items()}))")
    (evidence / "tool-versions.json").write_text(versions, encoding="utf-8")
    # Separate disposable HTTP peer proves runner -> host.docker.internal -> published sibling connectivity.
    code = "from http.server import BaseHTTPRequestHandler,HTTPServer\nclass H(BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200);self.end_headers();self.wfile.write(b'owned-linux-probe')\nHTTPServer(('0.0.0.0',8765),H).serve_forever()"
    guard.verify_engine()
    probe = guard.docker("create", *guard.labels(), "--publish", "127.0.0.1::8765", "--tmpfs", "/root/.m2:rw,size=16777216",
                         image, "python3", "-c", code)
    guard.capture(probe)
    guard.require(probe)
    guard.docker("start", probe)
    guard.require(probe)
    binding = guard.inspect(probe)["NetworkSettings"]["Ports"]["8765/tcp"][0]
    port = str(int(binding["HostPort"]))
    guard.require(runner)
    guard.docker("exec", runner, "python3", "-c",
                 "import urllib.request,time\ntime.sleep(1)\nassert urllib.request.urlopen('http://host.docker.internal:"
                 + port + "',timeout=10).read()==b'owned-linux-probe'")
    guard.remove(probe)
    (evidence / "connectivity.json").write_text('{"host_docker_internal":true}', encoding="utf-8")
    for name in ("scan", "unit", "integration"):
        result = phase(guard, runner, name, evidence)
        if result:
            return result
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    args = parser.parse_args()
    run = uuid.uuid4().hex
    evidence = ROOT / "local-evidence" / "linux-tests" / run
    evidence.mkdir(parents=True)
    archive = evidence / "snapshot.tar"
    committed_archive(args.source, archive)
    # Forward only host runtime variables required to invoke the explicitly local Docker CLI.
    env = {k: v for k, v in os.environ.items() if k.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT"}}
    guard = Guard(run, args.source, env)
    result, cleanup = 1, False
    metadata = {"run": run, "source": args.source, "context": guard.context, "engine": guard.engine,
                "socket_authority": "full local Docker daemon control; labels are operation guards"}
    try:
        context = evidence / "build"
        context.mkdir()
        dockerfile = subprocess.run(["git", "show", f"{args.source}:tools/linux-test/Dockerfile"],
                                    cwd=ROOT, capture_output=True, check=True).stdout
        (context / "Dockerfile").write_bytes(dockerfile)
        tag = f"test365alm-linux-test:{run}"
        guard.verify_engine()
        guard.docker("build", *guard.labels(), "--tag", tag, str(context), timeout=900)
        image = guard.docker("image", "inspect", "--format", "{{.Id}}", tag)
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise ValueError("Missing built image content identity")
        metadata["image"] = image
        print(json.dumps(metadata), flush=True)
        result = execute(guard, args.source, image, archive, evidence)
    except (ValueError, ResourceOwnershipError, OSError, subprocess.SubprocessError) as error:
        metadata["failure_type"] = type(error).__name__
        print(f"FAIL: {type(error).__name__}: {error}", flush=True)
    finally:
        cleanup = guard.cleanup()
        metadata.update(exit_code=result, cleanup=cleanup,
                        containers=[dataclasses.asdict(identity) for identity in guard.resources.values()])
        (evidence / "run.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        print(f"cleanup={cleanup} evidence={evidence}", flush=True)
    return result if cleanup else 1


if __name__ == "__main__":
    raise SystemExit(main())
