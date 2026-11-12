"""Detect the open big map by its zoom slider (the "+" and "-" buttons on the left).

The slider is fixed UI that only the big map shows. It stays in place when the marker side
panel opens, over clouds and with any selected layer. Detection works on a frame scaled to a
fixed height: both buttons must be found, stacked vertically at the expected distance, and the
slider track between them must be visible.

Templates are cut from a 3120x1440 screenshot (pass it with --template-from). Geometry is in
units of frame height so other resolutions with the same UI scale work too.

    python -I spike/bigmap_detect.py --template-from <bigmap_3120x1440.jpg> images <img>...
    python -I spike/bigmap_detect.py --template-from <...> video <video.mp4> <out.csv> [--fps 2]
"""
import argparse
import csv
import math
import sys
from pathlib import Path

import cv2
import numpy as np

WORK_H = 720
# Measured on 3120x1440 (184124): button centres (199, 559.5) and (199, 879.5), 37x36 px.
PLUS_C = (199.0 / 1440, 559.5 / 1440)
MINUS_C = (199.0 / 1440, 879.5 / 1440)
HALF = 24.0 / 1440            # half-size of the template window around a button centre
TRACK_X = (190.0 / 1440, 208.0 / 1440)  # outline columns of the track between the buttons
# Search window, in units of frame height, measured from the left/top edges.
ROI_X = (0.0, 0.32)
ROI_Y = (0.28, 0.72)

PEAK_MIN = 0.70


def to_work_gray(bgr):
    h, w = bgr.shape[:2]
    k = WORK_H / h
    small = cv2.resize(bgr, (round(w * k), WORK_H), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)


def cut_templates(ref_bgr):
    g = to_work_gray(ref_bgr)
    out = []
    for cx, cy in (PLUS_C, MINUS_C):
        x0 = round((cx - HALF) * WORK_H)
        y0 = round((cy - HALF) * WORK_H)
        s = round(2 * HALF * WORK_H)
        out.append(g[y0:y0 + s, x0:x0 + s].copy())
    return out


def match_map(roi, tpl):
    # Ignore corners outside the diamond: map icons can move behind those pixels.
    y, x = np.mgrid[:24, :24]
    mask = ((abs(x - 11.5) + abs(y - 11.5)) <= 11).astype(np.uint8) * 255
    r = cv2.matchTemplate(roi, tpl, cv2.TM_CCOEFF_NORMED, mask=mask)
    return np.nan_to_num(r, nan=-1.0, posinf=-1.0, neginf=-1.0)


def pair_match(roi, templates, dy):
    """Best position of the "+" button such that "-" sits dy pixels below it (±1 px).

    The buttons look alike, so matching them separately lets one template lock onto the other
    button when the knob touches it. Scoring the pair jointly removes that ambiguity.
    """
    rp, rm = (match_map(roi, t) for t in templates)
    if rp.shape[0] <= dy:
        return 0.0, 0.0, (0, 0)
    rm = cv2.dilate(rm, np.ones((3, 3), np.uint8))  # tolerate rounding of dy / sub-pixel shift
    a = rp[:rp.shape[0] - dy]
    b = rm[dy:]
    joint = np.minimum(a, b)
    _, _, _, loc = cv2.minMaxLoc(joint)
    x, y = loc
    return float(a[y, x]), float(b[y, x]), loc


def track_contrast(g, x_plus, y_plus, y_minus):
    """Outline columns of the track should be brighter than its interior along the whole gap."""
    xl = round(x_plus + (TRACK_X[0] - PLUS_C[0]) * WORK_H)
    xr = round(x_plus + (TRACK_X[1] - PLUS_C[0]) * WORK_H)
    y0 = round(y_plus + 20.0 / 1440 * WORK_H * 2)
    y1 = round(y_minus - 20.0 / 1440 * WORK_H * 2)
    if xl < 1 or xr + 2 >= g.shape[1] or y1 - y0 < 10:
        return 0.0
    col = lambda x: g[y0:y1, x - 1:x + 2].max(axis=1).astype(np.float32)
    left, right = col(xl), col(xr)
    inner = g[y0:y1, xl + 3:xr - 2].astype(np.float32).mean(axis=1)
    # Knob covers part of the track; require the outline on most rows only.
    ok = (left - inner > 12) & (right - inner > 12)
    return float(ok.mean())


def detect(bgr, templates):
    g = to_work_gray(bgr)
    H, W = g.shape
    x0, x1 = int(ROI_X[0] * H), min(W, int(ROI_X[1] * H))
    y0, y1 = int(ROI_Y[0] * H), int(ROI_Y[1] * H)
    roi = g[y0:y1, x0:x1]
    s = templates[0].shape[0]
    dy = round((MINUS_C[1] - PLUS_C[1]) * H)
    pp, mp, (lx, ly) = pair_match(roi, templates, dy)
    xp, yp = lx + x0 + s / 2, ly + y0 + s / 2
    track = track_contrast(g, xp, yp, yp + dy)
    is_map = pp >= PEAK_MIN and mp >= PEAK_MIN and track >= 0.6
    return {
        "bigmap": is_map, "plus": round(pp, 3), "minus": round(mp, 3),
        "track": round(track, 2), "x": round(xp / H * 1440, 1), "y": round(yp / H * 1440, 1),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template-from", required=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("images")
    a.add_argument("paths", nargs="+")
    v = sub.add_parser("video")
    v.add_argument("path")
    v.add_argument("out_csv")
    v.add_argument("--fps", type=float, default=2.0)
    export = sub.add_parser("export-header")
    export.add_argument("path")
    args = ap.parse_args()

    ref = cv2.imread(args.template_from)
    if ref is None:
        sys.exit("cannot read template source")
    templates = cut_templates(ref)

    if args.cmd == "images":
        for p in args.paths:
            im = cv2.imread(p)
            print(p.rsplit("/", 1)[-1], detect(im, templates))
        return

    if args.cmd == "export-header":
        text = "// Generated by spike/bigmap_detect.py export-header. UI-only crops; no UID.\n#pragma once\n#include <cstdint>\nnamespace luxin::bigmap_templates {\n"
        for name, t in zip(("plus", "minus"), templates):
            text += f"inline constexpr uint8_t {name}[24 * 24] = {{\n"
            text += "\n".join("    " + ", ".join(map(str, row)) + "," for row in t) + "\n};\n"
        Path(args.path).write_text(text + "}\n")
        return

    if not math.isfinite(args.fps) or args.fps <= 0:
        ap.error("--fps must be positive and finite")
    cap = cv2.VideoCapture(args.path)
    if not cap.isOpened():
        sys.exit("cannot open video")
    # Android recordings are VFR: index/average_fps can move transitions by seconds.
    next_t, previous = 0.0, -1.0
    with open(args.out_csv, "w", newline="") as f:
        w = None
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000
            if not math.isfinite(t) or t < previous:
                raise ValueError("invalid/non-monotonic video PTS")
            previous = t
            if t + 1e-6 < next_t:
                continue
            r = {"t": round(t, 3), **detect(frame, templates)}
            if w is None:
                w = csv.DictWriter(f, fieldnames=list(r))
                w.writeheader()
            w.writerow(r)
            next_t = (math.floor(t * args.fps + 1e-6) + 1) / args.fps
    cap.release()


if __name__ == "__main__":
    main()
