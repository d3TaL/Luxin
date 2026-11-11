"""Shared pieces for the minimap experiments: reference images, the minimap crop and
masked template matching on gradient magnitude.

Scripts in this directory are run with `python -I`, which leaves the script's directory off
sys.path; they add it themselves before importing this module.
"""
import json
import math
from pathlib import Path

import cv2
import numpy as np

RING = (399.5, 207.5, 176.0)  # ring centre and radius on a 1440 px high screen (measure_minimap.py)
BASE_SCALE = 0.75             # minimap px per marker unit on a 1440 px high screen at zoom x1
ZOOMS = (1, 2, 3)
ARROW_LO, ARROW_HI = (88, 150, 200), (102, 255, 255)  # HSV of the player arrow

SIFT = cv2.SIFT_create(nfeatures=0, contrastThreshold=0.02)


def geometry(frame_height):
    """Ring centre x, y, radius and the factor from 1440 px high screens."""
    k = frame_height / 1440.0
    return RING[0] * k, RING[1] * k, RING[2] * k, k


def arrow_pixels(frame, geom):
    """Number of arrow-coloured pixels in the middle of the minimap: zero when it is hidden."""
    cx, cy, _, k = geom
    a = int(round(26 * k))
    patch = frame[int(cy) - a:int(cy) + a + 1, int(cx) - a:int(cx) + a + 1]
    return cv2.countNonZero(cv2.inRange(cv2.cvtColor(patch, cv2.COLOR_BGR2HSV), ARROW_LO, ARROW_HI))


def grad(img):
    img = cv2.GaussianBlur(img.astype(np.float32), (0, 0), 1.0)
    return cv2.magnitude(cv2.Sobel(img, cv2.CV_32F, 1, 0), cv2.Sobel(img, cv2.CV_32F, 0, 1))


def ncc(image, templ, mask, floor=0.25):
    """Masked zero-mean normalised cross-correlation of templ at every position of image.

    cv2.matchTemplate(TM_CCOEFF_NORMED, mask=...) divides by the image deviation under the mask,
    so where the image is flat (a layer image is mostly plain background) it returns arbitrary
    values up to 0.6. Here the image deviation is not allowed to drop below `floor` times the
    template deviation, which sends such places to zero and leaves textured places unchanged.
    """
    shape = (image.shape[0] - templ.shape[0] + 1, image.shape[1] - templ.shape[1] + 1)
    n = float(mask.sum())
    if n < 1:
        return np.zeros(shape, np.float32)
    t = (templ - float((templ * mask).sum()) / n) * mask
    t_ss = float((t * t).sum())
    if t_ss <= 0:
        return np.zeros(shape, np.float32)
    num = cv2.matchTemplate(image, t, cv2.TM_CCORR)          # the template has zero mean under the mask
    s1 = cv2.matchTemplate(image, mask, cv2.TM_CCORR)
    s2 = cv2.matchTemplate(image * image, mask, cv2.TM_CCORR)
    i_ss = np.maximum(s2 - s1 * s1 / n, floor * floor * t_ss)
    return np.clip(num / np.sqrt(t_ss * i_ss), -1.0, 1.0)


def subpixel(score, loc):
    """Peak position refined by a parabola through the neighbours."""
    x, y = loc
    fx, fy = float(x), float(y)
    if 0 < x < score.shape[1] - 1:
        a, b, c = score[y, x - 1], score[y, x], score[y, x + 1]
        d = a - 2 * b + c
        if d < 0:
            fx += 0.5 * (a - c) / d
    if 0 < y < score.shape[0] - 1:
        a, b, c = score[y - 1, x], score[y, x], score[y + 1, x]
        d = a - 2 * b + c
        if d < 0:
            fy += 0.5 * (a - c) / d
    return fx, fy


class Ref:
    """Reference image made by fetch_ref.py or layer_ref.py (PREFIX.png + PREFIX.json)."""

    def __init__(self, name, prefix):
        meta = json.loads(Path(prefix + ".json").read_text())
        self.name = name
        self.bgr = cv2.imread(prefix + ".png")
        self.gray = cv2.cvtColor(self.bgr, cv2.COLOR_BGR2GRAY)
        self.upp = float(meta["units_per_px"])
        self.ox, self.oy = meta["origin"]
        self.h, self.w = self.gray.shape
        self.grad = grad(self.gray)
        self.coarse = {}
        self.sift = None

    def contains(self, mx, my):
        return 0 <= (mx - self.ox) / self.upp < self.w and 0 <= (my - self.oy) / self.upp < self.h

    def coarse_grad(self, g):
        key = round(g, 4)
        if key not in self.coarse:
            self.coarse[key] = grad(cv2.resize(self.gray, None, fx=g, fy=g, interpolation=cv2.INTER_AREA))
        return self.coarse[key]

    def sift_index(self):
        if self.sift is None:
            kp, des = SIFT.detectAndCompute(self.gray, None)
            matcher = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=64))
            matcher.add([des])
            matcher.train()
            self.sift = (kp, matcher)
        return self.sift


