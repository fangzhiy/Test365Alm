"""Real asset facilities for one explicitly owned local browser run.

No service starts on import. The caller supplies its pinned resource guard, which
must succeed before each probe and each independent provisioning connection.
"""
from __future__ import annotations

import subprocess
import time
import uuid

from tools import provision_asset_scanner_role as scanner_provision
from tools.r02_resource_guard import ResourceOwnershipError, _inspect
from tools.verify_r11_services import check, validate_targets

# Match the existing R11 digest-pinned services; never accept an arbitrary image.
SERVICES = (
    ("asset-store", "chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d", "8333/tcp", "R11_S3_PORT"),
    ("asset-scanner", "clamav/clamav:1.5.4-debian13-slim@sha256:9a96980b265cb2b51a20bdcca921dc80aa55ed0e56cdfb076198ee27a9171927", "3310/tcp", "R11_CLAMD_PORT"),
)


def configure(values: dict[str, str], used: set[int], allocate_port) -> None:
    s3, clamd = (str(allocate_port(used)) for _ in range(2))
    values.update(TEST365ALM_ASSETS_ENABLED="true", R11_S3_PORT=s3, R11_CLAMD_PORT=clamd,
                  TEST365ALM_ASSETS_S3_ENDPOINT=f"http://127.0.0.1:{s3}",
                  TEST365ALM_ASSETS_CLAMD_HOST="127.0.0.1", TEST365ALM_ASSETS_CLAMD_PORT=clamd,
                  TEST365ALM_ASSETS_SCANNER_DB_USERNAME="r03_step_scanner_" + uuid.uuid4().hex)
    # prepare_r03_dev already generated an independent fresh scanner password.


def prepare_document(document: dict) -> None:
    if set(document["services"]) != {"postgres", "keycloak", "asset-store", "asset-scanner"}:
        raise ResourceOwnershipError("Only the four explicitly owned browser services are allowed")
    # Flyway creates the NOLOGIN capability; the repository operator tool creates
    # a distinct restricted child LOGIN after migration. Never bootstrap a group LOGIN.
    document["services"]["postgres"]["environment"].pop("POSTGRES_SCANNER_PASSWORD", None)
    for name, image, _, _ in SERVICES:
        if document["services"][name]["image"] != image:
            raise ResourceOwnershipError("Asset service image is not the approved digest")


def assert_owned(values: dict, services: dict, environment: dict, guard) -> None:
    guard()  # Verifies exact IDs, creation/image pins, labels, context and Engine.
    if (set(services) != {"postgres", "keycloak", "asset-store", "asset-scanner"}
            or len({service["id"] for service in services.values()}) != 4):
        raise ResourceOwnershipError("Missing or unexpected owned asset service")
    if (values.get("TEST365ALM_ASSETS_ENABLED") != "true"
            or values.get("TEST365ALM_ASSETS_S3_ENDPOINT") != f"http://127.0.0.1:{values.get('R11_S3_PORT')}"
            or values.get("TEST365ALM_ASSETS_CLAMD_HOST") != "127.0.0.1"
            or values.get("TEST365ALM_ASSETS_CLAMD_PORT") != values.get("R11_CLAMD_PORT")):
        raise ResourceOwnershipError("Asset endpoint is not the explicitly owned loopback endpoint")
    try:
        validate_targets(values["TEST365ALM_ASSETS_S3_ENDPOINT"], "127.0.0.1", int(values["R11_CLAMD_PORT"]))
    except (KeyError, ValueError) as error:
        raise ResourceOwnershipError("Invalid owned asset endpoints") from error
    for name, image, private_port, public_port in SERVICES:
        expected = services[name]
        actual = _inspect("container", expected["id"], environment)
        if (any(actual.get(k) != expected[v] for k, v in (("Id", "id"), ("Created", "created"), ("Image", "image")))
                or not actual.get("State", {}).get("Running")
                or actual.get("Config", {}).get("Image") != image
                or actual.get("Config", {}).get("Labels", {}).get("com.docker.compose.service") != name
                or actual.get("NetworkSettings", {}).get("Ports", {}).get(private_port)
                != [{"HostIp": "127.0.0.1", "HostPort": values[public_port]}]):
            raise ResourceOwnershipError("Asset service identity or published endpoint changed")


def wait_ready(values: dict, guard, seconds: int = 240) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        guard()  # Ownership refusal is never swallowed as a readiness retry.
        if check(values["TEST365ALM_ASSETS_S3_ENDPOINT"], values["TEST365ALM_ASSETS_CLAMD_HOST"],
                 int(values["TEST365ALM_ASSETS_CLAMD_PORT"])):
            return  # Genuine private S3 response and benign clamd INSTREAM=CLEAN.
        time.sleep(2)
    raise RuntimeError("Owned private S3 or real benign scan did not become ready")


def provision(values: dict, services: dict, environment: dict, guard) -> None:
    guard()
    source = dict(environment)
    source.update(PGHOST="127.0.0.1", PGPORT="5432", PGDATABASE=values["R03_DB_NAME"],
                  PGUSER=values["R03_MIGRATION_USER"], PGPASSWORD=values["R03_MIGRATION_PASSWORD"],
                  TEST365ALM_PROVISION_CONTAINER_ID=services["postgres"]["id"],
                  TEST365ALM_ASSETS_SCANNER_DB_USERNAME=values["TEST365ALM_ASSETS_SCANNER_DB_USERNAME"],
                  TEST365ALM_ASSETS_SCANNER_DB_PASSWORD=values["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"],
                  DOCKER_CONTEXT=environment["TEST365ALM_DOCKER_CONTEXT"])

    def guarded_run(*args, **kwargs):
        guard()  # Includes the independent SCRAM verification connection.
        return subprocess.run(*args, **kwargs)

    if scanner_provision.main(source, run=guarded_run) != 0:
        raise RuntimeError("Owned scanner restricted LOGIN provisioning failed")
