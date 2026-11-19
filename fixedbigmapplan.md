## 3c9c8b8+ — P3 (полное выполнение): атомарный snapshot, L2 invalidation, L3 expiry

### 1. Атомарный native snapshot с provenance (один владелец, снимок без нового вычисления)
- `BigMapSession::snapshot()` — возвращает копию последней известной геометрии (`geometry_anchor_`) без вызова SIFT/регистрации
- `BigMapSession::snapshot_revision()` — ревизия состояния, инкрементируется на каждом изменении (follow/reset/re registration)
- `snapshot()` не изменяет состояние: `absolute_attempted=false`, `reset_reason="none"`, `follow.outcome="not_attempted"`
- `nativeSnapshotBigMap()` — JNI entry point, возвращает v2 envelope с `snapshot_revision`
- `NativeSplitTracker.snapshotBigMapJson()` — Kotlin обёртка
- `testSnapshotAtomicNoNewComputation` — проверяет: одинаковая ревизия, одинаковая геометрия, нет нового вычисления, после reset ревизия растёт

### 2. Полная инвалидация при rotation/resize (L2)
- `CaptureService.resizeCapture()` теперь вызывает `engine.resetBigMap()` — сброс native `BigMapSession` (очистка `geometry_anchor_`, `anchor_`, `previous_transform_`)
- Также сбрасывается `bigMap` (BigMapConfirmation), `bigMapRate`, publicada геометрия помечается как сброшенная
- Старый якорь не наследуется новым размером/ориентацией кадра

### 3. Native завис — матрица истекает независимо (L3)
- `ensureActive()` после native return: если корутина отменена (пауза/стоп/rotation), результат не публикуется → `continue` к следующему кадру
- `geometryExpiryJob` истекает матрицу по времени независимо от завершения native вызова
- Generation/age проверка после native: устаревший кадр не публикуется

## Статус P3 (выполнено полностью)
- ✅ `BigMapGeometry.version` поле (>=1)
- ✅ `BigMapGeometryDecoder.decode()` с версионным диспетчем: версия 1 и 2 принимаются, неизвестная версия → hidden без геометрии
- ✅ `decodeStrictV1()`: отрицательный тест совместимости (v2 payload к старому consumer → null geometry)
- ✅ `nativeRegisterBigMapV2`: нативная точка входа с version=2 в v2 envelope
- ✅ `nativeRegisterBigMap` (legacy): emits version=1, оставлен для rollback
- ✅ `BigMapGeometryVersionTest`: unit-тесты для v1/v2/unknown version decoding
- ✅ `CaptureService`: использует `nativeRegisterBigMapV2`, cancellation guard после native return
- ✅ Атомарный native snapshot с provenance (один владелец, снимок без нового вычисления)
- ✅ Полная инвалидация при rotation/resize (L2)
- ✅ Native завис — матрица истекает независимо (L3)

## Статус P4 (не начат)
- ⬜ Android CI через GitHub Actions (`./gradlew testDebugUnitTest assembleDebug --stacktrace`)
- ⬜ Адресный сценарий на телефоне: pan/zoom/stop, layer/space, пауза, Stop/Start, rotation, выход из покрытия
- ⬜ Измерение FPS игры, нагрева, end-to-end задержки (только на устройстве)

## Проверки (desktop C++ — зелёные)
`test_bigmap_session`, `test_bigmap_geometry`, `test_bigmap_detector`, `test_bigmap_follow_mask` пройдены после snapshot/L2/L3 изменений.

## Следующие шаги
P4: Android CI запустится автоматически при push в `feat/**`. Адресное тестирование на устройстве требует устройства Samsung SM-S938B и разрешения на установку APK.