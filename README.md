# RoverCtrl

Система дистанционного управления ровером с автономным трекингом объектов.

## Компоненты

| Компонент | Платформа | Описание |
|-----------|-----------|----------|
| Android App | Kotlin / Android Studio | Управление, видео, трекинг |
| Rover Firmware | ESP32-S3 (Arduino) | Мотор, руль, энкодеры, лазер |
| Turret Firmware | XIAO ESP32S3 Sense (Arduino) | Pan/tilt камера, MJPEG стрим |

## Архитектура

```
┌─────────────────────────────────────┐
│  rover_ap.ino v2.4                  │
│  ESP32-S3                           │
│  WiFi AP "RoverAP"                  │
│  IP: 192.168.4.1                    │
│                                     │
│  • DC мотор + серво руля            │
│  • Энкодеры PCNT (440 CPR)          │
│  • Лазер                            │
│  • 2 передачи (GEAR 1/2)            │
│  • Motor watchdog 500ms             │
│  • Телеметрия UDP :4211 (500ms)     │
│  • Команды UDP :4210                │
└─────────────────────────────────────┘
                │
    WiFi "RoverAP" / rover12345
                │
       ┌────────┴────────┐
       │                 │
       ▼                 ▼
┌─────────────────┐  ┌──────────────────────┐
│ turret_client    │  │  Android App          │
│ v2.3             │  │  (OnePlus 8T)         │
│ XIAO ESP32S3    │  │                        │
│ Sense            │  │  3 вкладки:            │
│ 192.168.4.2     │  │  • Control (джойстики  │
│                  │  │    + HUD + 2D карта)   │
│ • Pan (позиц.)  │  │  • Video (CameraX +    │
│ • Tilt (CR+PID) │  │    MJPEG + трекинг)    │
│ • OV2640 камера │  │  • Settings (IP/порты) │
│ • MJPEG :81     │  │                        │
│ • UDP :4210     │  │  Режимы трекинга:       │
└─────────────────┘  │  Manual / Laser / YOLO │
                      │  / Gyro Tilt           │
                      └──────────────────────┘
```

## Возможности

**Управление:**
- Двойной джойстик: левый — газ/руль, правый — pan/tilt камеры
- 2 передачи: 1я (макс 50%) и 2я (макс 100%)
- Лазерный указатель вкл/выкл
- PiP окно с камеры турели на вкладке Control

**Трекинг (вкладка Video):**
- **Manual** — ручное управление камерой джойстиком
- **Laser Dot** — HSV + bright-spot детекция лазерной точки
- **Object (YOLOv8)** — YOLOv8n TFLite, 80 классов COCO
- **Gyro Tilt** — стабилизация по IMU телефона

**Видео:**
- PiP swap: переключение основного/PiP источника (XIAO ↔ телефон)
- Overlay джойстики в Manual режиме
- YOLO/Laser анализ XIAO стрима при swap
- Latency tracker (pipeline timing)

**Одометрия:**
- Ackermann bicycle model (`headingRate = v × tan(steerAngle) / wheelBase`)
- 2D карта с автомасштабом, градиентным треком, pinch-to-zoom
- Реальная скорость из RPM энкодеров (м/с, км/ч)

## Быстрый старт

1. Прошить ровер и турель (см. `firmware/README.md`)
2. Включить ровер → включить турель
3. Подключить телефон к WiFi **RoverAP** (пароль: `rover12345`)
4. Открыть приложение → Settings → Connect

## Сборка приложения

```bash
cd RoverCtrl
./gradlew assembleDebug
```

Требуется Android Studio + Kotlin, minSdk 24, targetSdk 34.

Модель `yolov8n.tflite` должна лежать в `app/src/main/assets/`.

## Структура проекта

```
RoverCtrl/
├── app/src/main/
│   ├── java/org/rhanet/roverctrl/
│   │   ├── data/          # TelemetryData, ConnectionConfig, TrackingMode
│   │   ├── network/       # CommandSender, TelemetryReceiver, MjpegDecoder
│   │   ├── tracking/      # OdometryTracker, LaserTracker, ObjectTracker,
│   │   │                  # CalibrationData, LatencyTracker, PidController,
│   │   │                  # GyroTiltController
│   │   └── ui/
│   │       ├── control/   # ControlFragment, JoystickView, RoverVizView
│   │       ├── video/     # VideoFragment, CalibrationFragment, TrackingOverlayView
│   │       └── cfg/       # CfgFragment
│   ├── res/layout/        # fragment_control, fragment_video, fragment_cfg, ...
│   └── assets/            # yolov8n.tflite
├── firmware/
│   ├── rover_ap/          # Прошивка ровера (ESP32-S3)
│   └── turret_client/     # Прошивка турели (XIAO ESP32S3 Sense)
└── README.md
```

## TODO

- [ ] OTA прошивка по WiFi (ArduinoOTA / ESP HTTP OTA)
- [ ] Интеграция Raspberry Pi 5 (камера + AI HAT+)
- [ ] Настройка чувствительности джойстиков в Settings
- [ ] Запись видео / телеметрии
- [ ] Автопилот (waypoint навигация)
