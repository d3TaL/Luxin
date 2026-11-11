"""Save full-resolution frames of a screen recording at the given times.

Usage: python -I video_frames.py <video> <out_dir> <seconds> [<seconds> ...]
Writes <out_dir>/f_<seconds>.png (the first frame at or after each time).
Append --pts for real timestamps in variable-frame-rate recordings.
"""
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parent))
from video_time import VideoClock  # noqa: E402

use_pts = "--pts" in sys.argv
sys.argv = [arg for arg in sys.argv if arg != "--pts"]
video, out = sys.argv[1], Path(sys.argv[2])
times = sorted(float(v) for v in sys.argv[3:])
out.mkdir(parents=True, exist_ok=True)

cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS)
clock = VideoClock(fps, "pts" if use_pts else "fps")
idx = 0
while times and cap.grab():  # sequential read: seeking in H.264 is not frame-accurate
    t = clock.at(cap, idx)
    idx += 1
    if t + 1e-6 < times[0]:
        continue
    ok, frame = cap.retrieve()
    if not ok:
        break
    while times and t + 1e-6 >= times[0]:
        name = out / f"f_{times.pop(0):07.2f}.png"
        cv2.imwrite(str(name), frame)
        print(name, f"actual {t:.3f}s")
