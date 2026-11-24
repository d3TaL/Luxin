"""Offline evaluation of big-map frame-to-frame motion estimators (Stage 2).

Compares four methods on either a diagnostic frame dump directory (PNG + frames.csv)
or a screen-recording video:
  1. orb_prev     - ORB + BFMatcher knn (ratio 0.75) + estimateAffinePartial2D (prev -> curr)
  2. lk_3dof      - Pyramidal Lucas-Kanade + forward-backward check + 3-DOF (s, tx, ty) RANSAC
  3. phase_corr   - Hanning-windowed phase correlation (translation only, reference)
  4. fixed_anchor - Current baseline: ORB against a fixed anchor with re-anchoring on failures
  (Optional: lk_3dof_pred - LK 3-DOF with constant-velocity initial flow prediction.)

Usage:
  /tmp/opencode/luxin-venv/bin/python -I spike/bigmap_motion_eval.py <dump_dir_or_video>
      [--work-height 360] [--methods orb_prev,lk_3dof,phase_corr,fixed_anchor]
      [--lk-predict] [--out-csv out.csv] [--plot out.png]
      [--gt-sift | --gt-json poses.json | --gt-tracker-bin BIN --gt-ref PNG SPACE UPP OX OY ...]
"""
from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from video_time import VideoClock  # noqa: E402


# Ported verbatim from tracker/src/bigmap_geometry.cpp (bigMapGeometryMask, 3120x1440 space).
UI_MASK_BOXES_3120: Tuple[Tuple[int, int, int, int], ...] = (
    (0, 0, 760, 520),
    (140, 510, 120, 420),
    (1460, 0, 1660, 140),
    (2020, 0, 1100, 1440),
    (1190, 1270, 1930, 170),
    (140, 1270, 140, 140),
)

# Largest clean interior map rectangle free of all UI_MASK_BOXES_3120 boxes:
# x in [760, 2020], y in [140, 1270] in 3120x1440 reference space.
PHASE_CORR_ROI_3120: Tuple[int, int, int, int] = (760, 140, 1260, 1130)

DEFAULT_METHODS: Tuple[str, ...] = ("orb_prev", "lk_3dof", "phase_corr", "fixed_anchor")
ALL_METHODS: Tuple[str, ...] = ("orb_prev", "lk_3dof", "lk_3dof_pred", "phase_corr", "fixed_anchor")


def bigmap_geometry_mask(width: int, height: int) -> np.ndarray:
    """Port of luxin::bigMapGeometryMask(cv::Size) from tracker/src/bigmap_geometry.cpp."""
    if width <= 0 or height <= 0:
        raise ValueError("mask dimensions must be positive")
    mask = np.full((height, width), 255, dtype=np.uint8)
    sx = width / 3120.0
    sy = height / 1440.0
    for bx, by, bw, bh in UI_MASK_BOXES_3120:
        x0 = max(0, min(width, int(math.floor(bx * sx))))
        y0 = max(0, min(height, int(math.floor(by * sy))))
        x1 = max(0, min(width, int(math.ceil((bx + bw) * sx))))
        y1 = max(0, min(height, int(math.ceil((by + bh) * sy))))
        if x1 > x0 and y1 > y0:
            mask[y0:y1, x0:x1] = 0
    return mask


def preprocess_frame(image: np.ndarray, work_height: int) -> np.ndarray:
    """Convert to grayscale and downscale to at most work_height (INTER_AREA)."""
    if image is None or image.size == 0:
        raise ValueError("empty frame")
    if image.ndim == 3:
        if image.shape[2] == 4:
            gray = cv2.cvtColor(image, cv2.COLOR_BGRA2GRAY)
        elif image.shape[2] == 3:
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        else:
            gray = image[:, :, 0]
    else:
        gray = image
    h, w = gray.shape[:2]
    if work_height > 0 and h > work_height:
        scale = work_height / float(h)
        new_w = max(1, int(round(w * scale)))
        new_h = max(1, int(round(h * scale)))
        return cv2.resize(gray, (new_w, new_h), interpolation=cv2.INTER_AREA)
    return gray.copy()


def to_3x3(mat2x3: np.ndarray) -> np.ndarray:
    m = np.eye(3, dtype=np.float64)
    m[:2, :] = np.asarray(mat2x3, dtype=np.float64)
    return m


def compose_affine(second: np.ndarray, first: np.ndarray) -> np.ndarray:
    """Compose two 2x3 affines: returns second(first(p))."""
    return (to_3x3(second) @ to_3x3(first))[:2, :]


def invert_affine(mat2x3: np.ndarray) -> Optional[np.ndarray]:
    m = to_3x3(mat2x3)
    det = m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]
    if not math.isfinite(det) or abs(det) <= 1e-12:
        return None
    return np.linalg.inv(m)[:2, :]


def make_3dof(scale: float, tx: float, ty: float) -> np.ndarray:
    return np.array([[scale, 0.0, tx], [0.0, scale, ty]], dtype=np.float64)


def decompose_similarity(mat2x3: np.ndarray) -> Tuple[float, float, float, float]:
    """Return (scale, angle_deg, tx, ty) for a 2x3 similarity/affine matrix."""
    a = float(mat2x3[0, 0])
    b = float(mat2x3[1, 0])
    scale = math.hypot(a, b)
    angle_deg = math.degrees(math.atan2(b, a))
    return scale, angle_deg, float(mat2x3[0, 2]), float(mat2x3[1, 2])


def pose_corner_error_3120(
    est: np.ndarray, ref: np.ndarray, width: int, height: int
) -> float:
    """Max corner displacement of (ref * est^-1) in 3120-width reference pixels."""
    inv_est = invert_affine(est)
    if inv_est is None:
        return float("nan")
    err_mat = compose_affine(ref, inv_est)
    corners = np.array(
        [[0.0, 0.0], [float(width), 0.0], [0.0, float(height)], [float(width), float(height)]],
        dtype=np.float64,
    )
    warped = corners @ err_mat[:, :2].T + err_mat[:, 2]
    diff = warped - corners
    max_work_px = float(np.max(np.hypot(diff[:, 0], diff[:, 1])))
    return max_work_px * (3120.0 / float(width))


def transform_center_shift_3120(mat2x3: np.ndarray, width: int, height: int) -> Tuple[float, float]:
    """Displacement of the frame center under mat2x3, expressed in 3120-width pixels."""
    cx, cy = width * 0.5, height * 0.5
    nx = mat2x3[0, 0] * cx + mat2x3[0, 1] * cy + mat2x3[0, 2]
    ny = mat2x3[1, 0] * cx + mat2x3[1, 1] * cy + mat2x3[1, 2]
    k = 3120.0 / float(width)
    return float((nx - cx) * k), float((ny - cy) * k)


@dataclass
class FrameItem:
    index: int
    timestamp_s: float
    gray: np.ndarray
    orig_width: int   # actual PNG / video frame width on disk
    orig_height: int  # actual PNG / video frame height on disk
    path: Optional[Path] = None
    screen_mode: str = "unknown"
    dropped_before: int = 0
    has_color: bool = False
    color_path: Optional[Path] = None
    source_width: int = 0   # original capture width before 720p burst downscale (0 -> orig_width)
    source_height: int = 0  # original capture height before 720p burst downscale (0 -> orig_height)
    capture_scale: float = 1.0


@dataclass
class StepEstimate:
    ok: bool
    matrix: Optional[np.ndarray] = None  # 2x3 prev -> curr (or anchor -> curr for fixed_anchor)
    inliers: int = 0
    matches: int = 0
    error_px: float = float("nan")
    reason: str = "ok"


@dataclass
class FrameEvalRow:
    method: str
    frame_idx: int
    timestamp_s: float
    ok: bool
    step_ms: float
    inliers: int
    matches: int
    scale: float
    tx_3120: float
    ty_3120: float
    cum_scale: float
    cum_tx_3120: float
    cum_ty_3120: float
    drift_3120_px: float
    dropout_streak: int
    reason: str


