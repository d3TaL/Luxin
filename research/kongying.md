# Kongying Tavern map (空荧酒馆) — programmatic data access notes

Research date: 2026-11-10 (all live checks done that day, about 40 sequential requests in total, curl only).
Labels used below: **[V]** = verified with a live request, **[S]** = read in source only (inferred), **[?]** = could not be determined.

Repos read (shallow clones under `/root/genshin/research/src/`, nothing from them was executed):

| repo | HEAD | last commit | role |
|---|---|---|---|
| `kongying-tavern/genshin-web-v3` | `0e80dd09` | 2026-08-28 | the public site (Quasar/Vue + Leaflet), live at `https://v3.yuanshen.site` |
| `kongying-tavern/genshin-admin-v3` | `b5ea156b` | 2026-11-10 | marker editor (Vue + deck.gl); newest schema, protobuf, tile code |
| `kongying-tavern/genshin-cloud-api` | `87687907` | 2026-10-29 | backend (Spring Cloud gateway + OAuth2) |
| `kongying-tavern/genshin-map-v5` | `dfc071fa` | 2023-08-20 | stale, NOT the current front end |

## 0. Corrections to the "already known" list

- `https://yuanshen.site` only JS-redirects to `https://v3.yuanshen.site`, which is built from `genshin-web-v3`, not `genshin-map-v5` **[V]** (`samples/live_site/`).
- Base map tiles are served from `https://assets.yuanshen.site/tiles_<code>/...`, not from `tiles.yuanshen.site` **[V]**. `tiles.yuanshen.site` is an AList file server: any non-`/d/` path returns its HTML UI (HTTP 200 `text/html`), and `/d/<path>` answers `302` to `https://oss.yuanshen.site/kyjg/...`. It hosts the layered-map overlay images and 189 of the 1194 icons **[V]**.
- `https://assets.yuanshen.site/dadian-preview.json.bz2`, `dadian.json.bz2`, `dadian.json` all return **404** now **[V]**. Current config files are `https://assets.yuanshen.site/webapp.json` (plain JSON, used by web-v3) and `https://assets.yuanshen.site/system.json.bz2` (bzip2, used by admin-v3) **[V]**.
- "Big datasets as protobuf/binary" is only partly true: the chunked `*_doc/list_page_bin` endpoints are **gzip'd JSON**; protobuf is used only by `marker_doc/list_markers` (gzip'd) and `marker_doc/list_diff_snapshot` (raw) **[V]**.

## 1. API access

- Production backend base: `https://cloud.yuanshen.site` — token at `/oauth/token`, data under `/api/...` **[V]**.
  - Source: `genshin-web-v3/src/boot/axios.js:12` (`baseURL: ${process.env.VITE_API_BASE}/api`), `genshin-web-v3/src/service/user_request.js:20-31`; the production value of `VITE_API_BASE` is not in the repo, it was read from the live bundle (`samples/live_site/index.6b600c49.js.txt` contains `url:"https://cloud.yuanshen.site/oauth/token"`).
  - `genshin-admin-v3/.env.production` points at a different (dev) backend, `https://genshin-dev.momincong.com`; not touched.
- Guest token flow **[V]** (`genshin-web-v3/src/service/user_request.js:20-31`, README.md:31-41):

```
curl -X POST 'https://cloud.yuanshen.site/oauth/token?grant_type=client_credentials&refresh_token=all' \
     -H 'Authorization: Basic Y2xpZW50OnNlY3JldA=='
```

  - `Y2xpZW50OnNlY3JldA==` is base64 of `client:secret` (hard-coded in the public front end). Parameters go in the query string; no body.
  - Response (HTTP 200, `application/json`): `{"access_token":"<JWT, RS256, 514 chars>","token_type":"bearer","expires_in":1799,"scope":"all","jti":"<uuid>"}`. JWT payload: `{"scope":["all"],"exp":...,"jti":"...","client_id":"client"}`. No refresh token is issued.
  - Lifetime: **1799 s (30 min)**. The value lives in the backend DB (`oauth_client_details.access_token_validity`), not in the repo, so it can change. web-v3 re-requests 360 s before expiry (`user_request.js:6-17`).
  - Use: `Authorization: Bearer <access_token>` on every `/api/...` call. Without it: HTTP **401**, empty body **[V]**.
- What a guest may call **[S]**: gateway whitelist for role `VISITOR` in `genshin-cloud-api/genshin-map-ability/genshin-map-ability-gateway/src/main/resources/bootstrap.yml:13-63`:
  `/api/icon/get/**, /api/icon_type/get/**, /api/icon_doc/**, /api/area/get/**, /api/item/get/**, /api/item_type/get/**, /api/item_doc/**, /api/marker/get/**, /api/marker_doc/**, /api/marker_link/get/**, /api/marker_link_doc/**, /api/notice/get/**`. Anything else gives 403 (`.../gateway/oauth2/SecurityFilter.java:40-91`).
  `RoleEnum.java:11-16`: `VISITOR(..., 100, 1|8)` — data level mask = hidden flags 0 (normal) and 3 (easter egg); flags 1 (invisible) and 2 (beta) are never returned to guests (`HiddenFlagEnum.java:14-29`).
- JSON envelope for all non-binary endpoints **[V]**:
  `{"error":false,"errorStatus":200,"errorData":null,"message":"成功","data":...,"users":{...},"time":"2026-11-10T16:08:35.450186"}`.
  `users` maps creator/updater ids to `{username,nickname,qq,phone,logo,remark}` of contributors (personal data — ignore it; it is stripped from the saved samples).

## 2. Endpoints and payloads

All verified with a guest token on `https://cloud.yuanshen.site`. `$T` = access token.

