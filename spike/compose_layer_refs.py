"""Build active-floor references with muted neighbouring floors from cached Kongying images.

Unlike layer_ref.py, this keeps the other floors as context, as seen in the user's minimap.
No network access; no tracking thresholds or video labels are used to generate references.

Usage: python -I spike/compose_layer_refs.py CONFIG AREA GROUP CACHE BASE_PREFIX NEW_OUT_DIR
    --levels LEVEL1 LEVEL2 LEVEL3 [--context-dim 0.45] [--base-dim 0.18] [--pad 250]
Writes one PREFIX.png/.json per active level. Output directory must not exist.
"""
import argparse
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

import cv2
import numpy as np

BACKDROP = (38, 34, 30)


def chunk_key(chunk):
    key = chunk.get("value") or Path(unquote(urlsplit(chunk["url"]).path)).stem
    if not key or key in (".", "..") or "/" in key or "\\" in key:
        raise ValueError("invalid chunk cache key")
    return key


def paint(canvas, origin, overlay, bounds, brightness=1.0, clip=False):
    """Alpha-compose one floor at its canonical bounds, at one output pixel per map unit."""
    (xmin, ymin), (xmax, ymax) = bounds
    if xmax <= xmin or ymax <= ymin or not 0 <= brightness <= 1:
        raise ValueError("invalid bounds or brightness")
    if overlay.ndim != 3 or overlay.shape[2] != 4:
        raise ValueError("floor image must be RGBA")
    w, h = int(round(xmax - xmin)), int(round(ymax - ymin))
    x, y = int(round(xmin - origin[0])), int(round(ymin - origin[1]))
    if not clip and (x < 0 or y < 0 or x + w > canvas.shape[1] or y + h > canvas.shape[0]):
        raise ValueError("floor is outside the common canvas")
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(canvas.shape[1], x + w), min(canvas.shape[0], y + h)
    if x0 >= x1 or y0 >= y1:
        return
    image = cv2.resize(overlay, (w, h), interpolation=cv2.INTER_CUBIC).astype(np.float32)
    image = image[y0 - y:y1 - y, x0 - x:x1 - x]
    alpha = image[..., 3:4] / 255.0
    roi = canvas[y0:y1, x0:x1]
    roi[:] = roi * (1 - alpha) + image[..., :3] * brightness * alpha


def make_reference(base, origin, floors, active, context_dim=0.45, clip=False):
    """All inactive floors first, active floor LAST. Input base is never mutated."""
    if active not in floors:
        raise ValueError("unknown active floor")
    result = base.copy().astype(np.float32)
    for name in floors:
        if name != active:
            for overlay, bounds in floors[name]:
                paint(result, origin, overlay, bounds, context_dim, clip=clip)
    for overlay, bounds in floors[active]:
        paint(result, origin, overlay, bounds, clip=clip)
    return np.clip(result, 0, 255).astype(np.uint8)


