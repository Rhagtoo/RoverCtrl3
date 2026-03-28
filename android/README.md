# RoverCtrl — Android / Kotlin

Управление WiFi-ровером с камерой телефона (CameraX 120fps) и ML-трекингом.

## Архитектура

```
┌─────────────────────────┐      UDP :4210         ┌────────────────────────┐
│   Android (телефон)      │ ───────────────────▶   │  XIAO ESP32S3          │
│   в руках оператора      │ SPD;STR;FWD;LASER      │  Моторы + серво руля   │
│                          │                        │  Лазер + энкодеры      │
│  CameraX → ML pipe      │ ◀───────────────────  │  Телеметрия UDP :4211  │
│  LaserTracker / YOLO     │  JSON telemetry        │  192.168.88.24         │
│                          │                        └────────────────────────┘
│  Gyro/Accel → tilt ctrl  │      UDP :4210
│  PiP ← MJPEG            │ ───────────────────▶   ┌────────────────────────┐
│                          │ PAN;TILT               │  XIAO Sense (турель)   │
│                          │ ◀───────────────────  │  Серво pan/tilt        │
│                          │ HTTP MJPEG :81         │  OV2640 камера         │
└─────────────────────────┘                        │  192.168.88.23         │
                                                    └────────────────────────┘
```

**Камера телефона** — CameraX + Camera2 Interop, до 120fps (трекинг).
**Камера турели** — OV2640 на XIAO Sense, MJPEG стрим (FPV PiP).
**Телефон** — в руках оператора (не на ровере!).

## Структура проекта

```
app/src/main/
├── java/org/rhanet/roverctrl/
│   ├── MainActivity.kt                — Single Activity host
│   ├── data/RoverState.kt             — Data classes, enums (+ GYRO_TILT)
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
│   │   └── cfg/CfgFragment.kt         — Настройки (IP, порты, stream port)
│   ├── network/
│   │   ├── CommandSender.kt           — UDP: команды роверу + Xiao
│   │   ├── TelemetryReceiver.kt       — UDP :4211, JSON парсинг
│   │   └── MjpegDecoder.kt            — MJPEG стрим → Bitmap (для PiP)
│   └── tracking/
│       ├── PidController.kt           — Полный PID (P+I+D)
│       ├── LaserTracker.kt            — HSV маска + bright-spot → PID
│       ├── ObjectTracker.kt           — YOLOv8n TFLite → PID
│       ├── OdometryTracker.kt         — Dead reckoning (RPM или fallback)
│       ├── CalibrationData.kt         — Данные калибровки + линейная регрессия
│       └── PhoneImuController.kt      — ★ NEW: гиро → pan/tilt турели
├── res/...
firmware/
└── xiao_sense_turret_cam/
    ├── xiao_sense_turret_cam.ino       — ★ NEW: Arduino прошивка XIAO Sense
    └── README.md                       — Инструкция по прошивке
```

## Новые фичи

### PiP — вид из турели
Камера OV2640 на XIAO Sense стримит MJPEG по HTTP. Телефон показывает
картинку в маленьком окне (PiP) поверх основного UI. Ось камеры совпадает
с лучом лазера → вы видите куда светит лазер.

- Автопоказ PiP при подключении
- Крестик в центре PiP = ось лазера
- Тап по PiP = recenter гироскопа (если GYRO_TILT активен)
- Доступен на вкладках Control и Video

### Gyro Tilt — управление головой ровера наклоном телефона
Наклоняете телефон → турель поворачивается. FPV-управление «головой».

- Roll телефона → Pan турели (наклон влево/вправо)
- Pitch телефона → Tilt турели (наклон вперёд/назад)
- Low-pass фильтр от дрожания рук
- Мёртвая зона 2.5° (настраиваемая)
- Чувствительность: ±30° телефона = полный ход серво
- Recenter: тап по PiP или переключение режима
- GAME_ROTATION_VECTOR (без магнитного дрейфа)

## Протокол

| Назначение | Получатель | Формат | Порт |
|---|---|---|---|
| Движение + лазер | Ровер (192.168.88.24) | `SPD:{};STR:{};FWD:{};LASER:{}\n` | 4210 |
| Pan/Tilt серво | Xiao (192.168.88.23) | `PAN:{};TILT:{}\n` | 4210 |
| Телеметрия ← | Телефон | `{"bat":N,"yaw":F,...,"rpmL":F,"rpmR":F}` | 4211 |
| MJPEG стрим ← | Телефон | HTTP multipart/x-mixed-replace | 81 |

## Трекинг

| Режим | Источник | Описание |
|---|---|---|
| Manual | Джойстик | Правый джойстик управляет pan/tilt |
| Laser Dot | Камера телефона | HSV-маска красного → PID |
| Object (YOLOv8) | Камера телефона | TFLite inference → PID |
| **Gyro Tilt** | **IMU телефона** | **Наклон → pan/tilt (FPV)** |

## Setup

### 1. Прошивка XIAO Sense (турель + камера)
См. [firmware/xiao_sense_turret_cam/README.md](firmware/xiao_sense_turret_cam/README.md)

### 2. Android Studio
- Открыть папку `RoverCtrl` как проект
- Gradle sync → выбрать устройство → Run
- Камера запрашивается при переходе на вкладку Video

### 3. YOLOv8 модель (опционально)
```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640
# Скопировать yolov8n_float32.tflite → app/src/main/assets/yolov8n.tflite
```

## Defaults

| Параметр | Значение |
|---|---|
| Rover IP | 192.168.88.24 |
| Xiao IP | 192.168.88.23 |
| Command port | 4210 |
| Telemetry port | 4211 |
| Turret stream port | 81 |
| Phone camera FPS | 120 (fallback на макс.) |
| Turret camera | QVGA 320×240 ~15fps |

## TODO

- [ ] Настройки PID через экран Settings
- [ ] Сохранение конфигурации в SharedPreferences
- [ ] GPU delegate fallback (NNAPI)
- [x] PiP стрим с камеры турели
- [x] Gyro Tilt управление
- [ ] Настройки чувствительности гиро через UI
- [ ] Детекция лазерной точки на камере XIAO (локальный closed-loop)
- [ ] Запись видео с турели на SD-карту
