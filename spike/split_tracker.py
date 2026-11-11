"""Separate canonical position from active-layer recognition (experimental, SIFT not SURF).

Shared geometry and cvAutoTrack-inspired retry policy: feature_tracker.py / THIRD_PARTY_NOTICES.md.
A known position is NOT permission to render layer markers. No labels/timestamps are inputs.
"""
from dataclasses import dataclass, field
import json
import math
from pathlib import Path

import cv2
import numpy as np

from feature_tracker import FeatureQuery, FeatureReference
from minimap_match import Ref


@dataclass
class PositionFix:
    space: str
    x: float
    y: float
    scale: float
    rotation: float
    support: float
    inliers: int
    error_px: float
    score: float


@dataclass
class LayerDecision:
    layer: str | None = None
    mode: str = "unknown"
    reason: str = "no position"
    error: float | None = None
    margin: float | None = None
    evidence: list[dict] = field(default_factory=list)


@dataclass(frozen=True)
class SplitGates:
    min_inliers: int = 6
    min_inlier_fraction: float = 0.35
    min_scene_support: float = 0.10
    max_error_px: float = 2.0
    position_margin: float = 0.10
    min_layer_pixels: int = 256
    min_active_pixels: int = 128
    max_layer_error: float = 20.0
    min_layer_margin: float = 5.0
    min_layer_correlation: float = 0.4


class LayerModel:
    def __init__(self, name, prefix):
        self.ref = Ref(name, prefix)
        self.features = FeatureReference(self.ref)
        meta = json.loads(Path(prefix + ".json").read_text())
        if not meta.get("layer") or "/" not in meta.get("map_id", ""):
            raise ValueError("use compose_layer_refs.py references with explicit space/layer identity")
        self.layer = meta["layer"]
        self.space = meta["map_id"].split("/", 1)[0]
        if meta["map_id"] != f"{self.space}/{self.layer}":
            raise ValueError("inconsistent space/layer metadata")
        self.active = self._mask(prefix, meta["masks"]["active"])
        self.context = self._mask(prefix, meta["masks"]["context"])

    def _mask(self, prefix, name):
        if Path(name).name != name:
            raise ValueError("mask must be a sibling filename")
        image = cv2.imread(str(Path(prefix).parent / name), cv2.IMREAD_GRAYSCALE)
        if image is None or image.shape != self.ref.gray.shape:
            raise ValueError("missing or mismatched layer mask")
        return image


def pose_matrix(ref, mm, fix):
    """Native minimap crop pixels -> reference pixels, identical pose for ALL layer models."""
    angle = math.radians(fix.rotation)
    a, b = math.cos(angle) / (fix.scale * ref.upp), math.sin(angle) / (fix.scale * ref.upp)
    A = np.array([[a, -b], [b, a]], np.float64)
    center = np.array([(fix.x - ref.ox) / ref.upp, (fix.y - ref.oy) / ref.upp])
    return np.column_stack((A, center - A @ np.array([mm.lx, mm.ly])))


def warp(image, matrix, shape, mask=False):
    return cv2.warpAffine(image, matrix, shape[::-1], flags=cv2.WARP_INVERSE_MAP |
                         (cv2.INTER_NEAREST if mask else cv2.INTER_LINEAR))


