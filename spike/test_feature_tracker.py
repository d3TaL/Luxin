"""Asset-free checks: python -I spike/test_feature_tracker.py"""
from pathlib import Path
from types import SimpleNamespace
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from feature_tracker import (Candidate, FeatureQuery, FeatureReference, FeatureTracker, Gates,
                             apply_gates, choose, patch_support, spatial_support)  # noqa: E402


def candidate(ref="a", **overrides):
    values = dict(ref=ref, x=200.0, y=200.0, scale=1.0, inliers=12, matches=16,
                  coverage=0.4, cells=8, error_px=0.4, patches=12, good_patches=9,
                  patch_fraction=0.75, patch_median=0.7, score=0.735)
    values.update(overrides)
    return Candidate(**values)


class SupportTests(unittest.TestCase):
    def test_cluster_is_not_wide_support(self):
        mask = np.full((160, 160), 255, np.uint8)
        pts = np.array([[60, 60], [61, 60], [61, 61], [60, 61]], np.float32)
        coverage, cells = spatial_support(pts, mask)
        self.assertLess(coverage, 0.001)
        self.assertEqual(cells, 1)
        pts = np.array([[20, 20], [140, 20], [140, 140], [20, 140]], np.float32)
        coverage, cells = spatial_support(pts, mask)
        self.assertGreater(coverage, 0.5)
        self.assertEqual(cells, 4)

    def test_empty_mask_and_collinear_points(self):
        pts = np.array([[20, 20], [50, 50], [90, 90]], np.float32)
        self.assertEqual(spatial_support(pts, np.zeros((100, 100), np.uint8)), (0, 0))
        self.assertEqual(spatial_support(pts, np.full((100, 100), 255, np.uint8))[0], 0)

    def test_identical_patches_and_occlusion(self):
        rng = np.random.default_rng(3)
        img = rng.integers(0, 256, (160, 160), dtype=np.uint8)
        mask = np.full_like(img, 255)
        n, good, fraction, median = patch_support(img, img, mask)
        self.assertEqual((n, good, fraction), (16, 16, 1))
        self.assertAlmostEqual(median, 1)
        other = img.copy()
        other[80:, 80:] = rng.integers(0, 256, (80, 80), dtype=np.uint8)
        n, good, fraction, median = patch_support(img, other, mask)
        self.assertEqual(n, 16)
        self.assertGreaterEqual(good, 10)
        self.assertLess(good, 16)
        self.assertGreater(median, 0.8)

    def test_flat_backdrop_is_not_evidence(self):
        img = np.full((160, 160), 35, np.uint8)
        self.assertEqual(patch_support(img, img, np.full_like(img, 255)), (0, 0, 0, 0))

    def test_missing_reference_details_count_against_a_shared_fragment(self):
        rng = np.random.default_rng(4)
        query = rng.integers(0, 256, (160, 160), dtype=np.uint8)
        reference = np.full_like(query, 35)
        reference[:80, :80] = query[:80, :80]
        n, good, fraction, _ = patch_support(query, reference, np.full_like(query, 255))
        self.assertEqual(n, 16)
        self.assertLess(fraction, 0.45)

    def test_many_clustered_inliers_do_not_win(self):
        gates = Gates()
        narrow = apply_gates(candidate("wrong", inliers=80, coverage=0.01, cells=1, score=0.99), gates)
        wide = apply_gates(candidate("wide"), gates)
        best, mode = choose([narrow, wide], gates)
        self.assertEqual((best.ref, mode), ("wide", "candidate"))

    def test_same_position_different_layers_remains_ambiguous(self):
        a = candidate("upper")
        b = candidate("lower", inliers=50, score=a.score + 0.02)
        self.assertEqual(choose([a, b], Gates()), (None, "ambiguous"))

    def test_overlapping_crops_of_one_surface_are_one_hypothesis(self):
        a = candidate("crop_a", map_id="twt702/surface")
        b = candidate("crop_b", map_id="twt702/surface", x=201, score=a.score + 0.01)
        best, mode = choose([a, b], Gates())
        self.assertEqual((best.ref, mode), ("crop_b", "candidate"))

    def test_same_surface_different_positions_or_scales_are_not_merged(self):
        a = candidate("crop_a", map_id="twt702/surface")
        for b in (candidate("crop_b", map_id="twt702/surface", x=220),
                  candidate("crop_b", map_id="twt702/surface", scale=2)):
            self.assertEqual(choose([a, b], Gates()), (None, "ambiguous"))

    def test_different_spaces_with_identical_coordinates_are_ambiguous(self):
        a = candidate("crop_a", map_id="twt702/surface")
        b = candidate("crop_b", map_id="kzsd2/surface")
        self.assertEqual(choose([a, b], Gates()), (None, "ambiguous"))

    def test_bad_patches_and_geometry_are_rejected(self):
        c = candidate(error_px=4, good_patches=1, patch_fraction=0.1)
        apply_gates(c, Gates())
        self.assertIn("geometric error", c.reasons)
        self.assertIn("patch disagreement", c.reasons)
        self.assertIsNone(choose([c], Gates())[0])


