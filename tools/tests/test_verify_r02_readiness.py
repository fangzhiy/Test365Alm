import socket
import subprocess
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.verify_r02_readiness import (
    VerificationError,
    cleanup_compose,
    is_loopback_address,
    parse_listener_output,
    port_is_free,
    validate_test_datasource,
    wait_for,
)


class DeadProcess:
    def poll(self):
        return 17


class ReadinessScriptTests(unittest.TestCase):
    def test_port_occupied_is_not_available_and_is_not_killed(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            self.assertFalse(port_is_free(port))
            self.assertTrue(listener.fileno() >= 0)

    def test_dead_backend_fails_before_a_simulated_http_response_can_pass(self):
        with self.assertRaisesRegex(VerificationError, "exited"):
            wait_for("http://127.0.0.1:1", "/health/live", {200}, time.monotonic() + 1,
                     DeadProcess(), Path("startup.log"))

    def test_loopback_and_mapped_addresses_are_distinguished_from_wildcards(self):
        for address in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
            self.assertTrue(is_loopback_address(address), address)
        for address in ("0.0.0.0", "::", "192.0.2.10"):
            self.assertFalse(is_loopback_address(address), address)

    def test_listener_parser_handles_ipv4_ipv6_and_mapped_ipv6(self):
        output = "\n".join([
            "LISTEN 0 128 127.0.0.1:18080 0.0.0.0:*",
            "LISTEN 0 128 [::ffff:127.0.0.1]:18080 [::]:*",
            "LISTEN 0 128 0.0.0.0:18080 0.0.0.0:*",
        ])
        self.assertEqual(
            ["127.0.0.1", "::ffff:127.0.0.1", "0.0.0.0"],
            parse_listener_output(output, 18080),
        )

    def test_unavailable_listener_enumeration_is_not_success(self):
        with patch("tools.verify_r02_readiness.subprocess.run", side_effect=FileNotFoundError), \
                patch("tools.verify_r02_readiness.Path.read_text", side_effect=OSError):
            from tools.verify_r02_readiness import listening_addresses
            self.assertIsNone(listening_addresses(18080))

    def test_external_datasource_is_rejected_before_destructive_actions(self):
        with self.assertRaisesRegex(ValueError, "non-local"):
            validate_test_datasource({
                "POSTGRES_DB": "test365alm",
                "POSTGRES_HOST_PORT": "54329",
                "POSTGRES_USER": "test",
                "POSTGRES_PASSWORD": "secret",
                "TEST365ALM_DATASOURCE_URL": "jdbc:postgresql://db.example.invalid:5432/test365alm",
            })

    def test_cleanup_refuses_unowned_resources(self):
        with patch("tools.verify_r02_readiness.run_compose") as run_compose:
            self.assertFalse(cleanup_compose("test365alm-r02-x", ".env.r02-test", {}, None))
            self.assertFalse(cleanup_compose("test365alm-r02-x", ".env.r02-test", {}, ("id", "other-project")))
            run_compose.assert_not_called()


if __name__ == "__main__":
    unittest.main()