def position_from_registration(model, reg, mm, gates):
    """Normalize support by visible SCENE geometry, not by total ring or active-floor size."""
    projected = warp(model.context, reg.matrix, mm.gray.shape, mask=True)
    scene = ((projected > 127) & (mm.mask > 0)).astype(np.uint8)
    footprint = cv2.findNonZero(scene)
    if footprint is None or len(footprint) < 3:
        return None
    total = cv2.contourArea(cv2.convexHull(footprint))
    if total <= 0:
        return None
    # Descriptor centers slightly outside an alpha boundary still describe that boundary.
    near_scene = cv2.dilate(scene, np.ones((5, 5), np.uint8))
    pts = reg.inlier_points
    coords = np.round(pts).astype(int)
    keep = ((coords[:, 0] >= 0) & (coords[:, 0] < scene.shape[1]) &
            (coords[:, 1] >= 0) & (coords[:, 1] < scene.shape[0]))
    coords = coords[keep]
    pts = pts[keep]
    pts = pts[near_scene[coords[:, 1], coords[:, 0]] > 0]
    if len(pts) < gates.min_inliers:
        return None
    support = min(1.0, cv2.contourArea(cv2.convexHull(pts)) / total)
    fraction = reg.inliers / reg.matches
    if (support < gates.min_scene_support or reg.error_px > gates.max_error_px
            or fraction < gates.min_inlier_fraction):
        return None
    score = 0.5 * support + 0.3 * fraction + 0.2 * math.exp(-reg.error_px)
    return PositionFix(model.space, reg.x, reg.y, reg.scale, reg.rotation,
                       support, reg.inliers, reg.error_px, score)


def choose_position(candidates, gates):
    """Different FLOOR names at the same pose are one position hypothesis, not one layer."""
    good = sorted(candidates, key=lambda c: -c.score)
    if not good:
        return None, "rejected"
    best = good[0]
    conflicting = [c for c in good[1:] if (c.space != best.space
                   or math.hypot(c.x - best.x, c.y - best.y) > 3
                   or abs(math.log(c.scale / best.scale)) > 0.03)]
    if conflicting and best.score - conflicting[0].score < gates.position_margin:
        return None, "ambiguous"
    return best, "candidate"


def appearance_errors(query, predictions, active_masks, mask, gates):
    """Compare only visible discriminating floor fragments, using ONE fixed geometric pose.

    Robust positive gain/offset compensates brightness, but never changes geometry. Equal
    weighting of mask-membership regions prevents a large neighbour dwarfing a small floor.
    No full-ring coverage threshold, per-region refitting or per-model geometric adjustment.
    """
    images = np.asarray(predictions, np.float64)
    footprints = np.asarray(active_masks, bool)
    if images.ndim != 3 or len(images) < 2 or len(images) > 8:
        return [], "need 2..8 layer models"
    informative = ((np.ptp(images, axis=0) > 8) & (mask > 0) &
                   np.any(footprints, axis=0) & (query < 210))
    if np.count_nonzero(informative) < gates.min_layer_pixels:
        return [], "no visible layer difference"
    # Membership bitsets make disjoint regions, including overlapping floor footprints.
    groups = sum(m.astype(np.uint16) * (1 << i) for i, m in enumerate(footprints))[informative]
    q = query[informative].astype(np.float64)
    regions = [groups == g for g in np.unique(groups) if np.count_nonzero(groups == g) >= 32]
    if len(regions) < 2:
        return [], "insufficient independent floor regions"
    result = []
    for image, active in zip(images, footprints):
        a = image[informative]
        A = np.column_stack((a, np.ones(len(a))))
        coef = np.linalg.lstsq(A, q, rcond=None)[0]
        for _ in range(4):
            residual = q - A @ coef
            weights = np.minimum(1, 12 / np.maximum(np.abs(residual), 1e-5))
            coef = np.linalg.lstsq(A * np.sqrt(weights[:, None]), q * np.sqrt(weights), rcond=None)[0]
            coef[0] = np.clip(coef[0], 0.2, 3.0)
            coef[1] = np.median(q - coef[0] * a)
        error = np.abs(q - A @ coef)
        group_error = float(np.mean([np.median(error[r]) for r in regions]))
        correlation = float(np.corrcoef(a, q)[0, 1]) if np.std(a) > 1 and np.std(q) > 1 else 0.0
        result.append(dict(error=group_error, correlation=correlation,
                           active_pixels=int(np.count_nonzero(active & informative)),
                           gain=float(coef[0]), offset=float(coef[1])))
    return result, "scored"


