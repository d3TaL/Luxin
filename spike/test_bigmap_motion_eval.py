"""Synthetic tests for Stage 2 big-map motion evaluation.

Run: /tmp/opencode/luxin-venv/bin/python -I spike/test_bigmap_motion_eval.py
"""
from __future__ import annotations

import csv
import math
from pathlib import Path
import sys
import tempfile
import unittest

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bigmap_motion_eval as bme  # noqa: E402


def make_textured_canvas(width: int = 1600, height: int = 1000, seed: int = 7) -> np.ndarray:
    """Create a rich multi-scale synthetic map texture (strong corners + descriptors + smooth bands)."""
    rng = np.random.default_rng(seed)
    coarse = rng.integers(30, 225, (height // 16, width // 16), dtype=np.uint8)
    coarse = cv2.resize(coarse, (width, height), interpolation=cv2.INTER_CUBIC)
    medium = rng.integers(0, 255, (height // 4, width // 4), dtype=np.uint8)
    medium = cv2.resize(medium, (width, height), interpolation=cv2.INTER_LINEAR)
    fine = cv2.GaussianBlur(rng.integers(0, 255, (height, width), dtype=np.uint8), (3, 3), 0.8)
    canvas = (0.45 * coarse + 0.35 * medium + 0.20 * fine).astype(np.uint8)
    # Add geometric landmarks (roads, islands, contours) for crisp multi-scale features.
    for _ in range(180):
        cx = int(rng.integers(20, width - 20))
        cy = int(rng.integers(20, height - 20))
        r = int(rng.integers(6, 26))
        val = int(rng.integers(20, 240))
        cv2.circle(canvas, (cx, cy), r, val, int(rng.integers(1, 3)), cv2.LINE_AA)
    for _ in range(120):
        p1 = (int(rng.integers(0, width)), int(rng.integers(0, height)))
        p2 = (int(rng.integers(0, width)), int(rng.integers(0, height)))
        cv2.line(canvas, p1, p2, int(rng.integers(30, 230)), 2, cv2.LINE_AA)
    return canvas


def synthesize_sequence(
    canvas: np.ndarray,
    frame_size: tuple[int, int],
    cum_transforms: list[np.ndarray],
    add_static_ui: bool = False,
) -> list[bme.FrameItem]:
    """Generate frames where frame_t = warpAffine(frame_0, cum_transforms[t]) sampled from canvas."""
    w, h = frame_size
    ch, cw = canvas.shape[:2]
    # Place frame 0 centered in the large canvas so shifts & zoom-out stay inside canvas.
    ox = (cw - w) * 0.5
    oy = (ch - h) * 0.5
    base_to_canvas = bme.make_3dof(1.0, ox, oy)  # frame0_px -> canvas_px

    ui_mask = bme.bigmap_geometry_mask(w, h) if add_static_ui else None
    frames: list[bme.FrameItem] = []
    for i, m_0_to_t in enumerate(cum_transforms):
        inv_m = bme.invert_affine(m_0_to_t)
        assert inv_m is not None
        # frame_t_px -> frame_0_px -> canvas_px
        t_to_canvas = bme.compose_affine(base_to_canvas, inv_m)
        # warpAffine with WARP_INVERSE_MAP maps dst(x, y) <- src(M * [x, y, 1])
        gray = cv2.warpAffine(
            canvas,
            t_to_canvas,
            (w, h),
            flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
            borderMode=cv2.BORDER_REFLECT101,
        )
        if ui_mask is not None:
            # Stamp bright static UI chrome into the excluded regions.
            gray[ui_mask == 0] = 240
            cv2.rectangle(gray, (10, 10), (140, 90), 30, -1)
            cv2.putText(gray, "MAP UI", (18, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.8, 255, 2)
        frames.append(
            bme.FrameItem(
                index=i,
                timestamp_s=i / 30.0,
                gray=gray,
                orig_width=w,
                orig_height=h,
            )
        )
    return frames


class MaskAndMathTests(unittest.TestCase):
    def test_bigmap_geometry_mask_matches_cpp_boxes(self):
        mask = bme.bigmap_geometry_mask(3120, 1440)
        self.assertEqual(mask.shape, (1440, 3120))
        # Points strictly inside each of the 6 excluded UI boxes must be 0.
        for x, y in [(100, 100), (200, 700), (1800, 50), (2500, 700), (1500, 1350), (200, 1350)]:
            self.assertEqual(mask[y, x], 0, f"expected masked at ({x}, {y})")
        # Clean map interior must be 255.
        for x, y in [(1000, 600), (1500, 700), (1900, 1000)]:
            self.assertEqual(mask[y, x], 255, f"expected visible at ({x}, {y})")

        small = bme.bigmap_geometry_mask(780, 360)
        self.assertEqual(small.shape, (360, 780))
        self.assertEqual(small[0, 0], 0)
        self.assertEqual(small[180, 350], 255)
        self.assertEqual(small[180, 700], 0)

        with self.assertRaises(ValueError):
            bme.bigmap_geometry_mask(0, 360)

    def test_ransac_3dof_recovers_transform_with_outliers(self):
        rng = np.random.default_rng(123)
        n_in = 80
        n_out = 40
        src_in = rng.uniform([20, 20], [760, 340], size=(n_in, 2))
        true_s, true_tx, true_ty = 1.12, -24.5, 13.25
        dst_in = true_s * src_in + np.array([true_tx, true_ty]) + rng.normal(0.0, 0.2, size=(n_in, 2))
        src_out = rng.uniform([20, 20], [760, 340], size=(n_out, 2))
        # Non-scaling / static outliers (like map icons or false matches)
        dst_out = src_out + rng.normal(0.0, 3.0, size=(n_out, 2))
        src = np.vstack([src_in, src_out])
        dst = np.vstack([dst_in, dst_out])

        mat, mask, err, reason = bme.estimate_3dof_ransac(src, dst, reproj_thresh=2.0, seed=0)
        self.assertIsNotNone(mat, reason)
        self.assertEqual(reason, "ok")
        self.assertGreaterEqual(int(np.sum(mask)), n_in - 2)
        self.assertAlmostEqual(mat[0, 0], true_s, delta=0.005)
        self.assertAlmostEqual(mat[0, 2], true_tx, delta=1.5)
        self.assertAlmostEqual(mat[1, 2], true_ty, delta=1.5)
        self.assertLess(err, 0.5)

    def test_parse_tracker_frames_output_handles_multiline_opencv_mat(self):
        sample = (
            "input=1 mode=found phase=absolute ref=reference-0 inliers=90 errorPx=0.4 support=0.5 "
            "ms=120 featuresMs=80 matchingMs=30 followMs=0 matrix=[1.5, 0.0, 100.0;\n"
            " 0.0, 1.5, 200.0]\n"
            "input=2 mode=rejected phase=absolute ref= inliers=0 errorPx=0 support=0 ms=90\n"
            "input=3 mode=found phase=absolute ref=reference-0 inliers=85 errorPx=0.5 support=0.5 "
            "ms=110 featuresMs=75 matchingMs=25 followMs=0 matrix=[1.5, 0.0, 120.0; 0.0, 1.5, 190.0]\n"
        )
        poses = bme.parse_tracker_frames_output(sample)
        self.assertEqual(set(poses.keys()), {0, 2})
        self.assertAlmostEqual(poses[0][0, 2], 100.0)
        self.assertAlmostEqual(poses[2][1, 2], 190.0)


class SyntheticMotionRecoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.canvas = make_textured_canvas(1800, 1100, seed=42)
        cls.work_size = (780, 360)  # 3120x1440 scaled 4x down (same aspect ratio)
        cls.mask = bme.bigmap_geometry_mask(*cls.work_size)

    def test_all_methods_recover_pure_translation_with_static_ui(self):
        # 7 frames shifting by (+14, -7) working px per step -> (+56, -28) px in 3120 scale.
        step_dx, step_dy = 14.0, -7.0
        cum = [bme.make_3dof(1.0, i * step_dx, i * step_dy) for i in range(7)]
        frames = synthesize_sequence(self.canvas, self.work_size, cum, add_static_ui=True)
        gt = {i: cum[i] for i in range(len(cum))}

        for method in ("orb_prev", "lk_3dof", "lk_3dof_pred", "phase_corr", "fixed_anchor"):
            rows, summary = bme.evaluate_method(method, frames, self.mask, gt_poses=gt)
            self.assertEqual(summary.success_rate, 1.0, f"{method} failed steps: {[r.reason for r in rows]}")
            self.assertEqual(summary.max_dropout, 0, method)
            self.assertLess(summary.drift_max_3120_px, 12.0, f"{method} drift too high: {summary.drift_max_3120_px}")
            self.assertLess(summary.closure_mean_3120_px, 10.0, method)
            self.assertLess(summary.fb_mean_3120_px, 10.0, method)

    def test_scale_and_shift_recovery_and_fixed_anchor_limit(self):
        # Zoom around center by 1.06x per step + pan (-8, +5) px over 8 steps (total zoom ~1.59x > 1.4x).
        w, h = self.work_size
        cx, cy = w * 0.5, h * 0.5
        s_step = 1.06
        dx, dy = -8.0, 5.0
        step_mat = bme.make_3dof(s_step, dx - (s_step - 1.0) * cx, dy - (s_step - 1.0) * cy)
        cum = [bme.make_3dof(1.0, 0.0, 0.0)]
        for _ in range(8):
            cum.append(bme.compose_affine(step_mat, cum[-1]))
        frames = synthesize_sequence(self.canvas, self.work_size, cum, add_static_ui=True)
        gt = {i: cum[i] for i in range(len(cum))}

        for method in ("orb_prev", "lk_3dof", "lk_3dof_pred"):
            rows, summary = bme.evaluate_method(method, frames, self.mask, gt_poses=gt)
            self.assertEqual(summary.success_rate, 1.0, f"{method}: {[r.reason for r in rows]}")
            self.assertLess(summary.drift_max_3120_px, 20.0, f"{method} drift={summary.drift_max_3120_px}")
            self.assertAlmostEqual(rows[-1].cum_scale, s_step ** 8, delta=0.03)

        # Current fixed_anchor baseline drops out and re-anchors under sustained zoom + pan.
        fa_rows, fa_summary = bme.evaluate_method("fixed_anchor", frames, self.mask, gt_poses=gt)
        self.assertLess(fa_summary.success_rate, 1.0)
        self.assertGreaterEqual(fa_summary.max_dropout, 3)
        self.assertTrue(any(r.reason.startswith("reanchor:") for r in fa_rows), [r.reason for r in fa_rows])

    def test_fast_swipe_benefits_from_lk_prediction(self):
        # Accelerating swipe up to 60 working px/frame (240 px/frame in 3120 scale), then sudden stop.
        dx_cum = [0.0, 15.0, 45.0, 90.0, 145.0, 205.0, 205.0]
        cum = [bme.make_3dof(1.0, dx, -dx * 0.25) for dx in dx_cum]
        frames = synthesize_sequence(self.canvas, self.work_size, cum, add_static_ui=True)
        gt = {i: cum[i] for i in range(len(cum))}
        rows_pred, sum_pred = bme.evaluate_method("lk_3dof_pred", frames, self.mask, gt_poses=gt)
        _, sum_plain = bme.evaluate_method("lk_3dof", frames, self.mask, gt_poses=gt)
        self.assertEqual(sum_pred.success_rate, 1.0, [r.reason for r in rows_pred])
        self.assertLess(sum_pred.drift_max_3120_px, 8.0)
        self.assertGreater(sum_pred.success_rate, sum_plain.success_rate)

    def test_sift_pseudo_gt_matches_true_sequence(self):
        cum = [bme.make_3dof(1.0 + 0.03 * i, i * 10.0 - 0.03 * i * 390.0, i * -6.0 - 0.03 * i * 180.0) for i in range(5)]
        frames = synthesize_sequence(self.canvas, self.work_size, cum, add_static_ui=True)
        sift_gt = bme.compute_gt_from_sift(frames, self.mask)
        self.assertEqual(len(sift_gt), len(frames))
        for i in range(1, len(frames)):
            err = bme.pose_corner_error_3120(sift_gt[i], cum[i], *self.work_size)
            self.assertLess(err, 8.0, f"SIFT pseudo-GT frame {i} error={err}")

    def test_cli_end_to_end_on_dump_directory(self):
        cum = [bme.make_3dof(1.0, i * 10.0, i * -4.0) for i in range(5)]
        frames = synthesize_sequence(self.canvas, self.work_size, cum, add_static_ui=True)
        with tempfile.TemporaryDirectory() as tmp:
            dump_dir = Path(tmp) / "bigmap_dump_20261124_120000"
            dump_dir.mkdir()
            csv_path = dump_dir / "frames.csv"
            with csv_path.open("w", encoding="utf-8", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["index", "timestampNs", "width", "height", "generation", "screenMode", "dropped_before"])
                for i, f in enumerate(frames):
                    ts_ns = 1_000_000_000 + i * 33_333_333
                    bgr = cv2.cvtColor(f.gray, cv2.COLOR_GRAY2BGR)
                    cv2.imwrite(str(dump_dir / f"f_{i:06d}_{ts_ns}.png"), bgr)
                    w.writerow([i, ts_ns, f.orig_width, f.orig_height, 1, "big_map", 0])

            out_csv = Path(tmp) / "eval.csv"
            out_plot = Path(tmp) / "traj.png"
            rc = bme.main([
                str(dump_dir),
                "--work-height", "360",
                "--lk-predict",
                "--gt-sift",
                "--out-csv", str(out_csv),
                "--plot", str(out_plot),
            ])
            self.assertEqual(rc, 0)
            self.assertTrue(out_csv.is_file())
            self.assertTrue(out_plot.is_file())
            with out_csv.open("r", encoding="utf-8") as fh:
                rows = list(csv.DictReader(fh))
            # 5 methods * 4 steps = 20 rows
            self.assertEqual(len(rows), 20)
            plot_img = cv2.imread(str(out_plot))
            self.assertIsNotNone(plot_img)
            self.assertEqual(plot_img.shape[:2], (900, 1400))

    def test_burst_720p_gray_and_color_keyframe_layout(self):
        # Simulate new Stage 1 burst dump: 720p 8-bit gray f_*.png + c_000000_*.png + 11-col frames.csv.
        burst_size = (1560, 720)
        cum = [bme.make_3dof(1.0, i * 20.0, i * -8.0) for i in range(4)]
        frames = synthesize_sequence(self.canvas, burst_size, cum, add_static_ui=True)
        with tempfile.TemporaryDirectory() as tmp:
            dump_dir = Path(tmp) / "bigmap_dump_20261124_150000"
            dump_dir.mkdir()
            csv_path = dump_dir / "frames.csv"
            with csv_path.open("w", encoding="utf-8", newline="") as fh:
                w = csv.writer(fh)
                w.writerow([
                    "index", "timestampNs", "width", "height", "generation", "screenMode",
                    "dropped_before", "has_color", "source_width", "source_height", "scale",
                ])
                for i, f in enumerate(frames):
                    ts_ns = 2_000_000_000 + i * 16_666_667
                    # Write 8-bit 1-channel grayscale PNG
                    cv2.imwrite(str(dump_dir / f"f_{i:06d}_{ts_ns}.png"), f.gray)
                    has_color = 1 if i == 0 else 0
                    if has_color:
                        cv2.imwrite(str(dump_dir / f"c_{i:06d}_{ts_ns}.png"), cv2.cvtColor(f.gray, cv2.COLOR_GRAY2BGR))
                    w.writerow([i, ts_ns, 1560, 720, 1, "big_map", 0, has_color, 3120, 1440, "0.500000"])

            loaded = bme.load_frames_from_dump(dump_dir, work_height=360)
            self.assertEqual(len(loaded), 4)
            self.assertEqual(loaded[0].gray.shape, (360, 780))
            self.assertEqual((loaded[0].orig_width, loaded[0].orig_height), (1560, 720))
            self.assertEqual((loaded[0].source_width, loaded[0].source_height), (3120, 1440))
            self.assertAlmostEqual(loaded[0].capture_scale, 0.5, delta=1e-6)
            self.assertTrue(loaded[0].has_color)
            self.assertIsNotNone(loaded[0].color_path)
            self.assertFalse(loaded[1].has_color)
            self.assertIsNone(loaded[1].color_path)

            # Verify 3120-scale metrics: 20 px/step at 1560x720 = 40 px/step in 3120 scale -> 120 px over 3 steps.
            mask = bme.bigmap_geometry_mask(780, 360)
            rows, summary = bme.evaluate_method("lk_3dof", loaded, mask)
            self.assertEqual(summary.success_rate, 1.0)
            expected_shift = math.hypot(3 * 40.0, 3 * -16.0)
            self.assertAlmostEqual(summary.total_shift_3120_px, expected_shift, delta=2.0)

            # Without frames.csv, c_*.png must not be double-counted as separate frames.
            csv_path.unlink()
            loaded_nocsv = bme.load_frames_from_dump(dump_dir, work_height=360)
            self.assertEqual(len(loaded_nocsv), 4)
            self.assertTrue(loaded_nocsv[0].has_color)

    def test_video_loader_reads_frames_and_respects_stride(self):
        cum = [bme.make_3dof(1.0, i * 6.0, 0.0) for i in range(6)]
        frames = synthesize_sequence(self.canvas, (390, 180), cum, add_static_ui=False)
        with tempfile.TemporaryDirectory() as tmp:
            vid = Path(tmp) / "clip.avi"
            writer = cv2.VideoWriter(str(vid), cv2.VideoWriter_fourcc(*"MJPG"), 30.0, (390, 180))
            for f in frames:
                writer.write(cv2.cvtColor(f.gray, cv2.COLOR_GRAY2BGR))
            writer.release()
            loaded = bme.load_frames_from_video(vid, work_height=180, stride=2)
            self.assertEqual(len(loaded), 3)
            self.assertEqual([f.index for f in loaded], [0, 2, 4])
            self.assertAlmostEqual(loaded[1].timestamp_s, 2.0 / 30.0, delta=1e-4)


if __name__ == "__main__":
    unittest.main()
