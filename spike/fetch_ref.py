"""Download Kongying base tiles around a point and stitch a reference mosaic.

Coordinates are Kongying marker units (1 unit = 1 px at z=13, 2 units per px at z=12, ...).
Writes <out>.png and <out>.json with the marker coordinates of the mosaic's top-left pixel.

Usage: python -I fetch_ref.py <tile_code> <center_x> <center_y> <cx> <cy> <half_extent> <cache_dir> <out_prefix> [zoom]
  tile_code          e.g. twt702
  center_x center_y  the tile set's `center` from the Kongying config (twt702: 3568 6969)
  cx cy              point of interest in marker units
  half_extent        half side of the square to cover, marker units
  zoom               tile zoom level 10..13, default 13
"""
import http.client
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import cv2
import numpy as np

code = sys.argv[1]
center = (float(sys.argv[2]), float(sys.argv[3]))
cx, cy, half = float(sys.argv[4]), float(sys.argv[5]), float(sys.argv[6])
zoom = int(sys.argv[9]) if len(sys.argv) > 9 else 13
cache = Path(sys.argv[7]) / code / str(zoom)
out_prefix = Path(sys.argv[8])
cache.mkdir(parents=True, exist_ok=True)
out_prefix.parent.mkdir(parents=True, exist_ok=True)

TILE = 256
upp = 2 ** (13 - zoom)  # marker units per tile pixel
span = TILE * upp       # marker units per tile
tx0 = int(np.floor((cx - half + center[0]) / span))
tx1 = int(np.floor((cx + half + center[0]) / span))
ty0 = int(np.floor((cy - half + center[1]) / span))
ty1 = int(np.floor((cy + half + center[1]) / span))

mosaic = np.zeros(((ty1 - ty0 + 1) * TILE, (tx1 - tx0 + 1) * TILE, 3), np.uint8)
fetched = missing = 0
for ty in range(ty0, ty1 + 1):
    for tx in range(tx0, tx1 + 1):
        f = cache / f"{tx}_{ty}.bin"
        if not f.exists():
            url = f"https://assets.yuanshen.site/tiles_{code}/{zoom}/{tx}_{ty}.png"
            for attempt in range(4):
                try:
                    with urllib.request.urlopen(url, timeout=30) as r:
                        f.write_bytes(r.read())
                    fetched += 1
                    break
                except urllib.error.HTTPError as e:
                    if e.code != 404:
                        raise
                    f.write_bytes(b"")  # sparse tile set: 404 means empty
                    break
                except (ConnectionError, TimeoutError, urllib.error.URLError, http.client.HTTPException):
                    if attempt == 3:
                        raise
                    time.sleep(2 * (attempt + 1))  # dropped connection: wait and retry
            time.sleep(0.15)
        data = f.read_bytes()
        if not data:
            missing += 1
            continue
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)  # format by content
        if img is None or img.shape[:2] != (TILE, TILE):
            raise SystemExit(f"bad tile {tx}_{ty}: {None if img is None else img.shape}")
        mosaic[(ty - ty0) * TILE:(ty - ty0 + 1) * TILE, (tx - tx0) * TILE:(tx - tx0 + 1) * TILE] = img

cv2.imwrite(f"{out_prefix}.png", mosaic)
meta = {
    "tile_code": code,
    "units_per_px": float(upp),
    # marker coordinates of the mosaic's top-left pixel
    "origin": [tx0 * span - center[0], ty0 * span - center[1]],
    "size": [mosaic.shape[1], mosaic.shape[0]],
}
Path(f"{out_prefix}.json").write_text(json.dumps(meta))
print(f"tiles x {tx0}..{tx1} y {ty0}..{ty1}; fetched {fetched}, empty {missing}; {meta}")