@dataclass
class MethodSummary:
    method: str
    total_steps: int
    ok_steps: int
    success_rate: float
    mean_ms: float
    p95_ms: float
    max_dropout: int
    drift_end_3120_px: float
    drift_max_3120_px: float
    closure_mean_3120_px: float
    closure_p95_3120_px: float
    fb_mean_3120_px: float
    scale_min: float
    scale_max: float
    total_shift_3120_px: float


# ---------------------------------------------------------------------------
# 3-DOF RANSAC solver
# ---------------------------------------------------------------------------

def estimate_3dof_ransac(
    src: np.ndarray,
    dst: np.ndarray,
    reproj_thresh: float = 2.0,
    max_iters: int = 300,
    min_inliers: int = 12,
    min_inlier_ratio: float = 0.35,
    scale_bounds: Tuple[float, float] = (0.5, 2.0),
    min_pair_dist: float = 5.0,
    seed: int = 0,
) -> Tuple[Optional[np.ndarray], np.ndarray, float, str]:
    """Estimate q = s * p + t via vectorized 2-point RANSAC + least-squares refit."""
    src = np.asarray(src, dtype=np.float64).reshape(-1, 2)
    dst = np.asarray(dst, dtype=np.float64).reshape(-1, 2)
    n = len(src)
    empty_mask = np.zeros(n, dtype=bool)
    if n < max(2, min_inliers):
        return None, empty_mask, float("nan"), "insufficient_points"

    rng = np.random.default_rng(seed)
    idx_i = rng.integers(0, n, size=max_iters)
    idx_j = (idx_i + rng.integers(1, n, size=max_iters)) % n

    dp = src[idx_j] - src[idx_i]
    dq = dst[idx_j] - dst[idx_i]
    denom = np.sum(dp * dp, axis=1)
    valid = denom >= (min_pair_dist * min_pair_dist)
    if not np.any(valid):
        return None, empty_mask, float("nan"), "degenerate_pairs"

    idx_i = idx_i[valid]
    idx_j = idx_j[valid]
    dp = dp[valid]
    dq = dq[valid]
    denom = denom[valid]

    s = np.sum(dp * dq, axis=1) / denom
    s_ok = np.isfinite(s) & (s >= scale_bounds[0]) & (s <= scale_bounds[1])
    if not np.any(s_ok):
        return None, empty_mask, float("nan"), "scale_bounds"

    idx_i = idx_i[s_ok]
    idx_j = idx_j[s_ok]
    s = s[s_ok]

    tx = 0.5 * ((dst[idx_i, 0] + dst[idx_j, 0]) - s * (src[idx_i, 0] + src[idx_j, 0]))
    ty = 0.5 * ((dst[idx_i, 1] + dst[idx_j, 1]) - s * (src[idx_i, 1] + src[idx_j, 1]))

    rx = s[:, None] * src[None, :, 0] + tx[:, None] - dst[None, :, 0]
    ry = s[:, None] * src[None, :, 1] + ty[:, None] - dst[None, :, 1]
    err2 = rx * rx + ry * ry
    thresh2 = reproj_thresh * reproj_thresh
    inlier_matrix = err2 <= thresh2
    counts = np.sum(inlier_matrix, axis=1)
    best_count = int(np.max(counts))
    if best_count < min_inliers:
        return None, empty_mask, float("nan"), "insufficient_inliers"

    # Among models tying best_count, pick lowest sum of squared inlier residuals.
    candidates = np.flatnonzero(counts == best_count)
    best_idx = int(
        candidates[
            int(
                np.argmin(
                    [float(np.sum(err2[c, inlier_matrix[c]])) for c in candidates]
                )
            )
        ]
    )
    mask = inlier_matrix[best_idx]

    # Closed-form LS refit + one re-threshold pass.
    for _ in range(2):
        if int(np.sum(mask)) < min_inliers:
            return None, empty_mask, float("nan"), "insufficient_inliers"
        p_in = src[mask]
        q_in = dst[mask]
        pm = np.mean(p_in, axis=0)
        qm = np.mean(q_in, axis=0)
        pc = p_in - pm
        qc = q_in - qm
        d = float(np.sum(pc * pc))
        if d <= 1e-9:
            return None, empty_mask, float("nan"), "degenerate_inliers"
        s_fit = float(np.sum(pc * qc) / d)
        if not math.isfinite(s_fit) or not (scale_bounds[0] <= s_fit <= scale_bounds[1]):
            return None, empty_mask, float("nan"), "scale_bounds"
        t_fit = qm - s_fit * pm
        res = np.hypot(s_fit * src[:, 0] + t_fit[0] - dst[:, 0], s_fit * src[:, 1] + t_fit[1] - dst[:, 1])
        mask = res <= reproj_thresh

    inlier_count = int(np.sum(mask))
    if inlier_count < min_inliers:
        return None, mask, float("nan"), "insufficient_inliers"
    if inlier_count / float(n) < min_inlier_ratio:
        return None, mask, float("nan"), "inlier_ratio"

    mean_err = float(np.mean(res[mask]))
    if not math.isfinite(mean_err) or mean_err > reproj_thresh:
        return None, mask, mean_err, "reprojection_error"

    return make_3dof(s_fit, float(t_fit[0]), float(t_fit[1])), mask, mean_err, "ok"


# ---------------------------------------------------------------------------
# Motion estimators
# ---------------------------------------------------------------------------

def _match_orb_descriptors(
    kp1: Sequence[cv2.KeyPoint],
    desc1: Optional[np.ndarray],
    kp2: Sequence[cv2.KeyPoint],
    desc2: Optional[np.ndarray],
    ratio: float = 0.75,
) -> Tuple[np.ndarray, np.ndarray]:
    if desc1 is None or desc2 is None or len(desc1) < 2 or len(desc2) < 2:
        return np.empty((0, 2), dtype=np.float32), np.empty((0, 2), dtype=np.float32)
    matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
    pairs = matcher.knnMatch(desc1, desc2, k=2)
    src_pts: List[Tuple[float, float]] = []
    dst_pts: List[Tuple[float, float]] = []
    used_train = set()
    for pair in pairs:
        if len(pair) != 2:
            continue
        m, n = pair
        if m.distance >= ratio * n.distance or m.trainIdx in used_train:
            continue
        used_train.add(m.trainIdx)
        src_pts.append(kp1[m.queryIdx].pt)
        dst_pts.append(kp2[m.trainIdx].pt)
    if not src_pts:
        return np.empty((0, 2), dtype=np.float32), np.empty((0, 2), dtype=np.float32)
    return np.asarray(src_pts, dtype=np.float32), np.asarray(dst_pts, dtype=np.float32)


def estimate_orb_step(
    prev_gray: np.ndarray,
    curr_gray: np.ndarray,
    mask: np.ndarray,
    prev_cache: Optional[Tuple[Sequence[cv2.KeyPoint], Optional[np.ndarray]]] = None,
    nfeatures: int = 1200,
    min_matches: int = 12,
    min_inliers: int = 10,
    min_inlier_ratio: float = 0.35,
    scale_bounds: Tuple[float, float] = (0.5, 2.0),
    max_angle_deg: float = 3.0,
    ransac_thresh: float = 2.0,
) -> Tuple[StepEstimate, Tuple[Sequence[cv2.KeyPoint], Optional[np.ndarray]]]:
    orb = cv2.ORB_create(nfeatures=nfeatures)
    if prev_cache is None:
        kp1, desc1 = orb.detectAndCompute(prev_gray, mask)
    else:
        kp1, desc1 = prev_cache
    kp2, desc2 = orb.detectAndCompute(curr_gray, mask)
    curr_cache = (kp2, desc2)

    if desc1 is None or desc2 is None or len(desc1) < 2 or len(desc2) < 2:
        return StepEstimate(False, reason="insufficient_descriptors"), curr_cache
    src, dst = _match_orb_descriptors(kp1, desc1, kp2, desc2, ratio=0.75)
    matches = len(src)
    if matches < min_matches:
        return StepEstimate(False, matches=matches, reason="insufficient_matches"), curr_cache

    mat, inliers_arr = cv2.estimateAffinePartial2D(
        src, dst, method=cv2.RANSAC, ransacReprojThreshold=ransac_thresh
    )
    if mat is None or inliers_arr is None or not np.all(np.isfinite(mat)):
        return StepEstimate(False, matches=matches, reason="invalid_transform"), curr_cache

    scale, angle_deg, _, _ = decompose_similarity(mat)
    if not (scale_bounds[0] <= scale <= scale_bounds[1]):
        return StepEstimate(False, matches=matches, reason="scale_bounds"), curr_cache
    if abs(angle_deg) > max_angle_deg:
        return StepEstimate(False, matches=matches, reason="rotation_bounds"), curr_cache

    inlier_mask = inliers_arr.ravel().astype(bool)
    inliers = int(np.sum(inlier_mask))
    if inliers < min_inliers:
        return StepEstimate(False, inliers=inliers, matches=matches, reason="insufficient_inliers"), curr_cache
    if inliers / float(matches) < min_inlier_ratio:
        return StepEstimate(False, inliers=inliers, matches=matches, reason="inlier_ratio"), curr_cache

    pred = src[inlier_mask] @ mat[:, :2].T + mat[:, 2]
    mean_err = float(np.mean(np.hypot(pred[:, 0] - dst[inlier_mask, 0], pred[:, 1] - dst[inlier_mask, 1])))
    if mean_err > ransac_thresh:
        return StepEstimate(False, inliers=inliers, matches=matches, error_px=mean_err, reason="reprojection_error"), curr_cache
    return StepEstimate(True, matrix=mat.astype(np.float64), inliers=inliers, matches=matches, error_px=mean_err, reason="ok"), curr_cache


