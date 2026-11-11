"""Asset-free timing checks: python -I spike/test_video_time.py"""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from video_time import VideoClock, next_sample_time  # noqa: E402


class VideoTimeTests(unittest.TestCase):
    def test_legacy_clock_is_unchanged(self):
        clock = VideoClock(30, "fps")
        self.assertEqual(clock.at(None, 90), 3)

    def test_pts_not_average_fps_defines_real_time(self):
        cap = Mock()
        cap.get.side_effect = [0, 13442]
        clock = VideoClock(54.9385, "pts")
        self.assertEqual(clock.at(cap, 0), 0)
        self.assertAlmostEqual(clock.at(cap, 550), 13.442)

    def test_invalid_or_backwards_pts_is_not_replaced_by_fps(self):
        cap = Mock()
        cap.get.side_effect = [1000, 900]
        clock = VideoClock(60, "pts")
        clock.at(cap, 1)
        with self.assertRaises(ValueError):
            clock.at(cap, 2)
        cap.get.side_effect = [float("nan")]
        with self.assertRaises(ValueError):
            VideoClock(60, "pts").at(cap, 0)

    def test_sampling_skips_missed_ticks_after_a_gap(self):
        self.assertEqual(next_sample_time(3.2, 0, 2), 3.5)
        self.assertEqual(next_sample_time(3.5, 0, 2), 4)
        self.assertEqual(next_sample_time(70.53, 68, 2), 71)

    def test_invalid_sampling_grid(self):
        for args in [(1, 2, 2), (1, 0, 0), (float("nan"), 0, 2)]:
            with self.assertRaises(ValueError):
                next_sample_time(*args)


if __name__ == "__main__":
    unittest.main()