class Minimap:
    """The minimap circle cut out of one frame."""

    def __init__(self, frame, geom):
        cx, cy, r, k = geom
        pad = int(r) + 4
        x0, y0 = int(round(cx)) - pad, int(round(cy)) - pad
        self.gray = cv2.cvtColor(frame[y0:y0 + 2 * pad, x0:x0 + 2 * pad], cv2.COLOR_BGR2GRAY)
        self.lx, self.ly = cx - x0, cy - y0  # ring centre inside the crop
        self.mask = np.zeros(self.gray.shape, np.uint8)
        cv2.circle(self.mask, (int(self.lx), int(self.ly)), int(r - 6 * k), 255, -1)  # inside the ring
        cv2.circle(self.mask, (int(self.lx), int(self.ly)), int(24 * k), 0, -1)       # player arrow
        self.cache = {}

    def template(self, f):
        """Gradient template and mask with the crop resized by f."""
        key = round(f, 4)
        if key not in self.cache:
            h, w = self.gray.shape
            size = (max(int(round(w * f)), 8), max(int(round(h * f)), 8))
            interp = cv2.INTER_AREA if f < 1 else cv2.INTER_CUBIC
            templ = grad(cv2.resize(self.gray, size, interpolation=interp))
            mask = (cv2.resize(self.mask, size, interpolation=cv2.INTER_NEAREST) > 0).astype(np.float32)
            self.cache[key] = (templ, mask)
        return self.cache[key]


def local_search(ref, mm, scale, mx, my, radius):
    """Best match within `radius` marker units of (mx, my). Returns (peak, x, y) or None.

    scale is screen px per marker unit.
    """
    f = 1.0 / (scale * ref.upp)  # crop px -> reference px
    templ, mask = mm.template(f)
    th, tw = templ.shape
    s = int(math.ceil(radius / ref.upp))
    x0 = int(round((mx - ref.ox) / ref.upp - mm.lx * f)) - s
    y0 = int(round((my - ref.oy) / ref.upp - mm.ly * f)) - s
    x1, y1 = min(x0 + tw + 2 * s, ref.w), min(y0 + th + 2 * s, ref.h)
    x0, y0 = max(x0, 0), max(y0, 0)
    if x1 - x0 < tw or y1 - y0 < th:
        return None
    score = ncc(ref.grad[y0:y1, x0:x1], templ, mask)
    _, best, _, loc = cv2.minMaxLoc(score)
    sx, sy = subpixel(score, loc)
    return (float(best),
            (x0 + sx + mm.lx * f) * ref.upp + ref.ox,
            (y0 + sy + mm.ly * f) * ref.upp + ref.oy)


def global_search(ref, mm, scale):
    """Best match anywhere in the reference. Returns (peak, second peak, x, y) or None."""
    f = 1.0 / (scale * ref.upp)
    g = min(1.0, 1.0 / f)  # never enlarge the template: shrink the reference instead
    image = ref.grad if g == 1.0 else ref.coarse_grad(g)
    templ, mask = mm.template(f * g)
    if image.shape[0] < templ.shape[0] or image.shape[1] < templ.shape[1]:
        return None
    score = ncc(image, templ, mask)
    _, best, _, loc = cv2.minMaxLoc(score)
    rest = score.copy()
    cv2.circle(rest, loc, max(templ.shape) // 2, -1.0, -1)
    second = float(rest.max())
    mx = (loc[0] + mm.lx * f * g) / g * ref.upp + ref.ox
    my = (loc[1] + mm.ly * f * g) / g * ref.upp + ref.oy
    fine = local_search(ref, mm, scale, mx, my, 6.0 * ref.upp / g)
    if fine is None:
        return float(best), second, mx, my
    return fine[0], second, fine[1], fine[2]


def sift_fix(ref, mm, up=2):
    """Independent fix by SIFT. Returns (inliers, x, y, screen px per unit) or None."""
    kp_r, matcher = ref.sift_index()
    g = cv2.resize(mm.gray, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
    m = cv2.resize(mm.mask, None, fx=up, fy=up, interpolation=cv2.INTER_NEAREST)
    kp, des = SIFT.detectAndCompute(g, m)
    if des is None or len(kp) < 4:
        return None
    good = [p[0] for p in matcher.knnMatch(des, k=2) if len(p) == 2 and p[0].distance < 0.8 * p[1].distance]
    if len(good) < 4:
        return None
    src = np.float32([kp[d.queryIdx].pt for d in good])
    dst = np.float32([kp_r[d.trainIdx].pt for d in good])
    M, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=4.0)
    if M is None:
        return None
    n = int(inl.sum())
    scale = math.hypot(M[0, 0], M[1, 0])
    rot = math.degrees(math.atan2(M[1, 0], M[0, 0]))
    if n < 6 or abs(rot) > 2 or scale < 1e-6:  # too few points, rotated or degenerate: not a fix
        return None
    px = M[0, 0] * mm.lx * up + M[0, 1] * mm.ly * up + M[0, 2]
    py = M[1, 0] * mm.lx * up + M[1, 1] * mm.ly * up + M[1, 2]
    return n, px * ref.upp + ref.ox, py * ref.upp + ref.oy, 1.0 / (scale * ref.upp * up)