def _predict_3dof_step(
    prev_mat: np.ndarray, dt_ratio: float, width: int, height: int
) -> np.ndarray:
    s_prev, _, tx_prev, ty_prev = decompose_similarity(prev_mat)
    if s_prev <= 0.1 or not math.isfinite(s_prev):
        return make_3dof(1.0, 0.0, 0.0)
    alpha = max(0.25, min(2.5, dt_ratio)) if math.isfinite(dt_ratio) and dt_ratio > 0 else 1.0
    cx, cy = width * 0.5, height * 0.5
    s_pred = s_prev ** alpha
    dcx = (s_prev - 1.0) * cx + tx_prev
    dcy = (s_prev - 1.0) * cy + ty_prev
    tx_pred = alpha * dcx - (s_pred - 1.0) * cx
    ty_pred = alpha * dcy - (s_pred - 1.0) * cy
    return make_3dof(s_pred, tx_pred, ty_pred)


def estimate_lk_3dof_step(
    prev_gray: np.ndarray,
    curr_gray: np.ndarray,
    mask: np.ndarray,
    max_corners: int = 250,
    fb_thresh: float = 1.5,
    reproj_thresh: float = 2.0,
    min_inliers: int = 12,
    init_transform: Optional[np.ndarray] = None,
    seed: int = 0,
) -> StepEstimate:
    h, w = prev_gray.shape[:2]
    win_size = 21
    # Erode feature mask by half the LK window so corners never sit on static UI edges.
    if int(np.count_nonzero(mask == 0)) > 0:
        pad = win_size // 2 + 1
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (2 * pad + 1, 2 * pad + 1))
        detect_mask = cv2.erode(mask, kernel, iterations=1, borderType=cv2.BORDER_CONSTANT, borderValue=0)
        if int(np.count_nonzero(detect_mask)) < 64:
            detect_mask = mask
    else:
        detect_mask = mask

    min_dist = max(6, h // 36)
    p0 = cv2.goodFeaturesToTrack(
        prev_gray,
        maxCorners=max_corners,
        qualityLevel=0.01,
        minDistance=min_dist,
        mask=detect_mask,
        blockSize=7,
    )
    if p0 is None or len(p0) < min_inliers:
        return StepEstimate(False, reason="insufficient_corners")

    base_levels = 3 if h >= 540 else 2
    criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01)

    def run_lk(pred_mat: Optional[np.ndarray]) -> StepEstimate:
        # Keep pyramid shallow when refining a predicted flow so coarse levels do not
        # pull corners back onto static UI chrome outside the mask.
        max_level = 2 if pred_mat is not None else base_levels
        lk_params = dict(winSize=(win_size, win_size), maxLevel=max_level, criteria=criteria)
        if pred_mat is not None:
            pts0 = p0.reshape(-1, 2).astype(np.float64)
            init_pts = (pts0 @ pred_mat[:, :2].T + pred_mat[:, 2]).astype(np.float32).reshape(-1, 1, 2)
            p1, st_fwd, _ = cv2.calcOpticalFlowPyrLK(
                prev_gray, curr_gray, p0, init_pts, flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk_params
            )
        else:
            p1, st_fwd, _ = cv2.calcOpticalFlowPyrLK(prev_gray, curr_gray, p0, None, **lk_params)
        if p1 is None or st_fwd is None:
            return StepEstimate(False, reason="lk_failed")

        inv_pred = invert_affine(pred_mat) if pred_mat is not None else None
        if inv_pred is not None:
            pts1 = p1.reshape(-1, 2).astype(np.float64)
            back_init = (pts1 @ inv_pred[:, :2].T + inv_pred[:, 2]).astype(np.float32).reshape(-1, 1, 2)
            p0_back, st_bwd, _ = cv2.calcOpticalFlowPyrLK(
                curr_gray, prev_gray, p1, back_init, flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk_params
            )
        else:
            p0_back, st_bwd, _ = cv2.calcOpticalFlowPyrLK(curr_gray, prev_gray, p1, None, **lk_params)
        if p0_back is None or st_bwd is None:
            return StepEstimate(False, reason="lk_back_failed")

        src_all = p0.reshape(-1, 2)
        dst_all = p1.reshape(-1, 2)
        back_all = p0_back.reshape(-1, 2)
        fb_err = np.hypot(back_all[:, 0] - src_all[:, 0], back_all[:, 1] - src_all[:, 1])

        ix = np.rint(dst_all[:, 0]).astype(np.int32)
        iy = np.rint(dst_all[:, 1]).astype(np.int32)
        in_bounds = (ix >= 0) & (ix < w) & (iy >= 0) & (iy < h)
        valid = (st_fwd.ravel() == 1) & (st_bwd.ravel() == 1) & in_bounds & (fb_err <= fb_thresh)
        if np.any(valid):
            valid_idx = np.flatnonzero(valid)
            in_mask = mask[iy[valid_idx], ix[valid_idx]] > 0
            keep = valid_idx[in_mask]
        else:
            keep = np.empty(0, dtype=np.int32)

        matches = len(keep)
        if matches < min_inliers:
            return StepEstimate(False, matches=matches, reason="insufficient_fb_matches")

        mat, inlier_mask, mean_err, reason = estimate_3dof_ransac(
            src_all[keep],
            dst_all[keep],
            reproj_thresh=reproj_thresh,
            min_inliers=min_inliers,
            seed=seed,
        )
        inliers = int(np.sum(inlier_mask))
        if mat is None:
            return StepEstimate(False, inliers=inliers, matches=matches, error_px=mean_err, reason=reason)
        return StepEstimate(True, matrix=mat, inliers=inliers, matches=matches, error_px=mean_err, reason="ok")

    res = run_lk(init_transform)
    if not res.ok and init_transform is not None:
        # Sudden stop / direction reversal fallback: retry with zero initial flow.
        res = run_lk(None)
    return res


def _phase_corr_roi(width: int, height: int, mask: np.ndarray) -> Tuple[int, int, int, int]:
    """Return a clean interior (x0, y0, x1, y1) ROI avoiding UI mask edges."""
    if int(np.count_nonzero(mask == 0)) == 0:
        return 0, 0, width, height
    bx, by, bw, bh = PHASE_CORR_ROI_3120
    sx = width / 3120.0
    sy = height / 1440.0
    x0 = max(0, min(width - 1, int(math.ceil(bx * sx))))
    y0 = max(0, min(height - 1, int(math.ceil(by * sy))))
    x1 = max(x0 + 1, min(width, int(math.floor((bx + bw) * sx))))
    y1 = max(y0 + 1, min(height, int(math.floor((by + bh) * sy))))
    if (x1 - x0) < 32 or (y1 - y0) < 32:
        return 0, 0, width, height
    return x0, y0, x1, y1


