"""Asset-free label evaluator checks: python -I spike/test_layer_labels.py"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate_layer_labels import evaluate  # noqa: E402


class LayerLabelTests(unittest.TestCase):
    def setUp(self):
        self.labels = dict(space="twt702", boundary_exclusion_s=1,
                           intervals=[dict(start_s=10, end_s=20, layer="UG1")])

    def test_boundaries_and_abstentions(self):
        rows = [dict(t=10.5, mode="track", x="0", map_id="twt702/WRONG"),
                dict(t=11, mode="track", x="0", map_id="twt702/UG1"),
                dict(t=12, mode="rejected", x=""),
                dict(t=19, mode="track", x="0", map_id="twt702/WRONG")]
        r = evaluate(rows, self.labels)[0]
        self.assertEqual((r["frames"], r["correct"], r["unresolved"], r["wrong"]), (2, 1, 1, 0))
        self.assertEqual(r["position_known"], 1)
        self.assertEqual(r["position_coverage"], 0.5)
        self.assertEqual(r["correct_coverage"], 0.5)
        self.assertEqual(r["precision_when_resolved"], 1)

    def test_no_predictions_is_not_perfect_precision(self):
        r = evaluate([dict(t=12, mode="ambiguous", x="")], self.labels)[0]
        self.assertEqual(r["position_known"], 0)
        self.assertEqual(r["position_coverage"], 0)
        self.assertEqual(r["correct_coverage"], 0)
        self.assertIsNone(r["precision_when_resolved"])

    def test_legacy_alias_and_unmapped_prediction(self):
        row = dict(t=12, mode="found", x="0", ref="a", map_id="unknown:a")
        r = evaluate([row], self.labels)[0]
        self.assertEqual(r["unmapped"], 1)
        self.assertEqual(r["position_known"], 1)
        self.assertEqual(r["position_coverage"], 1)
        self.assertIsNone(r["precision_when_resolved"])
        self.assertEqual(evaluate([row], self.labels, {"a": "UG1"})[0]["correct"], 1)

    def test_other_space_is_wrong_even_with_alias(self):
        row = dict(t=12, mode="track", x="0", ref="a", map_id="kzsd2/UG1")
        self.assertEqual(evaluate([row], self.labels, {"a": "UG1"})[0]["wrong"], 1)

    def test_known_position_without_layer_is_unresolved(self):
        for mode in ("found", "track"):
            for layer_mode in ("known", "unknown"):
                with self.subTest(mode=mode, layer_mode=layer_mode):
                    row = dict(t=12, mode=mode, space="twt702", x="0", y="0",
                               layer="", layer_mode=layer_mode, overlay_ready="0",
                               ref="a", map_id="twt702/UG1")
                    r = evaluate([row], self.labels, {"a": "UG1"})[0]
                    self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                                      r["unmapped"]), (0, 0, 1, 0))
                    self.assertEqual(r["position_known"], 1)
                    self.assertEqual(r["position_coverage"], 1)
                    self.assertEqual(r["correct_coverage"], 0)
                    self.assertIsNone(r["precision_when_resolved"])

    def test_unconfirmed_layer_is_unresolved(self):
        for layer_mode in ("pending", "unknown", "", None):
            with self.subTest(layer_mode=layer_mode):
                row = dict(t=12, mode="track", space="twt702", x="0", y="0",
                           layer="twt702/UG1", overlay_ready="1")
                if layer_mode is not None:
                    row["layer_mode"] = layer_mode
                r = evaluate([row], self.labels)[0]
                self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                                  r["unmapped"]), (0, 0, 1, 0))
                self.assertEqual(r["position_known"], 1)
                self.assertIsNone(r["precision_when_resolved"])

    def test_known_layer_is_correct(self):
        for mode in ("found", "track"):
            for overlay_ready in ("0", "1"):
                with self.subTest(mode=mode, overlay_ready=overlay_ready):
                    row = dict(t=12, mode=mode, space="twt702", x="0", y="0",
                               layer="twt702/UG1", layer_mode="known",
                               overlay_ready=overlay_ready)
                    r = evaluate([row], self.labels)[0]
                    self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                                      r["unmapped"]), (1, 0, 0, 0))
                    self.assertEqual(r["position_known"], 1)
                    self.assertEqual(r["position_coverage"], 1)
                    self.assertEqual(r["correct_coverage"], 1)
                    self.assertEqual(r["precision_when_resolved"], 1)

    def test_bare_split_layer_id_is_correct(self):
        layer = "SNOWMELT_DRAINAGE_CHANNEL_UG2"
        labels = dict(space="twt702", boundary_exclusion_s=1,
                      intervals=[dict(start_s=110, end_s=120, layer=layer)])
        row = dict(t=112, mode="track", space="twt702", x="0", y="0",
                   layer=layer, layer_mode="known", overlay_ready="1")
        r = evaluate([row], labels)[0]
        self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                          r["unmapped"]), (1, 0, 0, 0))
        self.assertEqual(r["position_known"], 1)
        self.assertEqual(r["correct_coverage"], 1)
        self.assertEqual(r["precision_when_resolved"], 1)

    def test_split_space_and_layer_must_both_match_despite_alias(self):
        for space, layer in (("kzsd2", "UG1"),
                             ("kzsd2", "twt702/UG1"),
                             ("kzsd2", "kzsd2/UG1"),
                             ("twt702", "kzsd2/UG1"),
                             ("twt702", "twt702/WRONG")):
            with self.subTest(space=space, layer=layer):
                row = dict(t=12, mode="track", space=space, x="0", y="0",
                           layer=layer, layer_mode="known", overlay_ready="1",
                           ref="a", map_id="twt702/UG1")
                r = evaluate([row], self.labels, {"a": "UG1"})[0]
                self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                                  r["unmapped"]), (0, 1, 0, 0))
                self.assertEqual(r["position_known"], 1)
                self.assertEqual(r["precision_when_resolved"], 0)

    def test_split_unconfirmed_position_is_unresolved(self):
        for mode, x in (("pending", "0"), ("rejected", "0"), ("hidden", "0"),
                        ("found", ""), ("track", "")):
            with self.subTest(mode=mode, x=x):
                row = dict(t=12, mode=mode, space="twt702", x=x, y="0",
                           layer="twt702/UG1", layer_mode="known", overlay_ready="1")
                r = evaluate([row], self.labels)[0]
                self.assertEqual((r["correct"], r["wrong"], r["unresolved"],
                                  r["unmapped"]), (0, 0, 1, 0))
                self.assertEqual(r["position_known"], 0)
                self.assertEqual(r["position_coverage"], 0)
                self.assertIsNone(r["precision_when_resolved"])

    def test_empty_interval_has_null_coverage_and_precision(self):
        r = evaluate([], self.labels)[0]
        self.assertEqual((r["frames"], r["position_known"], r["correct"], r["wrong"],
                          r["unresolved"], r["unmapped"]), (0, 0, 0, 0, 0, 0))
        self.assertIsNone(r["position_coverage"])
        self.assertIsNone(r["correct_coverage"])
        self.assertIsNone(r["precision_when_resolved"])


if __name__ == "__main__":
    unittest.main()
