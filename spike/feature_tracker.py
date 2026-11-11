"""Experimental SIFT tracker with spatial/patch support and explicit ambiguity refusal.

The global/local retry policy is adapted from cvAutoTrack 7.9.5 SurfMatch::match (MIT).
See THIRD_PARTY_NOTICES.md. Feature extraction, scoring and safety gates here are Luxin's
SIFT adaptation, NOT the original SURF tracker. Thresholds are experimental, not calibrated.
No game access: input is a Minimap crop and cached Kongying reference images.
"""
from dataclasses import dataclass, field
import math

import cv2
import numpy as np

from minimap_match import SIFT, grad


@dataclass(frozen=True)
class Gates:
    min_inliers: int = 8
    min_coverage: float = 0.10
    min_cells: int = 4
    max_error_px: float = 2.0
    min_patches: int = 4
    min_good_patches: int = 3
    min_good_fraction: float = 0.45
    patch_threshold: float = 0.45
    ambiguity_margin: float = 0.10


@dataclass
class Candidate:
    ref: str
    x: float
    y: float
    scale: float
    inliers: int
    matches: int
    coverage: float
    cells: int
    error_px: float
    patches: int
    good_patches: int
    patch_fraction: float
    patch_median: float
    score: float
    reasons: list[str] = field(default_factory=list)
    map_id: str = ""

    @property
    def valid(self):
        return not self.reasons


def spatial_support(points, mask, grid=4):
    """Convex hull / usable ring area, and occupied grid cells; points in native crop pixels."""
    pts = np.asarray(points, np.float32).reshape(-1, 2)
    area = cv2.countNonZero(mask)
    if len(pts) < 3 or area == 0:
        return 0.0, 0
    hull = min(1.0, cv2.contourArea(cv2.convexHull(pts)) / area)
    h, w = mask.shape
    xs = np.clip((pts[:, 0] * grid / w).astype(int), 0, grid - 1)
    ys = np.clip((pts[:, 1] * grid / h).astype(int), 0, grid - 1)
    return float(hull), len(set(zip(xs.tolist(), ys.tolist())))


def patch_support(query, rendered, mask, threshold=0.45, grid=4):
    """Equal-weight local gradient correlations; flat/unobserved patches are not evidence.

    No independent translation of patches is allowed: every patch uses the SAME estimated
    transform. A small matching fragment cannot win merely by generating many keypoints.
    """
    qg, rg = grad(query), grad(rendered)
    scores = []
    h, w = mask.shape
    for y0, y1 in zip(np.linspace(0, h, grid + 1, dtype=int)[:-1],
                      np.linspace(0, h, grid + 1, dtype=int)[1:]):
        for x0, x1 in zip(np.linspace(0, w, grid + 1, dtype=int)[:-1],
                          np.linspace(0, w, grid + 1, dtype=int)[1:]):
            use = mask[y0:y1, x0:x1] > 0
            if np.count_nonzero(use) < max(32, use.size * 0.25):
                continue
            q = qg[y0:y1, x0:x1][use].astype(np.float64)
            r = rg[y0:y1, x0:x1][use].astype(np.float64)
            q -= q.mean()
            r -= r.mean()
            # Flat in BOTH images is uninformative, not agreement. Texture missing in
            # only one image is negative evidence, otherwise a tiny shared fragment wins.
            q_flat, r_flat = np.std(q) < 5, np.std(r) < 5
            if q_flat and r_flat:
                continue
            if q_flat or r_flat:
                scores.append(0.0)
                continue
            score = np.dot(q, r) / math.sqrt(np.dot(q, q) * np.dot(r, r))
            scores.append(float(np.clip(score, -1, 1)))
    good = sum(s >= threshold for s in scores)
    fraction = good / len(scores) if scores else 0.0
    median = float(np.median(scores)) if scores else 0.0
    return len(scores), good, fraction, median


def apply_gates(c, gates):
    reasons = []
    if c.inliers < gates.min_inliers:
        reasons.append("few inliers")
    if c.coverage < gates.min_coverage or c.cells < gates.min_cells:
        reasons.append("clustered support")
    if c.error_px > gates.max_error_px:
        reasons.append("geometric error")
    if c.patches < gates.min_patches or c.good_patches < gates.min_good_patches:
        reasons.append("few agreeing patches")
    if c.patch_fraction < gates.min_good_fraction:
        reasons.append("patch disagreement")
    c.reasons = reasons
    return c


