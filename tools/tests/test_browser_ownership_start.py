"""Offline pre-business ownership capture, never creates or inspects real resources."""
import copy
import unittest
from unittest.mock import patch
from tools import local_browser_runner as runner
from tools.r02_resource_guard import ResourceManifest, ResourceOwnershipError


class BrowserOwnershipStartTests(unittest.TestCase):
    def fixture(self):
        run, source, project = "r03-local-" + "a" * 32, "b" * 40, "owned-project"
        labels = {runner.RUN_LABEL: run, runner.SOURCE_LABEL: source, "com.docker.compose.project": project}
        containers = [str(i) * 64 for i in range(1, 5)]
        network = "5" * 64
        volumes = ["owned-pg", "owned-assets", "owned-signatures"]
        actual = {}
        for identity in containers:
            actual["container", identity[:12]] = {"Id": identity, "Created": "created", "Image": "sha256:fixture", "Config": {"Labels": dict(labels), "Env": ["SECRET=must-not-export"]}}
        actual["network", network[:12]] = {"Id": network, "Created": "network-created", "Name": "owned-network", "Driver": "bridge", "Labels": dict(labels), "Options": {"secret": "must-not-export"}}
        for name in volumes:
            actual["volume", name] = {"Name": name, "CreatedAt": "volume-created", "Driver": "local", "Labels": dict(labels), "Options": {"secret": "must-not-export"}}
        resources = ResourceManifest(project, run, "desktop-linux", tuple(i[:12] for i in containers), (network[:12],), tuple(volumes), "engine", runner.RUN_LABEL)
        return resources, source, actual

    def capture(self, resources, source, actual, valid=True):
        with patch.object(runner, "verify_docker_engine"), patch.object(runner, "verify_manifest", return_value=valid), \
                patch.object(runner, "_inspect", side_effect=lambda kind, identity, env: actual[kind, identity]):
            return runner.ownership_start(resources, source, {})

    def test_captures_full_container_and_network_identity_and_exact_volume_names_without_secrets(self):
        resources, source, actual = self.fixture()
        result = self.capture(resources, source, actual)
        self.assertEqual(source, result["source"])
        self.assertEqual(resources.run_id, result["run"])
        self.assertEqual(4, len(result["containers"]))
        for item in result["containers"]:
            self.assertEqual(64, len(item["id"]))
            self.assertEqual("created", item["created"])
            self.assertEqual("sha256:fixture", item["image"])
            self.assertEqual(resources.project, item["labels"]["com.docker.compose.project"])
        self.assertEqual("5" * 64, result["networks"][0]["id"])
        self.assertEqual("network-created", result["networks"][0]["created"])
        self.assertNotIn("image", result["networks"][0])
        self.assertEqual(set(resources.volumes), {item["name"] for item in result["volumes"]})
        self.assertNotIn("must-not-export", str(result))

    def test_any_foreign_scope_or_incomplete_or_replaced_full_identity_refuses_capture(self):
        resources, source, actual = self.fixture()
        for key in actual:
            for label in (runner.RUN_LABEL, runner.SOURCE_LABEL, "com.docker.compose.project"):
                bad = copy.deepcopy(actual)
                labels = bad[key]["Config"]["Labels"] if key[0] == "container" else bad[key]["Labels"]
                labels[label] = "foreign"
                with self.subTest(resource=key, label=label), self.assertRaises(ResourceOwnershipError):
                    self.capture(resources, source, bad)
            if key[0] != "volume":
                for identity in (key[1], "f" * 64, None):
                    bad = copy.deepcopy(actual); bad[key]["Id"] = identity
                    with self.subTest(resource=key, identity=identity), self.assertRaises(ResourceOwnershipError):
                        self.capture(resources, source, bad)
                for field in (["Created", "Image"] if key[0] == "container" else ["Created"]):
                    bad = copy.deepcopy(actual); del bad[key][field]
                    with self.subTest(resource=key, missing=field), self.assertRaises(ResourceOwnershipError):
                        self.capture(resources, source, bad)
            else:
                bad = copy.deepcopy(actual); bad[key]["Name"] = "foreign-volume"
                with self.assertRaises(ResourceOwnershipError):
                    self.capture(resources, source, bad)
        with self.assertRaises(ResourceOwnershipError):
            self.capture(resources, source, actual, valid=False)

    def test_capture_is_written_before_start_or_any_business_process(self):
        from pathlib import Path
        source = Path(runner.__file__).read_text(encoding="utf-8")
        capture = source.index('(evidence / "ownership-start.json").write_text(')
        self.assertLess(capture, source.index('compose("start")'))
        self.assertLess(capture, source.index('"server-build"'))


if __name__ == "__main__":
    unittest.main()
