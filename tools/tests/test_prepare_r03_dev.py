import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import prepare_r03_dev


class PrepareR03DevTests(unittest.TestCase):
    def test_windows_import_explicitly_allows_generated_asset_application_keys(self):
        script = (Path(prepare_r03_dev.__file__).resolve().parent / "import_r03_env.ps1").read_text(encoding="utf-8")
        match = re.search(r"\$allowed\s*=\s*@\((.*?)\)", script, re.S)
        self.assertIsNotNone(match)
        allowed = set(re.findall(r"'([A-Z0-9_]+)'", match.group(1)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / ".env.r03"
            realm = root / "realm.json"
            with patch.object(prepare_r03_dev, "ENV", env), patch.object(prepare_r03_dev, "REALM", realm):
                self.assertEqual(0, prepare_r03_dev.main())
                generated = {line.split("=", 1)[0] for line in env.read_text(encoding="utf-8").splitlines()}
        asset_keys = {key for key in generated if key.startswith("TEST365ALM_ASSETS_")}
        self.assertEqual(10, len(asset_keys))
        self.assertEqual(asset_keys, {key for key in allowed if key.startswith("TEST365ALM_ASSETS_")})
        self.assertTrue({"SPRING_CONFIG_LOCATION", "SPRING_APPLICATION_JSON", "COMPOSE_FILE",
                         "JAVA_TOOL_OPTIONS", "TEST365ALM_ASSETS_UNEXPECTED"}.isdisjoint(allowed))
        self.assertIn("$allowed -contains $Matches[1]", script)

    def test_creates_isolated_config_and_never_overwrites_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / ".env.r03"
            realm = root / "local-evidence" / "r03" / "realm.json"
            with patch.object(prepare_r03_dev, "ENV", env), patch.object(prepare_r03_dev, "REALM", realm):
                self.assertEqual(0, prepare_r03_dev.main())
                before = env.read_bytes()
                self.assertIn(b"SPRING_FLYWAY_USER=test365alm_migrator", before)
                self.assertIn(b"TEST365ALM_DATASOURCE_USERNAME=test365alm_runtime", before)
                data = json.loads(realm.read_text(encoding="utf-8"))
                self.assertEqual("S256", data["clients"][0]["attributes"]["pkce.code.challenge.method"])
                self.assertEqual(["http://127.0.0.1:5173/login/oauth2/code/test365alm"],
                                 data["clients"][0]["redirectUris"])
                self.assertEqual({"r03-user", "r03-member", "r03-viewer"},
                                 {user["username"] for user in data["users"]})
                self.assertIn(b"R03_VIEWER_PASSWORD=", before)
                self.assertIn(b"R03_MEMBER_PASSWORD=", before)
                self.assertIn(b"TEST365ALM_ASSETS_ENABLED=true", before)
                self.assertIn(b"TEST365ALM_ASSETS_S3_SECRET_KEY=", before)
                self.assertIn(b"TEST365ALM_ASSETS_S3_QUARANTINE_BUCKET=r11-quarantine", before)
                self.assertEqual(2, prepare_r03_dev.main())
                self.assertEqual(before, env.read_bytes())


if __name__ == "__main__":
    unittest.main()
