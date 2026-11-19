# Журнал изменений — Branch `feat/bigmap-detection-fable`

## 1bcf3e0 — Этап 5/6: метки всегда видны, цвет по этажу

- `BigMapMarkerFilter`: показываются все этажи всегда; выбранный этаж = синий, остальные = серые с серой обводкой
- `BigMapMarkerOverlay`: рендер выбранного этажа в синем, других этажей в сером
- `BigMapSession`: публикация последней известной геометрии даже во время `moving`; маркеры не исчезают при движении карты
- `OverlayService`: сбор отфильтрованных маркеров через `BigMapMarkerFilter` и перерисовка `BigMapMarkerOverlay`

## 08bf5fb — Этап 5: Canvas-оверлей маркеров grande карты

- `BigMapMarkerOverlay`: полноэкранный View, рисует метки через `BigMapGeometry.toDisplay`, фиксированный размер в пикселях экрана (не меняется при зуме)
- `OverlayService`: добавлен полноэкранный слой маркеров grande карты
- `BigMapMarkerOverlayTest`: проекция и фильтрация за пределами кадра

## 79174e8 — Этап 4: фильтрация маркеров по espace/этажу/видимости

- `BigMapMarkerFilter`: фильтрация Kongying-меток по пространству, этажу и видимости внутри кадра
- `BigMapMarkerFilterTest`: исключение другого пространства, неизвестный этаж, поверхность/подземелье, совпадение уровня, границы кадра

## 6d4c5e2 — Этап 3: детектор этажа grande карты

- `BigMapLayerDetector`: определяет выбранный этаж по колонке кнопков уровней справа. Подсвеченная кнопка в нижней половине колонки = поверхность, иначе = уровень. При сомнении — `unknown` (неизвестный этаж ≠ поверхность)
- `BigMapLayerDetectorTest`: тесты surface/layer/unknown/empty bitmap

## 79174e8 — Этап 1+2: стабильный якорь grande карты + индекс маркеров

### Этап 1 — Данные маркеров Kongying
- `KongyingMarkerStore`: потокобезопасное хранилище с индексом `space → список маркеров`. Методы: `replaceAll` (перестройка индекса), `upsert` (инкрементальное обновление с переносом между spaces), `remove`, `markersForSpace`, `markerById`, `allMarkers` (StateFlow), `markersForSpaceFlow` (отслеживание изменений)
- `KongyingSyncManager`: загрузка **всех 46 чанков** маркеров Kongying (ранее только первый и последний из 46). Сохранение всех записей в `KongyingMarkerStore` с индексом по `space`
- `KongyingMarkerStoreTest`: тесты `replaceAll`, `upsert` (в т.ч. перемещение маркера между spaces), `remove`, очистка пустых индексов, `allMarkers`

### Этап 2 — Стабильная се grande карты (не сбрасывать якорь при малом сдвиге)
- `bigmap_session.hpp`: разделение `geometry_anchor_` (опубликованная абсолютная привязка, не перезаписывается follow) и `anchor_` (фиксированный кадр регистрации, не передвигается). Счётчик `not_ok_counter_` с порогом `MAX_NOT_OK = 3`
- `bigmap_session.cpp`:
  - Follow меряется против фиксированного `anchor_`, не rolling-кадра
  - Геометрия компонуется `geometry_anchor_ ∘ motion` (inverse * motion * scale * anchor)
  - При `appearance_failure` якорь не уничтожается сразу — только после 3 неудач подряд
  - `last_attempt_` / SIFT cooldown сохраняются при сбросе якоря
  - `previous_transform_` обновляется только при измеримом движении
- `test_bigmap_session.cpp`: новые stage-2 тесты (стабильность якоря при малом сдвиге, восстановление после transient-плиточного изменения, сброс после 3 неудач, масштаб якоря независим от слоя)
- `test_bigmap_session.cpp` (архив): перенесены старые тесты (изменённое поведение follow)

## 595f3ac — Pre-stage (без коммита в ветке до squash)
- `fixedbigmapplan.md`: стратегия отслеживания grande карты, инварианты C1–C6 (упрощённые), этапы 1–7

## 3545771 — Документ контракта C2/C3/C6 ослаблен
- `research/bigmap_tracking_v2.md`: матрица может публиковаться для позиции игрока при неизвестном этаже; полная перепроверка эталонов только при сбросе/уходе/таймауте; первая версия может быть рабочей с флагом `markers_allowed`

## Неотслеживаемые изменения (были до начала работы)
- `DESIGN.md`, `PLAN.md`, `tracker/CMakeLists.txt`, `tracker/tests/bigmap_geometry_frames.cpp` — модифицированы до создания ветки

## Проверки
- Десктоп C++: `test_bigmap_session`, `test_bigmap_geometry`, `test_bigmap_detector`, `test_bigmap_follow_mask`, `test_bigmap_acquisition`, `test_bigmap_replay_io`, `test_bigmap_competition`, `test_bigmap_pair_collection`, `test_bigmap_shadow`, `test_bigmap_revalidation`, `test_bigmap_local_geometry`, `benchmark_frames` — зелёные
- Android CI: не запускался локально (запрещено планом), запустится автоматически при push в `feat/**` через GitHub Actions

## Следующие этапы (не начаты)
- P3: native state/ABI и Kotlin guard — атомарный snapshot, decoder с версионированием (уже частично в коде: `version` field, `decodeStrictV1`), инвалидация, владение ресурсами, L1–L3/I1
- P4: CI и устройство — exact-commit Android CI, затем адресный сценарий на телефоне в режиме одного приложения: pan/zoom/stop, layer/space, пауза, Stop/Start, rotation, выход из покрытия. Миникарта должна сохранить поведение
