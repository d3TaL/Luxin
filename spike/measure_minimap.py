"""Locate the minimap ring in gameplay screenshots.

The ring is a faint static UI element, so gradient magnitude is averaged over all
gameplay frames (scene content cancels out, static UI stays) and a circle is fitted
by brute force around the expected position.

Usage: python -I measure_minimap.py <screenshots_dir> <out_dir>
"""
import sys
from pathlib import Path

import cv2
import numpy as np

src, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

GAMEPLAY = ["183843", "183905", "183932", "183948", "184035", "184052",
            "184118", "184147", "184229", "185959"]
X0, Y0, W, H = 150, 0, 520, 440  # native-pixel ROI around the minimap

acc = np.zeros((H, W), np.float32)
for tag in GAMEPLAY:
    img = cv2.imread(str(next(src.glob(f"*_{tag}_*.jpg"))))
    gray = cv2.cvtColor(img[Y0:Y0 + H, X0:X0 + W], cv2.COLOR_BGR2GRAY).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    acc += np.minimum(mag, 60.0)  # clip so one strong scene edge cannot dominate
acc /= len(GAMEPLAY)
cv2.imwrite(str(out / "static_edges.png"), cv2.convertScaleAbs(acc, alpha=4))

theta = np.linspace(0, 2 * np.pi, 720, endpoint=False)
cos, sin = np.cos(theta), np.sin(theta)
best = (-1.0, 0, 0, 0)
for cx in np.arange(235, 266, 0.5):
    for cy in np.arange(195, 226, 0.5):
        for r in np.arange(165, 192, 0.5):
            xs = np.round(cx + r * cos).astype(int)
            ys = np.round(cy + r * sin).astype(int)
            ok = (xs >= 0) & (xs < W) & (ys >= 0) & (ys < H)
            score = float(acc[ys[ok], xs[ok]].mean())
            if score > best[0]:
                best = (score, cx, cy, r)

score, cx, cy, r = best
print(f"ring: center=({cx + X0:.1f},{cy + Y0:.1f}) r={r:.1f} score={score:.1f} (native px, 3120x1440)")
vis = cv2.cvtColor(cv2.convertScaleAbs(acc, alpha=4), cv2.COLOR_GRAY2BGR)
cv2.circle(vis, (int(round(cx)), int(round(cy))), int(round(r)), (0, 0, 255), 1)
cv2.imwrite(str(out / "ring_fit.png"), vis)
