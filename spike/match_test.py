"""Feasibility check: register game screenshots against a Kongying reference map.

Big map:  screenshot -> similarity transform (SIFT + RANSAC) -> Kongying teleport waypoints
          projected back onto the screenshot; they should land on the game's own icons.
Minimap:  crop from a gameplay screenshot -> position on the reference, two ways:
          SIFT + RANSAC (no prior knowledge) and masked template matching at a known scale.

The reference is <ref>.png + <ref>.json made by fetch_ref.py or layer_ref.py.
All screen coordinates in arguments are native pixels of a 3120x1440 frame.

Usage: python -I match_test.py --ref PREFIX --out DIR [--markers FILE [--title TITLE]]
           [--bigmap FILE [--ui plain|layer|panel] [--arrow X Y]]
           [--gameplay FILE [--minimap-scale PX_PER_UNIT]]
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np

PLAIN_UI = [
    (0, 0, 760, 520),          # banner and quest list
    (160, 530, 240, 910),      # zoom slider
    (1460, 0, 2960, 130),      # resource chips, close button
    (2790, 500, 2960, 950),    # layer switcher column
    (2150, 1270, 3120, 1440),  # area name, exploration progress, UID
    (140, 1270, 270, 1400),    # settings gear
]
UI_MASKS = {
    "plain": PLAIN_UI,
    # a layer is selected: its name tooltip and the bottom banner appear
    "layer": PLAIN_UI + [(2280, 720, 2790, 840), (1190, 1280, 1930, 1400)],
    # an info side panel is open: it covers the right part, the layer column moves left
    "panel": [(160, 530, 240, 910), (2180, 0, 3120, 1440), (2020, 540, 2140, 890)],
}

ap = argparse.ArgumentParser()
ap.add_argument("--ref", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--markers")
ap.add_argument("--title", default="传送锚点", help="marker title to project; default: teleport waypoint")
ap.add_argument("--bigmap")
ap.add_argument("--ui", choices=sorted(UI_MASKS), default="plain")
ap.add_argument("--arrow", nargs=2, type=float, help="player arrow on the big-map screenshot")
ap.add_argument("--gameplay")
ap.add_argument("--minimap-scale", type=float, help="minimap px per unit for the template test")
args = ap.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)

ref = cv2.imread(f"{args.ref}.png")
meta = json.loads(Path(f"{args.ref}.json").read_text())
ox, oy = meta["origin"]      # marker coords of reference pixel (0, 0)
upp = meta["units_per_px"]   # marker units per reference pixel

sift = cv2.SIFT_create(nfeatures=0, contrastThreshold=0.02)
ref_gray = cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY)
ref_kp, ref_desc = sift.detectAndCompute(ref_gray, None)
print(f"reference {ref.shape[1]}x{ref.shape[0]} at {1 / upp:.2f} px per unit: {len(ref_kp)} keypoints")
flann = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=64))


def register(gray, mask, label, ratio=0.75, thresh=3.0, upsampled=1):
    """Return (M, stats). M maps query pixels -> reference pixels (similarity transform).

    `upsampled` is how many times the query was enlarged before the call; it only affects
    the printed scale, which is given in original screen pixels.
    """
    kp, desc = sift.detectAndCompute(gray, mask)
    if desc is None or len(kp) < 4:
        print(f"[{label}] keypoints {0 if desc is None else len(kp)} -> not enough")
        return None, None
    good = [p[0] for p in flann.knnMatch(desc, ref_desc, k=2)
            if len(p) == 2 and p[0].distance < ratio * p[1].distance]
    if len(good) < 4:
        print(f"[{label}] keypoints {len(kp)}, good matches {len(good)} -> not enough")
        return None, None
    src = np.float32([kp[m.queryIdx].pt for m in good])
    dst = np.float32([ref_kp[m.trainIdx].pt for m in good])
    M, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=thresh)
    if M is None:
        print(f"[{label}] keypoints {len(kp)}, good {len(good)} -> RANSAC failed")
        return None, None
    keep = inl.ravel() == 1
    scale = math.hypot(M[0, 0], M[1, 0])  # reference px per query px
    rot = math.degrees(math.atan2(M[1, 0], M[0, 0]))
    res = np.linalg.norm((src[keep] @ M[:, :2].T + M[:, 2]) - dst[keep], axis=1)
    print(f"[{label}] keypoints {len(kp)}, good {len(good)}, inliers {int(keep.sum())}, "
          f"screen px per unit {1 / (scale * upp * upsampled):.4f}, rotation {rot:+.2f} deg, "
          f"inlier residual median {np.median(res) * upp:.2f} units")
    return M, dict(inliers=int(keep.sum()), good=len(good), scale=scale, rot=rot)


def to_marker(M, x, y):
    rx, ry = M[:, :2] @ np.array([x, y]) + M[:, 2]
    return rx * upp + ox, ry * upp + oy


def to_query(M, mx, my):
    A = np.vstack([M, [0, 0, 1]])
    x, y, _ = np.linalg.inv(A) @ np.array([(mx - ox) / upp, (my - oy) / upp, 1.0])
    return x, y


def ref_in_query_space(M, size):
    """Reference image resampled into the query image's pixel grid."""
    return cv2.warpAffine(ref, M, size, flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)


