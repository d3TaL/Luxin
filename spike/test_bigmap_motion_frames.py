"""End-to-end desktop replay checks. Run with `python -I .../test_bigmap_motion_frames.py BINARY`."""
import csv
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cv2
from compare_bigmap_motion_frames import compare, matrix
from test_bigmap_motion_eval import make_textured_canvas, synthesize_sequence
from bigmap_motion_eval import make_3dof
from bigmap_motion_eval import pose_corner_error_3120

HEADER = 'index,timestampNs,width,height,generation,screenMode,dropped_before,has_color,source_width,source_height,scale'.split(',')
BINARY = None


class ReplayTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dump = Path(tmp.name)
        self.rows = []

    def burst(self, count=16, size=(1170, 540), panel=False):
        w, h = size
        canvas = make_textured_canvas(1800, 1000)
        transforms = []
        for i in range(count):
            s = 1.025**i
            transforms.append(make_3dof(s, i*5 + (1-s)*w*.5, -i*3 + (1-s)*h*.5))
        frames = synthesize_sequence(canvas, size, transforms)
        ns = 9007199254740993  # must not pass through a float
        for i, f in enumerate(frames):
            ns += [16, 33, 67][i % 3] * 1000000
            index = i*2 + 7
            if panel and i == count-1:
                cv2.rectangle(f.gray, (int(w*.89), int(h*.33)), (int(w*.96), int(h*.67)), 0, -1)
            # Mix plain/padded indexes: never infer ordering/timestamps from a directory listing.
            name = f'f_{index:06d}_{ns}.png' if i%2 else f'f_{index}_{ns}.png'
            self.assertTrue(cv2.imwrite(str(self.dump/name), f.gray))
            self.rows.append([index,ns,w,h,1,'big_map',int(i==3),0,3120,1440,f'{h/1440:.6f}'])
        self.manifest()

    def manifest(self):
        with (self.dump/'frames.csv').open('w', newline='') as fh:
            writer = csv.writer(fh)
            writer.writerow(HEADER)
            writer.writerows(self.rows)

    def run_native(self, ok=True, stride=1):
        p = subprocess.run([str(BINARY),str(self.dump),'--stride',str(stride)], capture_output=True, text=True)
        self.assertEqual(p.returncode == 0, ok, p.stderr)
        return list(csv.DictReader(io.StringIO(p.stdout)))

    def test_reference_agreement(self):
        self.burst(panel=True)
        native = self.run_native()
        self.assertEqual(len(native), len(self.rows))
        self.assertEqual([int(r['timestampNs']) for r in native], [r[1] for r in self.rows])
        self.assertEqual(native[0]['reason'], 'no_previous_frame')
        self.assertEqual(native[0]['step_scale'], '')
        self.assertAlmostEqual(float(native[0]['cum_scale']), 1)
        # A panel appearing on the last moving frame is not yet screen-fixed evidence.
        self.assertEqual(native[-1]['roi_right'], 'unverifiable')
        self.assertEqual(native[-1]['overlay_hidden'], '0')
        self.assertGreater(float(native[-1]['right_warped_residual']),0.)
        self.assertGreater(float(native[-1]['right_fixed_residual']),0.)
        self.assertEqual(native[-1]['ok'], '1')  # valid relative LK despite hidden geometry
        self.assertTrue(all(float(r['update_ms'])>=float(r['lk_ms'])>=0 for r in native))
        steps, err, idx = compare(BINARY, self.dump)
        self.assertEqual(steps, 15)
        self.assertLessEqual(err, 3.)
        print(f'synthetic_540p frames={steps+1} worst_index={idx} max_corner_error_3120={err:.9g}')

    def test_generation_and_loss(self):
        self.burst(5,(780,360))
        self.rows[-1][4] = 2
        self.manifest()
        idx, ns = self.rows[2][:2]
        path = self.dump/f'f_{idx}_{ns}.png'
        img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        img[:] = 0
        self.assertTrue(cv2.imwrite(str(path), img))
        rows = self.run_native()
        self.assertEqual(rows[2]['ok'], '0')
        self.assertEqual(rows[2]['cum_scale'], '')
        self.assertEqual(rows[3]['bridge'], '1')
        self.assertNotEqual(rows[3]['epoch'], rows[4]['epoch'])
        self.assertEqual(rows[4]['reason'], 'no_previous_frame')
        with self.assertRaisesRegex(ValueError, 'epoch boundary'):
            compare(BINARY, self.dump)
        with self.assertRaisesRegex(ValueError, 'epoch boundary'):
            compare(BINARY, self.dump, ok_rate_only=True)
        self.rows[-1][4] = 1
        self.manifest()
        with self.assertRaisesRegex(ValueError, 'no comparable fresh step'):
            compare(BINARY, self.dump)

    def test_corner_metric_detects_zoom_at_fixed_centre(self):
        # Wrong zoom with zero centre error must still exceed the 3px budget.
        ref = matrix(1., 0., 0., 1440, 780, 360)
        est = matrix(1.003, 0., 0., 1440, 780, 360)
        self.assertGreater(pose_corner_error_3120(est, ref, 3120, 1440), 3.)

    def test_stride_selects_rows_and_preserves_timestamps(self):
        self.burst(9, (780, 360))
        native = self.run_native(stride=4)
        self.assertEqual([int(r['index']) for r in native], [r[0] for r in self.rows[::4]])
        self.assertEqual([int(r['timestampNs']) for r in native], [r[1] for r in self.rows[::4]])
        details = []
        steps, error, _ = compare(BINARY, self.dump, stride=4, details=details)
        self.assertEqual(steps, 2)
        self.assertEqual([i for i, _ in details], [r[0] for r in self.rows[4::4]])
        self.assertLessEqual(error, 3.)
        self.assertEqual(compare(BINARY, self.dump, stride=4, ok_rate_only=True), (2, 2, 2))
        self.run_native(ok=False, stride=0)
        self.run_native(ok=False, stride=100)
        with self.assertRaisesRegex(ValueError, 'stride must be positive'):
            compare(BINARY, self.dump, stride=0)
        # Even a skipped row must not hide a malformed timeline.
        self.rows[2][1] = self.rows[0][1]
        self.manifest()
        self.run_native(ok=False, stride=4)

    def test_reject_invalid_manifest_or_missing_image(self):
        self.burst(3,(780,360))
        last = self.rows[-1][:]
        for col,value in [(0,self.rows[0][0]), (1,self.rows[0][1]), (2,700), (9,0), (10,'nan')]:
            self.rows[-1][col] = value
            self.manifest(); self.run_native(ok=False)
            self.rows[-1] = last[:]
        self.manifest()
        (self.dump/f'f_{last[0]}_{last[1]}.png').unlink()
        self.run_native(ok=False)


if __name__ == '__main__':
    BINARY = Path(sys.argv.pop(1)).resolve()
    cv2.setNumThreads(1)
    unittest.main(verbosity=2)
