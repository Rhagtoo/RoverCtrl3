# v3.0.1 — Tracking Pipeline Fixes + PID Auto-Tune UI

Target branch: feature/v3.0-tracking-tuning

## PID Auto-Tune UI (Fix #4 — из списка улучшений)

### Что уже было реализовано (но не подключено):
- `PidAutoTuner.kt` — relay feedback test (Åström–Hägglund), 4 метода Z-N
- `ObjectTracker.kt` — `startAutoTune()`, `abortAutoTune()`, relay bypass в `process()`
- `fragment_video.xml` — кнопка `btn_auto_tune` (`"AT"`, visibility=gone)

### Что добавлено:
- **`showAutoTuneDialog()`** — диалог выбора метода, запуск/abort, progress HUD, показ результатов
- **`startAutoTune()`** — подключение callbacks: progress → tvStatus, axis done → log, complete → result dialog, fail → toast
- **`showAutoTuneResult()`** — диалог с полными результатами (Kp/Ki/Kd + Ku/Tu/amplitude/cycles), Keep/Revert
- **`updateOverlayControlsVisibility()`** — кнопка AT видна только в OBJECT_TRACK mode
- Import `PidAutoTuner`

### Как пользоваться:
1. Включить OBJECT_TRACK mode (спиннер → "YOLO")
2. Навести камеру на объект (person или cat)
3. Убедиться что bounding box стабильно отображается
4. Нажать **AT** → выбрать метод (рекомендуется Tyreus-Luyben) → **Start**
5. Камера будет осциллировать ~30 сек (PAN тест → TILT тест)
6. По завершении — диалог с результатами, **Keep** или **Revert**

### Методы настройки:
| Метод | Характер | Когда использовать |
|-------|----------|-------------------|
| Ziegler-Nichols | Агрессивный, возможен overshoot | Быстрое отслеживание, не критичен overshoot |
| Tyreus-Luyben | Сбалансированный (default) | Шумная система, сетевая задержка |
| Some Overshoot | Промежуточный | Компромисс скорость/стабильность |
| No Overshoot | Консервативный | Максимальная плавность |

### Ограничения:
- Tilt (CR серво) имеет integrator в plant → Z-N менее точен
- Сетевая задержка ~50ms добавляет фазовый сдвиг → Tyreus-Luyben предпочтительнее
- Требует стабильную детекцию объекта на протяжении всего теста (~30 сек)
- При потере объекта >2 сек — тест прерывается автоматически

## Исправленные баги

### Fix #1: INT8 модель — BufferOverflowException (ObjectTracker.kt)
- Буфер выделялся из расчёта 1 байт/канал для INT8, но `fillNhwcBuffer`/`fillNchwBuffer` всегда
  писали `putFloat()` (4 байта) → crash при загрузке `yolov8n_int8.tflite`
- Добавлены `fillNhwcInt8`/`fillNchwInt8` с `buf.put()` (raw 0–255 bytes)
- Единый `fillInputBuffer()` выбирает реализацию по `modelIsInt8`

### Fix #2: TSET parsing — коллизия D: ↔ DB:/DC: (turret_client.ino → v2.7.2)
- `strstr(cmd, "D:")` находил `D` внутри `DB:5.0` → dpsCamDn тихо сбрасывался на 10°/s
- Все ключи теперь ищутся с `;` префиксом: `";N:"`, `";S:"`, `";U:"`, `";D:"`, `";DB:"`, `";DC:"`
- Совпадает с форматом CommandSender, который всегда ставит `;` перед каждым ключом

### Fix #3: Потеря объекта не обнуляла команды (VideoFragment.kt)
- При `r.found == false` не вызывался `vm.setPanTilt()` → cmdTick 50ms слал последние ненулевые команды
- Добавлено `vm.setPanTilt(0, 0)` в else-ветку для обоих режимов (LASER_DOT, OBJECT_TRACK)

### Fix #4: Kalman velocity — использовал post-update residual (SimpleKalmanFilter.kt)
- Старый код: `x += k * (measurement - x)` затем `v += k * (measurement - x) / dt`
  Второе `(measurement - x)` уже равно `(1-K) * innovation` → velocity получала ~9% сигнала
- Новый код: `val innovation = measurement - x` → `x += k * innovation` → `v += k * innovation / dt`
- Velocity prediction теперь реально работает

### Fix #5: Kalman tracking — prediction-only между детекциями (SimpleKalmanFilter.kt + ObjectTracker.kt)
- Старый код: `kalman.update(lastDetection)` на каждом кадре → один и тот же measurement → velocity → 0
- Новый `predict()`: экстраполирует по velocity без measurement, P растёт (confidence падает)
- Velocity декаёт ×0.98 при prediction (объект может остановиться)
- `trackingConfidence *= 0.95f` между детекциями → быстрый возврат к detect()