def estimate_phase_corr_step(
    prev_gray: np.ndarray,
    curr_gray: np.ndarray,
    mask: np.ndarray,
    min_response: float = 0.08,
) -> StepEstimate:
    h, w = prev_gray.shape[:2]
    x0, y0, x1, y1 = _phase_corr_roi(w, h, mask)
    a = prev_gray[y0:y1, x0:x1].astype(np.float32)
    b = curr_gray[y0:y1, x0:x1].astype(np.float32)
    a -= float(np.mean(a))
    b -= float(np.mean(b))
    win = cv2.createHanningWindow((x1 - x0, y1 - y0), cv2.CV_32F)
    (dx, dy), response = cv2.phaseCorrelate(a, b, win)
    if not (math.isfinite(dx) and math.isfinite(dy) and math.isfinite(response)):
        return StepEstimate(False, reason="non_finite")
    if response < min_response:
        return StepEstimate(False, error_px=float(response), reason="low_response")
    # OpenCV phaseCorrelate(a, b) returns (dx, dy) where features move from p in a to p + (dx, dy) in b.
    return StepEstimate(True, matrix=make_3dof(1.0, float(dx), float(dy)), inliers=1, matches=1, error_px=float(response), reason="ok")


def _fixed_anchor_follow(
    anchor_kp: Sequence[cv2.KeyPoint],
    anchor_desc: Optional[np.ndarray],
    curr_gray: np.ndarray,
    mask: np.ndarray,
    orb: cv2.ORB,
) -> Tuple[StepEstimate, Tuple[Sequence[cv2.KeyPoint], Optional[np.ndarray]]]:
    """Port of current geometric + overlap gates in tracker/src/bigmap_session.cpp::follow."""
    kp, desc = orb.detectAndCompute(curr_gray, mask)
    cache = (kp, desc)
    if anchor_desc is None or desc is None or len(anchor_desc) < 2 or len(desc) < 2:
        return StepEstimate(False, reason="insufficient_descriptors"), cache
    src, dst = _match_orb_descriptors(anchor_kp, anchor_desc, kp, desc, ratio=0.75)
    matches = len(src)
    if matches < 24:
        return StepEstimate(False, matches=matches, reason="insufficient_matches"), cache

    mat, inliers_arr = cv2.estimateAffinePartial2D(
        src, dst, method=cv2.RANSAC, ransacReprojThreshold=1.5
    )
    if mat is None or inliers_arr is None or not np.all(np.isfinite(mat)):
        return StepEstimate(False, matches=matches, reason="invalid_transform"), cache

    scale, angle_deg, _, _ = decompose_similarity(mat)
    if scale < 0.7 or scale > 1.4:
        return StepEstimate(False, matches=matches, reason="scale_bounds"), cache
    if abs(angle_deg) > 1.0:
        return StepEstimate(False, matches=matches, reason="rotation_bounds"), cache

    inlier_mask = inliers_arr.ravel().astype(bool)
    kept_dst = dst[inlier_mask]
    inliers = len(kept_dst)
    if inliers < 24:
        return StepEstimate(False, inliers=inliers, matches=matches, reason="insufficient_inliers"), cache
    if inliers < matches * 0.65:
        return StepEstimate(False, inliers=inliers, matches=matches, reason="inlier_ratio"), cache

    h, w = curr_gray.shape[:2]
    cells = {(int(px * 4 / w), int(py * 4 / h)) for px, py in kept_dst}
    if len(cells) < 4:
        return StepEstimate(False, inliers=inliers, matches=matches, reason="grid_support"), cache

    pred = src[inlier_mask] @ mat[:, :2].T + mat[:, 2]
    mean_err = float(np.mean(np.hypot(pred[:, 0] - kept_dst[:, 0], pred[:, 1] - kept_dst[:, 1])))
    if mean_err > 0.8:
        return StepEstimate(False, inliers=inliers, matches=matches, error_px=mean_err, reason="reprojection_error"), cache

    hull = cv2.convexHull(kept_dst.astype(np.float32))
    hull_area = float(cv2.contourArea(hull))
    if hull_area < (w * h) * 0.015:
        return StepEstimate(False, inliers=inliers, matches=matches, error_px=mean_err, reason="hull_support"), cache

    # Overlap gate mirroring bigMapFollowComparisonMask (eroded mask warped into current).
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    eroded = cv2.erode(mask, kernel, iterations=1, borderType=cv2.BORDER_CONSTANT, borderValue=0)
    support = eroded.astype(np.float32) * (1.0 / 255.0)
    warped_support = cv2.warpAffine(
        support, mat, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0.0
    )
    valid_overlap = (warped_support >= 1.0) & (eroded > 0)
    if int(np.count_nonzero(valid_overlap)) < (w * h) * 0.20:
        return StepEstimate(False, inliers=inliers, matches=matches, error_px=mean_err, reason="overlap"), cache

    return StepEstimate(True, matrix=mat.astype(np.float64), inliers=inliers, matches=matches, error_px=mean_err, reason="ok"), cache


# ---------------------------------------------------------------------------
# Reference / Ground-Truth pose providers
# ---------------------------------------------------------------------------

def parse_tracker_frames_output(stdout_text: str) -> Dict[int, np.ndarray]:
    """Parse stdout of tracker/tests/bigmap_geometry_frames into {0-based index: 2x3 matrix}."""
    poses: Dict[int, np.ndarray] = {}
    chunks = re.split(r"(?=\binput=\d+\b)", stdout_text)
    for chunk in chunks:
        m_idx = re.search(r"\binput=(\d+)\b", chunk)
        m_mode = re.search(r"\bmode=(\S+)", chunk)
        if not m_idx or not m_mode or m_mode.group(1) != "found":
            continue
        m_mat = re.search(r"matrix=\[\s*([^;\]]+);\s*([^\]]+)\]", chunk, flags=re.DOTALL)
        if not m_mat:
            continue
        row0 = [float(v) for v in re.split(r"[,\s]+", m_mat.group(1).strip()) if v]
        row1 = [float(v) for v in re.split(r"[,\s]+", m_mat.group(2).strip()) if v]
        if len(row0) == 3 and len(row1) == 3:
            poses[int(m_idx.group(1)) - 1] = np.array([row0, row1], dtype=np.float64)
    return poses


def compute_gt_from_tracker_binary(
    binary: Path,
    refs: Sequence[Sequence[str]],
    frames: Sequence[FrameItem],
) -> Dict[int, np.ndarray]:
    """Run bigmap_geometry_frames on saved PNG frames and convert to working-space M_{0->t}."""
    if not frames or any(f.path is None for f in frames):
        raise ValueError("--gt-tracker-bin requires frame files on disk (dump directory)")
    cmd: List[str] = [str(binary)]
    for ref in refs:
        cmd.append("--ref")
        cmd.extend(ref)
    cmd.append("--frames")
    cmd.extend(str(f.path) for f in frames)
    proc = subprocess.run(cmd, check=True, capture_output=True, text=True)
    abs_poses = parse_tracker_frames_output(proc.stdout)
    if 0 not in abs_poses:
        raise RuntimeError("Tracker binary failed to register frame 0; cannot form relative GT")

    work_h, work_w = frames[0].gray.shape[:2]
    sx = work_w / float(frames[0].orig_width)
    sy = work_h / float(frames[0].orig_height)
    scale_mat = np.array([[sx, 0.0, 0.0], [0.0, sy, 0.0], [0.0, 0.0, 1.0]], dtype=np.float64)
    inv_scale = np.array([[1.0 / sx, 0.0, 0.0], [0.0, 1.0 / sy, 0.0], [0.0, 0.0, 1.0]], dtype=np.float64)
    inv_a0 = invert_affine(abs_poses[0])
    if inv_a0 is None:
        raise RuntimeError("Singular frame-0 tracker matrix")
    inv_a0_3 = to_3x3(inv_a0)

    rel: Dict[int, np.ndarray] = {0: make_3dof(1.0, 0.0, 0.0)}
    for idx, abs_mat in abs_poses.items():
        orig_rel = to_3x3(abs_mat) @ inv_a0_3
        work_rel = (scale_mat @ orig_rel @ inv_scale)[:2, :]
        rel[idx] = work_rel
    return rel