| what | request | format | sample |
|---|---|---|---|
| areas | `POST /api/area/get/list` body `{"parentId":-1,"isTraverse":true}` | JSON envelope, 48 areas | `samples/api/area_get_list.json` |
| item types | `POST /api/item_type/get/list_all` body `{}` | JSON envelope, 11 types | `samples/api/item_type_get_list_all.json` |
| items (bulk) | `GET /api/item_doc/list_page_bin_md5` then `GET /api/item_doc/list_page_bin/{md5}` | md5 list (2 chunks); each chunk = gzip of a JSON array; 5130 items in total | `samples/api/item_doc_*` |
| icons (bulk) | `GET /api/icon_doc/all_bin_md5`, `GET /api/icon_doc/all_bin` | `{md5,time}`; gzip of a JSON array, 1194 icons | `samples/api/icon_doc_*` |
| icon types | `POST /api/icon_type/get/list` body `{"current":1,"size":100}` | JSON envelope, paged (`data.record[]`, `total`, `size`); only 2 dummy types exist | `samples/api/icon_type_get_list.json` |
| markers, chunked | `GET /api/marker_doc/list_page_bin_md5` then `GET /api/marker_doc/list_page_bin/{md5}` | md5 list (46 chunks); each chunk = gzip of a JSON array of markers | `samples/api/marker_doc_list_page_bin_*`, `marker_doc_page_*.decompressed.json` |
| markers, all at once | `GET /api/marker_doc/list_markers` | gzip of protobuf `MarkerVoList`; 12.3 MB gz, 33.2 MB raw, 131,113 markers, took 18.6 s | `samples/api/marker_doc_list_markers.users_stripped.pb.gz`, `.decoded.json` |
| markers, version index | `GET /api/marker_doc/list_diff_snapshot` | raw (uncompressed) protobuf `MarkerDiffSnapshotVoList`, 1.26 MB, 131,113 `{version,id,linkage_id?}` | `samples/api/marker_doc_list_diff_snapshot.pb` |
| markers, filtered | `POST /api/marker/get/list_byinfo` body e.g. `{"itemIdList":[4857]}` (also `areaIdList`, `typeIdList`) | JSON envelope, `data` = marker array | `samples/api/marker_get_list_byinfo_item4857.json` |
| markers, by id | `POST /api/marker/get/list_byid` body `{"markerIdList":[...]}` (`MarkerController.java:78-90`, `MarkerSearchVo.java:26-27`) | JSON envelope | not fetched **[S]** |

Working commands:

```
curl -H "Authorization: Bearer $T" https://cloud.yuanshen.site/api/marker_doc/list_page_bin_md5
curl -H "Authorization: Bearer $T" -o chunk.bin https://cloud.yuanshen.site/api/marker_doc/list_page_bin/ae01602a28fbd8ce3b46dff8d0e164bc   # then gunzip
curl -H "Authorization: Bearer $T" -o markers.pb.gz https://cloud.yuanshen.site/api/marker_doc/list_markers
curl -H "Authorization: Bearer $T" -o snap.pb https://cloud.yuanshen.site/api/marker_doc/list_diff_snapshot
curl -H "Authorization: Bearer $T" https://cloud.yuanshen.site/api/item_doc/list_page_bin_md5
curl -H "Authorization: Bearer $T" -o icons.bin https://cloud.yuanshen.site/api/icon_doc/all_bin
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"parentId":-1,"isTraverse":true}' https://cloud.yuanshen.site/api/area/get/list
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{}' https://cloud.yuanshen.site/api/item_type/get/list_all
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"itemIdList":[4857]}' https://cloud.yuanshen.site/api/marker/get/list_byinfo
```

Endpoint definitions in source: `genshin-admin-v3/src/api/alova/apiDefinitions.ts:99-113` (doc endpoints), `genshin-web-v3/src/service/base_request.js:27-79` (POST endpoints), backend `.../genshin/core/controller/MarkerDocController.java:36-75`, `ItemDocController.java`, `IconDocController.java`, `MarkerController.java:51-97`.

### 2.1 Chunk discovery and compression

- md5 list **[V]**: `data` is an array of `{"md5":"ae01602a28fbd8ce3b46dff8d0e164bc","time":1794325316150}` (`time` = cache generation time, epoch ms). Icons return a single object instead of an array.
- A chunk is `gzip(UTF-8 JSON array)`; the md5 is the md5 of the **compressed** bytes exactly as downloaded (checked with `md5sum`) **[V]**. Source: `.../dao/impl/MarkerDaoImpl.java:455-458`, `genshin-map-commons/.../CompressUtils.java:16-27`.
- Marker chunking **[S]**: normal markers go to chunk index `id / 3000` (`.../data/helper/cache/MarkerCacheSplitter.java:15`), each other hidden flag gets one chunk; list is ordered by hidden flag then index (`MarkerDaoImpl.java:378-398`). Observed: 46 chunks; the first holds 2991 markers with ids 1..2999 (188 KB gz, 1.74 MB JSON) **[V]**. 46 = 45 normal chunks (max id 133437) + presumably 1 easter-egg chunk **[S]**.
- Responses are `application/octet-stream` with `cache-control: no-cache, no-store`; no ETag. Change detection = compare the md5 list (or use `list_diff_snapshot` versions).
- Incremental sync as done by admin-v3 (`src/stores/marker.ts:222-240`, `src/api/alova/index.ts:72-157`) **[S]**: full load via `list_markers`; afterwards fetch `list_diff_snapshot`, compare `{id,version}` locally, fetch changed ids via `marker/get/list_byid`.

### 2.2 Protobuf schema (verbatim, `genshin-admin-v3/src/api/protobuf/MarkerVo.proto`; same as backend `genshin-cloud-api/genshin-map-data/genshin-map-data-core/genshin-map-data-core-model/src/main/resources/proto/MarkerVo.proto`)

```proto
message MarkerVo {
    uint64 version = 1; uint64 id = 2; uint64 creator_id = 3; optional uint64 create_time = 4;
    uint64 updater_id = 5; optional uint64 update_time = 6; string marker_title = 8; string position = 9;
    string content = 10; string picture = 11; string video_path = 12; int64 refresh_time = 13;
    uint32 hidden_flag = 14; repeated MarkerItemLinkVo item_list = 15;
    optional uint64 marker_creator_id = 100; optional uint64 picture_creator_id = 101;
    string marker_stamp = 200; MarkerExtra extra = 400; string linkage_id = 500; }
message MarkerItemLinkVo { uint64 item_id = 1; uint64 icon_id = 2; uint32 count = 10; }
message MarkerExtra { optional MarkerExtraUnderground underground = 1; optional MarkerExtraIconOverride icon_override = 2;
    repeated string v_1_6_island = 100; optional MarkerExtra28Island v_2_8_island = 101; }
message MarkerExtraUnderground { bool is_underground = 1; optional bool is_global = 2; repeated string region_levels = 9; }
message MarkerExtraIconOverride { uint64 id = 1; float min_zoom = 2; float max_zoom = 3; }
message MarkerExtra28Island { string island_name = 1; repeated string island_state = 2; }
// MarkerVoList.proto
message MarkerVoList { repeated MarkerVo markers = 1; map<uint64, SysUserSmallVo> users = 2; }
// SysUserSmallVo: username=1, nickname=2, qq=3, optional phone=4, optional logo=5, optional remark=15
message MarkerDiffSnapshotVo { uint64 version = 1; uint64 id = 2; optional string linkage_id = 15; }
message MarkerDiffSnapshotVoList { repeated MarkerDiffSnapshotVo snapshots = 1; }
```

