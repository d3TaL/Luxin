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


def compare(binary, dump, stride=1, details=None, ok_rate_only=False):
    if stride < 1:
        raise ValueError('stride must be positive')
    # A partial native CSV (e.g. a missing PNG midway through) must never pass.
    run = subprocess.run([str(binary.resolve()), str(dump), '--stride', str(stride)], check=True, text=True, stdout=subprocess.PIPE)
    native = list(csv.DictReader(run.stdout.splitlines()))
    with (dump / 'frames.csv').open(newline='', encoding='utf-8') as fh:
        manifest = list(csv.DictReader(fh))[::stride]
    frames = reference.load_frames_from_dump(dump, work_height=360, stride=stride)
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
    if any(int(n['index']) != r.frame_idx for n, r in zip(native[1:], rows)):
        raise ValueError('reference frame identity mismatch')
    if ok_rate_only:
        return len(rows), sum(r['ok']=='1' for r in native[1:]), sum(r.ok for r in rows)
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
        if details is not None:
            details.append((ref.frame_idx,error))
        if error > worst:
            worst, worst_index = error, ref.frame_idx
    return len(rows), worst, worst_index


def compare_sift(binary, dump, stride, details=False):
    """Evaluate whole native chain against a common full-rate SIFT trajectory. After a
    fallback, seed the Python LK oracle with that step (not ground truth) and keep
    checking cumulative error on every following non-fallback frame."""
    if stride < 1:
        raise ValueError('stride must be positive')
    all_frames = reference.load_frames_from_dump(dump, work_height=360)
    frames = all_frames[::stride]
    h, w = frames[0].gray.shape
    mask = reference.bigmap_geometry_mask(w,h)
    gt = reference.compute_gt_from_sift(all_frames,mask)
    run = subprocess.run([binary.resolve(),dump,'--stride',str(stride)],check=True,text=True,stdout=subprocess.PIPE)
    rows = list(csv.DictReader(run.stdout.splitlines()))
    if len(frames) < 2 or len(rows) != len(frames) or any(int(r['index']) != f.index for r,f in zip(rows,frames)):
        raise ValueError('incomplete/misaligned replay')
    def work(row,prefix):
        s,dx,dy = (float(row[f'{prefix}_{k}']) for k in ('scale','tx_3120','ty_3120'))
        return reference.make_3dof(s, dx*w/3120+(1-s)*w/2, dy*w/3120+(1-s)*h/2)
    prev = None
    chain = reference.make_3dof(1,0,0)
    worst_lk = 0.
    errors = []
    count = missed = 0
    last_good = 0
    for i,(frame,row) in enumerate(zip(frames[1:],rows[1:]),1):
        if row['epoch'] != rows[0]['epoch'] or row['lost'] != '0':
            raise ValueError(f"lost chain at {frame.index}")
        if row['ok'] != '1':
            if row['cum_scale'] or not int(row['fallback_tried']): raise ValueError(f'unexpected dropout {frame.index}')
            missed += 1
            continue
        if bool(int(row['bridge'])) != (last_good != i-1): raise ValueError('incorrect bridge provenance')
        native = work(row,'cum')
        if i*stride in gt:
            err = reference.pose_corner_error_3120(native,gt[i*stride],w,h)
            if not math.isfinite(err): raise ValueError('invalid GT comparison')
            errors.append((frame.index,err))
        dt = frame.timestamp_s - frames[last_good].timestamp_s
        if int(row['fallback_ok']):
            if i*stride not in gt: raise ValueError(f'no independent SIFT coverage at fallback {frame.index}')
            count += 1
            step = work(row,'step')
        else:
            if last_good != i-1: raise ValueError(f'no Python velocity oracle for LK bridge at {frame.index}')
            prediction = reference._predict_3dof_step(prev,dt/prev_dt,w,h) if prev is not None else None
            lk = reference.estimate_lk_3dof_step(frames[i-1].gray,frame.gray,mask,init_transform=prediction)
            if not lk.ok: raise ValueError(f'Python LK rejected non-fallback step {frame.index}')
            step = lk.matrix
        chain = reference.compose_affine(step,chain)
        if not int(row['fallback_ok']):
            err = reference.pose_corner_error_3120(chain,native,w,h)
            if not math.isfinite(err): raise ValueError('invalid LK comparison')
            worst_lk = max(worst_lk,err)
        prev,prev_dt,last_good = step,dt,i
    if not errors or errors[-1][0] != frames[-1].index: raise ValueError('missing SIFT endpoint')
    if details: print('GT_index:error_3120='+','.join(f'{i}:{v:.3f}' for i,v in errors))
    print(f'stride={stride} steps={len(rows)-1} fallback_ok={count} rejected={missed} lost=0 gt_samples={len(errors)} '
          f'gt_max={max(v for _,v in errors):.3f} gt_end={errors[-1][1]:.3f} lk_max={worst_lk:.5f}')
    return fails_sift_budget(worst_lk, errors, stride)


def fails_sift_budget(worst_lk, errors, stride):
    return worst_lk > 3 or max(v for _,v in errors) > (30 if stride <= 4 else 40)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('dump', type=Path)
    parser.add_argument('--stride', type=int, default=1)
    parser.add_argument('--ok-rate-only', action='store_true')
    parser.add_argument('--gt-sift', action='store_true', help='full-rate SIFT pseudo-GT plus LK-only agreement')
    parser.add_argument('--details', action='store_true', help='top ten errors and indices 107/126')
    args = parser.parse_args()
    reference.cv2.setNumThreads(1)
    try:
        if args.gt_sift:
            return int(compare_sift(args.binary,args.dump,args.stride,args.details))
        details=[]
        steps, error, idx = compare(args.binary, args.dump,args.stride,details,args.ok_rate_only)
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as exc:
        parser.exit(2, f'NOT VERIFIED: {exc}\n')
    if args.ok_rate_only:
        print(f'stride={args.stride} steps={steps} native_ok={error}/{steps} python_ok={idx}/{steps}')
        return 0
    print(f'stride={args.stride} steps={steps} max_corner_error_3120={error:.9g} worst_index={idx} limit=3')
    if args.details:
        print('top10='+','.join(f'{i}:{e:.9g}' for i,e in sorted(details,key=lambda d:d[1],reverse=True)[:10]))
        for wanted in (107,126):
            print(f'index={wanted} error_3120='+next((f'{e:.9g}' for i,e in details if i==wanted),'not_sampled'))
    return int(error > 3.)


if __name__ == '__main__':
    sys.exit(main())
