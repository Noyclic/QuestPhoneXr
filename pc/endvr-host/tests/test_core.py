import unittest
from endvr_host.metrics import HostMetrics
from endvr_host.server import EndVRHost
from endvr_host.test_pattern import make_test_frame

class EndVRHostTests(unittest.TestCase):
    def test_test_pattern_is_jpeg(self):
        frame = make_test_frame(320, 240, 3)
        self.assertTrue(frame.startswith(bytes([255, 216])))
        self.assertTrue(frame.endswith(bytes([255, 217])))

    def test_metrics(self):
        metrics = HostMetrics.create()
        metrics.frame_generated()
        metrics.client_connected()
        self.assertEqual(metrics.snapshot()["frames_generated"], 1)
        self.assertEqual(metrics.snapshot()["clients_connected"], 1)
        metrics.client_disconnected()
        self.assertEqual(metrics.snapshot()["clients_connected"], 0)

    def test_configuration_validation(self):
        with self.assertRaises(ValueError):
            EndVRHost(64, 64, 30)
        with self.assertRaises(ValueError):
            EndVRHost(640, 480, 120)

if __name__ == "__main__":
    unittest.main()
