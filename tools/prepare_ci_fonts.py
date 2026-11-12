"""Install verified upstream Noto SC fonts for disposable Linux browser runners.

No cache or package mirror fallback. All three downloads must verify before any
font is installed. Only a dedicated user-font directory is written.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

COMMIT = "523d033d6cb47f4a80c58a35753646f5c3608a78"  # official Sans2.004
BASE = f"https://raw.githubusercontent.com/notofonts/noto-cjk/{COMMIT}/"
FILES = {
    "NotoSansCJKsc-Regular.otf": ("Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf",
        "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b"),
    "NotoSansCJKsc-Bold.otf": ("Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Bold.otf",
        "b5f0d1a190a7f9b43c310a8850630af12553df32c4c050543f9059732d9b4c0a"),
    "LICENSE": ("LICENSE", "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2"),
}
PROBE = "合成演示执行记录测试目的前置条件步骤预期实际结果附件版本偏差复测关闭重新打开模拟签署简体中文粗体末页"


def checked_download(name: str) -> bytes:
    relative, digest = FILES[name]
    with urllib.request.urlopen(BASE + relative, timeout=60) as response:
        data = response.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Upstream content checksum mismatch: {name}")
    return data


def covers(charset: str, text: str) -> bool:
    ranges = [tuple(int(n, 16) for n in part.split("-")) for part in charset.split()]
    return all(any(r[0] <= ord(c) <= r[-1] for r in ranges) for c in text)


def checked_face(path: Path, style: str) -> dict:
    raw = subprocess.check_output(
        ["fc-query", "--format=%{family}\n%{style}\n%{charset}", str(path)], text=True)
    family, actual_style, charset = raw.split("\n", 2)
    if family != "Noto Sans CJK SC" or actual_style != style or not covers(charset, PROBE):
        raise ValueError(f"Font family/style/Chinese coverage mismatch: {path.name}")
    return {"file": str(path.resolve()), "family": family, "style": style,
            "chineseCoverage": True, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def install(destination: Path, evidence: Path) -> dict:
    for command in ("fc-query", "fc-cache", "fc-match"):
        if not shutil.which(command):
            raise ValueError(f"Required fontconfig tool missing: {command}")
    with tempfile.TemporaryDirectory(prefix="test365alm-fonts-") as temporary:
        staged = Path(temporary)
        for name in FILES:
            (staged / name).write_bytes(checked_download(name))
        for style in ("Regular", "Bold"):
            checked_face(staged / f"NotoSansCJKsc-{style}.otf", style)
        destination.mkdir(parents=True, exist_ok=True)
        for name in FILES:
            shutil.copyfile(staged / name, destination / name)
    subprocess.run(["fc-cache", "-f", str(destination)], check=True)
    checks = []
    for style in ("Regular", "Bold"):
        path = destination / f"NotoSansCJKsc-{style}.otf"
        check = checked_face(path, style)
        matched = subprocess.check_output(
            ["fc-match", "-f", "%{file}", f"Noto Sans CJK SC:style={style}:lang=zh-cn"], text=True)
        if Path(matched).resolve() != path.resolve():
            raise ValueError(f"Fontconfig selected another file for {style}")
        check["matchedFile"] = matched
        checks.append(check)
    evidence.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(destination / "LICENSE", evidence / "Noto-OFL.txt")
    return {"status": "READY", "upstream": "https://github.com/notofonts/noto-cjk",
            "version": "Sans2.004", "commit": COMMIT, "license": "SIL OFL 1.1",
            "sources": {n: {"url": BASE + v[0], "sha256": v[1]} for n, v in FILES.items()},
            "probe": PROBE, "faces": checks}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    try:
        result = install(Path.home() / ".local/share/fonts/test365alm-ci", args.evidence)
        status = 0
    except Exception as error:
        # No response bodies, inherited environment or arbitrary exception messages.
        result = {"status": "BLOCKED", "browserTests": "NOT_RUN", "errorType": type(error).__name__}
        status = 1
    (args.evidence / "fonts.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
