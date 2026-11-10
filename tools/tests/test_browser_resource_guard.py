import unittest
from unittest.mock import patch
from tools.r02_resource_guard import ResourceManifest, verify_manifest
from tools.local_browser_runner import EXPECTED, capture, fresh_config, validate_results, verify
from tools.r02_resource_guard import ResourceOwnershipError, assert_project_available
from pathlib import Path
import tempfile


class BrowserResourceGuardTests(unittest.TestCase):
    def test_every_recorded_container_must_match_not_just_last(self):
        def inspect(kind, identity, environment):
            labels = {"com.docker.compose.project": "project", "test365alm.r03.run": "run"}
            if identity == "foreign-first":
                labels["test365alm.r03.run"] = "foreign"
            return {"Config": {"Labels": labels}} if kind == "container" else {"Labels": labels}
        for plural in ("containers", "networks", "volumes"):
            resources = {"containers": ("container",), "networks": ("network",), "volumes": ("volume",)}
            resources[plural] = ("foreign-first", "owned-last")
            manifest = ResourceManifest("project", "run", "local", **resources,
                                        docker_engine="engine", run_label_key="test365alm.r03.run")
            with self.subTest(kind=plural), patch("tools.r02_resource_guard.docker_context", return_value="local"), \
                    patch("tools.r02_resource_guard.docker_engine", return_value="engine"), \
                    patch("tools.r02_resource_guard._inspect", side_effect=inspect):
                self.assertFalse(verify_manifest(manifest, {}))

    def test_preexisting_project_is_refused_before_creation(self):
        with patch("tools.r02_resource_guard.docker_context", return_value="local"), \
                patch("tools.r02_resource_guard.docker_engine", return_value="engine"), \
                patch("tools.r02_resource_guard.existing_resources", return_value={"containers": ("old",)}):
            with self.assertRaises(ResourceOwnershipError):
                assert_project_available("project", {})

    def test_capture_refuses_daemon_change_and_unknown_source(self):
        with patch("tools.local_browser_runner.verify_docker_engine", side_effect=ResourceOwnershipError("changed")), \
                patch("tools.local_browser_runner.existing_resources") as listing:
            with self.assertRaises(ResourceOwnershipError):
                capture("project", "run", "local", "source", {})
            listing.assert_not_called()
        with patch("tools.local_browser_runner.verify_docker_engine"), \
                patch("tools.local_browser_runner.docker_context", return_value="local"), \
                patch("tools.local_browser_runner.existing_resources", return_value={"containers": ("foreign",), "networks": (), "volumes": ()}), \
                patch("tools.local_browser_runner._inspect", return_value={"Config": {"Labels": {"com.docker.compose.project": "project", "test365alm.r03.run": "run", "test365alm.local.source": "foreign"}}}):
            with self.assertRaises(ResourceOwnershipError):
                capture("project", "run", "local", "source", {})

    def test_replaced_identity_or_source_refuses_operations(self):
        m = ResourceManifest("project", "run", "local", ("container",), (), (), "engine", "test365alm.r03.run")
        actual = {"Id": "container", "Created": "created", "Image": "image", "Config": {"Labels": {"test365alm.local.source": "source"}}}
        for change in ({"Id": "replacement"}, {"Created": "recreated"}, {"Image": "foreign"}):
            with patch("tools.local_browser_runner.verify_manifest", return_value=True), \
                    patch("tools.local_browser_runner._inspect", return_value={**actual, **change}):
                with self.assertRaises(ResourceOwnershipError):
                    verify(m, "source", {"container": actual}, {})
        with patch("tools.local_browser_runner.verify_manifest", return_value=False), \
                patch("tools.local_browser_runner._inspect") as inspect:
            with self.assertRaises(ResourceOwnershipError):
                verify(m, "source", {"container": actual}, {})
            inspect.assert_not_called()

    def test_existing_credentials_are_preserved_not_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".env.r03"
            path.write_text("preserve", encoding="utf-8")
            with self.assertRaises(ResourceOwnershipError):
                fresh_config(root, "run", "source")
            self.assertEqual("preserve", path.read_text())

    def test_only_three_exact_passes_without_retries_satisfy_browser_acceptance(self):
        report = {"status": "passed", "workers": 1, "retries": 0,
                  "cases": [{"name": name, "status": "passed", "expected": "passed", "retry": 0} for name in EXPECTED]}
        self.assertTrue(validate_results(report))
        for status in ("skipped", "failed", "timedOut"):
            changed = {**report, "cases": [{**report["cases"][0], "status": status}, *report["cases"][1:]]}
            self.assertFalse(validate_results(changed))
        for change in ({"workers": 2}, {"retries": 1}, {"cases": []}):
            self.assertFalse(validate_results({**report, **change}))


if __name__ == "__main__":
    unittest.main()