def classify_layer(mm, fix, models, gates):
    available = [m for m in models if m.space == fix.space]
    if not available:
        return LayerDecision(reason="no models for position space")
    images, footprints = [], []
    for model in available:
        M = pose_matrix(model.ref, mm, fix)
        images.append(warp(model.ref.gray, M, mm.gray.shape))
        footprints.append(warp(model.active, M, mm.gray.shape, mask=True) > 127)
    evidence, reason = appearance_errors(mm.gray, images, footprints, mm.mask, gates)
    if not evidence:
        return LayerDecision(reason=reason)
    for d, model in zip(evidence, available):
        d["layer"] = model.layer
    evidence.sort(key=lambda d: d["error"])
    best, runner = evidence[:2]
    margin = runner["error"] - best["error"]
    if (best["error"] > gates.max_layer_error or margin < gates.min_layer_margin
            or best["correlation"] < gates.min_layer_correlation
            or best["active_pixels"] < gates.min_active_pixels):
        return LayerDecision(reason="weak or ambiguous floor appearance", error=best["error"],
                             margin=margin, evidence=evidence)
    return LayerDecision(best["layer"], "candidate", "appearance", best["error"], margin, evidence)


class LayerLatch:
    """An unknown observation immediately hides the previous layer, never reuses stale labels."""
    def __init__(self):
        self.last = None

    def reset(self):
        self.last = None

    def update(self, decision, t):
        if decision.layer is None:
            self.reset()
            return decision
        confirmed = (self.last is not None and self.last[0] == decision.layer
                     and 0 < t - self.last[1] <= 2)
        self.last = decision.layer, t
        if confirmed:
            decision.mode = "known"
        else:
            decision.layer, decision.mode = None, "pending"
            decision.reason = "waiting for second consistent layer observation"
        return decision


class SplitTracker:
    def __init__(self, models, unit, gates=None):
        self.models, self.unit, self.gates = models, unit, gates or SplitGates()
        self.layer_latch = LayerLatch()
        self.reset()

    def reset(self):
        self.hint = self.last_t = self.pending = None
        self.failures = 0
        self.layer_latch.reset()

    def update(self, mm, t):
        dt = t - self.last_t if self.last_t is not None else 0
        if dt > 2:
            self.hint = None
        query = FeatureQuery(mm)
        radius = max(30, 35 * max(0, dt))
        search = "local" if self.hint is not None else "global"
        positions = []
        for model in self.models:
            if self.hint and model.space != self.hint.space:
                continue
            hint = (self.hint.x, self.hint.y, self.hint.scale) if self.hint else None
            reg = model.features.register(query, self.unit, hint=hint, radius=radius)
            if reg is not None:
                p = position_from_registration(model, reg, mm, self.gates)
                if p is not None:
                    positions.append(p)
        best, mode = choose_position(positions, self.gates)
        if best is None:
            self.pending = None
            self.failures += 1
            if self.failures >= 3:
                self.hint = None
            self.layer_latch.reset()
            return None, mode, LayerDecision(reason="no confirmed position"), search, positions
        continuous = (self.hint is not None and best.space == self.hint.space
                      and math.hypot(best.x - self.hint.x, best.y - self.hint.y) <= radius)
        confirmed = False
        if self.pending is not None:
            prev, pt = self.pending
            confirmed = (best.space == prev.space and 0 < t - pt <= 2
                         and math.hypot(best.x - prev.x, best.y - prev.y) <= 35 * (t - pt) + 2)
        if not (continuous or confirmed):
            self.pending = best, t
            self.failures += 1
            if self.failures >= 3:
                self.hint = None
            self.layer_latch.reset()
            return None, "pending", LayerDecision(reason="position not confirmed"), search, positions
        self.hint, self.last_t, self.pending = best, t, None
        self.failures = 0
        decision = self.layer_latch.update(classify_layer(mm, best, self.models, self.gates), t)
        return best, "track" if continuous else "found", decision, search, positions
