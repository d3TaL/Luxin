"""Compare the native relative chain with lk_3dof_pred on one complete Android burst."""
import argparse
import csv
import math
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bigmap_motion_eval as reference


def matrix(scale, dx, dy, height_3120, frame_width, frame_height):
    # Convert a centre displacement to the common aspect; uniform scale is invariant.
    dy *= height_3120 / (3120. * frame_height / frame_width)
    return reference.make_3dof(scale, dx + (1-scale)*1560., dy + (1-scale)*height_3120*.5)


def compare(binary, dump):
    # A partial native CSV (e.g. a missing PNG midway through) must never pass.
    run = subprocess.run([str(binary.resolve()), str(dump)], check=True, text=True, stdout=subprocess.PIPE)
    native = list(csv.DictReader(run.stdout.splitlines()))
    with (dump / 'frames.csv').open(newline='', encoding='utf-8') as fh:
        manifest = list(csv.DictReader(fh))
    frames = reference.load_frames_from_dump(dump, work_height=360)
    if not (len(native) == len(frames) == len(manifest) >= 2):
        raise ValueError('incomplete frame sequence')
    for row, frame, entry in zip(native, frames, manifest):
        if (int(row['index']) != frame.index or row['index'] != entry['index'] or
                int(row['timestampNs']) != int(entry['timestampNs'])):
            raise ValueError('frame identity mismatch')
    if any(row['epoch'] != native[0]['epoch'] for row in native):
        raise ValueError('epoch boundary inside comparison; use a single continuous burst')

    h, w = frames[0].gray.shape
    mask = reference.bigmap_geometry_mask(w, h)
    rows, _ = reference.evaluate_method('lk_3dof_pred', frames, mask)
    if len(rows) != len(frames)-1:
        raise ValueError('incomplete reference sequence')
    height = h * 3120. / w
    worst = 0.
    worst_index = frames[0].index
    for row, ref in zip(native[1:], rows):
        if int(row['index']) != ref.frame_idx or row['ok'] != '1' or not ref.ok:
            raise ValueError(f"no comparable fresh step at index {row['index']} (native={row['reason']}, py={ref.reason})")
        # Both CSV shifts describe the displacement of the centre, NOT the affine offset.
        # Convert both matrices to the same 3120-wide pixel grid. Use the oracle's actual
        # working aspect ratio (rounded resize can differ slightly from the dump aspect).
        actual = matrix(float(row['cum_scale']), float(row['cum_tx_3120']), float(row['cum_ty_3120']), height,
                        int(row['width']), int(row['height']))
        expected = matrix(ref.cum_scale, ref.cum_tx_3120, ref.cum_ty_3120, height, w, h)
        error = reference.pose_corner_error_3120(actual, expected, 3120, height)
        if not math.isfinite(error):
            raise ValueError('non-finite corner error')
        if error > worst:
            worst, worst_index = error, ref.frame_idx
    return len(rows), worst, worst_index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('dump', type=Path)
    args = parser.parse_args()
    reference.cv2.setNumThreads(1)
    try:
        steps, error, idx = compare(args.binary, args.dump)
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as exc:
        parser.exit(2, f'NOT VERIFIED: {exc}\n')
    print(f'steps={steps} max_corner_error_3120={error:.9g} worst_index={idx} limit=3')
    return int(error > 3.)


if __name__ == '__main__':
    sys.exit(main())
