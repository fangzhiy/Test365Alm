import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.verify_r02_readiness import (
    VerificationError,
    is_loopback_address,
    parse_listener_output,
    port_is_free,
    validate_test_datasource,
    load_env,
    wait_for,
)
from tools.r02_resource_guard import (
    ResourceManifest,
    ResourceOwnershipError,
    assert_project_available,
    cleanup_manifest,
    isolated_environment,
    verify_manifest,
    verify_docker_engine,
    java_environment,
)
import tools.verify_r02_readiness as readiness


class DeadProcess:
    def poll(self):
        return 17


class ReadinessScriptTests(unittest.TestCase):
    def test_port_occupied_is_not_available_and_is_not_killed(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            self.assertFalse(port_is_free(port))
            self.assertTrue(listener.fileno() >= 0)

    def test_dead_backend_fails_before_a_simulated_http_response_can_pass(self):
        with self.assertRaisesRegex(VerificationError, "exited"):
            wait_for("http://127.0.0.1:1", "/health/live", {200}, time.monotonic() + 1,
                     DeadProcess(), Path("startup.log"))

    def test_loopback_and_mapped_addresses_are_distinguished_from_wildcards(self):
        for address in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
            self.assertTrue(is_loopback_address(address), address)
        for address in ("0.0.0.0", "::", "192.0.2.10"):
            self.assertFalse(is_loopback_address(address), address)

    def test_listener_parser_handles_ipv4_ipv6_and_mapped_ipv6(self):
        output = "\n".join([
            "LISTEN 0 128 127.0.0.1:18080 0.0.0.0:*",
            "LISTEN 0 128 [::ffff:127.0.0.1]:18080 [::]:*",
            "LISTEN 0 128 0.0.0.0:18080 0.0.0.0:*",
        ])
        self.assertEqual(
            ["127.0.0.1", "::ffff:127.0.0.1", "0.0.0.0"],
            parse_listener_output(output, 18080),
        )

    def test_unavailable_listener_enumeration_is_not_success(self):
        with patch("tools.verify_r02_readiness.subprocess.run", side_effect=FileNotFoundError), \
                patch("tools.verify_r02_readiness.Path.read_text", side_effect=OSError):
            from tools.verify_r02_readiness import listening_addresses
            self.assertIsNone(listening_addresses(18080))

    def test_external_datasource_is_rejected_before_destructive_actions(self):
        with self.assertRaisesRegex(ValueError, "non-local"):
            validate_test_datasource({
                "POSTGRES_DB": "test365alm",
                "POSTGRES_HOST_PORT": "54329",
                "POSTGRES_USER": "test",
                "POSTGRES_PASSWORD": "secret",
                "TEST365ALM_DATASOURCE_URL": "jdbc:postgresql://db.example.invalid:5432/test365alm",
            })

    def test_preflight_rejects_existing_project_before_any_up(self):
        with patch("tools.r02_resource_guard.docker_context", return_value="desktop-linux"), \
                patch("tools.r02_resource_guard.existing_resources", return_value={
                    "containers": ("old-container",), "networks": (), "volumes": ("old-volume",)
                }), \
                patch("tools.r02_resource_guard._docker") as docker:
            with self.assertRaisesRegex(ResourceOwnershipError, "already has resources"):
                assert_project_available("test365alm-r02-collision", {})
            self.assertEqual(["info", "--format", "{{.ID}}"], docker.call_args.args[0])

    def test_cleanup_rejects_mismatched_run_without_deleting(self):
        manifest = ResourceManifest("project", "run-new", "desktop-linux", ("c",), ("n",), ("v",), "engine")
        with patch("tools.r02_resource_guard.docker_context", return_value="desktop-linux"), \
                patch("tools.r02_resource_guard.docker_engine", return_value="engine"), \
                patch("tools.r02_resource_guard._inspect", return_value={"Config": {"Labels": {"com.docker.compose.project": "project", "com.test365alm.r02.run-id": "other-run"}}}), \
                patch("tools.r02_resource_guard._docker") as docker:
            self.assertFalse(cleanup_manifest(manifest, {}))
            docker.assert_not_called()

    def test_changed_engine_blocks_mutation(self):
        with patch("tools.r02_resource_guard.docker_context", return_value="desktop-linux"), \
                patch("tools.r02_resource_guard.docker_engine", return_value="other-engine"):
            with self.assertRaisesRegex(ResourceOwnershipError, "changed"):
                verify_docker_engine({"TEST365ALM_DOCKER_CONTEXT": "desktop-linux",
                                      "TEST365ALM_DOCKER_ENGINE": "expected-engine"})

    def test_cleanup_failure_is_not_reported_success(self):
        manifest = ResourceManifest("project", "run-1", "desktop-linux", ("c",), (), (), "engine")
        with patch("tools.r02_resource_guard.docker_context", return_value="desktop-linux"), \
                patch("tools.r02_resource_guard.docker_engine", return_value="engine"), \
                patch("tools.r02_resource_guard._inspect", return_value={"Config": {"Labels": {
                    "com.docker.compose.project": "project", "com.test365alm.r02.run-id": "run-1"}}}), \
                patch("tools.r02_resource_guard._docker", side_effect=ResourceOwnershipError("daemon cleanup failed")) as docker:
            self.assertFalse(cleanup_manifest(manifest, {}))
            docker.assert_called_once_with(["container", "rm", "-f", "c"], {})

    def test_isolated_environment_removes_parent_spring_and_compose_overrides(self):
        with patch.dict("tools.r02_resource_guard.os.environ", {
            "SYSTEMROOT": r"C:\Windows",
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://example.invalid/bad",
            "SPRING_FLYWAY_URL": "jdbc:postgresql://example.invalid/bad",
            "SPRING_APPLICATION_JSON": "{bad}", "JAVA_TOOL_OPTIONS": "-Dbad=true",
            "COMPOSE_FILE": "attacker.yaml", "DOCKER_HOST": "unix:///owned.sock",
            "DOCKER_CONTEXT": "remote-context",
        }, clear=True):
            isolated = isolated_environment({
                "POSTGRES_DB": "test365alm", "POSTGRES_USER": "test", "POSTGRES_PASSWORD": "secret",
                "POSTGRES_HOST_PORT": "55432", "TEST365ALM_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:55432/test365alm",
            }, "run-1", "desktop-linux")
        self.assertEqual("run-1", isolated["TEST365ALM_RUN_ID"])
        self.assertEqual(r"C:\Windows", isolated["SYSTEMROOT"])
        self.assertNotIn("SPRING_DATASOURCE_URL", isolated)
        self.assertNotIn("SPRING_FLYWAY_URL", isolated)
        self.assertNotIn("SPRING_APPLICATION_JSON", isolated)
        self.assertNotIn("COMPOSE_FILE", isolated)
        self.assertNotIn("JAVA_TOOL_OPTIONS", isolated)
        self.assertNotIn("DOCKER_HOST", isolated)
        self.assertNotIn("DOCKER_CONTEXT", isolated)

    def test_java_environment_explicitly_binds_datasource_and_flyway(self):
        isolated = java_environment({"PATH": "x", "TEST365ALM_DOCKER_CONTEXT": "desktop-linux"},
                                     datasource_url="jdbc:postgresql://127.0.0.1:55432/test365alm",
                                     datasource_username="test", datasource_password="secret",
                                     server_port=18082, migration_locations="filesystem:C:/owned/V2")
        self.assertEqual(isolated["TEST365ALM_DATASOURCE_URL"], isolated["SPRING_FLYWAY_URL"])
        self.assertEqual("filesystem:C:/owned/V2", isolated["SPRING_FLYWAY_LOCATIONS"])
        self.assertEqual("classpath:/application.properties", isolated["SPRING_CONFIG_LOCATION"])

    def test_load_env_rejects_parent_override_keys(self):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as config:
            config.write("POSTGRES_DB=test365alm\nSPRING_DATASOURCE_URL=jdbc:postgresql://example.invalid/nope\n")
            path = Path(config.name)
        try:
            with self.assertRaisesRegex(ValueError, "unsupported test configuration key"):
                load_env(path)
        finally:
            path.unlink(missing_ok=True)

    def test_main_refuses_after_preflight_failure_before_cleanup_or_success(self):
        calls = []
        with patch.object(sys, "argv", ["verify_r02_readiness.py", "--compose-project", "collision-project"]), \
                patch.object(readiness, "port_is_free", return_value=True), \
                patch.object(readiness, "load_env", return_value={
                    "POSTGRES_DB": "test365alm", "POSTGRES_USER": "test", "POSTGRES_PASSWORD": "secret",
                    "POSTGRES_HOST_PORT": "54329",
                }), \
                patch.object(readiness, "jar_build_commit", return_value="unknown"), \
                patch.object(readiness, "assert_project_available", side_effect=ResourceOwnershipError("collision")), \
                patch.object(readiness, "isolated_environment", side_effect=lambda *args: {}), \
                patch.object(readiness, "run_compose", side_effect=lambda *args, **kwargs: calls.append("up")), \
                patch.object(readiness, "capture_manifest", side_effect=lambda *args: calls.append("capture")):
            result = readiness.main()
        self.assertEqual(1, result)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
