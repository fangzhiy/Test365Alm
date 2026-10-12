import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import tools.verify_r02_migration_failure as migration
from tools.r02_resource_guard import ResourceManifest, ResourceOwnershipError, java_environment as build_java_environment


class FinishedProcess:
    pid = 43210

    def __init__(self, *args, **kwargs):
        stdout = kwargs["stdout"]
        stdout.write(b"Flyway V2__intentional_failure\n")
        stdout.flush()

    def poll(self):
        return 1

    def terminate(self):
        return None

    def wait(self, timeout=None):
        return 1


class MigrationIsolationTests(unittest.TestCase):
    def test_existing_project_is_rejected_before_up(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / ".env.r02-test"
            env_file.write_text("POSTGRES_DB=test365alm\nPOSTGRES_USER=test\nPOSTGRES_PASSWORD=secret\nPOSTGRES_HOST_PORT=55432\n", encoding="utf-8")
            jar = root / "server.jar"
            jar.write_bytes(b"test")
            with patch.object(migration, "ROOT", root), \
                    patch.object(sys, "argv", ["verify_r02_migration_failure.py", "--env-file", str(env_file), "--jar", str(jar)]), \
                    patch.object(migration, "port_is_free", return_value=True), \
                    patch.object(migration, "assert_project_available", side_effect=ResourceOwnershipError("collision")), \
                    patch.object(migration, "run_compose") as compose:
                self.assertEqual(2, migration.main())
                compose.assert_not_called()

    def test_main_uses_isolated_explicit_flyway_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / ".env.r02-test"
            env_file.write_text(
                "POSTGRES_DB=test365alm\nPOSTGRES_USER=test\nPOSTGRES_PASSWORD=secret\n"
                "POSTGRES_HOST_PORT=55432\n",
                encoding="utf-8",
            )
            jar = root / "server.jar"
            jar.write_bytes(b"not-a-real-jar")
            manifest = ResourceManifest("owned-project", "run-1", "desktop-linux", ("c",), ("n",), ("v",))
            captured = {}

            def capture_java(environment, **kwargs):
                result = build_java_environment(environment, **kwargs)
                captured.update(result)
                return result

            with patch.object(migration, "ROOT", root), \
                    patch.object(sys, "argv", ["verify_r02_migration_failure.py", "--env-file", str(env_file), "--jar", str(jar)]), \
                    patch.object(migration, "port_is_free", return_value=True), \
                    patch.object(migration, "free_ephemeral_port", return_value=55432), \
                    patch.object(migration, "assert_project_available", return_value=("desktop-linux", "test-engine")), \
                    patch.object(migration, "run_compose"), \
                    patch.object(migration, "capture_manifest", return_value=manifest), \
                    patch.object(migration, "manifest_path"), \
                    patch.object(migration, "cleanup_manifest", return_value=True), \
                    patch.object(migration, "java_environment", side_effect=capture_java), \
                    patch.object(migration.subprocess, "Popen", side_effect=FinishedProcess), \
                    patch.object(migration, "jar_build_commit", return_value="unknown"):
                with patch.dict(os.environ, {
                    "SPRING_DATASOURCE_URL": "jdbc:postgresql://example.invalid/bad",
                    "SPRING_FLYWAY_URL": "jdbc:postgresql://example.invalid/bad",
                    "SPRING_APPLICATION_JSON": "{\"spring.flyway.enabled\":false}",
                    "JAVA_TOOL_OPTIONS": "-Dspring.flyway.enabled=false",
                    "COMPOSE_FILE": "attacker.yaml",
                }, clear=False):
                    self.assertEqual(0, migration.main())

            self.assertEqual("jdbc:postgresql://127.0.0.1:55432/test365alm", captured["SPRING_FLYWAY_URL"])
            self.assertEqual("classpath:/application.properties", captured["SPRING_CONFIG_LOCATION"])
            for key in ("SPRING_DATASOURCE_URL", "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "COMPOSE_FILE"):
                self.assertNotIn(key, captured)


if __name__ == "__main__":
    unittest.main()
