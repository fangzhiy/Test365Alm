import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.r02_resource_guard import ResourceOwnershipError
from tools.r03_resource_guard import (
    R03_RUN_LABEL,
    assert_project_available,
    capture_manifest,
)


class R03ResourceGuardTests(unittest.TestCase):
    def test_preflight_rejects_existing_resources_before_up(self):
        with patch("tools.r03_resource_guard._assert_project_available",
                   side_effect=ResourceOwnershipError("existing resource")) as preflight:
            with self.assertRaises(ResourceOwnershipError):
                assert_project_available("test365alm-r03-collision", {})
            preflight.assert_called_once()

    def test_capture_requires_the_r03_run_label_on_every_resource(self):
        with patch("tools.r03_resource_guard._capture_manifest") as capture:
            capture.return_value = object()
            result = capture_manifest("p", "r", "default", {})
            self.assertIsNotNone(result)
            self.assertEqual(R03_RUN_LABEL, capture.call_args.args[4])

    def test_manifest_json_keeps_run_label_identity(self):
        # This is a small contract check for the JSON consumed by the shared
        # cleanup path; no Docker daemon is touched by the unit test.
        manifest = {
            "project": "p", "run_id": "r", "docker_context": "default",
            "docker_engine": "engine", "containers": [], "networks": [],
            "volumes": [], "run_label_key": R03_RUN_LABEL,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "resource-manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(R03_RUN_LABEL, json.loads(path.read_text(encoding="utf-8"))["run_label_key"])


if __name__ == "__main__":
    unittest.main()