arrow_marker = None

# ---------- Big map ----------
if args.bigmap:
    big = cv2.imread(args.bigmap)
    H, W = big.shape[:2]
    sx, sy = W / 3120.0, H / 1440.0
    mask = np.full((H, W), 255, np.uint8)
    for x0, y0, x1, y1 in UI_MASKS[args.ui]:
        mask[int(y0 * sy):int(y1 * sy), int(x0 * sx):int(x1 * sx)] = 0
    M_big, st = register(cv2.cvtColor(big, cv2.COLOR_BGR2GRAY), mask, "big map")
    if M_big is not None:
        vis = big.copy()
        if args.markers:
            markers = json.loads(Path(args.markers).read_text(encoding="utf-8"))["markers"]
            shown = 0
            for m in markers:
                if m.get("markerTitle") != args.title:
                    continue
                mx, my = (float(v) for v in m["position"].split(","))
                x, y = to_query(M_big, mx, my)
                if not (0 <= x < W and 0 <= y < H):
                    continue
                ug = (m.get("extra") or {}).get("underground") or {}
                color = (255, 0, 255) if ug.get("is_underground") else (0, 255, 0)
                cv2.circle(vis, (int(round(x)), int(round(y))), 26, color, 4)
                cv2.drawMarker(vis, (int(round(x)), int(round(y))), color, cv2.MARKER_CROSS, 16, 2)
                shown += 1
                if ug.get("region_levels"):
                    print(f"    waypoint {m['id']} at screen ({x:.0f}, {y:.0f}): levels {ug['region_levels']}")
            print(f"[big map] projected {shown} Kongying markers titled {args.title!r} "
                  f"(green surface, magenta layered)")
        half = (W // 2, H // 2)
        cv2.imwrite(str(out / "bigmap_projected.jpg"), cv2.resize(vis, half, interpolation=cv2.INTER_AREA))
        blend = cv2.addWeighted(big, 0.5, ref_in_query_space(M_big, (W, H)), 0.5, 0)
        cv2.imwrite(str(out / "bigmap_blend.jpg"), cv2.resize(blend, half, interpolation=cv2.INTER_AREA))
        if args.arrow:
            arrow_marker = to_marker(M_big, *args.arrow)
            print(f"[big map] player arrow at screen {tuple(args.arrow)} -> marker "
                  f"({arrow_marker[0]:.1f}, {arrow_marker[1]:.1f})")

# ---------- Minimap ----------
if args.gameplay:
    CX, CY, R = 399.5, 207.5, 176.0  # ring measured on 3120x1440 (measure_minimap.py)
    game = cv2.imread(args.gameplay)
    k = game.shape[0] / 1440.0
    cx, cy, r = CX * k, CY * k, R * k
    pad = int(r) + 4
    x0, y0 = int(round(cx)) - pad, int(round(cy)) - pad
    crop = game[y0:y0 + 2 * pad, x0:x0 + 2 * pad]
    lx, ly = cx - x0, cy - y0  # ring centre inside the crop
    cmask = np.zeros(crop.shape[:2], np.uint8)
    cv2.circle(cmask, (int(lx), int(ly)), int(r - 6), 255, -1)   # inside the ring only
    cv2.circle(cmask, (int(lx), int(ly)), int(24 * k), 0, -1)    # player arrow
    crop_gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)

    def vs_arrow(mx, my):
        if arrow_marker is None:
            return ""
        return f"; {math.hypot(mx - arrow_marker[0], my - arrow_marker[1]):.1f} units from the big-map arrow"

    # (1) SIFT, nothing known in advance. Upsampling helps SIFT on a 350 px wide crop.
    sift_scales, sift_pos = [], None
    for up in (1, 2):
        g = crop_gray if up == 1 else cv2.resize(crop_gray, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
        m = cmask if up == 1 else cv2.resize(cmask, None, fx=up, fy=up, interpolation=cv2.INTER_NEAREST)
        M, st = register(g, m, f"minimap sift x{up}", ratio=0.8, thresh=4.0, upsampled=up)
        if M is None:
            continue
        pmx, pmy = to_marker(M, lx * up, ly * up)
        px_per_unit = 1 / (st["scale"] * upp * up)
        print(f"[minimap sift x{up}] player at marker ({pmx:.1f}, {pmy:.1f}), "
              f"{px_per_unit:.4f} minimap px per unit{vs_arrow(pmx, pmy)}")
        if st["inliers"] >= 6 and abs(st["rot"]) < 2:
            sift_scales.append(px_per_unit)
            sift_pos = (pmx, pmy)
            Mc = M.copy()
            Mc[:, :2] *= up  # crop px -> reference px
            side = np.hstack([crop, ref_in_query_space(Mc, (crop.shape[1], crop.shape[0]))])
            cv2.imwrite(str(out / "minimap_vs_ref.png"), side)

    # (2) Template matching with the scale known in advance (how tracking would work).
    scale = args.minimap_scale or (float(np.median(sift_scales)) if sift_scales else None)
    if scale:
        f = 1.0 / (scale * upp)  # crop px -> reference px
        size = (max(int(round(crop.shape[1] * f)), 8), max(int(round(crop.shape[0] * f)), 8))
        interp = cv2.INTER_AREA if f < 1 else cv2.INTER_CUBIC
        tmask = (cv2.resize(cmask, size, interpolation=cv2.INTER_NEAREST) > 0).astype(np.float32)

        def grad(img):
            img = cv2.GaussianBlur(img.astype(np.float32), (0, 0), 1.0)
            return cv2.magnitude(cv2.Sobel(img, cv2.CV_32F, 1, 0), cv2.Sobel(img, cv2.CV_32F, 0, 1))

        variants = {
            "gray": (ref_gray.astype(np.float32), cv2.resize(crop_gray, size, interpolation=interp).astype(np.float32)),
            "gradient": (grad(ref_gray), grad(cv2.resize(crop_gray, size, interpolation=interp))),
        }
        for name, (image, templ) in variants.items():
            if image.shape[0] < templ.shape[0] or image.shape[1] < templ.shape[1]:
                print(f"[minimap template {name}] reference smaller than the template")
                continue
            score = cv2.matchTemplate(image, templ, cv2.TM_CCOEFF_NORMED, mask=tmask)
            score = np.clip(np.nan_to_num(score, nan=-1.0, posinf=-1.0, neginf=-1.0), -1.0, 1.0)
            _, best, _, loc = cv2.minMaxLoc(score)
            rest = score.copy()
            cv2.circle(rest, loc, max(size) // 2, -1.0, -1)  # second peak outside the best one
            second = float(rest.max())
            pmx = (loc[0] + lx * f) * upp + ox
            pmy = (loc[1] + ly * f) * upp + oy
            line = (f"[minimap template {name}] scale {scale:.3f} px per unit: player at marker "
                    f"({pmx:.1f}, {pmy:.1f}), peak {best:.3f}, next best {second:.3f}{vs_arrow(pmx, pmy)}")
            if sift_pos:
                line += f"; {math.hypot(pmx - sift_pos[0], pmy - sift_pos[1]):.1f} units from the SIFT result"
            print(line)
    else:
        print("[minimap template] skipped: no scale given and SIFT gave none")
