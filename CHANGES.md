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
