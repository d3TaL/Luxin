"""Synthetic reference-pack checks; no downloaded assets. python -I spike/test_package_references.py"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import cv2
import numpy as np

SCRIPT = Path(__file__).with_name("package_references.py")


class PackageReferenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.prefix = self.root / "ref"
        cv2.imwrite(str(self.prefix) + ".png", np.full((32, 48, 3), 70, np.uint8))
        self.meta = dict(tile_code="twt702", units_per_px=2.0, origin=[-100, 200], size=[48, 32])

    def tearDown(self):
        self.temp.cleanup()

    def run_pack(self, mode, refs=None):
        Path(str(self.prefix) + ".json").write_text(json.dumps(self.meta))
        cmd = [sys.executable, "-I", str(SCRIPT), str(self.root / "pack.zip"), "--id", "test-pack",
               "--title", "Synthetic", "--mode", mode]
        for ref in refs or ["Surface=" + str(self.prefix)]:
            cmd += ["--ref", ref]
        return subprocess.run(cmd, capture_output=True, text=True)

    def test_position_only_pack_has_explicit_geometry_and_full_masks(self):
        r = self.run_pack("position_only")
        self.assertEqual(r.returncode, 0, r.stderr)
        with zipfile.ZipFile(self.root / "pack.zip") as z:
            m = json.loads(z.read("manifest.json"))
            layer = m["layers"][0]
            self.assertEqual(m["mode"], "position_only")
            self.assertEqual((layer["originX"], layer["originY"], layer["unitsPerPx"]), (-100, 200, 2))
            mask = cv2.imdecode(np.frombuffer(z.read(layer["contextMask"]), np.uint8), 0)
            self.assertEqual(mask.shape, (32, 48))
            self.assertTrue((mask == 255).all())

    def test_layered_pack_preserves_layer_identity_and_masks(self):
        cv2.imwrite(str(self.root / "active.png"), np.full((32, 48), 128, np.uint8))
        self.meta.update(layer="UG1", map_id="twt702/UG1", masks=dict(active="active.png", context="active.png"))
        r = self.run_pack("layered")
        self.assertEqual(r.returncode, 0, r.stderr)
        with zipfile.ZipFile(self.root / "pack.zip") as z:
            layer = json.loads(z.read("manifest.json"))["layers"][0]
            self.assertEqual((layer["space"], layer["id"]), ("twt702", "UG1"))
            self.assertTrue((cv2.imdecode(np.frombuffer(z.read(layer["activeMask"]), np.uint8), 0) == 128).all())

    def test_duplicate_layer_identity_does_not_create_pack(self):
        cv2.imwrite(str(self.root / "active.png"), np.full((32, 48), 255, np.uint8))
        self.meta.update(layer="UG1", map_id="twt702/UG1", masks=dict(active="active.png", context="active.png"))
        r = self.run_pack("layered", ["A=" + str(self.prefix), "B=" + str(self.prefix)])
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse((self.root / "pack.zip").exists())


if __name__ == "__main__":
    unittest.main()
