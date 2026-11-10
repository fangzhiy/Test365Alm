import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.linux_test_runner import Guard, Identity, RUN, SOURCE, committed_archive, validate
from tools.r02_resource_guard import ResourceOwnershipError

RUN_ID = "a" * 32
SHA = "b" * 40


def container():
    return {"Id": "c" * 64, "Created": "created", "Image": "sha256:image",
            "Config": {"Labels": {RUN: RUN_ID, SOURCE: SHA}}, "HostConfig": {}, "Mounts": []}


class LinuxRunnerGuardTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
