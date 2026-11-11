"""Follow the player through a screen recording using only the minimap.

Every sampled frame the minimap circle is compared with reference images (masked normalised
cross-correlation of gradient magnitude) at each known minimap zoom. While tracking, only a small
window around the last position is searched: in the current reference every frame, in the other
references that cover the position every few frames (this is how a layer change is noticed). On
start and after a loss the whole of every reference is searched; when the minimap comes back
after being hidden, the neighbourhood of the last position is tried first.

Usage: python -I track_video.py <video> <out_dir> --ref NAME=PREFIX [--ref ...]
           [--fps N] [--t-from S] [--t-to S] [--search UNITS] [--acquire P] [--distinct R] [--keep P]
           [--alt-every N] [--sift-every S] [--teleports FILE]
  --ref         reference made by fetch_ref.py / layer_ref.py (PREFIX.png + PREFIX.json)
  --fps         frames per second to process (default 10)
  --sift-every  also locate the minimap with SIFT every S seconds, as an independent check
  --teleports   Kongying marker dump; reports the distance from every fresh fix to the nearest
                teleport point
Output: <out_dir>/track.csv, <out_dir>/path_<ref>.jpg, summary on stdout.
"""
import argparse
import csv
import json
import math
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python -I leaves the script directory out
from minimap_match import (BASE_SCALE, ZOOMS, Minimap, Ref, arrow_pixels, geometry,  # noqa: E402
                           global_search, local_search, sift_fix)

TELEPORT_TITLES = ("传送锚点", "七天神像")

ap = argparse.ArgumentParser()
ap.add_argument("video")
ap.add_argument("out")
ap.add_argument("--ref", action="append", required=True)
ap.add_argument("--fps", type=float, default=10.0)
ap.add_argument("--t-from", type=float, default=0.0)
ap.add_argument("--t-to", type=float, default=1e9)
ap.add_argument("--search", type=float, default=30.0, help="tracking search radius, marker units")
# Conservative trial values from one recording, not calibrated for arbitrary regions or UI settings.
ap.add_argument("--acquire", type=float, default=0.50, help="minimum peak to start tracking")
ap.add_argument("--distinct", type=float, default=0.6,
                help="whole-reference search: the second peak must be below this share of the best one")
ap.add_argument("--keep", type=float, default=0.40, help="minimum peak to keep tracking")
ap.add_argument("--lost-after", type=int, default=5, help="weak frames in a row before a new search")
ap.add_argument("--alt-every", type=int, default=3,
                help="while tracking, compare with the other references every N processed frames")
ap.add_argument("--sift-every", type=float, default=0.0)
ap.add_argument("--teleports")
args = ap.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)


refs = [Ref(*spec.split("=", 1)) for spec in args.ref]
teleports = []
if args.teleports:
    for m in json.loads(Path(args.teleports).read_text())["markers"]:
        if m.get("markerTitle") in TELEPORT_TITLES:
            x, y = (float(v) for v in m["position"].split(","))
            teleports.append((x, y))

cap = cv2.VideoCapture(args.video)
vfps = cap.get(cv2.CAP_PROP_FPS)
H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
geom = geometry(H)
k = geom[3]
scales = [BASE_SCALE * k * z for z in ZOOMS]  # screen px per marker unit
arrow_min = 60 * k * k
print(f"video {int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))}x{H} {vfps:.3f} fps; ring centre "
      f"({geom[0]:.1f}, {geom[1]:.1f}) r {geom[2]:.1f}; scales {[round(s, 4) for s in scales]} px per unit")

FIELDS = ["t", "arrow_px", "mode", "ref", "zoom", "x", "y", "peak", "second", "p1", "p2", "p3",
          "alt_ref", "alt_peak", "ms", "sift_inliers", "sift_x", "sift_y", "sift_scale", "note"]
rows, events = [], []
state = None      # dict(ref, zoom index, x, y, low)
hint = None       # last known state, tried first when the minimap comes back
next_search_t = 0.0
next_t, next_sift_t, idx, tracked = args.t_from, args.t_from, 0, 0

