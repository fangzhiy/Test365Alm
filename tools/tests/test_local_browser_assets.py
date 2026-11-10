"""Offline refusal tests only; real S3/ClamAV acceptance is a separate owned run."""
import copy
import contextlib
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools import local_browser_runner as runner
from tools.r02_resource_guard import ResourceManifest, ResourceOwnershipError


class LocalBrowserAssetTests(unittest.TestCase):
    def test_explicit_asset_mode_has_fresh_ports_and_a_dedicated_scanner_login(self):
        with tempfile.TemporaryDirectory() as directory:
            values = runner.fresh_config(Path(directory), "r03-local-" + "a" * 32, "b" * 40, with_assets=True)
        self.assertEqual("true", values["TEST365ALM_ASSETS_ENABLED"])
        ports = [int(values[key]) for key in ("R03_POSTGRES_PORT", "R03_KEYCLOAK_PORT", "TEST365ALM_SERVER_PORT",
                                             "LOCAL_WEB_PORT", "R11_S3_PORT", "R11_CLAMD_PORT")]
        self.assertEqual(6, len(set(ports)))
        self.assertFalse(set(ports) & {5173, 8080, 15173, 18083})
        self.assertEqual(f"http://127.0.0.1:{values['R11_S3_PORT']}", values["TEST365ALM_ASSETS_S3_ENDPOINT"])
        self.assertEqual(values["R11_CLAMD_PORT"], values["TEST365ALM_ASSETS_CLAMD_PORT"])
        self.assertRegex(values["TEST365ALM_ASSETS_SCANNER_DB_USERNAME"], r"^r03_step_scanner_[a-f0-9]{32}$")
        self.assertNotEqual(values["R03_RUNTIME_PASSWORD"], values["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"])

    def fixture(self):
        from tools import local_browser_assets as assets
        values = {"TEST365ALM_ASSETS_ENABLED": "true", "R11_S3_PORT": "32001", "R11_CLAMD_PORT": "32002",
                  "R03_DB_NAME": "test365alm_r03", "R03_MIGRATION_USER": "owner", "R03_MIGRATION_PASSWORD": "offline-private",
                  "TEST365ALM_ASSETS_SCANNER_DB_USERNAME": "r03_step_scanner_12345678",
                  "TEST365ALM_ASSETS_SCANNER_DB_PASSWORD": "dedicated-scanner-secret-at-least24",
                  "TEST365ALM_ASSETS_S3_ENDPOINT": "http://127.0.0.1:32001",
                  "TEST365ALM_ASSETS_CLAMD_HOST": "127.0.0.1", "TEST365ALM_ASSETS_CLAMD_PORT": "32002"}
        services = {name: {"id": letter * 64, "created": "now", "image": "sha256:" + letter * 64}
                    for name, letter in (("postgres", "a"), ("keycloak", "b"), ("asset-store", "c"), ("asset-scanner", "d"))}
        actual = {}
        for name, image, private_port, public_port in assets.SERVICES:
            service = services[name]
            actual[service["id"]] = {
                "Id": service["id"], "Created": "now", "Image": service["image"], "State": {"Running": True},
                "Config": {"Image": image, "Labels": {"com.docker.compose.service": name}},
                "NetworkSettings": {"Ports": {private_port: [{"HostIp": "127.0.0.1", "HostPort": values[public_port]}]}}}
        return assets, values, services, actual

    def test_owned_assets_require_both_pins_images_and_exact_loopback_bindings(self):
        assets, values, services, actual = self.fixture()
        guard = Mock()
        with patch.object(assets, "_inspect", side_effect=lambda kind, identity, env: actual[identity]):
            assets.assert_owned(values, services, {}, guard)
            self.assertEqual(1, guard.call_count)
            changes = [lambda a: a.update(Id="replaced"), lambda a: a.update(Created="later"),
                       lambda a: a.update(Image="other"), lambda a: a["Config"].update(Image="unpinned"),
                       lambda a: a["State"].update(Running=False),
                       lambda a: a["Config"]["Labels"].update({"com.docker.compose.service": "foreign"}),
                       lambda a: a["NetworkSettings"].update(Ports={"3310/tcp": [{"HostIp": "0.0.0.0", "HostPort": "32002"}]}),
                       lambda a: a["NetworkSettings"].update(Ports={"3310/tcp": [{"HostIp": "127.0.0.1", "HostPort": "32003"}]})]
            identity = services["asset-scanner"]["id"]
            for change in changes:
                original = copy.deepcopy(actual[identity])
                change(actual[identity])
                with self.assertRaises(ResourceOwnershipError):
                    assets.assert_owned(values, services, {}, guard)
                actual[identity] = original
            for bad in ({**services, "unexpected": services["postgres"]}, {k: v for k, v in services.items() if k != "asset-store"}):
                with self.assertRaises(ResourceOwnershipError):
                    assets.assert_owned(values, bad, {}, guard)

    def test_foreign_endpoint_or_changed_daemon_prevents_all_network_and_provisioning(self):
        assets, values, services, actual = self.fixture()
        for change in ({"TEST365ALM_ASSETS_S3_ENDPOINT": "http://127.0.0.1:8080"},
                       {"TEST365ALM_ASSETS_CLAMD_HOST": "192.0.2.1"}, {"TEST365ALM_ASSETS_CLAMD_PORT": "8080"}):
            with patch.object(assets, "_inspect", side_effect=lambda kind, identity, env: actual[identity]):
                with self.assertRaises(ResourceOwnershipError):
                    assets.assert_owned({**values, **change}, services, {}, Mock())
        refused = Mock(side_effect=ResourceOwnershipError("daemon changed"))
        with patch.object(assets, "check") as network, patch.object(assets.subprocess, "run") as process:
            with self.assertRaises(ResourceOwnershipError):
                assets.wait_ready(values, refused)
            with self.assertRaises(ResourceOwnershipError):
                assets.provision(values, services, {}, refused)
            network.assert_not_called()
            process.assert_not_called()

    def test_provisioning_uses_real_repository_entrypoint_and_no_secret_argv(self):
        assets, values, services, _ = self.fixture()
        values.update(R03_DB_NAME="test365alm_r03", R03_MIGRATION_USER="owner", R03_MIGRATION_PASSWORD="owner-private",
                      TEST365ALM_ASSETS_SCANNER_DB_USERNAME="r03_step_scanner_12345678",
                      TEST365ALM_ASSETS_SCANNER_DB_PASSWORD="dedicated-scanner-secret-at-least24")
        calls = []
        def execute(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, values["TEST365ALM_ASSETS_SCANNER_DB_USERNAME"] if len(calls) == 2 else "", "")
        guard = Mock()
        with contextlib.redirect_stdout(io.StringIO()), patch.object(assets.subprocess, "run", side_effect=execute):
            assets.provision(values, services, {"PATH": "path", "TEST365ALM_DOCKER_CONTEXT": "local"}, guard)
        self.assertEqual(2, len(calls))  # Real provision SQL and independent SCRAM verification.
        self.assertGreaterEqual(guard.call_count, 3)
        for command, kwargs in calls:
            self.assertEqual(["docker", "exec", "-i"], command[:3])
            self.assertIn(services["postgres"]["id"], command)
            self.assertIn("require_auth=scram-sha-256", " ".join(command))
            self.assertEqual("local", kwargs["env"]["DOCKER_CONTEXT"])
            self.assertNotIn("private", " ".join(command))
            self.assertNotIn(values["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"], " ".join(command))
        self.assertIn("SCRAM-SHA-256", calls[0][1]["input"])

    def test_unavailable_real_scan_or_failed_provision_cannot_report_ready(self):
        assets, values, services, _ = self.fixture()
        with patch.object(assets, "check", return_value=False), patch.object(assets.time, "monotonic", side_effect=[0, 0, 2]), patch.object(assets.time, "sleep"):
            with self.assertRaises(RuntimeError):
                assets.wait_ready(values, Mock(), seconds=1)
        with patch.object(assets.scanner_provision, "main", return_value=1):
            with self.assertRaises(RuntimeError):
                assets.provision(values, services, {"TEST365ALM_DOCKER_CONTEXT": "local"}, Mock())

    def test_asset_document_retains_pinned_services_but_never_bootstraps_scanner_group_login(self):
        assets, _, _, _ = self.fixture()
        document = {"services": {"postgres": {"environment": {"POSTGRES_SCANNER_PASSWORD": "private", "POSTGRES_RUNTIME_PASSWORD": "runtime"}},
                                 "keycloak": {}, **{name: {"image": image} for name, image, _, _ in assets.SERVICES}}}
        assets.prepare_document(document)
        self.assertEqual({"POSTGRES_RUNTIME_PASSWORD": "runtime", "POSTGRES_INITDB_ARGS": "--auth-host=scram-sha-256",
                          "POSTGRES_HOST_AUTH_METHOD": "scram-sha-256"}, document["services"]["postgres"]["environment"])
        for service in ("asset-store", "asset-scanner"):
            changed = copy.deepcopy(document)
            changed["services"][service]["image"] = "latest"
            with self.assertRaises(ResourceOwnershipError):
                assets.prepare_document(changed)

    def test_fresh_owned_postgres_requires_scram_on_loopback_and_rejects_auth_overrides(self):
        assets, _, _, _ = self.fixture()
        document = {"services": {"postgres": {"environment": {}}, "keycloak": {},
                                 **{name: {"image": image} for name, image, _, _ in assets.SERVICES}}}
        assets.prepare_document(document)
        env = document["services"]["postgres"]["environment"]
        self.assertEqual("--auth-host=scram-sha-256", env.get("POSTGRES_INITDB_ARGS"))
        self.assertEqual("scram-sha-256", env.get("POSTGRES_HOST_AUTH_METHOD"))
        for key, value in (("POSTGRES_INITDB_ARGS", "--auth-host=trust"), ("POSTGRES_HOST_AUTH_METHOD", "trust"),
                           ("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256 --auth-local=trust")):
            changed = copy.deepcopy(document)
            changed["services"]["postgres"]["environment"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ResourceOwnershipError):
                assets.prepare_document(changed)

    def test_asset_stage_failures_stop_owned_processes_redact_and_clean_only_captured_ids(self):
        for failure in ("preflight", "provision"):
            with self.subTest(stage=failure), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                assets, _, services, _ = self.fixture()
                resource = ResourceManifest("project", "run", "local", tuple(s["id"] for s in services.values()),
                                            ("exact-network",), ("exact-pg-volume", "exact-s3-volume", "exact-clam-volume"), "engine", runner.RUN_LABEL)
                def archive(source, destination):
                    fixture = base / "fixture"
                    (fixture / "tools").mkdir(parents=True)
                    (fixture / "tools/r03_init_db.sh").write_text("#!/bin/sh\n")
                    with tarfile.open(destination, "w") as snapshot:
                        snapshot.add(fixture, arcname=".")
                document = {"services": {"postgres": {"environment": {"POSTGRES_SCANNER_PASSWORD": "unused-private"}, "volumes": []},
                                         "keycloak": {}, **{name: {"image": image} for name, image, _, _ in assets.SERVICES}},
                            "volumes": {"pg": {}, "s3": {}, "clam": {}}, "networks": {"default": {}}}
                by_id = {service["id"]: {"Id": service["id"], "Created": service["created"], "Image": service["image"],
                                         "Config": {"Labels": {"com.docker.compose.service": name}}} for name, service in services.items()}
                values = {}
                original_config = runner.fresh_config
                def config(*args, **kwargs):
                    values.update(original_config(*args, **kwargs))
                    return values
                def command(argv, **kwargs):
                    kwargs["stdout"].write("private-test " + values["TEST365ALM_ASSETS_S3_SECRET_KEY"])
                    return subprocess.CompletedProcess(argv, 0)
                stopped = [False]
                process = Mock()
                process.poll.side_effect = lambda: 0 if stopped[0] else None
                process.terminate.side_effect = lambda: stopped.__setitem__(0, True)
                with contextlib.redirect_stdout(io.StringIO()), patch.object(runner, "ROOT", base), \
                        patch.object(runner.sys, "argv", ["runner", "--source", "b" * 40, "--with-assets"]), \
                        patch.object(runner, "committed_archive", side_effect=archive), patch.object(runner, "fresh_config", side_effect=config), \
                        patch.object(runner, "assert_project_available", return_value=("local", "engine")), \
                        patch.object(runner, "verify_docker_engine"), patch.object(runner, "verify") as verify, \
                        patch.object(runner, "_docker", side_effect=lambda argv, *a, **k: json.dumps(document) if "config" in argv else ""), \
                        patch.object(runner, "capture", return_value=resource), patch.object(runner, "_inspect", side_effect=lambda kind, identity, env: by_id[identity]), \
                        patch.object(runner, "cleanup_manifest", return_value=True) as cleanup, patch.object(runner, "wait_http"), \
                        patch.object(runner.subprocess, "run", side_effect=command), patch.object(runner.subprocess, "Popen", return_value=process), \
                        patch.object(assets, "wait_ready", side_effect=RuntimeError("unavailable") if failure == "preflight" else None), \
                        patch.object(assets, "provision", side_effect=RuntimeError("refused")) as provision:
                    self.assertEqual(1, runner.main())
                cleanup.assert_called_once()
                self.assertIs(resource, cleanup.call_args.args[0])
                self.assertGreater(verify.call_count, 0)
                evidence = next((base / "local-evidence/local-browser").iterdir())
                report = json.loads((evidence / "run.json").read_text())
                self.assertTrue(report["cleanup"])
                self.assertTrue(report["processes_stopped"])
                self.assertEqual("FAILED" if failure == "preflight" else "PASSED", report["asset_preflight"])
                self.assertEqual("NOT_RUN" if failure == "preflight" else "FAILED", report["scanner_provision"])
                self.assertFalse(list(evidence.glob("*.private.log")))
                for log in evidence.glob("*.redacted.log"):
                    self.assertNotIn(values["TEST365ALM_ASSETS_S3_SECRET_KEY"], log.read_text())
                for secret in (".env.r03", "local-evidence/r03/realm.json", "local-evidence/local-browser-compose.json"):
                    self.assertFalse((evidence / "repo" / secret).exists())
                if failure == "preflight":
                    provision.assert_not_called()
                else:
                    process.terminate.assert_called()


if __name__ == "__main__":
    unittest.main()
