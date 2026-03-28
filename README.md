# RoverCtrl — Android / Kotlin

Управление WiFi-ровером с камерой телефона (CameraX 120fps) и ML-трекингом.

## Архитектура (v2.0 — AP Mode)

```
                              ┌────────────────────────────────────────────┐
                              │          WiFi Access Point                 │
                              │          SSID: RoverAP                     │
                              │          IP: 192.168.4.1                   │
                              └────────────────────────────────────────────┘
                                               │
                 ┌─────────────────────────────┼─────────────────────────────┐
                 │                             │                             │
                 ▼                             ▼                             ▼
┌─────────────────────────┐     ┌────────────────────────┐     ┌────────────────────────┐
│   Android (телефон)      │     │  XIAO ESP32S3 (Ровер)  │     │  XIAO Sense (турель)   │
│   в руках оператора      │     │  AP HOST               │     │  Station mode          │
│                          │     │  192.168.4.1           │     │  192.168.4.2           │
│  CameraX → ML pipe      │     │                        │     │                        │
│  LaserTracker / YOLO     │     │  Моторы + серво руля   │     │  Серво pan/tilt        │
│  Gyro/Accel → tilt ctrl  │     │  Лазер + энкодеры      │     │  OV2640 камера         │
│  PiP ← MJPEG            │     │  Телеметрия UDP :4211  │     │  MJPEG стрим :81       │
└─────────────────────────┘     └────────────────────────┘     └────────────────────────┘
         │                                   ▲                             │
         │      UDP :4210                    │      UDP :4210              │
         │ SPD;STR;FWD;LASER ────────────────│ ◀──────── PAN;TILT ─────────│
         │                                   │                             │
         │ ◀───── JSON telemetry ────────────│                             │
         │ ◀───── HTTP MJPEG :81 ────────────│─────────────────────────────│
```

**НОВОЕ в v2.0:**
- Ровер создаёт собственную WiFi точку доступа (не требуется внешний роутер!)
- XIAO турель подключается к AP ровера автоматически
- Телефон подключается к "RoverAP" (пароль: rover12345)
- Фиксированные IP адреса, не нужен DHCP резерв

## Исправления в v2.0

### Критические
- ✅ **CalibrationResult** — добавлен недостающий класс (раньше не компилировалось)
- ✅ **Motor Watchdog** — если нет команд >500ms, моторы автоматически останавливаются
- ✅ **Сохранение конфигурации** — IP и порты сохраняются в SharedPreferences

### Утечки памяти и надёжность
- ✅ **Bitmap утечка в MjpegDecoder** — исправлено освобождение bitmap
- ✅ **TelemetryReceiver reconnect** — добавлен SO_REUSEADDR и retry логика
- ✅ **CommandSender синхронизация** — явная синхронизация DatagramSocket.send()

### Улучшения детекции
- ✅ **isBrightSpot false positives** — улучшен алгоритм с локальным контрастом

### Документация
- ✅ **STR маппинг** — исправлено: map(-100,100, 40,140), не 145..35
- ✅ **Убрано дублирование** — android/ директория удалена

## Структура проекта