def compute_gt_from_sift(
    frames: Sequence[FrameItem],
    mask: np.ndarray,
    nfeatures: int = 4000,
    min_overlap: float = 0.35,
) -> Dict[int, np.ndarray]:
    """High-quality sparse-keyframe SIFT reference poses M_{0->t} when tracker binary is absent."""
    if not frames:
        return {}
    sift = cv2.SIFT_create(nfeatures=nfeatures)
    bf = cv2.BFMatcher(cv2.NORM_L2)
    h, w = frames[0].gray.shape[:2]

    def match_sift(
        kp_a: Sequence[cv2.KeyPoint],
        desc_a: Optional[np.ndarray],
        kp_b: Sequence[cv2.KeyPoint],
        desc_b: Optional[np.ndarray],
    ) -> Tuple[Optional[np.ndarray], float]:
        if desc_a is None or desc_b is None or len(desc_a) < 2 or len(desc_b) < 2:
            return None, 0.0
        pairs = bf.knnMatch(desc_a, desc_b, k=2)
        src_pts, dst_pts = [], []
        used = set()
        for pair in pairs:
            if len(pair) != 2:
                continue
            m, n = pair
            if m.distance < 0.70 * n.distance and m.trainIdx not in used:
                used.add(m.trainIdx)
                src_pts.append(kp_a[m.queryIdx].pt)
                dst_pts.append(kp_b[m.trainIdx].pt)
        if len(src_pts) < 16:
            return None, 0.0
        src = np.asarray(src_pts, dtype=np.float32)
        dst = np.asarray(dst_pts, dtype=np.float32)
        mat, inliers = cv2.estimateAffinePartial2D(
            src, dst, method=cv2.RANSAC, ransacReprojThreshold=1.5
        )
        if mat is None or inliers is None or int(np.sum(inliers)) < 14:
            return None, 0.0
        warped = cv2.warpAffine(mask, mat, (w, h), flags=cv2.INTER_NEAREST)
        overlap = float(np.count_nonzero((warped > 0) & (mask > 0))) / float(max(1, np.count_nonzero(mask > 0)))
        return mat.astype(np.float64), overlap

    key_kp, key_desc = sift.detectAndCompute(frames[0].gray, mask)
    key_pose = make_3dof(1.0, 0.0, 0.0)
    poses: Dict[int, np.ndarray] = {0: key_pose}
    prev_kp, prev_desc, prev_pose = key_kp, key_desc, key_pose

    for i in range(1, len(frames)):
        kp, desc = sift.detectAndCompute(frames[i].gray, mask)
        mat, overlap = match_sift(key_kp, key_desc, kp, desc)
        if mat is not None:
            pose = compose_affine(mat, key_pose)
            poses[i] = pose
            if overlap < min_overlap:
                key_kp, key_desc, key_pose = kp, desc, pose
            prev_kp, prev_desc, prev_pose = kp, desc, pose
        else:
            # Fallback: chain from previous SIFT frame and promote it to new keyframe.
            mat_prev, _ = match_sift(prev_kp, prev_desc, kp, desc)
            if mat_prev is not None:
                pose = compose_affine(mat_prev, prev_pose)
                poses[i] = pose
                key_kp, key_desc, key_pose = kp, desc, pose
                prev_kp, prev_desc, prev_pose = kp, desc, pose
    return poses


def load_gt_json(path: Path) -> Dict[int, np.ndarray]:
    data = json.loads(path.read_text(encoding="utf-8"))
    poses: Dict[int, np.ndarray] = {}
    for key, mat in data.items():
        poses[int(key)] = np.asarray(mat, dtype=np.float64).reshape(2, 3)
    return poses


# ---------------------------------------------------------------------------
# Input loaders (dump directory or video)
# ---------------------------------------------------------------------------

_FRAME_NAME_RE = re.compile(r"^f_(\d+)_(\d+)\.png$")


def _resolve_indexed_png(dump_dir: Path, prefix: str, idx: int, ts_ns: int) -> Optional[Path]:
    cand_padded = dump_dir / f"{prefix}_{idx:06d}_{ts_ns}.png"
    if cand_padded.is_file():
        return cand_padded
    cand_plain = dump_dir / f"{prefix}_{idx}_{ts_ns}.png"
    if cand_plain.is_file():
        return cand_plain
    return None


def load_frames_from_dump(
    dump_dir: Path,
    work_height: int,
    max_frames: Optional[int] = None,
    stride: int = 1,
    screen_mode_filter: Optional[str] = None,
) -> List[FrameItem]:
    csv_path = dump_dir / "frames.csv"
    items: List[FrameItem] = []
    if csv_path.is_file():
        with csv_path.open("r", encoding="utf-8", newline="") as fh:
            reader = csv.DictReader(fh)
            rows = list(reader)
        if screen_mode_filter:
            rows = [r for r in rows if r.get("screenMode") == screen_mode_filter]
        rows = rows[:: max(1, stride)]
        if max_frames is not None:
            rows = rows[:max_frames]
        t0_ns: Optional[int] = None
        for row in rows:
            idx = int(row["index"])
            ts_ns = int(row["timestampNs"])
            if t0_ns is None:
                t0_ns = ts_ns
            img_path = _resolve_indexed_png(dump_dir, "f", idx, ts_ns)
            if img_path is None:
                continue
            img = cv2.imread(str(img_path), cv2.IMREAD_UNCHANGED)
            if img is None:
                continue
            orig_h, orig_w = img.shape[:2]
            gray = preprocess_frame(img, work_height)
            src_w = int(row["source_width"]) if row.get("source_width") else orig_w
            src_h = int(row["source_height"]) if row.get("source_height") else orig_h
            cap_scale = float(row["scale"]) if row.get("scale") else (orig_h / float(max(1, src_h)))
            color_path = _resolve_indexed_png(dump_dir, "c", idx, ts_ns)
            has_color = (row.get("has_color") in ("1", "true", "True")) or (color_path is not None)
            items.append(
                FrameItem(
                    index=idx,
                    timestamp_s=(ts_ns - t0_ns) / 1e9,
                    gray=gray,
                    orig_width=orig_w,
                    orig_height=orig_h,
                    path=img_path,
                    screen_mode=row.get("screenMode", "unknown"),
                    dropped_before=int(row.get("dropped_before", 0)),
                    has_color=has_color,
                    color_path=color_path,
                    source_width=src_w,
                    source_height=src_h,
                    capture_scale=cap_scale,
                )
            )
    else:
        all_pngs = sorted(dump_dir.glob("*.png"))
        frame_pngs = [p for p in all_pngs if _FRAME_NAME_RE.match(p.name)]
        if not frame_pngs:
            # Exclude color keyframes c_*.png when scanning an unindexed directory.
            frame_pngs = [p for p in all_pngs if not p.name.startswith("c_")]
        frame_pngs = frame_pngs[:: max(1, stride)]
        if max_frames is not None:
            frame_pngs = frame_pngs[:max_frames]
        t0_ns = None
        for i, img_path in enumerate(frame_pngs):
            img = cv2.imread(str(img_path), cv2.IMREAD_UNCHANGED)
            if img is None:
                continue
            orig_h, orig_w = img.shape[:2]
            m = _FRAME_NAME_RE.match(img_path.name)
            color_path = None
            if m:
                idx = int(m.group(1))
                ts_ns = int(m.group(2))
                if t0_ns is None:
                    t0_ns = ts_ns
                ts_s = (ts_ns - t0_ns) / 1e9
                color_path = _resolve_indexed_png(dump_dir, "c", idx, ts_ns)
            else:
                idx = i
                ts_s = i / 30.0
            items.append(
                FrameItem(
                    index=idx,
                    timestamp_s=ts_s,
                    gray=preprocess_frame(img, work_height),
                    orig_width=orig_w,
                    orig_height=orig_h,
                    path=img_path,
                    has_color=color_path is not None,
                    color_path=color_path,
                    source_width=orig_w,
                    source_height=orig_h,
                    capture_scale=1.0,
                )
            )
    return items


