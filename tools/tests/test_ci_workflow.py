from pathlib import Path
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

from tools import cleanup_r02_resources


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
CLEANUP = ROOT / "tools" / "cleanup_r02_resources.py"


class CiWorkflowTests(unittest.TestCase):
    def test_ci_uses_owned_cleanup_and_never_project_down_volumes(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("down --volumes", workflow)
        self.assertGreaterEqual(workflow.count("cleanup_r02_resources.py"), 2)
        self.assertIn("exit \"$status\"", workflow)

    def test_checkout_evidence_names_merge_ref_and_both_shas(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("merge_ref=", workflow)
        self.assertIn("github_sha=", workflow)
        self.assertIn("checkout_sha=", workflow)

    def test_cleanup_command_requires_ownership_labels(self):
        cleanup = CLEANUP.read_text(encoding="utf-8")
        self.assertIn("cleanup_manifest", cleanup)
        self.assertIn("resource-manifest.json", cleanup)
        self.assertNotIn("docker compose", cleanup)
        self.assertNotIn("down --volumes", cleanup)

    def test_ci_cleanup_returns_failure_when_owned_cleanup_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "resource-manifest.json"
            manifest.write_text(json.dumps({
                "project": "owned-project", "run_id": "run-1", "docker_context": "local",
                "docker_engine": "engine", "containers": ["c"], "networks": ["n"], "volumes": ["v"],
            }), encoding="utf-8")
            with patch.object(sys, "argv", ["cleanup_r02_resources.py", "--root", directory]), \
                    patch.object(cleanup_r02_resources, "cleanup_manifest", return_value=False):
                self.assertEqual(1, cleanup_r02_resources.main())


if __name__ == "__main__":
    unittest.main()
