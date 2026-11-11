"""Asset-free split-state tests: python -I spike/test_split_tracker.py"""
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from split_tracker import (LayerDecision, LayerLatch, PositionFix, SplitGates, SplitTracker,
                           appearance_errors, choose_position, classify_layer, position_from_registration,
                           pose_matrix)  # noqa: E402


def position(space="twt702", **kwargs):
    return replace(PositionFix(space, 50, 50, 1.125, 0, 0.5, 12, 0.3, 0.8), **kwargs)


class SplitGeometryTests(unittest.TestCase):
    def test_same_position_on_different_floor_models_is_not_ambiguous(self):
        p = position()
        best, mode = choose_position([p, replace(p, x=50.2, score=0.81)], SplitGates())
        self.assertIsNotNone(best)
        self.assertEqual(mode, "candidate")

    def test_other_position_or_space_remains_ambiguous(self):
        p = position()
        for other in (replace(p, x=100), position("kzsd2")):
            self.assertEqual(choose_position([p, other], SplitGates()), (None, "ambiguous"))

    def test_small_visible_scene_is_not_compared_to_whole_ring(self):
        context = np.zeros((200, 200), np.uint8)
        context[20:41, 20:41] = 255
        pts = np.float32([[21, 21], [30, 21], [39, 21], [39, 30], [39, 39], [30, 39], [21, 39], [21, 30]])
        reg = SimpleNamespace(matrix=np.array([[1, 0, 0], [0, 1, 0]], float), inlier_points=pts,
                              inliers=8, matches=10, error_px=0.2, x=50, y=50, scale=1, rotation=0)
        model = SimpleNamespace(context=context, space="twt702")
        mm = SimpleNamespace(gray=context, mask=np.full_like(context, 255))
        fix = position_from_registration(model, reg, mm, SplitGates())
        self.assertIsNotNone(fix)
        self.assertGreater(fix.support, 0.7)

    def test_pose_uses_canonical_origin_and_units_per_pixel(self):
        ref = SimpleNamespace(upp=2, ox=1000, oy=-2000)
        mm = SimpleNamespace(lx=50, ly=50)
        fix = position(x=1200, y=-1800, scale=1, rotation=0)
        M = pose_matrix(ref, mm, fix)
        np.testing.assert_allclose(M @ [50, 50, 1], [100, 100])
        np.testing.assert_allclose(M @ [60, 50, 1], [105, 100])


class LayerAppearanceTests(unittest.TestCase):
    def scene(self):
        rng = np.random.default_rng(4)
        masks = [np.zeros((120, 120), bool) for _ in range(3)]
        masks[0][:60, :60] = True
        masks[1][65:115, :60] = True
        masks[2][70:86, 80:96] = True  # A genuine small active floor, <2% of the image.
        texture = rng.uniform(100, 170, (120, 120))
        models = []
        for active in range(3):
            image = np.full((120, 120), 30.0)
            for i, mask in enumerate(masks):
                image[mask] = texture[mask] * (1 if i == active else 0.45)
            models.append(image)
        return np.array(models), masks

    def test_brightness_change_does_not_change_active_layer(self):
        models, masks = self.scene()
        for active in range(3):
            query = np.clip(models[active] * 0.8 + 15, 0, 255).astype(np.uint8)
            scores, _ = appearance_errors(query, models, masks, np.full(query.shape, 255, np.uint8), SplitGates())
            self.assertEqual(int(np.argmin([s["error"] for s in scores])), active)
            self.assertLess(scores[active]["error"], 2)

    def test_identical_floor_images_give_no_layer_evidence(self):
        models, masks = self.scene()
        equal = [models[0]] * 3
        scores, _ = appearance_errors(models[0], equal, masks, np.full((120, 120), 255, np.uint8), SplitGates())
        self.assertEqual(scores, [])

    def test_hidden_discriminating_region_is_unknown(self):
        models, masks = self.scene()
        scores, _ = appearance_errors(models[0], models, masks, np.zeros((120, 120), np.uint8), SplitGates())
        self.assertEqual(scores, [])

    def classifier_models(self):
        images, masks = self.scene()
        models = [SimpleNamespace(space="twt702", layer=f"floor_{i}", active=mask.astype(np.uint8) * 255,
                                  ref=SimpleNamespace(gray=image.astype(np.uint8), upp=1, ox=0, oy=0))
                  for i, (image, mask) in enumerate(zip(images, masks))]
        return images, models

    def test_small_floor_can_be_identified_without_full_ring_coverage(self):
        images, models = self.classifier_models()
        query = (images[2] * 0.8 + 15).astype(np.uint8)
        mm = SimpleNamespace(gray=query, mask=np.full_like(query, 255), lx=60, ly=60)
        decision = classify_layer(mm, position(x=60, y=60, scale=1), models, SplitGates())
        self.assertEqual(decision.layer, "floor_2")
        self.assertEqual(decision.mode, "candidate")

    def test_unrelated_scene_does_not_force_a_layer(self):
        images, models = self.classifier_models()
        query = np.random.default_rng(7).integers(0, 200, (120, 120), dtype=np.uint8)
        mm = SimpleNamespace(gray=query, mask=np.full_like(query, 255), lx=60, ly=60)
        decision = classify_layer(mm, position(x=60, y=60, scale=1), models, SplitGates())
        self.assertIsNone(decision.layer)

    def test_wrong_space_cannot_supply_layer_models(self):
        images, models = self.classifier_models()
        mm = SimpleNamespace(gray=images[0], mask=np.full((120, 120), 255, np.uint8), lx=60, ly=60)
        self.assertIsNone(classify_layer(mm, position("kzsd2"), models, SplitGates()).layer)

    def test_overlay_icons_are_not_dominant_layer_evidence(self):
        models, masks = self.scene()
        query = (models[2] * 0.8 + 15).astype(np.uint8)
        query[10:35, 10:35] = 255
        scores, _ = appearance_errors(query, models, masks, np.full(query.shape, 255, np.uint8), SplitGates())
        self.assertEqual(int(np.argmin([s["error"] for s in scores])), 2)


