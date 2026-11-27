"""Export numeric ROI evidence from the first MOTION phone log, in capture order."""
import argparse
import re
from pathlib import Path


def export(log, neighbours):
    frames = []
    for line in log:
        if 'BigMapMotion stage=' not in line:
            continue
        f = dict(re.findall(r'(\w+)=([^\s]+)', line))
        if f['stage'] == 'step':
            frames.append((f, []))
        elif f['stage'] == 'control':
            step, controls = frames[-1]
            if f['tsNs'] != step['tsNs'] or int(f['roi']) != len(controls):
                raise ValueError('misaligned control log')
            controls.append(f)
    keep = set()
    for i, (_, controls) in enumerate(frames):
        if len(controls) != 2:
            raise ValueError('incomplete control log')
        if any(c['outcome'] == 'overlay' for c in controls):
            keep.update(range(i-2, i+3))
    print('# index roi scaleStep txStep tyStep coverage warpedResidual fixedResidual warpedFrac fixedFrac screenFixed verdict(0=?,1=map,2=overlay)')
    for i, (step, controls) in enumerate(frames):
        if neighbours and i not in keep:
            continue
        for c in controls:
            print(i, c['roi'], *(step[k] for k in ('scaleStep', 'txStep', 'tyStep')),
                  *(c[k] for k in ('coverage', 'warpedResidual', 'fixedResidual', 'warpedFrac', 'fixedFrac', 'screenFixed')),
                  {'unverifiable': 0, 'map': 1, 'overlay': 2}[c['outcome']])


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path)
    p.add_argument('--neighbours', action='store_true')
    args = p.parse_args()
    with args.log.open() as log:
        export(log, args.neighbours)
