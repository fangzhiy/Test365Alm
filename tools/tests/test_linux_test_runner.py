import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.linux_test_runner import Guard, Identity, RUN, SOURCE, committed_archive, main, validate, run_phases
from tools.linux_test_inside import summarize, valid_report, FOCUSED_METHODS, UNIT_METHODS, PHASES
from tools.r02_resource_guard import ResourceOwnershipError

RUN_ID = "a" * 32
SHA = "b" * 40


def container():
    return {"Id": "c" * 64, "Created": "created", "Image": "sha256:image",
            "Config": {"Labels": {RUN: RUN_ID, SOURCE: SHA}}, "HostConfig": {}, "Mounts": []}


class LinuxRunnerGuardTests(unittest.TestCase):
    def test_focused_command_and_successful_phase_order(self):
        with patch("tools.linux_test_runner.phase", return_value=0) as phase:
            self.assertEqual(0, run_phases(Mock(), "runner", Path("evidence")))
            self.assertEqual(["scan", "unit", "focused", "integration"], [call.args[2] for call in phase.call_args_list])
        self.assertEqual(["-Pintegration", "verify", "-Dit.test=BusinessFieldsDatabaseIT,BusinessFieldsMigrationUpgradeIT,ManualExecutionDatabaseIT,ManualExecutionMigrationUpgradeIT,AssetDatabaseIT#stepEvidence*,OidcCallbackSecurityIT#realOidcManualExecutionHttpRunsAndRejectsViewerWrites,AssetMigrationUpgradeIT,DefectMigrationUpgradeIT,ReleaseMigrationUpgradeIT,AssetProvisioningUpgradeIT#upgradedDatabaseUsesProvisionedRestrictedScannerLogin"], PHASES["focused"])

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
        guard = Mock(context="local", engine="engine", resources={})
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


if __name__ == "__main__":
    unittest.main()