```
app/src/main/
├── java/org/rhanet/roverctrl/
│   ├── MainActivity.kt                — Single Activity host
│   ├── data/RoverState.kt             — Data classes + SharedPreferences
│   ├── ui/
│   │   ├── RoverViewModel.kt          — Shared ViewModel (+ MJPEG decoder)
│   │   ├── control/
│   │   │   ├── JoystickView.kt        — Custom View (круговой джойстик)
│   │   │   ├── ControlFragment.kt     — Джойстики + PiP + Gyro Tilt
│   │   │   └── RoverVizView.kt        — 2D карта одометрии
│   │   ├── video/
│   │   │   ├── VideoFragment.kt       — CameraX 120fps + ML + PiP
│   │   │   ├── TrackingOverlayView.kt — Bbox / crosshair поверх превью
│   │   │   └── CalibrationFragment.kt — Калибровка лазер↔камера
│   │   └── cfg/CfgFragment.kt         — Настройки (+ сохранение)
│   ├── network/
│   │   ├── CommandSender.kt           — UDP: команды (синхронизирован)
│   │   ├── TelemetryReceiver.kt       — UDP :4211 (+ SO_REUSEADDR)
│   │   └── MjpegDecoder.kt            — MJPEG → Bitmap (без утечек)
│   └── tracking/
│       ├── PidController.kt           — Полный PID (P+I+D)
│       ├── LaserTracker.kt            — HSV + улучшенный bright-spot
│       ├── ObjectTracker.kt           — YOLOv8n TFLite → PID
│       ├── OdometryTracker.kt         — Dead reckoning (RPM)
│       ├── CalibrationData.kt         — Данные калибровки
│       ├── CalibrationResult.kt       — ★ NEW: результат калибровки
│       └── GyroTiltController.kt      — Гиро → pan/tilt турели
├── res/...
firmware/
├── rover_ap/
│   └── rover_ap.ino                   — ★ NEW: Ровер с AP mode + watchdog
└── turret_client/
    └── turret_client.ino              — ★ NEW: Турель как WiFi клиент
```

## Протокол

| Назначение | Получатель | Формат | Порт |
|---|---|---|---|
| Движение + лазер | Ровер (192.168.4.1) | `SPD:{};STR:{};FWD:{};LASER:{}\n` | 4210 |
| Pan/Tilt серво | Xiao (192.168.4.2) | `PAN:{};TILT:{}\n` | 4210 |
| Телеметрия ← | Телефон | `{"bat":N,"yaw":F,...,"rpmL":F,"rpmR":F}` | 4211 |
| MJPEG стрим ← | Телефон | HTTP multipart/x-mixed-replace | 81 |

## Трекинг

| Режим | Источник | Описание |
|---|---|---|
| Manual | Джойстик | Правый джойстик управляет pan/tilt |
| Laser Dot | Камера телефона | HSV-маска + улучшенный bright-spot → PID |
| Object (YOLOv8) | Камера телефона | TFLite inference → PID |
| Gyro Tilt | IMU телефона | Наклон → pan/tilt (FPV) |

## Быстрый старт

### 1. Прошивка ровера (AP mode)
```bash
# Arduino IDE: выбрать XIAO ESP32S3
# Загрузить firmware/rover_ap/rover_ap.ino
```

### 2. Прошивка турели
```bash
# Arduino IDE: выбрать XIAO ESP32S3 Sense
# Загрузить firmware/turret_client/turret_client.ino
```

### 3. Подключение телефона
- WiFi → RoverAP (пароль: rover12345)
- Запустить RoverCtrl
- Нажать Connect (настройки по умолчанию уже правильные)

### 4. Android Studio (для разработки)
```bash
# Открыть папку RoverCtrl как проект
# Gradle sync → выбрать устройство → Run
```

## Defaults (v2.0 AP Mode)

| Параметр | Значение |
|---|---|
| WiFi SSID | RoverAP |
| WiFi Password | rover12345 |
| Rover IP | 192.168.4.1 |
| Xiao IP | 192.168.4.2 |
| Command port | 4210 |
| Telemetry port | 4211 |
| Turret stream port | 81 |
| Motor watchdog | 500ms |

## Миграция со старой версии

Если вы использовали старую версию с внешним роутером:

1. **Прошивка:** перешейте оба модуля новыми прошивками
2. **Android:** сбросьте настройки кнопкой "Reset" в Settings
3. **WiFi:** подключитесь к RoverAP вместо вашей сети

## TODO

- [x] AP mode на ровере
- [x] Motor watchdog
- [x] Сохранение конфигурации
- [x] CalibrationResult класс
- [x] Fix bitmap утечки
- [x] Fix isBrightSpot false positives
- [ ] Настройки PID через UI
- [ ] Настройки чувствительности гиро через UI
- [ ] Детекция лазера на камере XIAO (локальный closed-loop)
- [ ] Запись видео с турели
- [ ] OTA обновление прошивок
#   R o v e r C t r l 2  
 #   R o v e r C t r l 2  
 #   R o v e r C t r l 3  
 #   R o v e r C t r l 3  
 