class LayerStateTests(unittest.TestCase):
    def test_switch_needs_two_frames_and_never_reports_old_floor(self):
        latch = LayerLatch()
        self.assertIsNone(latch.update(LayerDecision("UG1", "candidate"), 0).layer)
        self.assertEqual(latch.update(LayerDecision("UG1", "candidate"), 0.5).layer, "UG1")
        changed = latch.update(LayerDecision("UG2", "candidate"), 1)
        self.assertEqual((changed.layer, changed.mode), (None, "pending"))
        self.assertEqual(latch.update(LayerDecision("UG2", "candidate"), 1.5).layer, "UG2")

    def test_unknown_immediately_clears_old_floor(self):
        latch = LayerLatch()
        latch.update(LayerDecision("UG1", "candidate"), 0)
        latch.update(LayerDecision("UG1", "candidate"), 0.5)
        self.assertIsNone(latch.update(LayerDecision(reason="ambiguous"), 1).layer)
        self.assertEqual(latch.update(LayerDecision("UG1", "candidate"), 1.5).mode, "pending")

    @patch("split_tracker.classify_layer", side_effect=lambda *args: LayerDecision(reason="ambiguous"))
    @patch("split_tracker.position_from_registration", side_effect=lambda *args: position())
    @patch("split_tracker.FeatureQuery")
    def test_unknown_layer_does_not_reset_position_tracking(self, query, geo, classify):
        model = SimpleNamespace(space="twt702", features=SimpleNamespace(register=Mock(return_value=object())))
        tracker = SplitTracker([model], unit=0.5625)
        self.assertEqual(tracker.update(None, 0)[1], "pending")
        fix, mode, layer, search, _ = tracker.update(None, 0.5)
        self.assertEqual((mode, search, layer.layer), ("found", "global", None))
        self.assertIsNotNone(fix)
        fix, mode, layer, search, _ = tracker.update(None, 1)
        self.assertEqual((mode, search, layer.layer), ("track", "local", None))
        self.assertIsNotNone(fix)

    @patch("split_tracker.classify_layer")
    @patch("split_tracker.FeatureQuery")
    def test_no_position_never_runs_layer_classifier(self, query, classify):
        tracker = SplitTracker([], unit=0.5625)
        fix, mode, layer, _, _ = tracker.update(None, 0)
        self.assertIsNone(fix)
        self.assertIsNone(layer.layer)
        classify.assert_not_called()


if __name__ == "__main__":
    unittest.main()
