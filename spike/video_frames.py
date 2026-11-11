"""Save full-resolution frames of a screen recording at the given times.

Usage: python -I video_frames.py <video> <out_dir> <seconds> [<seconds> ...]
Writes <out_dir>/f_<seconds>.png (the first frame at or after each time).
"""
import sys
from pathlib import Path

import cv2

video, out = sys.argv[1], Path(sys.argv[2])
times = sorted(float(v) for v in sys.argv[3:])
out.mkdir(parents=True, exist_ok=True)

cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS)
idx = 0
while times and cap.grab():  # sequential read: seeking in H.264 is not frame-accurate
    t = idx / fps
    idx += 1
    if t + 1e-6 < times[0]:
        continue
    ok, frame = cap.retrieve()
    if not ok:
        break
    name = out / f"f_{times.pop(0):07.2f}.png"
    cv2.imwrite(str(name), frame)
    print(name, f"actual {t:.3f}s")
