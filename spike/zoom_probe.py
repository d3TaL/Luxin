"""Measure the minimap scale frame by frame, without assuming the known zoom steps.

For every frame in the time range the minimap is matched around the previous position at many
scales on a geometric grid; the best one is printed as a multiple of the x1 zoom. Shows how the
game moves from one zoom to another.

Usage: python -I zoom_probe.py <video> <ref_prefix> <t_from> <t_to> <x> <y> [search_units]
  x y   starting position in marker units (take it from track.csv of track_video.py)
"""
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python -I leaves the script directory out
from minimap_match import BASE_SCALE, Minimap, Ref, arrow_pixels, geometry, local_search  # noqa: E402

video, ref = sys.argv[1], Ref("ref", sys.argv[2])
t_from, t_to = float(sys.argv[3]), float(sys.argv[4])
x, y = float(sys.argv[5]), float(sys.argv[6])
radius = float(sys.argv[7]) if len(sys.argv) > 7 else 12.0

cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS)
geom = geometry(int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))
unit = BASE_SCALE * geom[3]  # screen px per marker unit at zoom x1
STEP, LO, HI = 1.02, 0.85, 3.4
factors = []
f = LO
while f <= HI:
    factors.append(f)
    f *= STEP

print("time_s  zoom  peak  x  y")
idx = 0
while cap.grab():
    t = idx / fps
    idx += 1
    if t < t_from:
        continue
    if t > t_to:
        break
    ok, frame = cap.retrieve()
    if not ok:
        break
    if arrow_pixels(frame, geom) < 60 * geom[3] ** 2:
        print(f"{t:7.3f}  minimap hidden")
        continue
    mm = Minimap(frame, geom)
    results = [local_search(ref, mm, unit * z, x, y, radius) for z in factors]
    peaks = [r[0] if r else -1.0 for r in results]
    i = max(range(len(peaks)), key=peaks.__getitem__)
    if results[i] is None:
        print(f"{t:7.3f}  no result")
        continue
    peak, bx, by = results[i]
    z = factors[i]
    if 0 < i < len(peaks) - 1:  # refine between grid steps: parabola through the neighbours
        d = peaks[i - 1] - 2 * peaks[i] + peaks[i + 1]
        if d < 0:
            z *= STEP ** (0.5 * (peaks[i - 1] - peaks[i + 1]) / d)
    if peak >= 0.3:  # follow the player only on a believable match
        x, y = bx, by
    print(f"{t:7.3f}  {z:5.3f}  {peak:.3f}  {bx:.1f}  {by:.1f}")