while cap.grab():
    t = idx / vfps
    idx += 1
    if t > args.t_to:
        break
    if t + 1e-6 < next_t:
        continue
    ok, frame = cap.retrieve()
    if not ok:
        break
    next_t += 1.0 / args.fps
    row = dict.fromkeys(FIELDS, "")
    row["t"] = round(t, 3)

    arrow_px = arrow_pixels(frame, geom)
    row["arrow_px"] = arrow_px
    if arrow_px < arrow_min:  # big map, loading screen, cutscene: no minimap
        if state is not None:
            hint, state = state, None
            events.append((t, "minimap hidden"))
        row["mode"] = "hidden"
        rows.append(row)
        continue

    mm = Minimap(frame, geom)
    t0 = time.perf_counter()
    if state is None:
        if t + 1e-6 >= next_search_t:
            found = None
            if hint is not None:  # the player is usually where the minimap was last seen
                cands = [(r, ref, zi) for ref in refs if ref.contains(hint["x"], hint["y"])
                         for zi in range(len(ZOOMS))
                         if (r := local_search(ref, mm, scales[zi], hint["x"], hint["y"], 2 * args.search))]
                if cands:
                    r, ref, zi = max(cands, key=lambda c: c[0][0])
                    if r[0] >= args.acquire:
                        found = (r[0], 0.0, r[1], r[2], ref, zi, "near last position")
            if found is None:
                cands = [(r, ref, zi) for ref in refs for zi in range(len(ZOOMS))
                         if (r := global_search(ref, mm, scales[zi]))]
                cands.sort(key=lambda c: -c[0][0])
                if cands:
                    r, ref, zi = cands[0]
                    row["peak"], row["second"] = round(r[0], 3), round(r[1], 3)
                    if len(cands) > 1:
                        row["alt_ref"] = f"{cands[1][1].name}/x{ZOOMS[cands[1][2]]}"
                        row["alt_peak"] = round(cands[1][0][0], 3)
                    if r[0] >= args.acquire and r[1] <= args.distinct * r[0]:
                        found = (r[0], r[1], r[2], r[3], ref, zi, "whole reference")
            if found:
                peak, second, x, y, ref, zi, how = found
                state = dict(ref=ref, zi=zi, x=x, y=y, low=0)
                note = f"found by {how}: {ref.name} x{ZOOMS[zi]} peak {peak:.2f}"
                if how == "whole reference":
                    note += f" (second {second:.2f})"
                if teleports:
                    d = min(math.hypot(x - tx, y - ty) for tx, ty in teleports)
                    note += f"; nearest teleport point {d:.1f} units away"
                events.append((t, note))
                row.update(mode="found", ref=ref.name, zoom=ZOOMS[zi], x=round(x, 2), y=round(y, 2),
                           peak=round(peak, 3), note=how)
            else:
                next_search_t = t + 1.0
                row["mode"] = "search failed"
                if cands:
                    row["note"] = f"best {cands[0][1].name}/x{ZOOMS[cands[0][2]]}"
        else:
            row["mode"] = "waiting"
    else:
        best, alt = None, None
        for zi in range(len(ZOOMS)):
            r = local_search(state["ref"], mm, scales[zi], state["x"], state["y"], args.search)
            if r is None:
                continue
            row[f"p{zi + 1}"] = round(r[0], 3)
            if best is None or r[0] > best[0][0]:
                best = (r, state["ref"], zi)
        # Other references that cover this place: checked now and then, and whenever the match is weak.
        tracked += 1
        if tracked % args.alt_every == 0 or best is None or best[0][0] < args.keep + 0.1:
            for ref in refs:
                if ref is state["ref"] or not ref.contains(state["x"], state["y"]):
                    continue
                for zi in range(len(ZOOMS)):
                    r = local_search(ref, mm, scales[zi], state["x"], state["y"], args.search)
                    if r and (alt is None or r[0] > alt[0][0]):
                        alt = (r, ref, zi)
        if alt:
            row["alt_ref"], row["alt_peak"] = f"{alt[1].name}/x{ZOOMS[alt[2]]}", round(alt[0][0], 3)
        # Another reference takes over only when it is clearly better (layer change, map edge).
        if alt and (best is None or alt[0][0] > best[0][0] + 0.05):
            best = alt
        if best and best[0][0] >= args.keep:
            r, ref, zi = best
            if ref is not state["ref"]:
                events.append((t, f"reference {state['ref'].name} -> {ref.name}"))
            if zi != state["zi"]:
                events.append((t, f"zoom x{ZOOMS[state['zi']]} -> x{ZOOMS[zi]}"))
            state.update(ref=ref, zi=zi, x=r[1], y=r[2], low=0)
            row.update(mode="track", ref=ref.name, zoom=ZOOMS[zi], x=round(r[1], 2), y=round(r[2], 2),
                       peak=round(r[0], 3))
        else:
            state["low"] += 1
            row.update(mode="weak", peak=round(best[0][0], 3) if best else "")
            if state["low"] >= args.lost_after:
                events.append((t, "lost"))
                hint, state = state, None
                next_search_t = t
    row["ms"] = round((time.perf_counter() - t0) * 1000, 1)

    if args.sift_every and state is not None and t + 1e-6 >= next_sift_t:
        next_sift_t = t + args.sift_every
        s = sift_fix(state["ref"], mm)
        if s:
            row.update(sift_inliers=s[0], sift_x=round(s[1], 2), sift_y=round(s[2], 2), sift_scale=round(s[3], 4))
        else:
            row["sift_inliers"] = 0
    rows.append(row)

