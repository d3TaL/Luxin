"""Synthetic regression checks for binary-masked NCC; no downloaded assets required.

Run: python -I spike/test_minimap_match.py
"""
import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from minimap_match import ncc  # noqa: E402


class MaskedNccTests(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(42)
        self.templ = self.rng.uniform(0, 100, (13, 15)).astype(np.float32)
        self.mask = np.ones(self.templ.shape, np.float32)
        self.mask[4:9, 5:10] = 0

    def test_shift_and_brightness_invariance(self):
        image = self.rng.uniform(0, 100, (40, 45)).astype(np.float32)
        image[12:25, 17:32] = self.templ * 1.5 + 20
        # Arbitrary pixels under the excluded player-arrow region must not affect NCC.
        image[16:21, 22:27] = 1000
        score = ncc(image, self.templ, self.mask)
        _, peak, _, loc = cv2.minMaxLoc(score)
        self.assertEqual(loc, (17, 12))
        self.assertAlmostEqual(peak, 1, places=5)

    def test_flat_reference_has_no_false_peaks(self):
        for value in (0, 42, 255):
            with self.subTest(value=value):
                score = ncc(np.full((40, 45), value, np.float32), self.templ, self.mask)
                self.assertTrue(np.isfinite(score).all())
                self.assertLess(float(np.max(np.abs(score))), 1e-4)

    def test_empty_mask(self):
        score = ncc(np.ones((40, 45), np.float32), self.templ, np.zeros_like(self.mask))
        self.assertTrue(np.isfinite(score).all())
        self.assertTrue((score == 0).all())

    def test_constant_template(self):
        score = ncc(np.ones((40, 45), np.float32), np.full_like(self.templ, 10), self.mask)
        self.assertTrue((score == 0).all())

    def test_matches_brute_force_binary_mask(self):
        image = self.rng.uniform(0, 100, (18, 20)).astype(np.float32)
        score = ncc(image, self.templ, self.mask)
        use = self.mask.astype(bool)
        t = self.templ[use].astype(float)
        t -= t.mean()
        for y in range(score.shape[0]):
            for x in range(score.shape[1]):
                patch = image[y:y + 13, x:x + 15][use].astype(float)
                patch -= patch.mean()
                expected = np.dot(t, patch) / np.sqrt(np.dot(t, t) * np.dot(patch, patch))
                self.assertAlmostEqual(float(score[y, x]), expected, places=5)


if __name__ == "__main__":
    unittest.main()
