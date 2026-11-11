"""Asset-free checks: python -I spike/test_layer_context.py"""
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compose_layer_refs import coverage_mask, make_reference, paint  # noqa: E402


class LayerContextTests(unittest.TestCase):
    def test_bounds_not_source_resolution_define_size(self):
        canvas = np.zeros((12, 12, 3), np.float32)
        rgba = np.full((2, 2, 4), 255, np.uint8)
        paint(canvas, (100, 200), rgba, [[102, 203], [108, 209]])
        self.assertTrue((canvas[3:9, 2:8] == 255).all())
        self.assertEqual(np.count_nonzero(canvas[:, :, 0]), 36)

    def test_transparency_preserves_context(self):
        canvas = np.full((4, 4, 3), 20, np.float32)
        rgba = np.full((4, 4, 4), 100, np.uint8)
        rgba[:, :, 3] = 0
        paint(canvas, (0, 0), rgba, [[0, 0], [4, 4]])
        self.assertTrue((canvas == 20).all())
        rgba[:, :, 3] = 128
        paint(canvas, (0, 0), rgba, [[0, 0], [4, 4]])
        self.assertAlmostEqual(float(canvas[0, 0, 0]), 20 * 127 / 255 + 100 * 128 / 255, places=4)

    def test_active_floor_is_drawn_last_without_mutating_base(self):
        base = np.full((8, 8, 3), 20, np.float32)
        a = np.full((8, 8, 4), 255, np.uint8)
        b = a.copy()
        a[:, :, :3] = 100
        b[:, :, :3] = 200
        floors = dict(a=[(a, [[0, 0], [8, 8]])], b=[(b, [[0, 0], [8, 8]])])
        self.assertTrue((make_reference(base, (0, 0), floors, "a") == 100).all())
        self.assertTrue((make_reference(base, (0, 0), floors, "b") == 200).all())
        self.assertTrue((base == 20).all())

    def test_all_chunks_of_active_floor_are_drawn(self):
        base = np.zeros((8, 8, 3), np.float32)
        image = np.full((2, 2, 4), 255, np.uint8)
        floors = dict(a=[(image, [[0, 0], [2, 2]]), (image, [[6, 6], [8, 8]])])
        rendered = make_reference(base, (0, 0), floors, "a")
        self.assertEqual(np.count_nonzero(rendered[:, :, 0]), 8)

    def test_coverage_uses_alpha_not_brightness(self):
        image = np.zeros((2, 2, 4), np.uint8)
        image[:, :, 3] = 255
        mask = coverage_mask((8, 8, 3), (100, 200), [(image, [[102, 202], [106, 206]])])
        self.assertEqual(np.count_nonzero(mask), 16)
        self.assertTrue((mask[2:6, 2:6] == 255).all())

    def test_coverage_of_overlapping_chunks_is_union(self):
        image = np.full((2, 2, 4), 255, np.uint8)
        chunks = [(image, [[0, 0], [4, 4]]), (image, [[2, 2], [6, 6]])]
        self.assertEqual(np.count_nonzero(coverage_mask((8, 8, 3), (0, 0), chunks)), 28)

    def test_bad_bounds_are_not_silently_clipped(self):
        with self.assertRaises(ValueError):
            paint(np.zeros((8, 8, 3), np.float32), (0, 0), np.zeros((4, 4, 4), np.uint8),
                  [[-1, 0], [3, 4]])


if __name__ == "__main__":
    unittest.main()
