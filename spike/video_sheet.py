"""Make contact sheets from a screen recording: one small frame every <step> seconds.

Usage: python -I video_sheet.py <video> <out_dir> <step_seconds> <columns> <rows_per_sheet> [t_from t_to [x y w h]]
  x y w h   optional crop of every frame before it is scaled down (e.g. the minimap)
"""
import sys
from pathlib import Path

import cv2

video, out = sys.argv[1], Path(sys.argv[2])
step, cols, rows = float(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
t_from = float(sys.argv[6]) if len(sys.argv) > 6 else 0.0
t_to = float(sys.argv[7]) if len(sys.argv) > 7 else 1e9
crop = [int(v) for v in sys.argv[8:12]] if len(sys.argv) > 11 else None
out.mkdir(parents=True, exist_ok=True)

cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS)
TILE_W = 2000 // cols
tiles, sheet, idx, next_t = [], 0, 0, t_from


def flush():
    global tiles, sheet
    if not tiles:
        return
    while len(tiles) % cols:
        tiles.append(tiles[-1] * 0)
    grid = cv2.vconcat([cv2.hconcat(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])
    name = out / f"sheet_{sheet:02d}.jpg"
    cv2.imwrite(str(name), grid, [cv2.IMWRITE_JPEG_QUALITY, 85])
    print(name)
    tiles, sheet = [], sheet + 1


while cap.grab():
    t = idx / fps
    idx += 1
    if t > t_to:
        break
    if t + 1e-6 < next_t:
        continue
    ok, frame = cap.retrieve()
    if not ok:
        break
    next_t += step
    if crop:
        frame = frame[crop[1]:crop[1] + crop[3], crop[0]:crop[0] + crop[2]]
    h, w = frame.shape[:2]
    small = cv2.resize(frame, (TILE_W, round(h * TILE_W / w)), interpolation=cv2.INTER_AREA)
    cv2.putText(small, f"{t:.1f}s", (6, small.shape[0] - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 4)
    cv2.putText(small, f"{t:.1f}s", (6, small.shape[0] - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
    tiles.append(small)
    if len(tiles) == cols * rows:
        flush()
flush()
print(f"frames read {idx}, fps {fps:.3f}")
