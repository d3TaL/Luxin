"""Evaluate active-layer predictions against user labels; never evaluates position accuracy.

Usage: python -I spike/evaluate_layer_labels.py TRACK_CSV LABELS_JSON [--alias ref=LAYER ...]
Boundary regions are excluded according to boundary_exclusion_s in the label file.
Position coverage is separate from layer coverage. Split-tracker rows require a known layer.
Unknown and rejected predictions remain unresolved, not correct; no predictions => no precision.
This is a development recording, not a held-out accuracy benchmark.
"""
import argparse
import csv
import json
from pathlib import Path


def evaluate(rows, labels, aliases=None):
    aliases = aliases or {}
    margin = labels.get("boundary_exclusion_s", 0)
    result = []
    for interval in labels["intervals"]:
        counts = dict(frames=0, position_known=0, correct=0, wrong=0, unresolved=0, unmapped=0)
        for row in rows:
            t = float(row["t"])
            if not interval["start_s"] + margin <= t < interval["end_s"] - margin:
                continue
            counts["frames"] += 1
            if row.get("mode") not in ("found", "track") or not row.get("x"):
                counts["unresolved"] += 1
                continue
            counts["position_known"] += 1
            if "layer" in row:
                # An explicit layer column never falls back to legacy IDs or aliases.
                if not row["layer"] or row.get("layer_mode") != "known":
                    counts["unresolved"] += 1
                elif (row.get("space") == labels["space"]
                      and row["layer"] in (interval["layer"],
                                           labels["space"] + "/" + interval["layer"])):
                    counts["correct"] += 1
                else:
                    counts["wrong"] += 1
                continue
            identity = row.get("map_id", "")
            prefix = labels["space"] + "/"
            if identity.startswith(prefix):
                prediction = identity[len(prefix):]
            elif identity and not identity.startswith("unknown:"):
                prediction = "different-space:" + identity
            else:
                prediction = aliases.get(row.get("ref"))
            if prediction is None:
                counts["unmapped"] += 1
            elif prediction == interval["layer"]:
                counts["correct"] += 1
            else:
                counts["wrong"] += 1
        n, resolved = counts["frames"], counts["correct"] + counts["wrong"]
        result.append(dict(start_s=interval["start_s"], end_s=interval["end_s"],
                           expected_layer=interval["layer"], **counts,
                           position_coverage=counts["position_known"] / n if n else None,
                           correct_coverage=counts["correct"] / n if n else None,
                           precision_when_resolved=counts["correct"] / resolved if resolved else None))
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("track")
    ap.add_argument("labels")
    ap.add_argument("--alias", action="append", default=[])
    args = ap.parse_args()
    aliases = dict(pair.split("=", 1) for pair in args.alias)
    labels = json.loads(Path(args.labels).read_text())
    with open(args.track, newline="") as fh:
        rows = list(csv.DictReader(fh))
    print(json.dumps(evaluate(rows, labels, aliases), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