def load_frames_from_video(
    video_path: Path,
    work_height: int,
    use_pts: bool = False,
    start_s: float = 0.0,
    end_s: Optional[float] = None,
    max_frames: Optional[int] = None,
    stride: int = 1,
) -> List[FrameItem]:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise IOError(f"Cannot open video: {video_path}")
    try:
        fps = cap.get(cv2.CAP_PROP_FPS)
        if not math.isfinite(fps) or fps <= 0:
            fps = 30.0
        clock = VideoClock(fps, "pts" if use_pts else "fps")
        items: List[FrameItem] = []
        raw_idx = 0
        kept = 0
        t0: Optional[float] = None
        while cap.grab():
            t = clock.at(cap, raw_idx)
            raw_idx += 1
            if t + 1e-6 < start_s:
                continue
            if end_s is not None and t - 1e-6 > end_s:
                break
            if (kept % max(1, stride)) != 0:
                kept += 1
                continue
            kept += 1
            ok, frame = cap.retrieve()
            if not ok or frame is None:
                break
            if t0 is None:
                t0 = t
            orig_h, orig_w = frame.shape[:2]
            items.append(
                FrameItem(
                    index=raw_idx - 1,
                    timestamp_s=t - t0,
                    gray=preprocess_frame(frame, work_height),
                    orig_width=orig_w,
                    orig_height=orig_h,
                )
            )
            if max_frames is not None and len(items) >= max_frames:
                break
        return items
    finally:
        cap.release()


# ---------------------------------------------------------------------------
# Evaluation engine & self-consistency metrics
# ---------------------------------------------------------------------------

def _single_pair_estimate(
    method: str,
    gray_a: np.ndarray,
    gray_b: np.ndarray,
    mask: np.ndarray,
) -> StepEstimate:
    if method == "orb_prev":
        est, _ = estimate_orb_step(gray_a, gray_b, mask)
        return est
    if method in ("lk_3dof", "lk_3dof_pred"):
        return estimate_lk_3dof_step(gray_a, gray_b, mask)
    if method == "phase_corr":
        return estimate_phase_corr_step(gray_a, gray_b, mask)
    if method == "fixed_anchor":
        orb = cv2.ORB_create(nfeatures=1200)
        kp_a, desc_a = orb.detectAndCompute(gray_a, mask)
        est, _ = _fixed_anchor_follow(kp_a, desc_a, gray_b, mask, orb)
        return est
    raise ValueError(f"Unknown method: {method}")


def evaluate_method(
    method: str,
    frames: Sequence[FrameItem],
    mask: np.ndarray,
    gt_poses: Optional[Dict[int, np.ndarray]] = None,
    reanchor_after: int = 3,
    consistency_stride: Optional[int] = None,
) -> Tuple[List[FrameEvalRow], MethodSummary]:
    if len(frames) < 2:
        raise ValueError("Need at least 2 frames to evaluate motion")
    h, w = frames[0].gray.shape[:2]
    rows: List[FrameEvalRow] = []

    cum_pose = make_3dof(1.0, 0.0, 0.0)
    step_mats: Dict[int, np.ndarray] = {}  # t -> M_{(t-1)->t}
    step_times_ms: List[float] = []
    drifts: List[float] = []
    scales: List[float] = [1.0]
    dropout = 0
    max_dropout = 0
    ok_steps = 0

    # State per method
    orb_cache: Optional[Tuple[Sequence[cv2.KeyPoint], Optional[np.ndarray]]] = None
    prev_step_mat: Optional[np.ndarray] = None
    prev_dt: float = 0.0

    anchor_orb = cv2.ORB_create(nfeatures=1200) if method == "fixed_anchor" else None
    anchor_kp: Sequence[cv2.KeyPoint] = ()
    anchor_desc: Optional[np.ndarray] = None
    anchor_pose = make_3dof(1.0, 0.0, 0.0)
    consec_anchor_fail = 0
    if method == "fixed_anchor" and anchor_orb is not None:
        anchor_kp, anchor_desc = anchor_orb.detectAndCompute(frames[0].gray, mask)

    for i in range(1, len(frames)):
        prev_f = frames[i - 1]
        curr_f = frames[i]
        dt = curr_f.timestamp_s - prev_f.timestamp_s

        t_start = time.perf_counter()
        if method == "orb_prev":
            est, orb_cache = estimate_orb_step(prev_f.gray, curr_f.gray, mask, prev_cache=orb_cache)
        elif method == "lk_3dof":
            est = estimate_lk_3dof_step(prev_f.gray, curr_f.gray, mask)
        elif method == "lk_3dof_pred":
            init_mat = None
            if prev_step_mat is not None:
                dt_ratio = (dt / prev_dt) if (prev_dt > 1e-6 and dt > 1e-6) else 1.0
                init_mat = _predict_3dof_step(prev_step_mat, dt_ratio, w, h)
            est = estimate_lk_3dof_step(prev_f.gray, curr_f.gray, mask, init_transform=init_mat)
        elif method == "phase_corr":
            est = estimate_phase_corr_step(prev_f.gray, curr_f.gray, mask)
        elif method == "fixed_anchor":
            assert anchor_orb is not None
            est, curr_cache = _fixed_anchor_follow(anchor_kp, anchor_desc, curr_f.gray, mask, anchor_orb)
        else:
            raise ValueError(f"Unknown method: {method}")
        step_ms = (time.perf_counter() - t_start) * 1000.0
        step_times_ms.append(step_ms)

        step_scale = float("nan")
        step_tx_3120 = float("nan")
        step_ty_3120 = float("nan")
        reason = est.reason

        if method == "fixed_anchor":
            if est.ok and est.matrix is not None:
                consec_anchor_fail = 0
                prev_pose = cum_pose
                cum_pose = compose_affine(est.matrix, anchor_pose)
                inv_prev = invert_affine(prev_pose)
                if inv_prev is not None:
                    rel_step = compose_affine(cum_pose, inv_prev)
                    step_mats[i] = rel_step
                    step_scale, _, _, _ = decompose_similarity(rel_step)
                    step_tx_3120, step_ty_3120 = transform_center_shift_3120(rel_step, w, h)
                dropout = 0
                ok_steps += 1
            else:
                consec_anchor_fail += 1
                dropout += 1
                max_dropout = max(max_dropout, dropout)
                if consec_anchor_fail >= reanchor_after:
                    # Baseline reset + re-anchor on current frame (loses motion during dropout).
                    anchor_kp, anchor_desc = curr_cache
                    anchor_pose = cum_pose.copy()
                    consec_anchor_fail = 0
                    reason = f"reanchor:{est.reason}"
        else:
            if est.ok and est.matrix is not None:
                cum_pose = compose_affine(est.matrix, cum_pose)
                step_mats[i] = est.matrix
                prev_step_mat = est.matrix
                prev_dt = dt
                step_scale, _, _, _ = decompose_similarity(est.matrix)
                step_tx_3120, step_ty_3120 = transform_center_shift_3120(est.matrix, w, h)
                dropout = 0
                ok_steps += 1
            else:
                prev_step_mat = None
                dropout += 1
                max_dropout = max(max_dropout, dropout)

        cum_scale, _, _, _ = decompose_similarity(cum_pose)
        cum_tx_3120, cum_ty_3120 = transform_center_shift_3120(cum_pose, w, h)
        if math.isfinite(cum_scale):
            scales.append(cum_scale)

        drift = float("nan")
        if gt_poses is not None and i in gt_poses:
            drift = pose_corner_error_3120(cum_pose, gt_poses[i], w, h)
            if math.isfinite(drift):
                drifts.append(drift)

        rows.append(
            FrameEvalRow(
                method=method,
                frame_idx=curr_f.index,
                timestamp_s=curr_f.timestamp_s,
                ok=est.ok,
                step_ms=step_ms,
                inliers=est.inliers,
                matches=est.matches,
                scale=step_scale,
                tx_3120=step_tx_3120,
                ty_3120=step_ty_3120,
                cum_scale=cum_scale,
                cum_tx_3120=cum_tx_3120,
                cum_ty_3120=cum_ty_3120,
                drift_3120_px=drift,
                dropout_streak=dropout,
                reason=reason,
            )
        )

    # Self-consistency metrics (untimed, independent of GT):
    # 1) Triplet closure error: M(A->B) * M(B->C) vs direct M(A->C)
    # 2) Forward-backward step error: M(A->B) * M(B->A) vs identity
    c_stride = consistency_stride if consistency_stride is not None else (1 if len(frames) <= 30 else 5)
    c_stride = max(1, c_stride)
    closure_errors: List[float] = []
    fb_errors: List[float] = []
    identity = make_3dof(1.0, 0.0, 0.0)

    for i in range(1, len(frames), c_stride):
        if i in step_mats:
            rev = _single_pair_estimate(method, frames[i].gray, frames[i - 1].gray, mask)
            if rev.ok and rev.matrix is not None:
                roundtrip = compose_affine(rev.matrix, step_mats[i])
                err = pose_corner_error_3120(roundtrip, identity, w, h)
                if math.isfinite(err):
                    fb_errors.append(err)
        if i >= 2 and (i - 1) in step_mats and i in step_mats:
            direct = _single_pair_estimate(method, frames[i - 2].gray, frames[i].gray, mask)
            if direct.ok and direct.matrix is not None:
                chained = compose_affine(step_mats[i], step_mats[i - 1])
                err = pose_corner_error_3120(chained, direct.matrix, w, h)
                if math.isfinite(err):
                    closure_errors.append(err)

    total_steps = len(frames) - 1
    cum_tx_end, cum_ty_end = transform_center_shift_3120(cum_pose, w, h)
    summary = MethodSummary(
        method=method,
        total_steps=total_steps,
        ok_steps=ok_steps,
        success_rate=ok_steps / float(total_steps),
        mean_ms=float(np.mean(step_times_ms)),
        p95_ms=float(np.percentile(step_times_ms, 95)),
        max_dropout=max_dropout,
        drift_end_3120_px=drifts[-1] if drifts else float("nan"),
        drift_max_3120_px=max(drifts) if drifts else float("nan"),
        closure_mean_3120_px=float(np.mean(closure_errors)) if closure_errors else float("nan"),
        closure_p95_3120_px=float(np.percentile(closure_errors, 95)) if closure_errors else float("nan"),
        fb_mean_3120_px=float(np.mean(fb_errors)) if fb_errors else float("nan"),
        scale_min=min(scales),
        scale_max=max(scales),
        total_shift_3120_px=math.hypot(cum_tx_end, cum_ty_end),
    )
    return rows, summary


