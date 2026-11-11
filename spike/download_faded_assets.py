"""Download missing overlay chunks and base tiles for Faded Castle (Petrichor).

Usage: python -I spike/download_faded_assets.py
"""
from pathlib import Path
import time
import urllib.error
import urllib.request
import cv2
import numpy as np

CHUNKS = [
    "Tex_4_6_1", "Tex_4_6_2", "Tex_4_6_3", "Tex_4_6_4",
    "Tex_4_6_extra1", "Tex_4_6_extra2", "Tex_4_6_extra3", "Tex_4_6_extra4"
]

OVERLAY_DIR = Path("research/tiles_cache/overlay")
OVERLAY_DIR.mkdir(parents=True, exist_ok=True)
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Luxin/1.0"}


def download_overlays():
    for c in CHUNKS:
        out_file = OVERLAY_DIR / f"{c}.bin"
        if out_file.exists() and out_file.stat().st_size > 0:
            print(f"Already in cache: {c}")
            continue
        url = f"https://tiles.yuanshen.site/d/underground/4.6/{c}.png"
        print(f"Fetching overlay {c} from {url}...")
        req = urllib.request.Request(url, headers=HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = resp.read()
            im = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
            if im is None or im.shape[2] != 4:
                raise ValueError(f"Corrupt or non-RGBA overlay {c}")
            out_file.write_bytes(data)
            print(f"Saved {c}.bin ({im.shape[1]}x{im.shape[0]} px)")
        except urllib.error.HTTPError as e:
            print(f"HTTP Error {e.code} for {c}")
        time.sleep(0.3)


if __name__ == "__main__":
    download_overlays()
