import unittest
from unittest.mock import patch
from tools.r02_resource_guard import ResourceManifest, verify_manifest
from tools.local_browser_runner import EXPECTED, capture, fresh_config, validate_results, verify
from tools.r02_resource_guard import ResourceOwnershipError, assert_project_available
from pathlib import Path
import tempfile
import tarfile
import json
from tools import local_browser_runner as runner


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

    def test_main_early_refusals_remove_only_new_secrets_without_touching_resources(self):
        for failure in ("daemon", "partial-config", "preexisting-env", "preexisting-realm", "preexisting-compose"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                def archive(source, destination):
                    fixture = base / "fixture"
                    (fixture / "local-evidence/r03").mkdir(parents=True)
                    existing = {"preexisting-env": ".env.r03", "preexisting-realm": "local-evidence/r03/realm.json",
                                "preexisting-compose": "local-evidence/local-browser-compose.json"}.get(failure)
                    if existing:
                        (fixture / existing).write_text("preserve")
                    with tarfile.open(destination, "w") as tar:
                        tar.add(fixture, arcname=".")
                def config(root, run, source):
                    if failure.startswith("preexisting"):
                        self.fail("Must reject existing secrets before generating configuration")
                    (root / ".env.r03").write_text("generated")
                    (root / "local-evidence/r03/realm.json").write_text("generated")
                    if failure == "partial-config":
                        raise ValueError("partial")
                    return {}
                with patch.object(runner, "ROOT", base), \
                        patch.object(runner.sys, "argv", ["runner", "--source", "a" * 40]), \
                        patch.object(runner, "committed_archive", side_effect=archive), \
                        patch.object(runner, "fresh_config", side_effect=config), \
                        patch.object(runner, "assert_project_available", side_effect=ResourceOwnershipError("refused")), \
                        patch.object(runner, "capture") as capture_mock, \
                        patch.object(runner, "cleanup_manifest") as cleanup_mock:
                    self.assertEqual(1, runner.main())
                capture_mock.assert_not_called()
                cleanup_mock.assert_not_called()
                evidence = next((base / "local-evidence/local-browser").iterdir())
                root = evidence / "repo"
                for name in (".env.r03", "local-evidence/r03/realm.json", "local-evidence/local-browser-compose.json"):
                    path = root / name
                    if path.exists():
                        self.assertTrue(failure.startswith("preexisting"))
                        self.assertEqual("preserve", path.read_text())
                    elif failure == "preexisting-env" and name == ".env.r03":
                        self.fail("Pre-existing credentials were removed")
                if failure.startswith("preexisting"):
                    self.assertEqual(1, sum((root / name).exists() for name in (
                        ".env.r03", "local-evidence/r03/realm.json", "local-evidence/local-browser-compose.json")))
                report = json.loads((evidence / "run.json").read_text())
                self.assertTrue(report["cleanup"])
                self.assertEqual(1, report["exit_code"])

    def test_only_five_exact_passes_without_retries_satisfy_browser_acceptance(self):
        report = {"status": "passed", "workers": 1, "retries": 0,
                  "cases": [{"name": name, "status": "passed", "expected": "passed", "retry": 0} for name in EXPECTED]}
        self.assertTrue(validate_results(report))
        self.assertEqual(6,len(EXPECTED))
        self.assertIn("real Keycloak UI simulated signature rejects wrong credentials binds identity and preserves signed revision",EXPECTED)
        self.assertIn("real Keycloak UI designated reviewer rejects approves and new revision requires fresh review",EXPECTED)
        for index in range(6):
            missing={**report,"cases":report["cases"][:index]+report["cases"][index+1:]}
            duplicate={**report,"cases":[*report["cases"][:index],report["cases"][(index+1)%6],*report["cases"][index+1:]]}
            replacement={**report,"cases":[*report["cases"][:index],{**report["cases"][index],"name":"unapproved"},*report["cases"][index+1:]]}
            replay={**report,"cases":[*report["cases"][:index],{**report["cases"][index],"retry":1},*report["cases"][index+1:]]}
            expected_skip={**report,"cases":[*report["cases"][:index],{**report["cases"][index],"expected":"skipped"},*report["cases"][index+1:]]}
            skipped={**report,"cases":[*report["cases"][:index],{**report["cases"][index],"status":"skipped","expected":"skipped"},*report["cases"][index+1:]]}
            for changed in (missing,duplicate,replacement,replay,expected_skip,skipped):self.assertFalse(validate_results(changed))

        for status in ("skipped", "failed", "timedOut"):
            changed = {**report, "cases": [{**report["cases"][0], "status": status}, *report["cases"][1:]]}
            self.assertFalse(validate_results(changed))
        for change in ({"workers": 2}, {"retries": 1}, {"cases": []}):
            self.assertFalse(validate_results({**report, **change}))


if __name__ == "__main__":
    unittest.main()