### Fix #7: Thread safety — @Volatile на tuning полях (ObjectTracker.kt)
- `DEADZONE`, `EXPO_POWER`, `MAX_DELTA_PER_FRAME` обновляются из main thread (Flow),
  читаются из analysisExecutor → формально data race на не-volatile полях
- `panSensitivity`, `tiltSensitivity` — аналогично
- Все помечены `@Volatile`

### Fix #9: Kalman не инициализировался при первой детекции (SimpleKalmanFilter.kt + ObjectTracker.kt)
- Старый код: `kalman.reset()` → state = (0, 0), затем predict() прыгает к реальной позиции
- Новый `initialize(detection)`: ставит `x = detection.cx`, `p = measurementNoise` → без скачка
- KalmanFilter2D.initialize() кеширует label/confidence для будущих predict()

### Fix #10: Дублирование tracking кода (VideoFragment.kt)
- `processFrame()` и `processXiaoFrame()` содержали идентичные блоки для LASER_DOT и OBJECT_TRACK
- Выделены `handleTracking(bitmap, imgW, imgH, ft)` и `postOverlay(imgW, imgH, detection)`
- ~60 строк copy-paste → 2 однострочных вызова

### Fix #6: Предупреждение о viewpoint mismatch (VideoFragment.kt)
- Non-swapped mode: YOLO на камере телефона → команды на турель XIAO
  Viewpoints не совпадают если телефон и турель смотрят в разных направлениях
- Добавлен комментарий-предупреждение в processFrame()

## Затронутые файлы

| Файл | Fixes |
|------|-------|
| `app/.../tracking/ObjectTracker.kt` | #1, #5, #7, #9 |
| `app/.../tracking/SimpleKalmanFilter.kt` | #4, #5, #9 |
| `app/.../ui/video/VideoFragment.kt` | #3, #6, #10, Auto-Tune UI |
| `firmware/turret_client/turret_client.ino` | #2 (→ v2.7.2) |

## Установка
1. Распаковать `v3.0-tracking-fixes.zip` в корень репы (перезапишет 4 файла)
2. Собрать APK
3. Залить firmware v2.7.2 на XIAO через OTA (`http://192.168.4.2:81/update`)

---

# v3.0 — Live Tracking Tuning + YOLO INT8 Support

Target branch: bugfixes-2026-04-05

## 1. Live Tracking Tuning Sliders

### AppSettings.kt
- Added: trackDeadzone (0.01–0.15), trackExpo (1.0–3.0), trackRateLimit (2–30)
- Persisted in SharedPreferences, loaded at startup

### ObjectTracker.kt
- Deadzone/expo/rateLimit now configurable (were hardcoded constants)
- New method: updateTrackingTuning(deadzone, expo, rateLimit)
- Settings take effect immediately without app restart

### CfgFragment.kt + fragment_cfg.xml
- New section "TRACKING TUNING" with three sliders:
  - Deadzone (1–15%): center dead area, no servo movement
  - Expo curve (1.0–3.0): 1=linear, 2=quadratic, 3=cubic
  - Rate limit (2–30): max command change per frame
- Changes saved to SharedPreferences and pushed to ObjectTracker in real-time

### VideoFragment.kt
- Pushes tracking tuning to ObjectTracker on settings change and on init

## 2. PWM Smoothing (firmware)

Already in v2.9 firmware — no changes needed:
- PAN: PAN_SMOOTH_STEP=3 (max 3°/tick at 100Hz)
- TILT: proportional speed + TILT_PWM_RAMP=5

## 3. YOLO INT8 Support

### VideoFragment.kt
- Auto-detects yolov8n_int8.tflite in assets, falls back to yolov8n.tflite
- Logs which model is loaded

### ObjectTracker.kt
- NNAPI delegate (try NNAPI → GPU → CPU) — already in v2.8
- INT8 model works with same code (TFLite handles quantization transparently)

### tools/export_yolo_int8.sh
- Script to generate INT8 model: `pip install ultralytics && bash tools/export_yolo_int8.sh`
- Produces yolov8n_int8.tflite (~4MB vs ~13MB float32)
- Copy to app/src/main/assets/ — app auto-detects

## Рекомендации по настройке

### Для плавного трекинга (стационарный объект, минимум дрыганья):
- Deadzone: 5-6%, Expo: 2.5, Rate limit: 5-6

### Для быстрого трекинга (движущийся объект):
- Deadzone: 2-3%, Expo: 1.5, Rate limit: 15-20

### Дефолт (баланс):
- Deadzone: 4%, Expo: 2.0, Rate limit: 8

## Установка
1. Распаковать в корень репы
2. Собрать APK
3. (Опционально) Сгенерировать INT8 модель:
   pip install ultralytics
   bash tools/export_yolo_int8.sh
   cp yolov8n_int8.tflite app/src/main/assets/
4. Прошивка не затронута
