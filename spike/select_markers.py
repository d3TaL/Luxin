"""Pick the markers of one Kongying area out of the full marker dump.

Markers carry no area field: the area is that of the marker's first item
(marker.itemList[0].itemId -> item.areaId).

Usage: python -I select_markers.py <markers.json> <area_id> <out.json> <items.json> [<items.json> ...]
  markers.json  {"markers": [...]} decoded from /api/marker_doc/list_markers
  items.json    decompressed pages of /api/item_doc/list_page_bin
"""
import collections
import json
import sys
from pathlib import Path

markers_path, area_id, out_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
item_area = {}
for f in sys.argv[4:]:
    for item in json.loads(Path(f).read_text(encoding="utf-8")):
        item_area[item["id"]] = item["areaId"]

markers = json.loads(Path(markers_path).read_text(encoding="utf-8"))["markers"]
picked = [m for m in markers
          if m.get("itemList") and item_area.get(m["itemList"][0]["itemId"]) == area_id]
Path(out_path).write_text(json.dumps({"markers": picked}, ensure_ascii=False), encoding="utf-8")

levels = collections.Counter()
for m in picked:
    ug = (m.get("extra") or {}).get("underground") or {}
    for lv in ug.get("region_levels") or (["(layered, no level)"] if ug.get("is_underground") else ["(surface)"]):
        levels[lv] += 1
print(f"area {area_id}: {len(picked)} markers")
for lv, n in levels.most_common():
    print(f"  {n:5d}  {lv}")