Decoded successfully with a hand-written wire decoder (`tools/decode_markers_pb.py`) **[V]**. The live `list_markers` body ends with 177 `users` map entries (top-level field 2; contributor names, QQ numbers, a few phone numbers) — a client should skip field 2. Proto3 defaults are omitted on the wire (empty strings, 0, `hidden_flag` 0, `refresh_time` 0); `refresh_time` is a signed varint (negative values take 10 bytes).

### 2.3 Marker fields (JSON names, verbatim from live data)

JSON chunk form (`marker_doc/list_page_bin`), marker id 1:

```json
{"content":"此处【风龙废墟顶部】收集1个【散失的风神瞳】、【冒险阅历+60】","createTime":1657736774000,"creatorId":1,"extra":{},"hiddenFlag":0,"id":1,"itemList":[{"count":1,"iconId":4,"itemId":6}],"linkageId":"","markerCreatorId":1,"markerStamp":"0_1","markerTitle":"散失的风神瞳","picture":"https://oss.yuanshen.site/kyjg/2024-12-11/5f81....png","pictureCreatorId":119,"position":"350.20001220703125,-4369.8005001106985","refreshTime":0,"updateTime":"2026-08-12 21:28:12.56","updaterId":7,"version":12,"videoPath":"https://www.bilibili.com/video/BV13y4y1z7Re?t=25.5&p=20"}
```

`marker/get/list_byinfo` form, a layered marker:

```json
{"version":5,"id":122077,"creatorId":10,"createTime":1782875103013,"updaterId":28,"updateTime":1786530167658,"markerStamp":"","markerTitle":"雪球花","position":"-10755.383777853294,-10755.202376835274","itemList":[{"itemId":4857,"count":1,"iconId":1309}],"content":"在此处【地下「古老废弃车站」】有1株【雪球花】","picture":"","markerCreatorId":10,"pictureCreatorId":null,"videoPath":"","refreshTime":-3,"hiddenFlag":0,"extra":{"underground":{"is_underground":true,"region_levels":["OLD_OBSOLETE_STATION"]}},"linkageId":""}
```

| field | meaning / format |
|---|---|
| `id` | integer marker id (1..133437 at the time; 131,113 present) |
| `position` | **string** `"x,y"`, two decimal numbers separated by one comma (all 131,113 markers; 5,599 are integers). Units = pixels of the zoom-13 tile mosaic relative to the tile set's `center` (section 5). x grows east/right, y grows south/down |
| `itemList` | `[{itemId, iconId, count}]` — the type reference. 26,770 markers have more than one entry. Markers with an empty list are dropped by the backend (`MarkerDaoImpl.java:333`) |
| area | **there is no area field on a marker.** Area = `itemList[0].itemId -> item.areaId -> area` (`genshin-admin-v3/src/stores/utils/createRenderMarkers.ts:41-70`). Only 63 markers have items spanning more than one area |
| `extra` | object (in the web-v3 flow it can also arrive as a JSON string: `genshin-web-v3/src/api/layer.js:118`). Keys seen: `underground` (21,015 markers), `2_8_island` (642), `1_6_island` (601), `iconOverride` (143). See section 3 |
| `refreshTime` | milliseconds; `0` = does not respawn; negative = special codes. Distribution: 0 (64,308), 43200000 = 12 h (27,967), -3 (21,775), 165600000 = 46 h (6,346), -1 (4,729), -5 (2,355), 86400000 (1,814), -4 (1,461), -112 (203), 259200000 (109), -8 (30), -6 (13). Codes from `system.json` `editor.refreshTimeSpecial`: -9 每日0/12点, -3 次日0点, -1 次日4点, -112 次日6点, -4 +2日0点, -7 +2日4点, -5 +3日0点, -8 +3日4点, -6 周一4点 |
| `picture` | full URL or `""`. Present on 22,683 markers; hosts `yuanshen.site` (16,469, e.g. `https://yuanshen.site/comment_png/1662828945019.jpg`) and `oss.yuanshen.site` (6,214) |
| `videoPath` | bilibili URL or `""` (9,824 markers) |
| `version` | integer, incremented on every edit (use for diffing) |
| `createTime`, `updateTime` | protobuf and `/api/marker/get/*`: epoch ms integers. **JSON chunks mix two formats**: epoch ms integer or string `"2026-08-12 21:28:12.56"` (same in item and icon chunks) |
| `hiddenFlag` | 0 normal, 1 invisible, 2 beta, 3 easter egg (`HiddenFlagEnum.java:14-29`). All 131,113 markers in the guest `list_markers` response have 0 |
| `markerTitle`, `content` | Chinese text; `content` uses 【】 highlighting and `\n` |
| `markerStamp` | legacy id string, e.g. `"0_1"` |
| `linkageId` | 32-hex id of a linked-marker group or `""` (6,816 markers); relations come from `/api/marker_link_doc/*` (not fetched) |
| `creatorId`, `updaterId`, `markerCreatorId`, `pictureCreatorId` | contributor ids |

### 2.4 Other entities (verbatim field names from live data)

