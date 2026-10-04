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

    def test_migration_job_records_formal_v11_before_intentional_v12_failure(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Verify formal V12 then intentional V13 migration startup failure", workflow)
        self.assertIn("Formal V12 migration and intentional V13 failure were detected", workflow)
        self.assertIn("verify_r02_migration_failure.py", workflow)

    def test_server_job_requires_project_access_rls_evidence(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Require project access and RLS integration report", workflow)
        self.assertIn("verify_r03_project_access_report.py", workflow)
        self.assertIn("apps/server/project-access-report.txt", workflow)

    def test_server_job_requires_runtime_oidc_http_evidence(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Require real HTTP OIDC callback integration report", workflow)
        self.assertIn("verify_r03_oidc_http_report.py", workflow)
        self.assertIn("apps/server/oidc-http-report.txt", workflow)

    def test_server_job_requires_requirement_integration_evidence(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("verify_r04_requirement_report.py", workflow)
        self.assertIn("apps/server/requirement-report.txt", workflow)

    def test_server_job_requires_manual_test_case_integration_evidence(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("verify_r05_test_case_report.py", workflow)
        self.assertIn("apps/server/test-case-report.txt", workflow)

    def test_browser_job_requires_dual_user_project_access_evidence(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Require real dual-user project access browser report", workflow)
        self.assertIn("verify_r03_project_browser_report.py", workflow)
        self.assertIn("local-evidence/r03/project-browser-report.txt", workflow)

    def test_browser_cleanup_uses_run_owned_manifest_not_compose_down(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("tools/r03_resource_guard.py preflight", workflow)
        self.assertIn("tools/r03_resource_guard.py capture", workflow)
        self.assertIn("tools/cleanup_r02_resources.py --root local-evidence/r03", workflow)
        self.assertNotIn('docker compose --env-file .env.r03 -f compose.r03.yaml -p "$project" down', workflow)

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