# ---------------------------------------------------------------------------
# Outputs: CSV, stdout summary, optional OpenCV PNG plot
# ---------------------------------------------------------------------------

CSV_FIELDS = (
    "method",
    "frame_idx",
    "timestamp_s",
    "ok",
    "step_ms",
    "inliers",
    "matches",
    "scale",
    "tx_3120",
    "ty_3120",
    "cum_scale",
    "cum_tx_3120",
    "cum_ty_3120",
    "drift_3120_px",
    "dropout_streak",
    "reason",
)


def write_eval_csv(path: Path, rows: Sequence[FrameEvalRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(CSV_FIELDS)
        for r in rows:
            writer.writerow(
                [
                    r.method,
                    r.frame_idx,
                    f"{r.timestamp_s:.6f}",
                    int(r.ok),
                    f"{r.step_ms:.3f}",
                    r.inliers,
                    r.matches,
                    "" if not math.isfinite(r.scale) else f"{r.scale:.6f}",
                    "" if not math.isfinite(r.tx_3120) else f"{r.tx_3120:.3f}",
                    "" if not math.isfinite(r.ty_3120) else f"{r.ty_3120:.3f}",
                    f"{r.cum_scale:.6f}",
                    f"{r.cum_tx_3120:.3f}",
                    f"{r.cum_ty_3120:.3f}",
                    "" if not math.isfinite(r.drift_3120_px) else f"{r.drift_3120_px:.3f}",
                    r.dropout_streak,
                    r.reason,
                ]
            )


def _fmt_opt(val: float, fmt: str = ".2f") -> str:
    return "-" if not math.isfinite(val) else format(val, fmt)


def format_summary_table(summaries: Sequence[MethodSummary]) -> str:
    headers = [
        "method",
        "ok%",
        "ms(avg/p95)",
        "max_drop",
        "drift_end/max(3120px)",
        "closure_avg/p95",
        "fb_avg",
        "scale_range",
        "shift_3120px",
    ]
    lines = []
    data = []
    for s in summaries:
        data.append(
            [
                s.method,
                f"{s.success_rate * 100.0:5.1f}%",
                f"{s.mean_ms:.2f}/{s.p95_ms:.2f}",
                str(s.max_dropout),
                f"{_fmt_opt(s.drift_end_3120_px)}/{_fmt_opt(s.drift_max_3120_px)}",
                f"{_fmt_opt(s.closure_mean_3120_px)}/{_fmt_opt(s.closure_p95_3120_px)}",
                _fmt_opt(s.fb_mean_3120_px),
                f"{s.scale_min:.3f}..{s.scale_max:.3f}",
                f"{s.total_shift_3120_px:.1f}",
            ]
        )
    widths = [max(len(headers[c]), *(len(row[c]) for row in data)) for c in range(len(headers))]
    fmt_row = lambda cols: "  ".join(cols[c].ljust(widths[c]) for c in range(len(cols)))
    lines.append(fmt_row(headers))
    lines.append("  ".join("-" * w for w in widths))
    for row in data:
        lines.append(fmt_row(row))
    return "\n".join(lines)


def render_trajectory_plot(
    path: Path,
    rows_by_method: Dict[str, List[FrameEvalRow]],
    gt_poses: Optional[Dict[int, np.ndarray]] = None,
    work_size: Optional[Tuple[int, int]] = None,
) -> None:
    """Render a 2x2 diagnostic chart (XY trajectory, scale, drift/dropout, step_ms) via OpenCV."""
    canvas_w, canvas_h = 1400, 900
    img = np.full((canvas_h, canvas_w, 3), 255, dtype=np.uint8)
    palette = [
        (31, 119, 180),
        (44, 160, 44),
        (214, 39, 40),
        (148, 103, 189),
        (255, 127, 14),
    ]
    colors = {m: palette[idx % len(palette)] for idx, m in enumerate(rows_by_method.keys())}

    panels = [
        ("Center trajectory (tx, ty in 3120px)", (60, 60, 650, 420)),
        ("Cumulative scale vs frame", (740, 60, 1340, 420)),
        ("Drift (3120px) vs frame", (60, 490, 650, 840)),
        ("Step time (ms) vs frame", (740, 490, 1340, 840)),
    ]
    for title, (x0, y0, x1, y1) in panels:
        cv2.rectangle(img, (x0, y0), (x1, y1), (200, 200, 200), 1)
        cv2.putText(img, title, (x0, y0 - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (30, 30, 30), 1, cv2.LINE_AA)

    # Legend at top
    lx = 60
    for m, col in colors.items():
        cv2.line(img, (lx, 22), (lx + 24, 22), col, 3)
        cv2.putText(img, m, (lx + 30, 27), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (20, 20, 20), 1, cv2.LINE_AA)
        lx += 160
    if gt_poses and work_size:
        cv2.line(img, (lx, 22), (lx + 24, 22), (0, 0, 0), 2)
        cv2.putText(img, "gt", (lx + 30, 27), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1, cv2.LINE_AA)

    def draw_series(
        rect: Tuple[int, int, int, int],
        series: Dict[str, List[Tuple[float, float]]],
        gt_series: Optional[List[Tuple[float, float]]] = None,
    ) -> None:
        all_pts = [pt for pts in series.values() for pt in pts if math.isfinite(pt[0]) and math.isfinite(pt[1])]
        if gt_series:
            all_pts.extend(pt for pt in gt_series if math.isfinite(pt[0]) and math.isfinite(pt[1]))
        if not all_pts:
            x0, y0, x1, y1 = rect
            cv2.putText(img, "no data", ((x0 + x1) // 2 - 35, (y0 + y1) // 2), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (140, 140, 140), 1)
            return
        xs = [p[0] for p in all_pts]
        ys = [p[1] for p in all_pts]
        min_x, max_x = min(xs), max(xs)
        min_y, max_y = min(ys), max(ys)
        if abs(max_x - min_x) < 1e-6:
            min_x -= 1.0
            max_x += 1.0
        if abs(max_y - min_y) < 1e-6:
            min_y -= 1.0
            max_y += 1.0
        x0, y0, x1, y1 = rect
        pad = 12

        def to_px(vx: float, vy: float) -> Tuple[int, int]:
            px = int(round(x0 + pad + (vx - min_x) / (max_x - min_x) * (x1 - x0 - 2 * pad)))
            py = int(round(y1 - pad - (vy - min_y) / (max_y - min_y) * (y1 - y0 - 2 * pad)))
            return px, py

        cv2.putText(img, f"{max_y:.1f}", (x0 + 4, y0 + 16), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (100, 100, 100), 1)
        cv2.putText(img, f"{min_y:.1f}", (x0 + 4, y1 - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (100, 100, 100), 1)

        if gt_series and len(gt_series) >= 2:
            pts = [to_px(vx, vy) for vx, vy in gt_series if math.isfinite(vx) and math.isfinite(vy)]
            for a, b in zip(pts, pts[1:]):
                cv2.line(img, a, b, (0, 0, 0), 2, cv2.LINE_AA)

        for m, data in series.items():
            pts = [to_px(vx, vy) for vx, vy in data if math.isfinite(vx) and math.isfinite(vy)]
            for a, b in zip(pts, pts[1:]):
                cv2.line(img, a, b, colors[m], 2, cv2.LINE_AA)

    gt_xy = None
    gt_scale = None
    if gt_poses and work_size:
        w, h = work_size
        sorted_k = sorted(gt_poses.keys())
        gt_xy = [transform_center_shift_3120(gt_poses[k], w, h) for k in sorted_k]
        gt_scale = [(float(k), decompose_similarity(gt_poses[k])[0]) for k in sorted_k]

    draw_series(
        panels[0][1],
        {m: [(0.0, 0.0)] + [(r.cum_tx_3120, r.cum_ty_3120) for r in rs] for m, rs in rows_by_method.items()},
        gt_series=gt_xy,
    )
    draw_series(
        panels[1][1],
        {m: [(float(i + 1), r.cum_scale) for i, r in enumerate(rs)] for m, rs in rows_by_method.items()},
        gt_series=gt_scale,
    )
    draw_series(
        panels[2][1],
        {m: [(float(i + 1), r.drift_3120_px) for i, r in enumerate(rs)] for m, rs in rows_by_method.items()},
    )
    draw_series(
        panels[3][1],
        {m: [(float(i + 1), r.step_ms) for i, r in enumerate(rs)] for m, rs in rows_by_method.items()},
    )

    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), img)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Offline evaluation of big-map motion estimators.")
    p.add_argument("input", type=Path, help="Dump directory (PNG + frames.csv) or video file.")
    p.add_argument("--work-height", type=int, default=360, help="Working frame height (e.g. 360 or 720).")
    p.add_argument(
        "--methods",
        default=",".join(DEFAULT_METHODS),
        help=f"Comma-separated methods from {','.join(ALL_METHODS)}.",
    )
    p.add_argument(
        "--lk-predict",
        action="store_true",
        help="Include lk_3dof_pred (constant-velocity initial guess for LK).",
    )
    p.add_argument("--no-mask", action="store_true", help="Disable bigMapGeometryMask (for maskless tests).")
    p.add_argument("--pts", action="store_true", help="Use video PTS timestamps instead of nominal FPS.")
    p.add_argument("--start", type=float, default=0.0, help="Video start time in seconds.")
    p.add_argument("--end", type=float, default=None, help="Video end time in seconds.")
    p.add_argument("--max-frames", type=int, default=None, help="Limit number of frames evaluated.")
    p.add_argument("--stride", type=int, default=1, help="Evaluate every N-th frame.")
    p.add_argument("--screen-mode", default=None, help="Filter dump frames.csv by screenMode (e.g. big_map).")
    p.add_argument("--reanchor-after", type=int, default=3, help="Consecutive failures before fixed_anchor re-anchors.")
    p.add_argument("--consistency-stride", type=int, default=None, help="Stride for closure & FB self-consistency checks.")
    p.add_argument("--gt-sift", action="store_true", help="Compute pseudo-GT via high-quality sparse SIFT keyframes.")
    p.add_argument("--gt-json", type=Path, default=None, help="Load reference poses M_{0->t} from JSON.")
    p.add_argument("--gt-tracker-bin", type=Path, default=None, help="Path to desktop bigmap_geometry_frames binary.")
    p.add_argument(
        "--gt-ref",
        nargs=5,
        action="append",
        metavar=("PNG", "SPACE", "UPP", "OX", "OY"),
        default=[],
        help="Reference argument tuple passed to --gt-tracker-bin (repeatable).",
    )
    p.add_argument("--out-csv", type=Path, default=None, help="Write per-frame metrics CSV.")
    p.add_argument("--plot", type=Path, default=None, help="Write PNG trajectory/metrics chart.")
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_arg_parser().parse_args(argv)
    inp: Path = args.input
    if inp.is_dir():
        frames = load_frames_from_dump(
            inp,
            work_height=args.work_height,
            max_frames=args.max_frames,
            stride=args.stride,
            screen_mode_filter=args.screen_mode,
        )
    elif inp.is_file():
        frames = load_frames_from_video(
            inp,
            work_height=args.work_height,
            use_pts=args.pts,
            start_s=args.start,
            end_s=args.end,
            max_frames=args.max_frames,
            stride=args.stride,
        )
    else:
        print(f"Input not found: {inp}", file=sys.stderr)
        return 2

    if len(frames) < 2:
        print(f"Need at least 2 frames, loaded {len(frames)}", file=sys.stderr)
        return 2

    h, w = frames[0].gray.shape[:2]
    mask = np.full((h, w), 255, dtype=np.uint8) if args.no_mask else bigmap_geometry_mask(w, h)

    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    if args.lk_predict and "lk_3dof_pred" not in methods:
        methods.append("lk_3dof_pred")
    for m in methods:
        if m not in ALL_METHODS:
            print(f"Unknown method: {m}. Valid: {', '.join(ALL_METHODS)}", file=sys.stderr)
            return 2

    gt_poses: Optional[Dict[int, np.ndarray]] = None
    if args.gt_json is not None:
        gt_poses = load_gt_json(args.gt_json)
    elif args.gt_tracker_bin is not None:
        if not args.gt_ref:
            print("--gt-tracker-bin requires at least one --gt-ref", file=sys.stderr)
            return 2
        gt_poses = compute_gt_from_tracker_binary(args.gt_tracker_bin, args.gt_ref, frames)
    elif args.gt_sift:
        gt_poses = compute_gt_from_sift(frames, mask)

    all_rows: List[FrameEvalRow] = []
    rows_by_method: Dict[str, List[FrameEvalRow]] = {}
    summaries: List[MethodSummary] = []

    for m in methods:
        rows, summary = evaluate_method(
            m,
            frames,
            mask,
            gt_poses=gt_poses,
            reanchor_after=args.reanchor_after,
            consistency_stride=args.consistency_stride,
        )
        all_rows.extend(rows)
        rows_by_method[m] = rows
        summaries.append(summary)

    src_w = frames[0].source_width or frames[0].orig_width
    src_h = frames[0].source_height or frames[0].orig_height
    color_kf = sum(1 for f in frames if f.has_color)
    print(
        f"Input: {inp} | frames={len(frames)} (color_kf={color_kf}) | "
        f"source={src_w}x{src_h} scale={frames[0].capture_scale:.3f} | work={w}x{h} | "
        f"mask={'off' if args.no_mask else 'bigMapGeometryMask'} | "
        f"gt={'yes(' + str(len(gt_poses)) + ')' if gt_poses else 'none'}"
    )
    print(format_summary_table(summaries))

    if args.out_csv is not None:
        write_eval_csv(args.out_csv, all_rows)
        print(f"CSV written: {args.out_csv}")
    if args.plot is not None:
        render_trajectory_plot(args.plot, rows_by_method, gt_poses=gt_poses, work_size=(w, h))
        print(f"Plot written: {args.plot}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