- Area: `{"version":3,"id":5,"creatorId":1,"createTime":1657736755000,"updaterId":7,"updateTime":1696771332947,"name":"蒙德","code":"C:MD","content":"蒙德","iconId":0,"parentId":-1,"isFinal":false,"hiddenFlag":0,"specialFlag":0,"sortIndex":99}`
- Item type: `{"version":1,"id":1,"creatorId":1,"createTime":...,"updaterId":1,"updateTime":...,"iconId":298,"name":"活动","content":"{{item.name}}","parentId":-1,"isFinal":true,"hiddenFlag":0,"sortIndex":3}`. The 11 types: 9 宝箱 (parent of 10 宝箱品质, 11 获取方式), 2 见闻, 3 特产, 4 矿石, 5 敌人, 6 食材, 7 素材, 8 洞天, 1 活动.
- Item: `{"areaId":6,"count":66,"countSplit":{"0":66},"createTime":...,"creatorId":1,"defaultContent":"在此处收集1个【散失的风神瞳】、【冒险阅历+60】","defaultCount":1,"defaultRefreshTime":0,"hiddenFlag":0,"iconId":4,"iconStyleType":3,"id":6,"name":"散失的风神瞳","sortIndex":99,"specialFlag":0,"typeIdList":[2],"updateTime":"2026-08-12 21:30:22.789","updaterId":7,"version":2}`. Items are per area (the same name exists once per area); `count` = number of markers.
- Icon: `{"createTime":...,"creatorId":1,"description":"","id":4,"tag":"散失的风神瞳","typeIdList":[],"updateTime":"2025-12-13 01:52:47.633","updaterId":7,"url":"https://assets.yuanshen.site/icons/0.png","urlVariants":{"active":"https://oss.yuanshen.site/kyjg/2025-12-03/706c....png","inactive":"https://oss.yuanshen.site/kyjg/2025-12-06/f7e2....png"},"version":3}`. Hosts: `oss.yuanshen.site` 566, `assets.yuanshen.site` 439, `tiles.yuanshen.site/d/marker_image/icons/...` 189. One icon fetched: `https://assets.yuanshen.site/icons/141.png` is a 64x64 **WebP** despite the `.png` name **[V]**.

## 3. Multi-level (layered / underground) maps

### 3.1 Marker side

`extra.underground = { is_underground: bool, is_global?: bool, region_levels?: string[] }` (`genshin-admin-v3/src/api/config/typings.d.ts:194-199`; protobuf `MarkerExtraUnderground`). Combinations in live data (21,015 markers with the key):

| is_underground | is_global | region_levels | count |
|---|---|---|---|
| true | absent | non-empty | 16,816 |
| true | true | empty | 4,093 |
| true | absent | empty | 58 |
| false | absent | empty | 47 |
| false | absent | non-empty | 1 |

- `region_levels` values are the `value` strings of `plugins[<area code>].extraConfig.underground.levels[].children[]` and, at the same time, of the overlay items (3.2). Checked for every area: all values used by markers exist in the config except one stray value (`DESHRET_KING_MAUSOLEUM_NW` in `A:XM:DESERT`) **[V]** (`tools/level_consistency.py`).
- 107 markers list more than one level (objects connecting two floors), e.g. id 128768: `"region_levels":["PALE_CROWN_PALACE_SPIRAL_TOWER","HIDDEN_DOOR_KNOCKED_PLACE"]`.
- `is_global: true` only occurs without levels. The generated API typings describe `is_underground` as "是否是分层层级" and `is_global` as "是否是非地面层级" (`genshin-admin-v3/src/api/alova/globals.d.ts:180-196`); no front-end code reads `is_global` **[S]**. Treat it as "not on the surface, no specific floor".
- Neither public front end filters markers by the selected level; web-v3 only adds a CSS class to underground markers (`genshin-web-v3/src/api/layer.js:128`), and overlays are toggled independently (`src/components/overlay/level_switch.vue`). Filtering markers by `region_levels` is something the app has to do itself **[S]**.
- Other `extra` keys: `"1_6_island":["2"]`, `"2_8_island":{"island_name":"ss","island_state":["3"]}` (event-island states, areas `A:APPLE:*`), `"iconOverride":{"id":1246,"maxZoom":1.0}` (also `minZoom`).

Real layered marker: id 122077 in 2.3 (level `OLD_OBSOLETE_STATION`, area `A:ZD:ZHIDONG1`). Its position (-10755.38, -10755.20) lies inside the bounds of the overlay below.

### 3.2 Config side (`plugins` section of `webapp.json` / `system.json`, identical in both)

Types: `genshin-admin-v3/src/api/config/typings.d.ts` (`PluginConfig`, `ExtraConfig.underground`, `OverlayBounds = [[xmin, ymin], [xmax, ymax]]`, `OverlayRole = 'default' | 'tile'`). Real entry, shortened to its first group (`plugins["A:ZD:ZHIDONG1"]`):

```json
{"extra":["underground"],
 "extraConfig":{"underground":{"useDetail":true,"levels":[
   {"label":"古老废弃车站","value":"OLD_OBSOLETE_STATION","children":[{"label":"古老废弃车站","value":"OLD_OBSOLETE_STATION"}]}, ...]}},
 "overlay":true,
 "overlayConfig":{"panelIcon":"mdi-snowflake","panelIconColor":"#d6fcff",
   "urlTemplate":"https://tiles.yuanshen.site/d/underground/7.0/{{chunkValue}}.png",
   "multiple":true,"overlayMask":true,
   "overlays":[
     {"label":"古老废弃车站","value":"OLD_OBSOLETE_STATION","children":[
       {"label":"古老废弃车站","value":"OLD_OBSOLETE_STATION","chunks":[
         {"value":"UI_Map_LayeredMap_1001","bounds":[[-10839,-11061],[-10450,-10575]]}]}]}, ...]}}
```

