import unittest
from unittest.mock import patch
from tools.r02_resource_guard import ResourceManifest, verify_manifest


class BrowserResourceGuardTests(unittest.TestCase):
    def test_every_recorded_container_must_match_not_just_last(self):
        manifest = ResourceManifest("project", "run", "local", ("foreign-first", "owned-last"),
                                    ("network",), ("volume",), "engine", "test365alm.r03.run")
        def inspect(kind, identity, environment):
            labels = {"com.docker.compose.project": "project", "test365alm.r03.run": "run"}
            if identity == "foreign-first":
                labels["test365alm.r03.run"] = "foreign"
            return {"Config": {"Labels": labels}} if kind == "container" else {"Labels": labels}
        with patch("tools.r02_resource_guard.docker_context", return_value="local"), \
                patch("tools.r02_resource_guard.docker_engine", return_value="engine"), \
                patch("tools.r02_resource_guard._inspect", side_effect=inspect):
            self.assertFalse(verify_manifest(manifest, {}))


if __name__ == "__main__":
    unittest.main()
