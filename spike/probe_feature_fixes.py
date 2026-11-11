"""Probe absolute feature fixes on selected video frames against each supplied reference.

This is a Luxin SIFT experiment, NOT a port or benchmark of cvAutoTrack's SURF implementation.
The existing sift_fix geometric checks are only a candidate filter, not production confidence.
Each candidate is cross-checked by local NCC at both the estimated and nominal scale.

Usage: python -I spike/probe_feature_fixes.py <video> <out.csv>
    --ref NAME=PREFIX [--ref ...] --times 72 90 110 125 145 160 168 172 180
    [--upsample 1 2 3]
CSV records failed attempts too. The reference images and raw video are not uploaded anywhere.
"""
import argparse
import csv
import math
import sys
import time
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent))
from minimap_match import (BASE_SCALE, ZOOMS, Minimap, Ref, arrow_pixels, geometry,
                           local_search, sift_fix)  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--ref", action="append", required=True)
    ap.add_argument("--times", nargs="+", type=float, required=True)
    ap.add_argument("--upsample", nargs="+", type=int, default=[1, 2, 3])
    args = ap.parse_args()
    if any(t < 0 or not math.isfinite(t) for t in args.times):
        ap.error("times must be non-negative and finite")
    if any(u < 1 or u > 4 for u in args.upsample):
        ap.error("upsample must be between 1 and 4")
    refs = [Ref(*spec.split("=", 1)) for spec in args.ref]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    cap = cv2.VideoCapture(args.video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not cap.isOpened() or fps <= 0:
        ap.error("cannot open video or read its frame rate")
    geom = geometry(int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))
    unit = BASE_SCALE * geom[3]
    fields = ["requested_t", "actual_t", "ref", "up", "status", "inliers", "x", "y",
              "scale", "zoom", "ncc_estimated", "ncc_nominal", "nominal_zoom", "ms"]
    idx, pending = 0, sorted(set(args.times))
    try:
        with out.open("w", newline="") as fh:
            writer = csv.DictWriter(fh, fields)
            writer.writeheader()
            while pending and cap.grab():
                t = idx / fps
                idx += 1
                if t + 1e-6 < pending[0]:
                    continue
                ok, frame = cap.retrieve()
                if not ok:
                    break
                due = []
                while pending and t + 1e-6 >= pending[0]:
                    due.append(pending.pop(0))
                visible = arrow_pixels(frame, geom) >= 60 * geom[3] ** 2
                mm = Minimap(frame, geom) if visible else None
                for requested_t in due:
                    for ref in refs:
                        for up in args.upsample:
                            row = dict(requested_t=requested_t, actual_t=round(t, 3), ref=ref.name, up=up,
                                       status="no fix" if visible else "hidden")
                            t0 = time.perf_counter()
                            fix = sift_fix(ref, mm, up=up) if mm is not None else None
                            if fix:
                                n, x, y, scale = fix
                                zi = min(ZOOMS, key=lambda z: abs(math.log(scale / (unit * z))))
                                # Compare at the same candidate neighbourhood, NOT a global NCC search.
                                exact = local_search(ref, mm, scale, x, y, 6)
                                nominal = local_search(ref, mm, unit * zi, x, y, 6)
                                row.update(status="candidate", inliers=n, x=round(float(x), 3), y=round(float(y), 3),
                                           scale=round(scale, 6), zoom=round(scale / unit, 4),
                                           ncc_estimated=round(exact[0], 4) if exact else "",
                                           ncc_nominal=round(nominal[0], 4) if nominal else "", nominal_zoom=zi)
                            row["ms"] = round((time.perf_counter() - t0) * 1000, 1)
                            writer.writerow(row)
                            if fix:
                                print(row, flush=True)
                fh.flush()
    finally:
        cap.release()
    if pending:
        raise SystemExit(f"Video ended before requested times: {pending}")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