- Structure: `overlays[]` (group: `label,value,url?,urlTemplate?,bounds?,multiple?,role?`) -> `children[]` (item = one level: `label,value,url?,urlTemplate?,bounds?,chunks?`) -> `chunks[]` (one image: `label?,value,url?,bounds`).
- URL: `chunk.url || item.url || group.url`, otherwise the template (`item.urlTemplate || group.urlTemplate || overlayConfig.urlTemplate`) with `{{groupLabel}} {{groupValue}} {{itemLabel}} {{itemValue}} {{chunkLabel}} {{chunkValue}}` (`genshin-web-v3/src/components/overlay/level_switch.vue:16,85-159`; `genshin-admin-v3/src/stores/overlay.ts:110-190`).
- Bounds: `chunk.bounds || item.bounds || group.bounds`, `[[xmin,ymin],[xmax,ymax]]` in **marker coordinates** (same space as `position`). web-v3 passes them straight to `L.imageOverlay` (`level_switch.vue:139,385-399`); admin-v3 adds the tile `center` to get world pixels (`stores/overlay.ts:134-147,170-185`).
- `role: "tile"` groups are alternative base-map patches (state variants such as `那夏镇` normal / 祈月之夜), drawn below the dimming mask; default role = level overlays drawn above it. `overlayMask: true` dims the base map (default opacity 0.55, `level_switch.vue:51-62`). Placeholder entries use `"url":"https://tiles.yuanshen.site/d/underground/0.0/Placeholder.png"` with bounds `[[0,0],[0,0]]`.
- Live check **[V]**: `https://tiles.yuanshen.site/d/underground/7.0/UI_Map_LayeredMap_1001.png` -> 302 -> `https://oss.yuanshen.site/kyjg/%E5%9C%B0%E4%B8%8B%E5%BA%95%E5%9B%BE/7.0/UI_Map_LayeredMap_1001.png`, PNG RGBA **292 x 364 px** for bounds of 389 x 486 units. The image is NOT 1:1 with map units (0.75 px per unit here); always stretch the image to its bounds. Saved as `samples/tiles/overlay_7.0_UI_Map_LayeredMap_1001.png`.
- Older areas use other chunk names, e.g. `plugins["A:DQ:HEGUAN"]`: `{"value":"Tex_0040_0","bounds":[[3921,6942],[4442,7315]]}` with template `.../underground/4.0/{{chunkValue}}.png`.

### 3.3 Which areas have it (from `plugins`, counts = level groups / levels / overlay images; "ver" = directory in the URL template)

- Detailed levels + overlay images (`useDetail: true`): A:SIMULANKA:4_8 (4.8; 1/3/3), A:MD:FENGXISHAN (6.5; 1/3/3), A:MD:SHENDIAN (6.5; 5/17/18), A:LY:CHENYUGU (4.4; 3/8/10), A:DQ:1 (4.0; 1/2/3), A:DQ:HEGUAN (4.0; 1/2/2), A:XM:FOREST (4.0; 10/25/25), A:XM:DESERT (4.0; 12/29/37), A:XM:DESERT2 (4.0; 5/11/19), A:XM:DESERT3 (4.0; 6/10/10), A:FD:FENGDAN (4.0; 6/21/30), A:FD:FENGDAN2 (4.1; 5/18/36), A:FD:FENGDAN3 (4.2; 9/24/36), A:FD:FENGDAN4 (4.6; 1/4/8), A:FD:ANCIENT_SEA (4.6; 4/11/12), A:NT:NATA (5.0; 8/27/30), A:NT:NATA2 (5.2; 6/24/24), A:NT:NATA3 (5.5; 2/5/5), A:NT:NATA4 (5.5; 1/3/5), A:NT:NATA5 (5.8; 2/6/7), A:NDKL:NDKL (6.0; 4/22/29), A:NDKL:NDKL2 (6.3; 4/13/15), A:NDKL:NDKL3 (6.7; 1/2/2), A:NDKL:SY (6.7; 7/17/19), A:ZD:ZHIDONG1 (7.0; 10/31/32), A:ZD:ZHIDONG2 (7.2; 7/22/23). Total 462 overlay images.
- Underground flag only, no levels, no overlay: A:VELURIYAM:3_8, A:MD:MENGDE, A:MD:XUESHAN, A:LY:LIYUE, A:LY:CENGYAN, A:LY:CENGYAN_UG, A:DQ:2, A:DQ:YUANXIAGONG, A:DQ:SANJIE.
- Event islands: A:APPLE:1_6 (`1_6_island` extra; overlay template `https://assets.yuanshen.site/tiles_qd16/overlay/{{itemValue}}.jpg`, role `tile`), A:APPLE:2_8 (`2_8_island`; `https://assets.yuanshen.site/tiles_qd28/other/{{groupValue}}/{{itemValue}}.png`).

## 4. Map spaces and areas

- Model: a two-level area tree. Top level `C:<XX>` (`parentId: -1`, `isFinal: false`) = nation/group; leaves `A:<XX>:<NAME>` (`isFinal: true`) own the items. `hiddenFlag: 3` on a top area = hidden "easter egg" area (old event maps); web-v3 hides those unless easter-egg mode is on (`genshin-web-v3/src/api/area.js:19-45`, filter `v.hiddenFlag !== 3`).
- A map "space" = a tile set. `tiles[<area code>]` in the config gives each leaf area its tile set (with one level of `extend`); areas sharing a tile `code` are the same space. There is no other notion of "world id". Tile configs are keyed by area code: `genshin-admin-v3/src/stores/tile.ts:58-61`.
- All areas (from `POST /api/area/get/list` **[V]**), tile set from the config **[V]**, marker counts from the full dump joined through `itemList[0]` **[V]** (`tools/marker_stats.py`); "ug" = markers with `is_underground`, "lvl" = markers with `region_levels`:

