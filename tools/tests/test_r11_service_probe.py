"""Local-only probe fixtures; no Docker or network is used here."""
import unittest
from unittest.mock import patch
from tools import verify_r11_services as probe


class AssetServiceProbeTests(unittest.TestCase):
    def test_nonloopback_storage_and_scanner_are_refused_before_network(self):
        for endpoint, host in [('http://example.invalid:8333', '127.0.0.1'),
                               ('http://127.0.0.1:8333', 'example.invalid')]:
            with self.subTest(endpoint=endpoint), patch.object(probe.socket, 'create_connection') as network:
                with self.assertRaises(ValueError):
                    probe.validate_targets(endpoint, host, 3310)
                network.assert_not_called()

    def test_only_private_storage_and_real_clean_scan_pass(self):
        with patch.object(probe, 'private_store', return_value=True), patch.object(probe, 'clean_scan', return_value=True):
            self.assertTrue(probe.check('http://127.0.0.1:8333', '127.0.0.1', 3310))
        for store, scan in [(False, True), (True, False)]:
            with patch.object(probe, 'private_store', return_value=store), patch.object(probe, 'clean_scan', return_value=scan):
                self.assertFalse(probe.check('http://127.0.0.1:8333', '127.0.0.1', 3310))

    def test_bounded_timeout_returns_nonzero_without_secret_output(self):
        with patch.dict(probe.os.environ, {'TEST365ALM_ASSETS_S3_ENDPOINT': 'http://127.0.0.1:8333',
                  'TEST365ALM_ASSETS_CLAMD_HOST': '127.0.0.1', 'TEST365ALM_ASSETS_CLAMD_PORT': '3310'}), \
                patch.object(probe, 'check', return_value=False), patch.object(probe.time, 'monotonic', side_effect=[0, 0, 500]), \
                patch.object(probe.time, 'sleep'), patch('builtins.print') as output:
            self.assertEqual(1, probe.main())
            self.assertIn('NOT_READY', output.call_args.args[0])
