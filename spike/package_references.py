"""Package PREPARED map references for Android SAF import; never includes gameplay frames.

python -I spike/package_references.py NEW_ZIP --id PACK_ID --title TITLE
    --mode layered|position_only --ref DISPLAY_NAME=PREFIX [--ref ...]
Layered inputs: compose_layer_refs.py outputs. Position-only: fetch_ref.py surface mosaic.
No files are downloaded or executed; output should live in ignored spike/out/.
"""
import argparse
import json
from pathlib import Path
import zipfile

import cv2
import numpy as np


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("out")
    ap.add_argument("--id", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--mode", choices=("layered", "position_only", "composite"), required=True)
    ap.add_argument("--ref", action="append", required=True)
    args = ap.parse_args()
    if not 1 <= len(args.ref) <= 24:
        ap.error("need 1..24 references")
    if args.mode == "position_only" and len(args.ref) != 1:
        ap.error("position-only pack currently contains one surface reference")
    manifest = dict(schemaVersion=1, id=args.id, title=args.title, mode=args.mode, layers=[])
    payloads = {}
    total_pixels = 0
    identities = set()
    for i, spec in enumerate(args.ref):
        name, prefix = spec.split("=", 1)
        meta = json.loads(Path(prefix + ".json").read_text())
        image = cv2.imread(prefix + ".png", cv2.IMREAD_GRAYSCALE)
        if image is None:
            ap.error("cannot decode reference image")
        h, w = image.shape
        total_pixels += w * h
        if max(h, w) > 4096 or w * h > 4 * 1024 * 1024 or total_pixels > 32 * 1024 * 1024:
            ap.error("reference exceeds Android import limits")
        is_layered = "masks" in meta and "layer" in meta
        if is_layered:
            layer_id = meta["layer"]
            space = meta["map_id"].split("/", 1)[0]
            if meta["map_id"] != f"{space}/{layer_id}":
                ap.error("inconsistent map identity")
            masks = [cv2.imread(str(Path(prefix).parent / meta["masks"][key]), 0) for key in ("active", "context")]
        else:
            space = meta.get("tile_code", "twt702")
            layer_id = meta.get("layer", f"surface_{name}")
            masks = [np.full((h, w), 255, np.uint8)] * 2
        if (space, layer_id) in identities:
            ap.error("duplicate layer identity")
        identities.add((space, layer_id))
        if any(mask is None or mask.shape != image.shape for mask in masks):
            ap.error("missing or mismatched mask")
        filenames = [f"ref{i}.png", f"ref{i}.active.png", f"ref{i}.context.png"]
        payloads[filenames[0]] = Path(prefix + ".png").read_bytes()
        for filename, mask in zip(filenames[1:], masks):
            ok, encoded = cv2.imencode(".png", mask)
            if not ok:
                ap.error("cannot encode mask")
            payloads[filename] = encoded.tobytes()
        manifest["layers"].append(dict(id=layer_id, name=name, space=space, image=filenames[0],
            activeMask=filenames[1], contextMask=filenames[2], unitsPerPx=meta["units_per_px"],
            originX=meta["origin"][0], originY=meta["origin"][1]))
    payloads["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    if sum(map(len, payloads.values())) > 128 * 1024 * 1024:
        ap.error("pack exceeds decompressed size limit")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("xb") as stream, zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as z:
        for name, data in payloads.items():
            z.writestr(name, data)
    print(f"Wrote {out}: {len(manifest['layers'])} references, {out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