| id | code | name | tile set | markers | ug | lvl |
|---|---|---|---|---|---|---|
| 5 | C:MD | 蒙德 | | | | |
| 6 | A:MD:MENGDE | 蒙德 | twt702 | 9670 | 90 | 0 |
| 17 | A:MD:XUESHAN | 龙脊雪山 | twt702 | 2056 | 133 | 0 |
| 54 | A:MD:FENGXISHAN | 风息山 | twt702 | 2589 | 104 | 25 |
| 55 | A:MD:SHENDIAN | 空之神殿 | **kzsd2** | 1238 | 354 | 309 |
| 1 | C:LY | 璃月 | | | | |
| 2 | A:LY:LIYUE | 璃月 | twt702 | 13009 | 319 | 0 |
| 3 | A:LY:CENGYAN | 层岩巨渊 | twt702 | 1331 | 67 | 0 |
| 4 | A:LY:CENGYAN_UG | 地下矿区 | **cyjy** | 1347 | 103 | 0 |
| 34 | A:LY:CHENYUGU | 沉玉谷 | twt702 | 5225 | 452 | 347 |
| 7 | C:APPLE (hidden 3) | 金苹果群岛 | | | | |
| 8 | A:APPLE:1_6 | 金苹果群岛(1.6) | **qd1** (jpg) | 606 | 0 | 0 |
| 10 | A:APPLE:2_8 | 金苹果群岛(2.8) | **qd28** | 819 | 0 | 0 |
| 11 | C:DQ | 稻妻 | | | | |
| 12 | A:DQ:1 | 鸣神岛、神无冢、八酝岛 | twt702 | 6344 | 383 | 87 |
| 13 | A:DQ:2 | 清籁岛、海祇岛 | twt702 | 3378 | 262 | 0 |
| 14 | A:DQ:HEGUAN | 鹤观 | twt702 | 1704 | 74 | 70 |
| 15 | A:DQ:YUANXIAGONG | 渊下宫 | **yxg** | 1820 | 188 | 0 |
| 16 | A:DQ:SANJIE (hidden 3) | 三界路飨祭(2.5) | **yxg** | 625 | 22 | 0 |
| 18 | C:XM | 须弥 | | | | |
| 19 | A:XM:FOREST | 诸法丛林 | twt702 | 11594 | 1653 | 1336 |
| 21 | A:XM:DESERT | 大赤沙海 | twt702 | 4512 | 1538 | 1437 |
| 22 | A:XM:DESERT2 | 千壑沙地 | twt702 | 3606 | 1244 | 947 |
| 23 | A:XM:DESERT3 | 苍漠囿土 | twt702 | 1768 | 421 | 407 |
| 25 | C:VELURIYAM (hidden 3) | 琉形蜃境 | | | | |
| 26 | A:VELURIYAM:3_8 | 琉形蜃境(3.8) | **lxsj** | 1571 | 221 | 0 |
| 27 | C:FD | 枫丹 | | | | |
| 28 | A:FD:FENGDAN | 枫丹廷区、白露区、苍晶区 | twt702 | 6903 | 1949 | 1863 |
| 29 | A:FD:FENGDAN2 | 枫丹动能工程科学研究院区、黎翡区 | twt702 | 4963 | 1116 | 970 |
| 30 | A:FD:FENGDAN3 | 莫尔泰区、伊黎耶林区 | twt702 | 5863 | 1393 | 1364 |
| 31 | A:FD:FENGDAN4 | 诺思托伊区 | twt702 | 451 | 151 | 146 |
| 32 | A:FD:ANCIENT_SEA | 旧日之海 | **jrzh** | 773 | 754 | 754 |
| 42 | C:SIMULANKA (hidden 3) | 希穆兰卡 | | | | |
| 43 | A:SIMULANKA:4_8 | 希穆兰卡(4.8) | **xmlk** | 656 | 54 | 23 |
| 44 | C:NT | 纳塔 (Natlan) | | | | |
| 45 | A:NT:NATA | 万火之瓯、坚岩隘谷、踞石山、涌流地 | twt702 | 7281 | 1452 | 1361 |
| 46 | A:NT:NATA2 | 镜璧山、翘枝崖、奥奇卡纳塔 | twt702 | 4793 | 601 | 532 |
| 47 | A:NT:NATA3 | 安饶之野 | twt702 | 2509 | 297 | 252 |
| 48 | A:NT:NATA4 | 远古圣山 | **ygss** | 1537 | 737 | 654 |
| 49 | A:NT:NATA5 | 悠悠度假村 | twt702 | 2569 | 223 | 173 |
| 50 | C:NDKL | 挪德卡莱 (Nod-Krai) | | | | |
| 51 | A:NDKL:NDKL | 伦波岛、希汐岛、帕哈岛 | twt702 | 4149 | 498 | 331 |
| 52 | A:NDKL:NDKL2 | 虚海望、逐浪野、烟硌山峰 | twt702 | 2344 | 316 | 290 |
| 53 | A:NDKL:NDKL3 | 杜南纳深坑 | twt702 | 157 | 48 | 48 |
| 56 | A:NDKL:SY | 霜月 | **moon2** | 1129 | 382 | 356 |
| 57 | C:ZD | 至冬 (Snezhnaya) | | | | |
| 58 | A:ZD:ZHIDONG1 | 永凝冻土、古兽冰原、焰羽谷、白桦雪葬地、霜殛寒峰 | twt702 | 7374 | 1783 | 1369 |
| 59 | A:ZD:ZHIDONG2 | 新基捷城、如翼之峰 | twt702 | 2848 | 1585 | 1366 |

- So there are 11 tile sets: `twt702` (all of open-world Teyvat: Mondstadt, Liyue, Inazuma, Sumeru, Fontaine, Natlan, Nod-Krai, Snezhnaya) and 10 separate spaces: `kzsd2`, `cyjy`, `yxg`, `jrzh`, `ygss`, `moon2`, `lxsj`, `xmlk`, `qd1`, `qd28`.
- Newest content **[V]**: Natlan (18,689 markers), Nod-Krai (7,779), Snezhnaya (10,222) are all present with markers, level definitions and overlay images; Snezhnaya is painted on the `twt702` tiles (tile `13/-29_-22` fetched, shows the map).
- The two screenshot names:
  - "Болото древа жизни, Снежная" = **生栖树沼, 至冬** (English "Teeming Mire"). The Chinese name appears verbatim in Kongying waypoint markers 121714 and 121715 (`【至冬 生栖树沼】`, area `A:ZD:ZHIDONG1`, positions about (-10981,-12499) and (-11233,-12425), levels `WING_SHADOWED_TOWN` 受翼遮蔽的小镇 / `HOLLOW_DREAM_HATCH_PLACE` 空洞之梦的破壳处, level group 焰羽谷) **[V]**. The Russian = Chinese equation comes from a web search hit (localization table of the Japanese Fandom wiki page 生命棲の樹海), not from game files.
  - "Храм пространства" = **空之神殿** (area `A:MD:SHENDIAN`, id 55, under 蒙德; its own tile set `kzsd2`; 1,238 markers; 17 levels with 18 overlay images under `underground/6.5/`). This mapping is **inferred**: HoYoWiki (ru) files the quest «Долгий день в горах» under "Мондштадт / Храм пространства" with a sub-location "Центр Зала столпов"; Kongying markers in 空之神殿 reference the quest 山中好长日 and the sub-area 柱殿中央区, which are literal matches. No source giving both names side by side was found.

