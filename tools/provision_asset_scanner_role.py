"""Explicit scanner login provisioning; never run automatically during application startup.

Use PGHOST, PGPORT, PGDATABASE, PGUSER and an authorized operator's normal
PGPASSWORD/PGPASSFILE/TLS configuration. Supply the new scanner identity through
TEST365ALM_ASSETS_SCANNER_DB_USERNAME and TEST365ALM_ASSETS_SCANNER_DB_PASSWORD.
No secrets are command-line arguments or written to files. SQL receives only a
client-side SCRAM verifier (also a secret). Session-only logging guards must succeed
before it is sent; preloaded extension hooks are refused. Existing restricted logins
are not password-rotated. No server-wide logging configuration is changed.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

SQL_FILE = Path(__file__).with_suffix(".sql")
_CONNECTION = ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGPASSFILE",
               "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY", "PGCHANNELBINDING")
_RUNTIME = ("PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TMP", "TEMP", "TMPDIR", "LANG", "LC_ALL")
_DOCKER = ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")
_SQLSTATES = frozenset({"08001", "08006", "28P01", "28000", "42501", "42601", "42703", "42P01", "42883", "22023", "23505", "P0001", "57014", "55P03"})
_STAGES = {"SCANNER_STAGE_CONNECTED": "SESSION_GUARD", "SCANNER_STAGE_SESSION_READY": "ROLE_SQL",
           "SCANNER_STAGE_ROLE_APPLIED": "COMMIT", "SCANNER_STAGE_COMMITTED": "VERIFY"}


def safe_diagnostic(result) -> str:
    # Never print subprocess output. Both tokens come from closed allowlists,
    # including when an external process sends malicious or credential-bearing text.
    phase = "PROCESS"
    for line in (result.stdout or "").splitlines():
        if line in _STAGES:
            phase = _STAGES[line]
    matches = re.findall(r"(?:ERROR|FATAL):\s+([A-Z0-9]{5})(?:\s|$)", result.stderr or "")
    state = next((value for value in matches if value in _SQLSTATES), "UNKNOWN")
    return f"SCANNER_PROVISION_DIAGNOSTIC phase={phase} sqlstate={state}"


def scram_verifier(password: str, salt: bytes | None = None) -> str:
    # ASCII-only generated/operator secrets avoid an ambiguous SASLprep fallback.
    if not isinstance(password, str) or not re.fullmatch(r"[!-~]{24,256}", password):
        raise ValueError("Scanner password must contain 24..256 printable ASCII characters without spaces")
    salt = secrets.token_bytes(16) if salt is None else salt
    salted = hashlib.pbkdf2_hmac("sha256", password.encode("ascii"), salt, 4096)
    stored = hashlib.sha256(hmac.new(salted, b"Client Key", hashlib.sha256).digest()).digest()
    server = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    enc = lambda value: base64.b64encode(value).decode("ascii")
    return f"SCRAM-SHA-256$4096:{enc(salt)}${enc(stored)}:{enc(server)}"


def build_sql(login: str, password: str) -> str:
    if not re.fullmatch(r"[a-z][a-z0-9_]{7,62}", login) or login.startswith("pg_") or login in {"test365alm_runtime", "test365alm_scanner"}:
        raise ValueError("A dedicated scanner child login is required")
    verifier = scram_verifier(password)
    # Both substitutions have closed alphabets; SQL role creation uses format(%I/%L).
    return ("\\echo SCANNER_STAGE_CONNECTED\nBEGIN;\nSET LOCAL search_path = pg_catalog;\nSET LOCAL statement_timeout = '15s';\n"
            "SET LOCAL lock_timeout = '5s';\n"
            "SET LOCAL log_statement = 'none';\n"
            "SET LOCAL log_min_error_statement = 'panic';\n"
            "SET LOCAL log_min_messages = 'panic';\n"
            "SET LOCAL log_min_duration_statement = -1;\n"
            "SET LOCAL log_min_duration_sample = -1;\n"
            "SET LOCAL log_transaction_sample_rate = 0;\n"
            "SET LOCAL log_parameter_max_length = 0;\n"
            "SET LOCAL log_parameter_max_length_on_error = 0;\n"
            "SET LOCAL log_error_verbosity = 'terse';\n"
            "DO $private_session$ BEGIN\n"
            " IF current_user <> session_user OR EXISTS (SELECT 1 FROM pg_catalog.pg_settings\n"
            " WHERE name IN ('shared_preload_libraries','session_preload_libraries','local_preload_libraries')\n"
            " AND setting <> '') THEN\n"
            " RAISE EXCEPTION 'Provisioning needs an unmodified authorized session without extension hooks';\n"
            " END IF; END $private_session$;\n"
            "\\echo SCANNER_STAGE_SESSION_READY\n"
            f"SELECT pg_catalog.set_config('test365alm.provision_scanner_login','{login}',true);\n"
            f"SELECT pg_catalog.set_config('test365alm.provision_scanner_verifier','{verifier}',true);\n"
            + SQL_FILE.read_text(encoding="utf-8").strip() + "\n\\echo SCANNER_STAGE_ROLE_APPLIED\nCOMMIT;\n\\echo SCANNER_STAGE_COMMITTED\n")


def main(environment=None, run=subprocess.run) -> int:
    source = os.environ if environment is None else environment
    provision_committed = False
    try:
        login = source.get("TEST365ALM_ASSETS_SCANNER_DB_USERNAME", "")
        password = source.get("TEST365ALM_ASSETS_SCANNER_DB_PASSWORD", "")
        if not source.get("PGHOST") or not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_-]{0,62}", source.get("PGDATABASE", "")) or not source.get("PGUSER"):
            raise ValueError("Explicit PGHOST, PGDATABASE and PGUSER are required")
        port = int(source.get("PGPORT", "5432"))
        if not 1 <= port <= 65535:
            raise ValueError("Invalid PostgreSQL port")
        payload = build_sql(login, password)
        env = {key: source[key] for key in (*_RUNTIME, *_CONNECTION) if key in source}
        env.update(PGPORT=str(port), PGCONNECT_TIMEOUT="5", PGAPPNAME="test365alm-scanner-provision")
        command = ["psql", "-X", "-w", "-q", "-t", "-A", "--set=ON_ERROR_STOP=1", "--set=ECHO=none", "--set=VERBOSITY=sqlstate"]
        container = source.get("TEST365ALM_PROVISION_CONTAINER_ID")
        if container:
            if not re.fullmatch(r"[a-f0-9]{64}", container) or env["PGHOST"] != "127.0.0.1" or port != 5432:
                raise ValueError("Container mode requires an explicit full container ID and its local PostgreSQL endpoint")
            env.update({key: source[key] for key in _DOCKER if key in source})
            # Docker reads each value from its own environment; credentials never appear in argv.
            # The operator/test harness is responsible for selecting an authorized owned resource.
            forward = [value for key in (*_CONNECTION, "PGCONNECT_TIMEOUT", "PGAPPNAME") for value in ("--env", key)]
            # libpq distinguishes a missing PGSERVICE from an empty service name.
            # Unset inherited values inside the container; KEY= is not equivalent.
            clear = [value for key in ("PGOPTIONS", "PGSERVICE", "PGSERVICEFILE", "PGHOSTADDR", "PSQLRC") for value in ("-u", key)]
            command = ["docker", "exec", "-i", *forward, container, "env", *clear, *command]
        result = run(command, input=payload, env=env, capture_output=True, text=True, timeout=30, check=False)
        if result.returncode:
            print(safe_diagnostic(result), file=sys.stderr)
            print("Scanner provisioning refused or failed; no raw SQL/credentials are emitted.", file=sys.stderr)
            return 1
        provision_committed = True
        verify_env = dict(env, PGUSER=login, PGPASSWORD=password)
        verify_env.pop("PGPASSFILE", None)
        verified = run(command, input="SELECT current_user;\n", env=verify_env, capture_output=True, text=True, timeout=15, check=False)
        if verified.returncode or verified.stdout.strip() != login:
            print("Scanner login verification failed; role creation may already be committed. No existing secret was rotated; inspect the authorized database before retrying.", file=sys.stderr)
            return 1
        print("Scanner restricted login provisioned/verified. Existing capability login settings were preserved.")
        return 0
    except (ValueError, OSError, subprocess.SubprocessError):
        if provision_committed:
            print("Scanner verification process failed; role creation may already be committed. Inspect the authorized database before retrying; no secret was rotated.", file=sys.stderr)
        else:
            print("Scanner provisioning configuration or process failed; secret values are not emitted.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
