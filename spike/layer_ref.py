"""Build a "layer view" reference: a Kongying layer overlay image stretched to its bounds,
optionally drawn over a dimmed base mosaic (the way the game shows a selected layer).

Output has the same format as fetch_ref.py: <out>.png and <out>.json (origin, units_per_px).

Usage: python -I layer_ref.py <out_prefix> <px_per_unit> <dim> <pad> <base_prefix|-> <overlay_file> <xmin> <ymin> <xmax> <ymax>
  px_per_unit  resolution of the output (overlay images themselves are 0.75 px per unit)
  dim          brightness factor for the base under the layer, 0..1 (ignored without a base)
  pad          margin around the overlay bounds, marker units
  base_prefix  mosaic made by fetch_ref.py, or "-" for a plain dark background
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

out_prefix = Path(sys.argv[1])
ppu, dim, pad = float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
base_prefix, overlay_file = sys.argv[5], sys.argv[6]
xmin, ymin, xmax, ymax = (float(v) for v in sys.argv[7:11])
out_prefix.parent.mkdir(parents=True, exist_ok=True)

ox, oy = xmin - pad, ymin - pad
w = int(round((xmax - xmin + 2 * pad) * ppu))
h = int(round((ymax - ymin + 2 * pad) * ppu))
BACKDROP = (38, 34, 30)  # dark blue-grey, close to the game's dimmed background
canvas = np.empty((h, w, 3), np.float32)
canvas[:] = BACKDROP

if base_prefix != "-":
    base = cv2.imread(f"{base_prefix}.png")
    bmeta = json.loads(Path(f"{base_prefix}.json").read_text())
    bx, by = bmeta["origin"]
    bupp = bmeta["units_per_px"]
    # canvas px (u, v) -> marker (ox + u / ppu, oy + v / ppu) -> base px
    s = 1.0 / (ppu * bupp)
    A = np.float32([[s, 0, (ox - bx) / bupp], [0, s, (oy - by) / bupp]])
    warped = cv2.warpAffine(base, A, (w, h), flags=cv2.INTER_AREA | cv2.WARP_INVERSE_MAP,
                            borderMode=cv2.BORDER_CONSTANT, borderValue=0).astype(np.float32)
    canvas = warped * dim + canvas * (1.0 - dim)

ov = cv2.imdecode(np.fromfile(overlay_file, np.uint8), cv2.IMREAD_UNCHANGED)
if ov is None or ov.ndim != 3 or ov.shape[2] != 4:
    raise SystemExit(f"overlay must be an RGBA image: {overlay_file}")
ow, oh = int(round((xmax - xmin) * ppu)), int(round((ymax - ymin) * ppu))
ov = cv2.resize(ov, (ow, oh), interpolation=cv2.INTER_CUBIC if ow > ov.shape[1] else cv2.INTER_AREA)
x0, y0 = int(round(pad * ppu)), int(round(pad * ppu))
alpha = ov[..., 3:4].astype(np.float32) / 255.0
roi = canvas[y0:y0 + oh, x0:x0 + ow]
roi[:] = ov[..., :3].astype(np.float32) * alpha + roi * (1.0 - alpha)

cv2.imwrite(f"{out_prefix}.png", np.clip(canvas, 0, 255).astype(np.uint8))
meta = {"units_per_px": 1.0 / ppu, "origin": [ox, oy], "size": [w, h]}
Path(f"{out_prefix}.json").write_text(json.dumps(meta))
print(meta)