def coverage_mask(shape, origin, chunks, clip=False):
    """Union of alpha footprints, independent of active-floor brightness/order."""
    mask = np.zeros(shape[:2], np.uint8)
    for image, bounds in chunks:
        (xmin, ymin), (xmax, ymax) = bounds
        w, h = int(round(xmax - xmin)), int(round(ymax - ymin))
        x, y = int(round(xmin - origin[0])), int(round(ymin - origin[1]))
        if w <= 0 or h <= 0 or (not clip and (x < 0 or y < 0 or x + w > mask.shape[1] or y + h > mask.shape[0])):
            raise ValueError("invalid footprint bounds")
        x0, y0 = max(0, x), max(0, y)
        x1, y1 = min(mask.shape[1], x + w), min(mask.shape[0], y + h)
        if x0 >= x1 or y0 >= y1:
            continue
        alpha = cv2.resize(image[..., 3], (w, h), interpolation=cv2.INTER_LINEAR)
        alpha = alpha[y0 - y:y1 - y, x0 - x:x1 - x]
        mask[y0:y1, x0:x1] = np.maximum(mask[y0:y1, x0:x1], alpha)
    return mask


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("config")
    ap.add_argument("area")
    ap.add_argument("group")
    ap.add_argument("cache")
    ap.add_argument("base")
    ap.add_argument("out")
    ap.add_argument("--levels", nargs="+", required=True)
    ap.add_argument("--context-dim", type=float, default=0.45)
    ap.add_argument("--base-dim", type=float, default=0.18)
    ap.add_argument("--pad", type=int, default=250)
    ap.add_argument("--window", nargs=4, type=float, metavar=("XMIN", "YMIN", "XMAX", "YMAX"),
                    help="explicit canonical crop; preserves geometry and clips chunks to this window")
    args = ap.parse_args()
    if not (0 <= args.context_dim <= 1 and 0 <= args.base_dim <= 1 and args.pad >= 0):
        ap.error("brightness must be in [0,1], pad must be non-negative")
    if len(args.levels) != len(set(args.levels)):
        ap.error("duplicate levels")
    cfg = json.loads(Path(args.config).read_text())["plugins"][args.area]["overlayConfig"]
    group = next(g for g in cfg["overlays"] if g["value"] == args.group)
    levels = {level["value"]: level for level in group["children"]}
    if set(args.levels) - levels.keys():
        ap.error("requested level not in the group")
    floors, metadata = {}, {}
    for name in args.levels:
        level = levels[name]
        floors[name], metadata[name] = [], []
        for chunk in level["chunks"]:
            key = chunk_key(chunk)
            bounds = chunk.get("bounds", level.get("bounds", group.get("bounds")))
            if bounds is None:
                ap.error(f"no bounds for {name}/{key}")
            path = Path(args.cache) / (key + ".bin")
            if not path.exists():
                path = Path(args.cache) / key / "image.png"
            image = cv2.imdecode(np.frombuffer(path.read_bytes(), np.uint8), cv2.IMREAD_UNCHANGED)
            if image is None:
                ap.error(f"cannot decode {path}")
            floors[name].append((image, bounds))
            metadata[name].append(dict(chunk=key, bounds=bounds, url=chunk.get("url")))
    bounds_list = [b for group in floors.values() for _, b in group]
    origin = [min(b[0][i] for b in bounds_list) - args.pad for i in (0, 1)]
    end = [max(b[1][i] for b in bounds_list) + args.pad for i in (0, 1)]
    clip = args.window is not None
    if clip:
        origin, end = args.window[:2], args.window[2:]
        if end[0] <= origin[0] or end[1] <= origin[1]:
            ap.error("empty window")
    w, h = [int(round(end[i] - origin[i])) for i in (0, 1)]
    base = cv2.imread(args.base + ".png")
    if base is None:
        ap.error("cannot read base image")
    bm = json.loads(Path(args.base + ".json").read_text())
    s = 1 / bm["units_per_px"]
    M = np.float32([[s, 0, (origin[0] - bm["origin"][0]) * s],
                    [0, s, (origin[1] - bm["origin"][1]) * s]])
    warped = cv2.warpAffine(base, M, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
    canvas = warped.astype(np.float32) * args.base_dim + np.array(BACKDROP, np.float32) * (1 - args.base_dim)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    context = coverage_mask(canvas.shape, origin, [chunk for chunks in floors.values() for chunk in chunks], clip=clip)
    if not cv2.imwrite(str(out / "context.mask.png"), context):
        raise OSError("cannot write context mask")
    for name in floors:
        rendered = make_reference(canvas, origin, floors, name, args.context_dim, clip=clip)
        prefix = out / name
        if not cv2.imwrite(str(prefix) + ".png", rendered):
            raise OSError(f"cannot write {prefix}.png")
        if not cv2.imwrite(str(prefix) + ".active.png", coverage_mask(canvas.shape, origin, floors[name])):
            raise OSError(f"cannot write {prefix}.active.png")
        meta = dict(units_per_px=1.0, origin=origin, size=[w, h], area=args.area,
                    masks=dict(active=name + ".active.png", context="context.mask.png"),
                    layer=name, map_id=f"{bm['tile_code']}/{name}", group=args.group,
                    rendering=dict(context_dim=args.context_dim, base_dim=args.base_dim,
                                   active_drawn_last=True, floors=metadata))
        Path(str(prefix) + ".json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
        print(prefix)


if __name__ == "__main__":
    main()
