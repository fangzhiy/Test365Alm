import base64
import contextlib
import hashlib
import hmac
import io
import subprocess
import unittest
from unittest.mock import Mock

from tools import provision_asset_scanner_role as provision


class ScannerProvisionTests(unittest.TestCase):
    def environment(self):
        return {"PGHOST": "127.0.0.1", "PGPORT": "15432", "PGDATABASE": "owned_fixture",
                "PGUSER": "authorized_operator", "PGPASSWORD": "synthetic-owner-secret",
                "TEST365ALM_ASSETS_SCANNER_DB_USERNAME": "scanner_owned_fixture",
                "TEST365ALM_ASSETS_SCANNER_DB_PASSWORD": "synthetic-scanner-secret-only"}

    def test_missing_or_unsafe_configuration_never_starts_psql(self):
        for key, value in [("PGHOST", ""), ("PGDATABASE", "postgresql://elsewhere/db"),
                           ("PGPORT", "0"), ("TEST365ALM_ASSETS_SCANNER_DB_USERNAME", "test365alm_runtime"),
                           ("TEST365ALM_ASSETS_SCANNER_DB_USERNAME", "evil'; DROP ROLE x;--"),
                           ("TEST365ALM_ASSETS_SCANNER_DB_PASSWORD", "short")]:
            with self.subTest(key=key, value=value):
                env = self.environment(); env[key] = value; run = Mock()
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertNotEqual(0, provision.main(env, run))
                run.assert_not_called()

    def test_password_becomes_scram_verifier_not_plaintext_sql(self):
        password = self.environment()["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"]
        verifier = provision.scram_verifier(password, b"0123456789abcdef")
        algorithm, parameters, keys = verifier.split("$")
        self.assertEqual("SCRAM-SHA-256", algorithm)
        rounds, salt = parameters.split(":")
        salted = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(rounds))
        client = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
        stored, server = keys.split(":")
        self.assertEqual(hashlib.sha256(client).digest(), base64.b64decode(stored))
        self.assertEqual(hmac.new(salted, b"Server Key", hashlib.sha256).digest(), base64.b64decode(server))
        sql = provision.build_sql("scanner_owned_fixture", password)
        self.assertNotIn(password, sql)
        self.assertIn(provision.SQL_FILE.read_text(encoding="utf-8").strip(), sql)
        self.assertIn("BEGIN;", sql); self.assertIn("COMMIT;", sql)

    def test_secret_statement_follows_session_logging_and_identity_guards(self):
        sql = provision.build_sql("scanner_owned_fixture", self.environment()["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"])
        secret_offset = sql.index("SELECT pg_catalog.set_config('test365alm.provision_scanner_verifier'")
        for guard in ("SET LOCAL log_statement = 'none'", "SET LOCAL log_min_error_statement = 'panic'",
                      "SET LOCAL log_min_duration_statement = -1", "SET LOCAL log_min_duration_sample = -1",
                      "SET LOCAL log_transaction_sample_rate = 0", "SET LOCAL log_parameter_max_length = 0",
                      "SET LOCAL log_parameter_max_length_on_error = 0", "SET LOCAL log_error_verbosity = 'terse'",
                      "shared_preload_libraries", "session_preload_libraries", "local_preload_libraries",
                      "current_user <> session_user"):
            self.assertGreaterEqual(sql.find(guard), 0, guard)
            self.assertLess(sql.index(guard), secret_offset, guard)
        self.assertNotIn("ALTER SYSTEM", sql)

    def test_success_uses_stdin_and_private_environment_then_verifies_scanner_login(self):
        env = self.environment(); env.update({"PGOPTIONS": "-c role=untrusted", "PSQLRC": "unsafe", "JAVA_TOOL_OPTIONS": "unsafe"})
        env["PGPASSFILE"]="operator-only-file"
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, "hidden", ""),
                                subprocess.CompletedProcess([], 0, "scanner_owned_fixture\n", "")])
        output = io.StringIO()
        with contextlib.redirect_stdout(output): self.assertEqual(0, provision.main(env, run))
        self.assertEqual(2, run.call_count)
        first, second = run.call_args_list
        self.assertIn("-X", first.args[0]); self.assertIn("-w", first.args[0])
        self.assertEqual(30, first.kwargs["timeout"])
        self.assertNotIn("PGOPTIONS", first.kwargs["env"])
        self.assertNotIn("PSQLRC", first.kwargs["env"])
        self.assertNotIn("JAVA_TOOL_OPTIONS", first.kwargs["env"])
        self.assertNotIn(env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"], str(first.args))
        self.assertNotIn(env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"], first.kwargs["input"])
        self.assertEqual("scanner_owned_fixture", second.kwargs["env"]["PGUSER"])
        self.assertEqual(env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"], second.kwargs["env"]["PGPASSWORD"])
        self.assertNotIn("PGPASSFILE", second.kwargs["env"])
        for secret in (env["PGPASSWORD"], env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"]): self.assertNotIn(secret, output.getvalue())

    def test_failure_does_not_print_server_output_or_attempt_login(self):
        env = self.environment(); secret = env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"]
        run = Mock(return_value=subprocess.CompletedProcess([], 3, secret, secret))
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(env, run))
        self.assertEqual(1, run.call_count); self.assertNotIn(secret, output.getvalue())

    def test_failure_diagnostic_is_only_known_stage_and_sqlstate_not_sql_or_verifier(self):
        secret = self.environment()["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"]
        run = Mock(return_value=subprocess.CompletedProcess([], 3,
            "SCANNER_STAGE_SESSION_READY\nSCRAM-SHA-256$private-verifier\n"+secret,
            "ERROR: 42601\nSQL: private SQL and "+secret))
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(self.environment(), run))
        self.assertIn("SCANNER_PROVISION_DIAGNOSTIC phase=ROLE_SQL sqlstate=42601", output.getvalue())
        self.assertNotIn(secret, output.getvalue()); self.assertNotIn("private", output.getvalue()); self.assertNotIn("SCRAM", output.getvalue())
        self.assertIn("--set=VERBOSITY=sqlstate", run.call_args.args[0])

    def test_unrecognized_diagnostic_text_cannot_be_echoed_as_a_phase_or_error_code(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 3, "SCANNER_STAGE_secret-value", "ERROR: ABCDE-secret-value"))
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(self.environment(), run))
        self.assertIn("SCANNER_PROVISION_DIAGNOSTIC phase=PROCESS sqlstate=UNKNOWN", output.getvalue())
        self.assertNotIn("secret-value", output.getvalue()); self.assertNotIn("ABCDE", output.getvalue())

    def test_timeout_is_safe_failure_not_a_success(self):
        env = self.environment(); secret = env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"]
        run = Mock(side_effect=subprocess.TimeoutExpired([secret], 30, output=secret, stderr=secret))
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(env, run))
        self.assertNotIn(secret, output.getvalue())

    def test_existing_login_wrong_secret_is_not_reported_as_success(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, "", ""), subprocess.CompletedProcess([], 2, "", "private failure")])
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(self.environment(), run))
        self.assertNotIn("private failure", output.getvalue())
        self.assertIn("may already be committed", output.getvalue())

    def test_login_verification_timeout_reports_possible_partial_completion(self):
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, "", ""), subprocess.TimeoutExpired(["psql"], 15)])
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output): self.assertNotEqual(0, provision.main(self.environment(), run))
        self.assertIn("may already be committed", output.getvalue())

    def test_explicit_owned_container_mode_forwards_names_not_secrets(self):
        env = self.environment(); env.update(PGPORT="5432", TEST365ALM_PROVISION_CONTAINER_ID="a" * 64)
        run = Mock(side_effect=[subprocess.CompletedProcess([], 0, "", ""), subprocess.CompletedProcess([], 0, "scanner_owned_fixture\n", "")])
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(0, provision.main(env, run))
        for call in run.call_args_list:
            self.assertEqual(["docker", "exec", "-i"], call.args[0][:3])
            self.assertIn("PGPASSWORD", call.args[0]); self.assertIn("a" * 64, call.args[0])
            container_index=call.args[0].index("a" * 64)
            isolated=call.args[0][container_index+1:]
            self.assertEqual("env",isolated[0])
            for variable in ("PGOPTIONS", "PGSERVICE", "PGSERVICEFILE", "PGHOSTADDR", "PSQLRC"):
                index=isolated.index(variable)
                self.assertEqual("-u",isolated[index-1])
                self.assertNotIn(variable+"=",call.args[0],"libpq treats empty PGSERVICE as a real service, not unset")
            self.assertNotIn(env["PGPASSWORD"], str(call.args[0]))
            self.assertNotIn(env["TEST365ALM_ASSETS_SCANNER_DB_PASSWORD"], str(call.args[0]))
        env["TEST365ALM_PROVISION_CONTAINER_ID"] = "unverified-name"
        denied = Mock()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()): self.assertNotEqual(0, provision.main(env, denied))
        denied.assert_not_called()


if __name__ == "__main__":
    unittest.main()
