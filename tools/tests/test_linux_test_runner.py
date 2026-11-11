import copy
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.linux_test_runner import Guard, Identity, RUN, SOURCE, committed_archive, main, validate, run_phases
from tools.linux_test_inside import summarize, valid_report, FOCUSED_METHODS, SIGNATURE_METHODS, UNIT_METHODS, INTEGRATION_METHODS, PHASES
from tools.r02_resource_guard import ResourceOwnershipError
from tools import linux_test_runner as runner

RUN_ID = "a" * 32
SHA = "b" * 40


def container():
    return {"Id": "c" * 64, "Created": "created", "Image": "sha256:image",
            "Config": {"Labels": {RUN: RUN_ID, SOURCE: SHA}}, "HostConfig": {}, "Mounts": [],
            "State": {"StartedAt": "first-start"}}


class LinuxRunnerGuardTests(unittest.TestCase):
    def test_fixed_inventory_preserves_every_accepted_baseline_method(self):
        # Hashes freeze all 95/202 accepted identities at 58efd478, independently of E additions.
        for methods, count, digest in (
            (UNIT_METHODS, 95, "cea2966c555db0f58143755121a4515f60f471c72e3c7adfec97554c0f4f6718"),
            (INTEGRATION_METHODS, 202, "906d9e17304a8d69f69c4803115840ee15380aea241b72c3dac42c4a0a53cfb6"),
            (FOCUSED_METHODS - SIGNATURE_METHODS, 66, "f5fa4502015f078c7324589be92d20284c0fcf82ab9612c1bbb122920ad72f20"),
        ):
            baseline = sorted((c,n) for c,n in methods if ".signature." not in c and not n.startswith("realOidcSignature")
                              and c != "com.test365alm.server.identity.LoginDiagnosticsTest"
                              and n != "lateAnonymousMeResponseCannotReplaceAuthorizationSession")
            self.assertEqual(count, len(baseline))
            self.assertEqual(digest, hashlib.sha256(json.dumps(baseline,separators=(",",":")).encode()).hexdigest())

    def test_focused_command_and_successful_phase_order(self):
        with patch("tools.linux_test_runner.phase", return_value=0) as phase:
            self.assertEqual(0, run_phases(Mock(), "runner", Path("evidence")))
            self.assertEqual(["scan", "unit", "focused", "integration"], [call.args[2] for call in phase.call_args_list])
        self.assertEqual(["-Pintegration", "verify", "-Dit.test=BusinessFieldsDatabaseIT,BusinessFieldsMigrationUpgradeIT,ManualExecutionDatabaseIT,ManualExecutionMigrationUpgradeIT,AssetDatabaseIT#stepEvidence*,OidcCallbackSecurityIT#realOidcManualExecutionHttpRunsAndRejectsViewerWrites+realOidcReviewHttpDesignatedIdentityCsrfVersionsReplayAndImmutableRevision+realOidcReviewHttpOldSessionsReauthorizeReviewerAndActorAfterRevocation+realOidcSignatureHttpCrossSessionRevocationAndLogoutRejectPendingOrReplayedWrites+realOidcSignatureHttpRejectsOtherSubjectMissingOrStaleAuthenticationAndInvalidNonce+realOidcSignatureHttpSameSubjectFreshLoginCsrfStrongVersionAndFrozenReplay,SignatureDatabaseIT,SignatureMigrationUpgradeIT,TestReviewDatabaseIT,TestReviewMigrationUpgradeIT,AssetMigrationUpgradeIT,DefectMigrationUpgradeIT,ReleaseMigrationUpgradeIT,AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin"], PHASES["focused"])

    def test_every_failed_phase_stops_before_later_phases(self):
        for failure_index in range(4):
            with self.subTest(failure_index=failure_index), patch("tools.linux_test_runner.phase", side_effect=[0]*failure_index+[7]) as phase:
                self.assertEqual(7, run_phases(Mock(), "runner", Path("evidence")))
                self.assertEqual(failure_index+1, phase.call_count)
                if failure_index<3:
                    self.assertNotIn("integration", [call.args[2] for call in phase.call_args_list])

    def test_focused_report_rejects_replaced_duplicate_missing_and_failed_methods(self):
        def summary(cases):
            return {"tests":len(cases),"cases":cases,"failures":0,"errors":0,"skipped":0}
        report={"unit":summary([{"class":c,"method":m} for c,m in sorted(UNIT_METHODS)]),
                "integration":summary([{"class":c,"method":m} for c,m in sorted(FOCUSED_METHODS)])}
        self.assertTrue(valid_report("focused",report))
        for alteration in ("replace","duplicate","missing","failures","errors","skipped"):
            bad=copy.deepcopy(report)
            if alteration=="replace":bad["integration"]["cases"][0]["method"]="other"
            elif alteration=="duplicate":bad["integration"]["cases"][0]=bad["integration"]["cases"][1]
            elif alteration=="missing":bad["integration"]["cases"].pop()
            else:bad["integration"][alteration]=1
            with self.subTest(alteration=alteration):self.assertFalse(valid_report("focused",bad))

    def guard(self):
        with patch("tools.linux_test_runner.docker_context", return_value="desktop-linux"), \
                patch("tools.linux_test_runner.docker_engine", return_value="engine"):
            return Guard(RUN_ID, SHA, {})

    def test_foreign_labels_missing_source_and_identity_replacement_refuse(self):
        actual = container()
        identity = validate(actual, RUN_ID, SHA)
        for change in (lambda c: c["Config"]["Labels"].update({RUN: "foreign"}),
                       lambda c: c["Config"]["Labels"].pop(SOURCE),
                       lambda c: c.update(Id="replacement"), lambda c: c.update(Created="recreated"),
                       lambda c: c["State"].update(StartedAt="restarted"),
                       lambda c: c.update(Image="changed")):
            changed = copy.deepcopy(actual)
            change(changed)
            with self.subTest(changed=changed), self.assertRaises(ResourceOwnershipError):
                validate(changed, RUN_ID, SHA, identity)

    def test_privilege_host_namespaces_and_windows_binds_refuse(self):
        for host, mounts in (({"Privileged": True}, []), ({"PidMode": "host"}, []),
                             ({"NetworkMode": "host"}, []), ({}, [{"Type": "bind", "Source": "C:/repo", "Destination": "/work"}])):
            actual = container()
            actual.update(HostConfig=host, Mounts=mounts)
            with self.assertRaises(ResourceOwnershipError):
                validate(actual, RUN_ID, SHA)
        actual = container()
        actual["Mounts"] = [{"Type": "bind", "Source": "/var/run/docker.sock", "Destination": "/var/run/docker.sock"}]
        validate(actual, RUN_ID, SHA)

    def test_daemon_or_context_change_refuses_mutation(self):
        guard = self.guard()
        guard.docker = Mock()
        for context, engine in (("other", "engine"), ("desktop-linux", "other")):
            with patch("tools.linux_test_runner.docker_context", return_value=context), \
                    patch("tools.linux_test_runner.docker_engine", return_value=engine), \
                    self.assertRaises(ResourceOwnershipError):
                guard.remove("unrecorded")
        guard.docker.assert_not_called()

    def test_cleanup_refuses_foreign_replacement_and_preserves_resources(self):
        guard = self.guard()
        actual = container()
        guard.resources[actual["Id"]] = validate(actual, RUN_ID, SHA)
        actual["Config"]["Labels"][RUN] = "foreign"
        guard.discover = Mock()
        guard.verify_engine = Mock()
        guard.inspect = Mock(return_value=actual)
        guard.docker = Mock()
        self.assertFalse(guard.cleanup())
        guard.docker.assert_not_called()

    def test_exception_cleanup_removes_only_recorded_verified_ids_and_handles_ryuk_disappearance(self):
        guard = self.guard()
        actual = container()
        guard.resources[actual["Id"]] = validate(actual, RUN_ID, SHA)
        guard.resources["already-gone"] = Identity("already-gone", "old", "old")
        guard.discover = Mock()
        guard.verify_engine = Mock()
        guard.inspect = Mock(side_effect=lambda c: actual if c == actual["Id"] else None)
        guard.docker = Mock()
        try:
            raise RuntimeError("phase failed")
        except RuntimeError:
            self.assertTrue(guard.cleanup())
        guard.docker.assert_called_once_with("container", "rm", "-f", "-v", actual["Id"])

    def test_unknown_resource_and_failed_cleanup_are_not_success(self):
        guard = self.guard()
        guard.verify_engine = Mock()
        with self.assertRaises(ResourceOwnershipError):
            guard.require("unknown")
        guard.discover = Mock(side_effect=ResourceOwnershipError("daemon changed"))
        self.assertFalse(guard.cleanup())

    def test_cleanup_confirms_exact_container_absence_when_ryuk_wins_the_require_or_rm_window(self):
        for window in ("require", "rm"):
            guard = self.guard()
            actual = container()
            guard.resources[actual["Id"]] = validate(actual, RUN_ID, SHA)
            guard.discover = Mock()
            guard.verify_engine = Mock()
            guard.inspect = Mock(side_effect=[actual, None, None] if window == "require" else [actual, actual, None])
            guard.docker = Mock()
            if window == "rm":
                guard.docker.side_effect = runner.OwnedContainerDisappeared()
            with self.subTest(window=window):
                self.assertTrue(guard.cleanup())
                self.assertEqual("confirmed_absent_after_not_found", guard.cleanup_events[-1]["reason"])
                self.assertEqual(actual["Id"], guard.cleanup_events[-1]["id"])
                self.assertEqual(0 if window == "require" else 1, guard.docker.call_count)

    def test_cleanup_refuses_non_not_found_errors_replacements_and_daemon_changes(self):
        for change in ("unexpected_error", "replacement", "restart", "image", "foreign", "daemon"):
            guard = self.guard()
            actual = container()
            guard.resources[actual["Id"]] = validate(actual, RUN_ID, SHA)
            guard.discover = Mock()
            guard.verify_engine = Mock()
            changed = copy.deepcopy(actual)
            if change == "replacement": changed["Id"] = "d" * 64
            if change == "restart": changed["State"]["StartedAt"] = "restarted"
            if change == "image": changed["Image"] = "sha256:changed"
            if change == "foreign": changed["Config"]["Labels"][RUN] = "foreign"
            guard.inspect = Mock(side_effect=[actual, changed])
            guard.remove = Mock(side_effect=ResourceOwnershipError("unsafe") if change == "unexpected_error" else runner.OwnedContainerDisappeared())
            if change == "daemon": guard.verify_engine.side_effect = [None, ResourceOwnershipError("daemon changed")]
            with self.subTest(change=change):
                self.assertFalse(guard.cleanup())
                guard.remove.assert_called_once_with(actual["Id"])
                self.assertEqual("refused", guard.cleanup_events[-1]["reason"])
                self.assertNotIn("unsafe", str(guard.cleanup_events))

    def test_only_exact_owned_rm_not_found_errors_are_classified_as_disappeared(self):
        guard = self.guard()
        identity = "c" * 64
        for error, code, expected in ((f"Error response from daemon: No such container: {identity}\n", 1, True),
                                      (f"Error response from daemon: No such container: {'d' * 64}\n", 1, False),
                                      (f"Error response from daemon: No such container: {identity}\nsecret-extra", 1, False),
                                      (f"Error response from daemon: No such container: {identity}\n", 125, False),
                                      ("permission denied secret", 1, False)):
            result = subprocess.CompletedProcess([], code, b"", error.encode())
            with self.subTest(code=code, expected=expected), patch("tools.linux_test_runner.subprocess.run", return_value=result):
                with self.assertRaises(runner.OwnedContainerDisappeared if expected else ResourceOwnershipError) as failure:
                    guard.docker("container", "rm", "-f", "-v", identity)
                self.assertEqual(expected, isinstance(failure.exception, runner.OwnedContainerDisappeared))
                self.assertNotIn("secret", str(failure.exception))

    def test_inspect_requires_a_complete_not_found_response_for_the_exact_id(self):
        guard = self.guard()
        identity = "c" * 64
        for error, code, absent in ((f"Error: No such container: {identity}\n", 1, True),
                                     (f"Error: No such object: {identity}\n", 1, True),
                                     (f"Error: No such container: {'d'*64}\n", 1, False),
                                     (f"Error: No such container: {identity}\nsecret-extra", 1, False),
                                     (f"Error: No such container: {identity}\n", 125, False),
                                     ("permission denied secret", 1, False)):
            result = subprocess.CompletedProcess([], code, b"[]", error.encode())
            with self.subTest(absent=absent, code=code), patch("tools.linux_test_runner.subprocess.run", return_value=result):
                if absent:
                    self.assertIsNone(guard.inspect(identity))
                else:
                    with self.assertRaises(ResourceOwnershipError): guard.inspect(identity)

    def test_first_start_is_recorded_once_and_a_later_restart_cannot_replace_it(self):
        guard = self.guard()
        actual = container()
        actual["State"]["StartedAt"] = "0001-01-01T00:00:00Z"
        guard.resources[actual["Id"]] = validate(actual, RUN_ID, SHA)
        guard.verify_engine = Mock()
        actual["State"]["StartedAt"] = "first-start"
        guard.inspect = Mock(return_value=actual)
        guard.require(actual["Id"])
        self.assertEqual("first-start", guard.resources[actual["Id"]].started_at)
        actual["State"]["StartedAt"] = "second-start"
        with self.assertRaises(ResourceOwnershipError): guard.require(actual["Id"])

    def test_archive_requires_exact_commit_and_refuses_daily_env_before_export(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "source.tar"
            with self.assertRaises(ValueError):
                committed_archive("HEAD", destination)
            with patch("tools.linux_test_runner.subprocess.run") as run:
                run.return_value.stdout = ".env\napps/server/pom.xml\n"
                with self.assertRaises(ValueError):
                    committed_archive(SHA, destination)
                self.assertEqual(2, run.call_count)
                self.assertFalse(destination.exists())

    def test_main_phase_exception_always_cleans_and_records_failure(self):
        guard = Mock(context="local", engine="engine", resources={}, cleanup_events=[])
        guard.labels.return_value = []
        guard.docker.side_effect = ["built", "sha256:" + "e" * 64]
        guard.cleanup.return_value = True
        with tempfile.TemporaryDirectory() as directory, \
                patch("tools.linux_test_runner.ROOT", Path(directory)), \
                patch("tools.linux_test_runner.committed_archive"), \
                patch("tools.linux_test_runner.Guard", return_value=guard), \
                patch("tools.linux_test_runner.subprocess.run") as run, \
                patch("tools.linux_test_runner.execute", side_effect=ResourceOwnershipError("phase failed")), \
                patch("sys.argv", ["runner", "--source", SHA]):
            run.return_value.stdout = b"FROM official@sha256:fixed\n"
            self.assertEqual(1, main())
            guard.cleanup.assert_called_once()
            import json
            report = json.loads(next(Path(directory).rglob("run.json")).read_text())
            self.assertTrue(report["cleanup"])
            self.assertEqual(1, report["exit_code"])
            self.assertEqual("ResourceOwnershipError", report["failure_type"])

    def test_export_has_status_but_no_properties_messages_or_system_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-safe.xml"
            path.write_text('<testsuite><properties><property name="secret" value="hidden"/></properties>'
                            '<testcase classname="SafeTest" name="rejects"><failure message="hidden">hidden</failure>'
                            '<system-out>hidden</system-out></testcase></testsuite>')
            report = summarize(Path(directory))
            self.assertEqual(1, report["failures"])
            self.assertNotIn("hidden", str(report))
            self.assertNotIn("secret", str(report))

    def test_duplicate_method_reports_refuse_export(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-dupe.xml").write_text('<testsuite>'
                '<testcase classname="SafeTest" name="same"/><testcase classname="SafeTest" name="same"/></testsuite>')
            with self.assertRaises(ValueError):
                summarize(Path(directory))

    def test_failure_diagnostics_export_only_known_types_states_and_source_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-safe.xml").write_text('''<testsuite>
              <testcase classname="com.test365alm.server.execution.ManualExecutionDatabaseIT"
                        name="manifestAssignmentAndOldAttemptStayFrozenAfterReassignmentRenameAndRevision">
                <failure type="org.opentest4j.AssertionFailedError" message="secret-message">
org.opentest4j.AssertionFailedError: secret-assertion-values
 at com.test365alm.server.execution.ManualExecutionDatabaseIT.manifestAssignmentAndOldAttemptStayFrozenAfterReassignmentRenameAndRevision(ManualExecutionDatabaseIT.java:1003)
 at evil.Secret.secret(Secret.java:12)
Caused by: org.postgresql.util.PSQLException: secret-sql SQLState: 42703
 at com.test365alm.server.execution.ExecutionService.attempt(ExecutionService.java:450)
                </failure><properties>secret-property</properties><system-out>secret-log</system-out>
              </testcase></testsuite>''', encoding="utf-8")
            report = summarize(Path(directory))
            diagnostics = report["cases"][0]["diagnostics"]
            self.assertEqual(["org.opentest4j.AssertionFailedError", "org.postgresql.util.PSQLException"], diagnostics["types"])
            self.assertEqual(["42703"], diagnostics["sqlstates"])
            self.assertEqual([{"class": "com.test365alm.server.execution.ManualExecutionDatabaseIT",
                               "file": "ManualExecutionDatabaseIT.java", "line": 1003},
                              {"class": "com.test365alm.server.execution.ExecutionService",
                               "file": "ExecutionService.java", "line": 450}], diagnostics["frames"])
            self.assertNotIn("secret", str(report).lower())
            self.assertNotIn("evil", str(report))

    def test_unknown_diagnostics_are_omitted_and_known_frames_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            frames = "\n".join(f" at com.test365alm.server.execution.ExecutionService.run(ExecutionService.java:{i})" for i in range(1, 30))
            (Path(directory) / "TEST-safe.xml").write_text('<testsuite><testcase classname="SafeTest" name="rejects">'
                '<error type="secret.CustomError">secret SQLState: ABCDE\n' + frames +
                '\n at com.test365alm.server.execution.ExecutionService.secret(Secret.java:999)</error></testcase></testsuite>', encoding="utf-8")
            diagnostic = summarize(Path(directory))["cases"][0]["diagnostics"]
            self.assertEqual([], diagnostic["types"])
            self.assertEqual([], diagnostic["sqlstates"])
            self.assertEqual(8, len(diagnostic["frames"]))
            self.assertNotIn("secret", str(diagnostic).lower())

    def test_flyway_diagnostics_keep_only_known_migration_line_and_sqlstate(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-safe.xml").write_text('''<testsuite><testcase classname="SafeTest" name="rejects">
              <error type="java.lang.IllegalStateException">secret-config
Caused by: org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException: Migration V26__simulated_signatures.sql failed
SQL State  : 42601
Error Code : 0
Message    : secret SQL and credentials
Location   : secret/path/V26__simulated_signatures.sql
Line       : 125
Statement  : secret SQL
              </error></testcase></testsuite>''', encoding="utf-8")
            diagnostic = summarize(Path(directory))["cases"][0]["diagnostics"]
            self.assertEqual(["java.lang.IllegalStateException",
                              "org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException"], diagnostic["types"])
            self.assertEqual(["42601"], diagnostic["sqlstates"])
            self.assertEqual([{"version": 26, "line": 125}], diagnostic["migrations"])
            for forbidden in ("secret", "Location", "Statement", "V26__simulated_signatures.sql", "Message", "path"):
                self.assertNotIn(forbidden, str(diagnostic))

    def test_flyway_diagnostics_refuse_foreign_migrations_bad_lines_and_unknown_states(self):
        from tools.linux_test_inside import failure_diagnostics
        import xml.etree.ElementTree as ET
        for name, line in (("V999__secret.sql", "125"), ("V26__simulated_signatures.sql", "0"),
                           ("V26__simulated_signatures.sql", "99999"),
                           ("../V26__simulated_signatures.sql", "125"),
                           ("V26__simulated_signatures.sql", "125 secret")):
            with self.subTest(name=name, line=line):
                node = ET.Element("error", type="secret.CustomError")
                node.text = f"Migration {name} failed\nSQL State  : ABCDE\nLine       : {line}\nsecret"
                diagnostic = failure_diagnostics(node)
                self.assertEqual([], diagnostic["migrations"])
                self.assertEqual([], diagnostic["sqlstates"])
                self.assertNotIn("secret", str(diagnostic))

    def test_flyway_diagnostics_bound_distinct_known_migration_locations(self):
        from tools.linux_test_inside import failure_diagnostics
        import xml.etree.ElementTree as ET
        node = ET.Element("error")
        node.text = "\n".join(f"Migration V26__simulated_signatures.sql failed\nLine : {line}" for line in range(1, 30))
        self.assertEqual([{"version": 26, "line": line} for line in range(1, 5)], failure_diagnostics(node)["migrations"])


if __name__ == "__main__":
    unittest.main()
