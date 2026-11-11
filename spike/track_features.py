"""Evaluate Luxin's cvAutoTrack-inspired SIFT adaptation on a recording, with no uploads.

Usage: python -I spike/track_features.py VIDEO NEW_OUT_DIR --ref NAME=PREFIX [--ref ...]
    [--fps 2] [--t-from 0] [--t-to 184]
Outputs track.csv and candidates.csv (including rejected candidates). No screenshots.
The output directory must not exist. This is an offline experiment, not Android/SURF timing.
"""
import argparse
from collections import Counter
import csv
from dataclasses import asdict, fields
import hashlib
import json
import math
from pathlib import Path
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from feature_tracker import Candidate, FeatureTracker  # noqa: E402
from minimap_match import BASE_SCALE, Minimap, Ref, arrow_pixels, geometry  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--ref", action="append", required=True)
    ap.add_argument("--fps", type=float, default=2)
    ap.add_argument("--t-from", type=float, default=0)
    ap.add_argument("--t-to", type=float, default=1e9)
    args = ap.parse_args()
    if (not all(math.isfinite(v) for v in (args.fps, args.t_from, args.t_to))
            or args.fps <= 0 or args.t_from < 0 or args.t_to <= args.t_from):
        ap.error("require finite fps > 0 and 0 <= t-from < t-to")
    names = [spec.split("=", 1)[0] for spec in args.ref]
    if len(names) != len(set(names)) or any("=" not in spec for spec in args.ref):
        ap.error("references must be unique NAME=PREFIX pairs")
    cap = cv2.VideoCapture(args.video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not cap.isOpened() or fps <= 0:
        ap.error("cannot open video")
    if args.fps > fps:
        ap.error("requested sampling rate exceeds the video frame rate")
    geom = geometry(int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))
    unit = BASE_SCALE * geom[3]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    refs = [Ref(*spec.split("=", 1)) for spec in args.ref]
    cv2.setRNGSeed(0)
    t0 = time.perf_counter()
    tracker = FeatureTracker(refs, unit)
    index_ms = (time.perf_counter() - t0) * 1000
    root = Path(__file__).resolve().parent
    manifest = dict(arguments=vars(args), opencv=cv2.__version__, python=sys.version,
                    gates=asdict(tracker.gates), index_ms=index_ms,
                    source_sha256={p: hashlib.sha256((root / p).read_bytes()).hexdigest()
                                   for p in ("track_features.py", "feature_tracker.py", "minimap_match.py")},
                    reference_sha256={spec: {ext: hashlib.sha256(Path(spec.split("=", 1)[1] + ext).read_bytes()).hexdigest()
                                             for ext in (".png", ".json")} for spec in args.ref})
    (out / "run.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Reference indexes built in {index_ms:.0f} ms", flush=True)
    columns = ["t", "mode", "search", "ref", "map_id", "x", "y", "scale", "zoom", "score", "coverage",
               "patches", "good_patches", "patch_median", "inliers", "ms"]
    cand_columns = ["t", "search"] + [f.name for f in fields(Candidate)]
    modes, searches, reasons = Counter(), Counter(), Counter()
    timings, next_t, idx = [], args.t_from, 0
    previous_ref = None
    try:
        with (out / "track.csv").open("x", newline="") as tr, (out / "candidates.csv").open("x", newline="") as ca:
            tw, cw = csv.DictWriter(tr, columns), csv.DictWriter(ca, cand_columns)
            tw.writeheader()
            cw.writeheader()
            while cap.grab():
                t = idx / fps
                idx += 1
                if t > args.t_to:
                    break
                if t + 1e-6 < next_t:
                    continue
                next_t += 1 / args.fps
                ok, frame = cap.retrieve()
                if not ok:
                    raise RuntimeError(f"Failed to retrieve video frame at {t:.3f}s")
                row = dict(t=round(t, 3), mode="hidden")
                if arrow_pixels(frame, geom) < 60 * geom[3] ** 2:
                    tracker.reset()
                    previous_ref = None
                else:
                    start = time.perf_counter()
                    fix, mode, search, candidates = tracker.update(Minimap(frame, geom), t)
                    ms = (time.perf_counter() - start) * 1000
                    timings.append(ms)
                    searches[search] += 1
                    row.update(mode=mode, search=search, ms=round(ms, 1))
                    for c in candidates:
                        d = asdict(c)
                        d["reasons"] = "; ".join(c.reasons)
                        cw.writerow(dict(t=round(t, 3), search=search, **d))
                        reasons.update(c.reasons)
                    if fix is not None:
                        row.update(ref=fix.ref, map_id=fix.map_id, x=round(fix.x, 3), y=round(fix.y, 3),
                                   scale=round(fix.scale, 6), zoom=round(fix.scale / unit, 4),
                                   score=round(fix.score, 4), coverage=round(fix.coverage, 4),
                                   patches=fix.patches, good_patches=fix.good_patches,
                                   patch_median=round(fix.patch_median, 4), inliers=fix.inliers)
                        if mode == "found" or fix.ref != previous_ref:
                            print(f"{t:.2f}s {mode}: {fix.ref} zoom {fix.scale / unit:.3f} "
                                  f"score {fix.score:.2f} coverage {fix.coverage:.2f}", flush=True)
                        previous_ref = fix.ref
                modes[row["mode"]] += 1
                tw.writerow(row)
                tr.flush()
                ca.flush()
    finally:
        cap.release()
    print("Modes:", dict(modes))
    print("Searches:", dict(searches))
    print("Rejections (one candidate can have several reasons):", dict(reasons))
    if timings:
        print(f"Offline step median {np.median(timings):.1f} ms, p95 {np.percentile(timings, 95):.1f} ms; "
              "includes query features, matching and patch checks, excludes index build/decode/capture")
    print(f"Wrote {out / 'track.csv'} and {out / 'candidates.csv'}")


if __name__ == "__main__":
    main()