## 5. Tiles and coordinates

### 5.1 URL template and parameters

```
https://assets.yuanshen.site/tiles_<code>/<z>/<x>_<y>.<extension>      z = 10..13, tile = 256 x 256 px
```

- Source: `genshin-web-v3/src/api/map.js:33-49` (`tiles_preUrl = "https://assets.yuanshen.site/tiles_"`, `z = coords.z + 13`, `maxNativeZoom: 0`, `minNativeZoom: -3`, default Leaflet tile size 256) and `genshin-admin-v3/src/packages/map/src/core/gsTileLayer.ts:14-39` (`ZOOM_MAPPING = 13`, `tileSize: 256`, `minZoom: -3` "对应服务端存储底图的 level 10", `maxZoom: 0` "level 13", URL `${BASE_URL}/tiles_${code}/${z + ZOOM_MAPPING}/${x}_${y}.${extension}`).
- Live **[V]**: z=13 and z=10 tiles return 200; z=14 and z=9 return 404. z=13 is the full resolution (1 marker unit = 1 px); each lower level halves it (a z=10 tile covers 2048 x 2048 units).
- Format **[V]**: do not trust the extension. `tiles_twt702/13/15_10.png` and `13/-29_-22.png` are real PNG (RGBA, 129 KB / 139 KB), `tiles_kzsd2/13/10_11.png` is PNG RGB, but `tiles_twt702/10/1_1.png` is served as `image/webp` (256x256, 39 KB). Decode by content.
- Sparse set **[V]**: indices can be negative. `13/-68_-40.png` (top-left corner) exists as a 1 KB blank tile, `13/-69_-41.png` (outside) is 404, and `13/50_20.png`, `13/49_63.png`, `13/50_63.png` (inside the declared size, far east/south) are also 404. Treat 404 as an empty tile. The exact set of existing tiles was not enumerated **[?]**.
- Per-tile-set parameters = `tiles` section of the config, fields (`genshin-admin-v3/src/api/config/typings.d.ts`, `TileConfig`): `code` (directory name), `name`, `extend` (one level of inheritance, merged with `defaultsDeep`), `center` ("坐标系原点所在位置"), `size` ("地图图片的总宽高（像素）"), `tilesOffset` ("瓦片偏移量（像素）"), `extension`, `settings.center/zoom` (initial viewport only, in marker coordinates). Defaults when a field is missing (`genshin-web-v3/src/api/map.js:65-70`): `center [3568,6286]`, `size [12288,15360]`, `tilesOffset [0,0]`, `extension "png"`.

Base entries (verbatim) and resulting sets:

```
提瓦特-base0 {"code":"twt702","name":"提瓦特","settings":{"center":[0,1742]},"center":[3568,6969],"size":[30370,26624],"tilesOffset":[-17408,-10240]}
提瓦特-base1 {"extension":"png","center":[3568,6286],"size":[12288,12288]}
金苹果-base0 {"extension":"jpg","settings":{"center":[600,-2190],"zoom":-2},"center":[3568,6286],"size":[8192,8192]}
```

| code | areas | center | size | tilesOffset | ext | z=13 tile index range (from size/offset) |
|---|---|---|---|---|---|---|
| twt702 | every `extend: 提瓦特-base0` area | [3568,6969] | [30370,26624] | [-17408,-10240] | png | x -68..50, y -40..63 |
| kzsd2 | A:MD:SHENDIAN 空之神殿 | [3568,6286] | [12288,12288] | [0,0] | png | 0..47 |
| cyjy | A:LY:CENGYAN_UG 地下矿区 | same | same | [0,0] | png | 0..47 |
| yxg | A:DQ:YUANXIAGONG, A:DQ:SANJIE | same | same | [0,0] | png | 0..47 |
| jrzh | A:FD:ANCIENT_SEA 旧日之海 | same | same | [0,0] | png | 0..47 |
| ygss | A:NT:NATA4 远古圣山 | same | same | [0,0] | png | 0..47 |
| lxsj | A:VELURIYAM:3_8 | same | same | [0,0] | png | 0..47 |
| xmlk | A:SIMULANKA:4_8 | same | same | [0,0] | png | 0..47 |
| moon2 | A:NDKL:SY 霜月 | [3568,6286] | [17408,9216] | [0,0] | png | x 0..67, y 0..35 |
| qd1 | A:APPLE:1_6 | [3568,6286] | [8192,8192] | [0,0] | jpg | 0..31 |
| qd28 | A:APPLE:2_8 | [3568,6286] | [8192,8192] | [0,0] | png | 0..31 |

Only `twt702` and `kzsd2` tiles were actually fetched; the other sets are from config only **[S]**. The full per-area list is in `samples/config/system.json` -> `tiles`.

### 5.2 Marker coordinates -> pixels

Front-end code (web-v3, `src/api/map.js:113-129`; markers are created with `L.marker(data.position.split(","))`, `src/api/layer.js:218`, so `lat = x`, `lng = y`):

```js
const mapCRS = L.Util.extend({}, L.CRS.Simple, {
  transformation: new L.Transformation(1, 0, 1, 0),
  projection: {
    project(latlng)  { return new L.Point(latlng.lat + mapCenter[0], latlng.lng + mapCenter[1]); },
    unproject(point) { return new L.LatLng(point.x - mapCenter[0], point.y - mapCenter[1]); },
  },
  bounds: L.bounds(L.point(0, 0), L.point(mapSize[0], mapSize[1])),
});
```

admin-v3 says the same: `toMapCoordinate = ([x, y]) => [x + ox, y + oy]` with `[ox, oy] = tile.center` (`src/stores/tile.ts:213-228`), `position: [Number(strX) + ox, Number(strY) + oy]` (`src/stores/utils/createRenderMarkers.ts:41-70`), tile layer extent `[ox, h + oy, w + ox, oy]` with `[ox, oy] = tilesOffset` (`gsTileLayer.ts:35`).

Resulting rules (marker `position = "mx,my"`, tile config after `extend` merge):

```
world  = (mx + center[0], my + center[1])                 # px at z=13, x right, y down, no flip
tile   = (floor(world.x / 256), floor(world.y / 256))     # z=13; in-tile px = world - tile*256
z < 13 : s = 2^(13-z); tile = floor(world / (256*s)); in-tile px = (world - tile*256*s) / s
mosaic = world - tilesOffset = (mx + center[0] - tilesOffset[0], my + center[1] - tilesOffset[1])
         # px in an image stitched from all z=13 tiles, whose top-left tile is tilesOffset/256
```

