#!/usr/bin/env python3
"""Write an allowlisted diagnostic file without disposable R03 credentials."""
from __future__ import annotations

import re
import sys
from pathlib import Path


def redact(env_text: str, log_text: str) -> str:
    secrets = []
    for line in env_text.splitlines():
        if "=" not in line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        if ("PASSWORD" in key or "SECRET" in key or "TOKEN" in key) and value:
            secrets.append(value)
    for secret in sorted(set(secrets), key=len, reverse=True):
        log_text = log_text.replace(secret, "[REDACTED]")
    log_text = re.sub(r"(?i)(authorization:\s*bearer\s+)\S+", r"\1[REDACTED]", log_text)
    return log_text


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: redact_r03_logs.py ENV INPUT OUTPUT", file=sys.stderr)
        return 2
    env_file, input_file, output_file = (Path(value) for value in sys.argv[1:])
    output_file.write_text(redact(env_file.read_text(encoding="utf-8"),
                                  input_file.read_text(encoding="utf-8", errors="replace")),
                           encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