with open(out / "track.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, FIELDS)
    w.writeheader()
    w.writerows(rows)

# ---------- Summary ----------
print("\nEvents:")
for t, text in events:
    print(f"  {t:7.2f}s  {text}")

print("\nSegments (same reference and zoom, no gaps):")
fixes = [r for r in rows if r["mode"] in ("found", "track")]
seg = []


def close_segment():
    if len(seg) < 2:
        return
    pts = np.array([(r["x"], r["y"]) for r in seg], float)
    ts = np.array([r["t"] for r in seg], float)
    steps = np.hypot(*np.diff(pts, axis=0).T)
    n = max(1, int(round(args.fps)))  # speed over one-second windows
    speed = [np.hypot(*(pts[i + n] - pts[i])) / (ts[i + n] - ts[i]) for i in range(len(pts) - n)]
    peaks = np.array([r["peak"] for r in seg], float)
    print(f"  {ts[0]:7.2f}-{ts[-1]:7.2f}s  {seg[0]['ref']:<12} x{seg[0]['zoom']}  frames {len(seg):4d}  "
          f"peak mean {peaks.mean():.2f} min {peaks.min():.2f}  path {steps.sum():7.1f} units  "
          f"max step {steps.max():5.2f}  speed max {max(speed) if speed else 0:5.1f} units/s")


prev = None
for r in rows:
    tracked = r["mode"] in ("found", "track")
    same = prev is not None and tracked and r["ref"] == prev["ref"] and r["zoom"] == prev["zoom"]
    if not same:
        close_segment()
        seg = []
    if tracked:
        seg.append(r)
    prev = r if tracked else None
close_segment()

shown = [r for r in rows if r["mode"] != "hidden"]
modes = {}
for r in shown:
    modes[r["mode"]] = modes.get(r["mode"], 0) + 1
print(f"\nFrames processed {len(rows)}, with minimap {len(shown)}: {modes}")
track_ms = [r["ms"] for r in rows if r["mode"] == "track"]
if track_ms:
    print(f"Tracking step: median {np.median(track_ms):.0f} ms, 95% {np.percentile(track_ms, 95):.0f} ms "
          f"(three zooms in the current reference, other references every {args.alt_every} frames; "
          f"Python + OpenCV on this machine)")
search_ms = [r["ms"] for r in rows if r["note"] == "whole reference" or r["mode"] == "search failed"]
rejected = [r for r in rows if r["mode"] == "search failed" and r["peak"] != ""]
if rejected:
    top = max(rejected, key=lambda r: r["peak"])
    print(f"Rejected searches: {len(rejected)}, highest peak {top['peak']} (second {top['second']}) "
          f"at {top['t']}s, {top['note']}")
if search_ms:
    print(f"Whole-reference search: median {np.median(search_ms):.0f} ms over {len(search_ms)} searches")

sift_rows = [r for r in rows if r["sift_inliers"] != ""]
if sift_rows:
    okr = [r for r in sift_rows if r["sift_inliers"] and r["x"] != ""]
    print(f"\nSIFT check: {len(okr)} of {len(sift_rows)} frames located")
    for z in ZOOMS:
        zr = [r for r in okr if r["zoom"] == z]
        if not zr:
            continue
        d = [math.hypot(r["x"] - r["sift_x"], r["y"] - r["sift_y"]) for r in zr]
        sc = [r["sift_scale"] for r in zr]
        print(f"  zoom x{z}: {len(zr)} frames, distance to template fix median {np.median(d):.2f} max {max(d):.2f} "
              f"units; SIFT scale median {np.median(sc):.4f} (min {min(sc):.4f}, max {max(sc):.4f}), "
              f"expected {scales[z - 1]:.4f}")

COLOURS = {1: (80, 255, 80), 2: (0, 220, 255), 3: (60, 60, 255)}  # zoom x1 green, x2 yellow, x3 red
for ref in refs:
    mine = [r for r in fixes if r["ref"] == ref.name]
    if not mine:
        continue
    img = ref.bgr.copy()
    last = None
    for r in mine:
        p = (int(round((r["x"] - ref.ox) / ref.upp)), int(round((r["y"] - ref.oy) / ref.upp)))
        if last is not None and r["t"] - last[1] < 0.5:
            cv2.line(img, last[0], p, COLOURS[r["zoom"]], 2, cv2.LINE_AA)
        else:
            cv2.circle(img, p, 5, (255, 255, 255), -1)
        last = (p, r["t"])
    xs = [(r["x"] - ref.ox) / ref.upp for r in mine]
    ys = [(r["y"] - ref.oy) / ref.upp for r in mine]
    m = 160
    x0, x1 = max(int(min(xs)) - m, 0), min(int(max(xs)) + m, ref.w)
    y0, y1 = max(int(min(ys)) - m, 0), min(int(max(ys)) + m, ref.h)
    cut = img[y0:y1, x0:x1]
    if max(cut.shape[:2]) < 700:
        cut = cv2.resize(cut, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
    cv2.imwrite(str(out / f"path_{ref.name}.jpg"), cut, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(f"wrote {out / f'path_{ref.name}.jpg'}")