def choose(candidates, gates):
    """Return no fix for competing references, even if their coordinates agree.

    Coincident coordinates on different layers do NOT establish which markers to display.
    """
    good = sorted((c for c in candidates if c.valid), key=lambda c: -c.score)
    if not good:
        return None, "rejected"
    alternatives = [c for c in good[1:] if not (
        map_key(c) == map_key(good[0])
        and math.hypot(c.x - good[0].x, c.y - good[0].y) <= 3
        and abs(math.log(c.scale / good[0].scale)) <= 0.03)]
    if alternatives and good[0].score - alternatives[0].score < gates.ambiguity_margin:
        return None, "ambiguous"
    return good[0], "candidate"


def map_key(candidate):
    return candidate.map_id or f"unknown:{candidate.ref}"


@dataclass
class Registration:
    """Geometry only: contains NO claim about the active layer."""
    x: float
    y: float
    scale: float
    rotation: float
    inliers: int
    matches: int
    error_px: float
    matrix: np.ndarray = field(repr=False)
    inlier_points: np.ndarray = field(repr=False)


class FeatureQuery:
    def __init__(self, mm, up=2):
        self.mm = mm
        img = cv2.resize(mm.gray, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
        mask = cv2.resize(mm.mask, None, fx=up, fy=up, interpolation=cv2.INTER_NEAREST)
        kp, self.descriptors = SIFT.detectAndCompute(img, mask)
        self.points = np.asarray([k.pt for k in kp], np.float32).reshape(-1, 2) / up


class FeatureReference:
    def __init__(self, ref):
        self.ref = ref
        kp, self.descriptors = SIFT.detectAndCompute(ref.gray, None)
        self.points = np.asarray([k.pt for k in kp], np.float32).reshape(-1, 2)
        self.matcher = self._matcher(self.descriptors)

    @staticmethod
    def _matcher(descriptors):
        if descriptors is None or len(descriptors) < 2:
            return None
        matcher = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=64))
        matcher.add([descriptors])
        matcher.train()
        return matcher

    def register(self, query, unit, hint=None, radius=30):
        """Geometry only. hint=(x,y,screen_px_per_unit); ROI includes view + motion."""
        ref, mm = self.ref, query.mm
        if self.matcher is None or query.descriptors is None or len(query.points) < 6:
            return None
        points, matcher = self.points, self.matcher
        if hint is not None:
            x, y, scale = hint
            cx, cy = (x - ref.ox) / ref.upp, (y - ref.oy) / ref.upp
            r = (max(mm.gray.shape) / (2 * scale) + radius) / ref.upp
            keep = (np.abs(points[:, 0] - cx) <= r) & (np.abs(points[:, 1] - cy) <= r)
            matcher = self._matcher(self.descriptors[keep])
            points = points[keep]
            if matcher is None:
                return None
        pairs = matcher.knnMatch(query.descriptors, k=2)
        good = sorted((p[0] for p in pairs if len(p) == 2 and p[0].distance < 0.8 * p[1].distance),
                      key=lambda m: m.distance)
        # SIFT may describe multiple orientations at one location: do not inflate support.
        unique, used_q, used_r = [], set(), set()
        for m in good:
            qkey = tuple(np.round(query.points[m.queryIdx] * 2).astype(int))
            rkey = tuple(np.round(points[m.trainIdx] * ref.upp * 2).astype(int))
            if qkey not in used_q and rkey not in used_r:
                unique.append(m)
                used_q.add(qkey)
                used_r.add(rkey)
        if len(unique) < 6:
            return None
        src = np.float32([query.points[m.queryIdx] for m in unique])
        dst = np.float32([points[m.trainIdx] for m in unique])
        M, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC,
                                            ransacReprojThreshold=3 / ref.upp)
        if M is None or inl is None or not np.isfinite(M).all():
            return None
        size = math.hypot(M[0, 0], M[1, 0])
        if size < 1e-6:
            return None
        scale = 1 / (size * ref.upp)
        rotation = math.degrees(math.atan2(M[1, 0], M[0, 0]))
        if abs(rotation) > 2 or not 0.8 <= scale / unit <= 3.4:
            return None
        use = inl.ravel().astype(bool)
        if np.count_nonzero(use) < 6:
            return None
        center = M @ np.array([mm.lx, mm.ly, 1.0])
        x, y = center * ref.upp + (ref.ox, ref.oy)
        if hint is not None and math.hypot(x - hint[0], y - hint[1]) > radius:
            return None
        projected = src @ M[:, :2].T + M[:, 2]
        error = np.linalg.norm(projected[use] - dst[use], axis=1) / size
        return Registration(float(x), float(y), scale, rotation, int(use.sum()), len(unique),
                            float(np.median(error)), M, src[use])

    def locate(self, query, gates, unit, hint=None, radius=30):
        """Legacy joint decision retained for baseline comparisons."""
        reg = self.register(query, unit, hint, radius)
        if reg is None:
            return None
        ref, mm, M = self.ref, query.mm, reg.matrix
        coverage, cells = spatial_support(reg.inlier_points, mm.mask)
        shape = (mm.gray.shape[1], mm.gray.shape[0])
        rendered = cv2.warpAffine(ref.gray, M, shape, flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
        bounds = cv2.warpAffine(np.full(ref.gray.shape, 255, np.uint8), M, shape,
                                flags=cv2.INTER_NEAREST | cv2.WARP_INVERSE_MAP)
        valid = cv2.bitwise_and(mm.mask, cv2.erode(bounds, np.ones((5, 5), np.uint8)))
        patches, good_count, fraction, median = patch_support(mm.gray, rendered, valid, gates.patch_threshold)
        # Do not reward raw keypoint count: many may describe the same small shared doorway.
        score = 0.7 * fraction + 0.3 * max(0.0, median)
        candidate = Candidate(ref.name, reg.x, reg.y, reg.scale, reg.inliers, reg.matches,
                              coverage, cells, reg.error_px, patches, good_count,
                              fraction, median, score, map_id=getattr(ref, "map_id", f"unknown:{ref.name}"))
        return apply_gates(candidate, gates)


class FeatureTracker:
    """Global acquire -> local follow -> global retry, adapted from cvAutoTrack 7.9.5.

    Unlike its old-position-on-error API, every uncertain result here has NO visible fix.
    New acquisitions/layer changes require two temporally consistent candidates.
    """
    def __init__(self, refs, unit, gates=None, retries=3):
        self.refs = [FeatureReference(ref) for ref in refs]
        self.unit = unit
        self.gates = gates or Gates()
        self.retries = retries
        self.hint = None
        self.last_t = None
        self.failures = 0
        self.pending = None

    def reset(self):
        self.hint, self.last_t, self.pending = None, None, None
        self.failures = 0

    def update(self, mm, t):
        query = FeatureQuery(mm)
        dt = t - self.last_t if self.last_t is not None else 0
        radius = max(30, 35 * max(dt, 0))
        # A stale local prior is not allowed to grow into a world-size ROI.
        if dt > 2:
            self.hint = None
        search = "local" if self.hint is not None else "global"
        candidates = []
        for item in self.refs:
            hint = None if self.hint is None else (self.hint.x, self.hint.y, self.hint.scale)
            if hint and not item.ref.contains(hint[0], hint[1]):
                continue
            c = item.locate(query, self.gates, self.unit, hint=hint, radius=radius)
            if c is not None:
                candidates.append(c)
        best, mode = choose(candidates, self.gates)
        if best is None:
            self.pending = None
            self.failures += 1
            if self.failures >= self.retries:
                self.hint = None
            return None, mode, search, candidates
        continuous = (self.hint is not None and map_key(best) == map_key(self.hint)
                      and math.hypot(best.x - self.hint.x, best.y - self.hint.y) <= radius)
        confirmed = False
        if self.pending is not None:
            p, pt = self.pending
            elapsed = t - pt
            confirmed = (map_key(p) == map_key(best) and 0 < elapsed <= 2
                         and math.hypot(best.x - p.x, best.y - p.y) <= 35 * elapsed + 2)
        if continuous or confirmed:
            mode = "track" if continuous else "found"
            self.hint, self.last_t, self.pending = best, t, None
            self.failures = 0
            return best, mode, search, candidates
        self.pending = (best, t)
        # Do not keep an incompatible old local prior indefinitely during a new acquisition.
        self.failures += 1
        if self.failures >= self.retries:
            self.hint = None
        return None, "pending", search, candidates
