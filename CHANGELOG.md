# Журнал изменений — Branch `feat/bigmap-detection-fable`

## 6d4c5e2 — Stage 3: детектор этажа grande карты
- `app/src/main/java/io/github/d3tal/luxin/tracker/BigMapLayerDetector.kt` (новый): определяет выбранный этаж grande карты по колонке кнопок уровней справа. Подсвеченная кнопка в нижней половине колонки = поверхность, иначе = уровень. При сомнении — `unknown` (неизвестный этаж ≠ поверхность).
- `app/src/test/java/io/github/d3tal/luxin/tracker/BigMapLayerDetectorTest.kt` (новый): тесты surface/layer/unknown/empty bitmap.
- Инвариант C1: при смене этажа геометрия grande карты (сетка координат) не меняется — меняется только набор маркеров.

## 79174e8 — Stage 1+2: стабильный якорь grande карты + индекс маркеров

### Stage 1 — Данные маркеров Kongying
- `app/src/main/java/io/github/d3tal/luxin/data/KongyingMarkerStore.kt` (новый): потокобезопасное хранилище маркеров с индексом `space → список маркеров`. Методы: `replaceAll` (перестройка индекса), `upsert` (инкрементальное обновление с переносом между spaces), `remove`, `markersForSpace`, `markerById`, `allMarkers` (StateFlow), `markersForSpaceFlow` (отслеживание изменений).
- `app/src/main/java/io/github/d3tal/luxin/data/KongyingSyncManager.kt`: загрузка **всех 46 чанков** маркеров Kongying (ранее только первый и последний из 46). Сохранение всех записей в `KongyingMarkerStore` с индексом по `space`.
- `app/src/test/java/io/github/d3tal/luxin/data/KongyingMarkerStoreTest.kt` (новый): тесты `replaceAll`, `upsert` (в т.ч. перемещение маркера между spaces), `remove`, очистка пустых индексов, `allMarkers`.

### Stage 2 — Стабильная се grande карты (не сбрасывать якорь при малом сдвиге)
- `tracker/include/luxin/bigmap_session.hpp`: разделение `geometry_anchor_` (опубликованная абсолютная привязка, не перезаписывается follow) и `anchor_` (фиксированный кадр регистрации, не передвигается). Счётчик `not_ok_counter_` с порогом `MAX_NOT_OK = 3`.
- `tracker/src/bigmap_session.cpp`:
  - Follow меряется против фиксированного `anchor_`, не rolling-кадра.
  - Геометрия компонуется `geometry_anchor_ ∘ motion` (inverse * motion * scale * anchor).
  - При `appearance_failure` якорь не уничтожается сразу — только после 3 неудач подряд.
  - `last_attempt_` / SIFT cooldown сохраняются при сбросе якоря.
  - `previous_transform_` обновляется только при измеримом движении.
- `tracker/tests/test_bigmap_session.cpp`: новые stage-2 тесты (стабильность якоря при малом сдвиге, восстановление после transient-плиточного изменения, сброс после 3 неудач, масштаб якоря независим от слоя).
- `tracker/tests/archive/test_bigmap_session.cpp`: перенесены старые тесты (изменённое поведение follow).

## 595f3ac — Pre-stage (без коммита в ветке до squash)
- `fixedbigmapplan.md` (новый): стратегия отслеживания grande карты, инварианты C1–C6 (упрощённые), этапы 1–7.

## 3545771 — Документ контракта C2/C3/C6 ослаблен
- `research/bigmap_tracking_v2.md`: матрица может публиковаться для позиции игрока при неизвестном этаже; полная перепроверка эталонов только при сбросе/уходе/таймауте; первая версия может быть рабочей с флагом `markers_allowed`.

## Неотслеживаемые изменения (были до начала работы)
- `DESIGN.md`, `PLAN.md`, `tracker/CMakeLists.txt`, `tracker/tests/bigmap_geometry_frames.cpp` — модифицированы до создания ветки.

## Проверки
- Десктоп C++: `test_bigmap_session`, `test_bigmap_geometry`, `test_bigmap_detector`, `test_bigmap_follow_mask`, `test_bigmap_acquisition`, `test_bigmap_replay_io`, `test_bigmap_competition`, `test_bigmap_pair_collection`, `test_bigmap_shadow`, `test_bigmap_revalidation`, `test_bigmap_local_geometry`, `benchmark_frames` — зелёные.
- Android CI: не запускался локально (запрещено планом), запустится автоматически при push в `feat/**` через GitHub Actions.

## Следующие этапы (не начаты)
- Этап 4: `BigMapMarkerFilter.kt` — фильтрация маркеров по espace/этажу/видимости.
- Этап 5: `BigMapMarkerOverlay.kt` + `OverlayService.kt` — Canvas-оверлей маркеров grande карты.
- Этап 6: `CaptureService.kt` — публикация геометрии в фазе `tracking`.