For `twt702`: `mosaic = (mx + 20976, my + 17209)`, declared size 30370 x 26624. For the `提瓦特-base1` sets: `mosaic = (mx + 3568, my + 6286)`.

Verification **[V]**: marker id 1 (散失的风神瞳 on top of 风龙废墟), position (350.2, -4369.8) -> world (3918.2, 2599.2) -> z=13 tile (15,10), in-tile px (78, 39): `samples/tiles/twt702_z13_15_10.png` shows the Stormterror's Lair tower with its centre at about (80, 40). Same point at z=10: tile (1,1), px (234, 69): `samples/tiles/twt702_z10_1_1.png` shows the tower ring centred there. Snezhnaya waypoint 121714 at (-10981, -12499) -> world (-7413, -5530) -> tile (-29,-22), which exists and shows painted terrain. For `kzsd2` only tile existence was checked, not pixel alignment.

Marker coordinate extents per tile set (min x, min y, max x, max y), from the full dump: twt702 [-19969, -15512, 7157, 8231]; kzsd2 [-2469, -4778, 1478, -2319]; cyjy [-1111, -2864, 4298, 1714]; yxg [-1928, -4131, 7793, 5319]; jrzh [-3177, -4863, -163, -3731]; ygss [-447, -4035, 2047, -1119]; moon2 [776, -4866, 12050, 1100]; lxsj [-954, -2722, 6146, 3220]; xmlk [-1380, -5127, 2347, -1309]; qd1 [-1476, -5127, 2538, -384]; qd28 [-1479, -5125, 2545, -312]. All fall inside `[tilesOffset - center, tilesOffset - center + size]`.

Overlay bounds use the same marker coordinate space, so `overlay mosaic px = bounds + center - tilesOffset`.

## 6. Rate limits, headers, restrictions

- No browser headers are required **[V]**: the token call succeeded with the User-Agent header removed and `Origin`/`Referer: https://example.com`; a tile was fetched the same way; all other calls used curl's default UA with no Referer/Origin. No cookies needed.
- CORS **[V]**: API, `assets.yuanshen.site`, `oss.yuanshen.site` all send `access-control-allow-origin: *`; the API also sends `access-control-allow-methods: GET,POST,OPTIONS,PUT,PATCH,DELETE` and an allow-headers list that includes `Authorization, Content-Type`. Irrelevant for a native client.
- Rate limits: no limiter is configured in the backend repo (no `RequestRateLimiter`/sentinel/bucket config found in `genshin-cloud-api`) **[S]**; no 429 and no rate-limit headers were seen on `cloud.yuanshen.site` or `assets.yuanshen.site` in about 40 sequential requests **[V]**. `oss.yuanshen.site` (S3-compatible store behind the overlay/picture URLs) returns `x-ratelimit-limit: 1350`, `x-ratelimit-remaining: 1350` **[V]**; what the number means was not determined **[?]**. Limits at the CDN / nginx level are unknown **[?]**.
- Infrastructure seen in headers: `server: openresty`, Tencent EdgeOne CDN (`eo-cache-status`, `eo-log-uuid`).
- Caching: tiles `cache-control: max-age=86400` with ETag and Last-Modified (twt702 tiles last modified 2026-11-03); `webapp.json` `max-age=300`; API responses `no-cache, no-store`; overlay images on oss `cache-control: no-cache` with ETag = md5.
- Cost of the big calls: `list_markers` 12.3 MB / 18.6 s, `list_diff_snapshot` 1.26 MB / 11 s (uncompressed). Fetch them rarely and cache.

## 7. Gotchas

1. Markers have no area and no map-space field; join through items (2.3).
2. Timestamps in the gzip-JSON chunks are either epoch ms or `"yyyy-MM-dd HH:mm:ss.SSS"` strings.
3. `.png` URLs may contain WebP (low-zoom tiles, icons).
4. Overlay images must be scaled to their bounds; their pixel size is not the bounds size.
5. Responses carry contributor personal data in `users` (JSON) / field 2 (protobuf); skip it.
6. Missing tiles are 404, also inside the declared `size`.
7. Token lasts 30 minutes; there is no refresh token, just request a new one.
8. `POST /api/icon_type/get/list` with `"typeIdList":[]` returned HTTP 500; without that field it works.
9. All text (names, titles, contents, level labels) is Simplified Chinese only; no Russian or English names anywhere in the data.

## 8. Not determined

- Exact set of existing tiles per tile set, and pixel alignment for tile sets other than `twt702`.
- Meaning of `x-ratelimit-limit: 1350` on `oss.yuanshen.site`; any CDN-level limits.
- Contents of the 46th marker chunk (presumed easter-egg markers) and whether the chunked JSON total equals the 131,113 markers of `list_markers`.
- `marker_link_doc/*` payloads (linked markers) and `marker/get/list_byid` were not fetched.
- A first-hand source equating "Храм пространства" with 空之神殿.

## 9. Files

- Samples (data only): `/root/genshin/research/samples/api/` (token response with the token redacted, md5 lists, raw and decompressed chunks, full marker dump as users-stripped protobuf and as decoded JSON, diff snapshot, area/type lists, response headers), `/root/genshin/research/samples/config/` (`webapp.json`, `system.json.bz2`, `system.json`), `/root/genshin/research/samples/tiles/` (4 tiles, 1 overlay image, headers), `/root/genshin/research/samples/icons/`, `/root/genshin/research/samples/live_site/` (site HTML and the JS bundle saved as `.txt`).
- Helper scripts (kept outside samples): `/root/genshin/research/tools/decode_markers_pb.py`, `strip_users_pb.py`, `marker_stats.py`, `level_consistency.py`, `grep_markers.py`.
- Web lookups for the two Russian names: https://genshin-impact.fandom.com/ja/wiki/%E7%94%9F%E5%91%BD%E6%A3%B2%E3%81%AE%E6%A8%B9%E6%B5%B7 , https://wiki.hoyolab.com/pc/genshin/entry/10562?lang=ru-ru
