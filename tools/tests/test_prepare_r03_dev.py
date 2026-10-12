import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import prepare_r03_dev


class PrepareR03DevTests(unittest.TestCase):
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
                self.assertEqual(2, prepare_r03_dev.main())
                self.assertEqual(before, env.read_bytes())


if __name__ == "__main__":
    unittest.main()