class ReferenceIdentityTests(unittest.TestCase):
    def load_ref(self, directory, name, metadata):
        from minimap_match import Ref
        prefix = Path(directory) / name
        cv2.imwrite(str(prefix) + ".png", np.zeros((32, 32, 3), np.uint8))
        meta = dict(units_per_px=1, origin=[0, 0], **metadata)
        Path(str(prefix) + ".json").write_text(json.dumps(meta))
        return Ref(name, str(prefix))

    def test_surface_identity_comes_from_tile_set_not_crop_name(self):
        with tempfile.TemporaryDirectory() as d:
            a = self.load_ref(d, "a", dict(tile_code="twt702"))
            b = self.load_ref(d, "b", dict(tile_code="twt702"))
            self.assertEqual(a.map_id, b.map_id)

    def test_unidentified_layers_are_not_merged(self):
        with tempfile.TemporaryDirectory() as d:
            a = self.load_ref(d, "a", {})
            b = self.load_ref(d, "b", {})
            self.assertNotEqual(a.map_id, b.map_id)

    def test_explicit_layer_identity_overrides_surface_default(self):
        with tempfile.TemporaryDirectory() as d:
            a = self.load_ref(d, "a", dict(tile_code="twt702", map_id="twt702/UG1"))
            self.assertEqual(a.map_id, "twt702/UG1")


class FeatureIntegrationTests(unittest.TestCase):
    def setUp(self):
        cv2.setRNGSeed(0)
        rng = np.random.default_rng(5)
        image = cv2.GaussianBlur(rng.integers(0, 256, (420, 420), dtype=np.uint8), (3, 3), 0.7)
        self.ref = SimpleNamespace(name="synthetic", gray=image, upp=1.0, ox=1000, oy=-2000)
        mask = np.zeros((192, 192), np.uint8)
        cv2.circle(mask, (96, 96), 89, 255, -1)
        cv2.circle(mask, (96, 96), 12, 0, -1)
        self.mm = SimpleNamespace(gray=image[160:352, 128:320].copy(), mask=mask, lx=96, ly=96)

    def test_recovers_known_position_and_scale(self):
        item = FeatureReference(self.ref)
        c = item.locate(FeatureQuery(self.mm), Gates(), unit=0.5625)
        self.assertIsNotNone(c)
        self.assertTrue(c.valid, c.reasons)
        self.assertAlmostEqual(c.x, 1224, delta=0.5)
        self.assertAlmostEqual(c.y, -1744, delta=0.5)
        self.assertAlmostEqual(c.scale, 1, delta=0.01)

    def test_wrong_local_prior_does_not_return_a_global_jump(self):
        item = FeatureReference(self.ref)
        c = item.locate(FeatureQuery(self.mm), Gates(), unit=0.5625,
                        hint=(1100, -1900, 1.0), radius=10)
        self.assertIsNone(c)

    def test_featureless_reference_is_safe(self):
        self.ref.gray = np.zeros_like(self.ref.gray)
        item = FeatureReference(self.ref)
        self.assertIsNone(item.locate(FeatureQuery(self.mm), Gates(), unit=0.5625))


class TrackingPolicyTests(unittest.TestCase):
    def make_tracker(self, responses):
        tr = FeatureTracker([], unit=1)
        ref = SimpleNamespace(contains=lambda x, y: True)
        locate = unittest.mock.Mock(side_effect=responses)
        tr.refs = [SimpleNamespace(ref=ref, locate=locate)]
        return tr

    @patch("feature_tracker.FeatureQuery")
    def test_acquire_needs_two_frames_then_local(self, query):
        tr = self.make_tracker([candidate(), candidate(x=202), candidate(x=204)])
        fix, mode, search, _ = tr.update(None, 0)
        self.assertEqual((fix, mode, search), (None, "pending", "global"))
        self.assertEqual(tr.update(None, 0.5)[1:3], ("found", "global"))
        self.assertEqual(tr.update(None, 1)[1:3], ("track", "local"))

    @patch("feature_tracker.FeatureQuery")
    def test_switching_surface_crops_does_not_restart_acquisition(self, query):
        tr = self.make_tracker([candidate("a", map_id="twt702/surface"),
                                candidate("a", map_id="twt702/surface"),
                                candidate("b", map_id="twt702/surface", x=205)])
        tr.update(None, 0)
        tr.update(None, 0.5)
        self.assertEqual(tr.update(None, 1)[1], "track")

    @patch("feature_tracker.FeatureQuery")
    def test_failure_hides_fix_and_retries_globally(self, query):
        tr = self.make_tracker([candidate(), candidate(), None, None, None, candidate()])
        tr.update(None, 0)
        tr.update(None, 0.2)
        for t in (0.4, 0.6, 0.8):
            self.assertIsNone(tr.update(None, t)[0])
        self.assertEqual(tr.update(None, 1)[1:3], ("pending", "global"))

    @patch("feature_tracker.FeatureQuery")
    def test_teleport_does_not_confirm_incompatible_pending_fix(self, query):
        tr = self.make_tracker([candidate(), candidate(x=1000), candidate(x=1001)])
        self.assertIsNone(tr.update(None, 0)[0])
        self.assertIsNone(tr.update(None, 0.2)[0])
        self.assertEqual(tr.update(None, 0.4)[1], "found")

    @patch("feature_tracker.FeatureQuery")
    def test_hidden_reset_discards_old_confirmation(self, query):
        tr = self.make_tracker([candidate(), candidate()])
        tr.update(None, 0)
        tr.reset()
        self.assertEqual(tr.update(None, 0.5)[1], "pending")


if __name__ == "__main__":
    unittest.main()
