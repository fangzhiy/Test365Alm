from pathlib import Path
import io
import json
import tempfile
import unittest
from unittest.mock import patch

from tools import prepare_ci_fonts as fonts


class CiFontsTests(unittest.TestCase):
    def test_download_rejects_wrong_content(self):
        with patch.object(fonts.urllib.request, "urlopen", return_value=io.BytesIO(b"not a font")):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                fonts.checked_download("NotoSansCJKsc-Regular.otf")

    def test_download_failure_never_creates_install_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "fonts"
            with patch.object(fonts.shutil, "which", return_value="present"), \
                    patch.object(fonts, "checked_download", side_effect=OSError("offline")):
                with self.assertRaises(OSError):
                    fonts.install(target, Path(temporary) / "evidence")
            self.assertFalse(target.exists())

    def test_second_invalid_download_does_not_install_first_valid_face(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "fonts"
            with patch.object(fonts.shutil, "which", return_value="present"), \
                    patch.object(fonts, "checked_download", side_effect=[b"verified", ValueError("hash")]):
                with self.assertRaises(ValueError):
                    fonts.install(target, Path(temporary) / "evidence")
            self.assertFalse(target.exists())

    def test_fontconfig_missing_is_not_success(self):
        with patch.object(fonts.shutil, "which", return_value=None):
            with self.assertRaisesRegex(ValueError, "tool missing"):
                fonts.install(Path("unused"), Path("unused"))

    def test_chinese_missing_or_wrong_weight_is_rejected(self):
        for metadata in ("Noto Sans CJK SC\nRegular\n20-7e", "Noto Sans CJK SC\nBold\n0-ffff",
                         "Other Font\nRegular\n0-ffff"):
            with self.subTest(metadata=metadata), \
                    patch.object(fonts.subprocess, "check_output", return_value=metadata):
                with self.assertRaisesRegex(ValueError, "coverage mismatch"):
                    fonts.checked_face(Path("regular.otf"), "Regular")

    def test_fallback_match_is_rejected_after_content_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(fonts.shutil, "which", return_value="present"), \
                    patch.object(fonts, "checked_download", return_value=b"verified"), \
                    patch.object(fonts, "checked_face", return_value={}), \
                    patch.object(fonts.subprocess, "run"), \
                    patch.object(fonts.subprocess, "check_output", return_value="/other/fallback.otf"):
                with self.assertRaisesRegex(ValueError, "another file"):
                    fonts.install(Path(temporary) / "fonts", Path(temporary) / "evidence")

    def test_valid_faces_and_license_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            target, evidence = Path(temporary) / "fonts", Path(temporary) / "evidence"
            with patch.object(fonts.shutil, "which", return_value="present"), \
                    patch.object(fonts, "checked_download", return_value=b"verified"), \
                    patch.object(fonts, "checked_face", return_value={}), \
                    patch.object(fonts.subprocess, "run"), \
                    patch.object(fonts.subprocess, "check_output", side_effect=[
                        str(target / f"NotoSansCJKsc-{s}.otf") for s in ("Regular", "Bold")]):
                result = fonts.install(target, evidence)
            self.assertEqual("READY", result["status"])
            self.assertEqual(2, len(result["faces"]))
            self.assertEqual(b"verified", (evidence / "Noto-OFL.txt").read_bytes())

    def test_failure_writes_blocked_not_run_evidence_and_returns_nonzero(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch("sys.argv", ["fonts", "--evidence", temporary]), \
                patch.object(fonts, "install", side_effect=OSError("private response body")), \
                patch("builtins.print"):
            self.assertEqual(1, fonts.main())
            result = json.loads((Path(temporary) / "fonts.json").read_text())
            self.assertEqual("BLOCKED", result["status"])
            self.assertEqual("NOT_RUN", result["browserTests"])
            self.assertNotIn("private", json.dumps(result))
