import unittest

from endvr_host.timing import advance_frame_deadline

class FrameDeadlineTests(unittest.TestCase):
    def test_keeps_the_next_slot_when_frame_finishes_on_time(self):
        self.assertAlmostEqual(advance_frame_deadline(10.0, 0.02, 10.015), 10.02)

    def test_skips_missed_slots_after_an_overrun(self):
        next_deadline = advance_frame_deadline(10.0, 0.02, 10.025)
        self.assertAlmostEqual(next_deadline, 10.04)
        self.assertGreater(next_deadline, 10.025)

    def test_skips_many_missed_slots_without_iterating_each_frame(self):
        next_deadline = advance_frame_deadline(10.0, 0.02, 10.102)
        self.assertAlmostEqual(next_deadline, 10.12)
        self.assertGreater(next_deadline, 10.102)

    def test_rejects_non_positive_period(self):
        with self.assertRaises(ValueError):
            advance_frame_deadline(10.0, 0.0, 10.0)

if __name__ == "__main__":
    unittest.main()
