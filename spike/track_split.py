"""Offline split position/layer experiment. No annotation files are inputs.

python -I spike/track_split.py VIDEO NEW_OUT_DIR --ref NAME=PREFIX [--ref ...]
    [--fps 2] [--t-from 68] [--t-to 170]
References must come from compose_layer_refs.py with active/context masks.
An empty layer is unresolved even when mode=found/track and x/y are present.
"""
import argparse
from collections import Counter
import csv
from dataclasses import asdict
import hashlib
import json
import math
from pathlib import Path
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from minimap_match import BASE_SCALE, Minimap, arrow_pixels, geometry  # noqa: E402
from split_tracker import LayerModel, SplitTracker  # noqa: E402
from video_time import VideoClock, next_sample_time  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--ref", action="append", required=True)
    ap.add_argument("--fps", type=float, default=2)
    ap.add_argument("--timestamps", choices=("fps", "pts"), default="fps",
                    help="use pts for variable-frame-rate videos; fps preserves historical runs")
    ap.add_argument("--t-from", type=float, default=0)
    ap.add_argument("--t-to", type=float, default=1e9)
    args = ap.parse_args()
    if (not all(math.isfinite(x) for x in (args.fps, args.t_from, args.t_to))
            or not 0.5 <= args.fps or not 0 <= args.t_from < args.t_to):
        ap.error("require finite fps >= 0.5 and 0 <= t-from < t-to")
    if any("=" not in s for s in args.ref):
        ap.error("references must be NAME=PREFIX")
    specs = [s.split("=", 1) for s in args.ref]
    if len(set(name for name, _ in specs)) != len(specs):
        ap.error("reference names must be unique")
    cv2.setRNGSeed(0)
    start = time.perf_counter()
    models = [LayerModel(name, prefix) for name, prefix in specs]
    if len({(m.space, m.layer) for m in models}) != len(models):
        ap.error("duplicate layer models")
    cap = cv2.VideoCapture(args.video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not cap.isOpened() or fps <= 0 or args.fps > fps:
        ap.error("invalid video or sampling frequency")
    geom = geometry(int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))
    unit = BASE_SCALE * geom[3]
    tracker = SplitTracker(models, unit)
    clock = VideoClock(fps, args.timestamps)
    index_ms = (time.perf_counter() - start) * 1000
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    scripts = ["track_split.py", "split_tracker.py", "feature_tracker.py", "minimap_match.py", "compose_layer_refs.py", "video_time.py"]
    root = Path(__file__).resolve().parent
    reference_hashes = {}
    for name, prefix in specs:
        metadata = json.loads(Path(prefix + ".json").read_text())
        files = [Path(prefix + ".png"), Path(prefix + ".json")]
        files += [Path(prefix).parent / n for n in metadata["masks"].values()]
        reference_hashes[name] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    manifest = dict(arguments=vars(args), opencv=cv2.__version__, python=sys.version,
                    gates=asdict(tracker.gates), index_ms=index_ms,
                    source_sha256={s: hashlib.sha256((root / s).read_bytes()).hexdigest() for s in scripts},
                    reference_sha256=reference_hashes)
    (out / "run.json").write_text(json.dumps(manifest, indent=2) + "\n")
    columns = ["t", "mode", "search", "space", "x", "y", "scale", "rotation", "position_support",
               "position_inliers", "layer", "layer_mode", "layer_reason", "layer_error", "layer_margin",
               "overlay_ready", "ms"]
    ec = ["t", "layer", "error", "correlation", "active_pixels", "gain", "offset"]
    pc = ["t", "space", "x", "y", "scale", "rotation", "support", "inliers", "error_px", "score"]
    modes, layer_modes, timings = Counter(), Counter(), []
    idx, next_t, prev_layer = 0, args.t_from, None
    print(f"Indexes {index_ms:.0f} ms; {len(models)} models; gates {asdict(tracker.gates)}", flush=True)
    try:
        with (out / "track.csv").open("x", newline="") as tr, (out / "layer_evidence.csv").open("x", newline="") as ev, (out / "positions.csv").open("x", newline="") as po:
            tw, ew, pw = csv.DictWriter(tr, columns), csv.DictWriter(ev, ec), csv.DictWriter(po, pc)
            tw.writeheader(); ew.writeheader(); pw.writeheader()
            while cap.grab():
                t = clock.at(cap, idx)
                idx += 1
                if t > args.t_to:
                    break
                if t + 1e-6 < next_t:
                    continue
                next_t = (next_sample_time(t, args.t_from, args.fps) if args.timestamps == "pts"
                          else next_t + 1 / args.fps)
                ok, frame = cap.retrieve()
                if not ok:
                    raise RuntimeError(f"Cannot retrieve frame at {t}")
                row = dict(t=round(t, 3), mode="hidden", layer_mode="unknown", overlay_ready=0)
                if arrow_pixels(frame, geom) < 60 * geom[3] ** 2:
                    tracker.reset()
                    prev_layer = None
                else:
                    t0 = time.perf_counter()
                    fix, mode, decision, search, positions = tracker.update(Minimap(frame, geom), t)
                    ms = (time.perf_counter() - t0) * 1000
                    timings.append(ms)
                    row.update(mode=mode, search=search, layer_mode=decision.mode, layer_reason=decision.reason,
                               layer_error=decision.error, layer_margin=decision.margin, ms=round(ms, 1))
                    for p in positions:
                        pw.writerow(dict(t=round(t, 3), **asdict(p)))
                    for e in decision.evidence:
                        ew.writerow(dict(t=round(t, 3), **e))
                    if fix is not None:
                        row.update(space=fix.space, x=round(fix.x, 3), y=round(fix.y, 3),
                                   scale=round(fix.scale, 6), rotation=round(fix.rotation, 4),
                                   position_support=round(fix.support, 4), position_inliers=fix.inliers,
                                   layer=decision.layer or "", overlay_ready=int(decision.mode == "known" and bool(decision.layer)))
                    if decision.layer != prev_layer:
                        print(f"{t:.2f}s position={mode}, layer={decision.layer or 'unknown'} "
                              f"error={decision.error}, margin={decision.margin}", flush=True)
                        prev_layer = decision.layer
                modes[row["mode"]] += 1
                layer_modes[row["layer_mode"]] += 1
                tw.writerow(row)
                tr.flush(); ev.flush(); po.flush()
    finally:
        cap.release()
    if not modes:
        raise SystemExit("No frames in requested time range")
    print("Position modes:", dict(modes))
    print("Layer modes:", dict(layer_modes))
    if timings:
        print(f"Offline step median {np.median(timings):.1f} ms; p95 {np.percentile(timings, 95):.1f} ms; "
              "excludes capture/decode/index build/drawing")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
