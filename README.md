# RoverCtrl

Remote control system for a rover with autonomous object tracking.

## Components

| Component | Platform | Description |
|-----------|----------|-------------|
| Android App | Kotlin / Android Studio | Control, video, tracking |
| Rover Firmware | ESP32-S3 (Arduino) | Motor, steering, encoders, laser |
| Turret Firmware | XIAO ESP32S3 Sense (Arduino) | Pan/tilt camera, MJPEG stream |

## Architecture

```
┌─────────────────────────────────────┐
│  rover_ap.ino v2.4                  │
│  ESP32-S3                           │
│  WiFi AP "RoverAP"                  │
│  IP: 192.168.4.1                    │
│                                     │
│  • DC motor + steering servo        │
│  • PCNT encoders (440 CPR)          │
│  • Laser                            │
│  • 2 gears (GEAR 1/2)               │
│  • Motor watchdog 500ms             │
│  • Telemetry UDP :4211 (500ms)      │
│  • Commands UDP :4210               │
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
│ Sense            │  │  3 tabs:               │
│ 192.168.4.2     │  │  • Control (joysticks  │
│                  │  │    + HUD + 2D map)     │
│ • Pan (positional)│  │  • Video (CameraX +  │
│ • Tilt (CR+PID) │  │    MJPEG + tracking)   │
│ • OV2640 camera │  │  • Settings (IP/ports) │
│ • MJPEG :81     │  │                        │
│ • UDP :4210     │  │  Tracking modes:        │
└─────────────────┘  │  Manual / Laser / YOLO │
                      │  / Gyro Tilt           │
                      └──────────────────────┘
```

## Features

**Control:**
- Dual joystick: left — throttle/steering, right — camera pan/tilt
- 2 gears: 1st (max 50%) and 2nd (max 100%)
- Laser pointer on/off
- PiP window from turret camera on Control tab

**Tracking (Video tab):**
- **Manual** — manual camera control with joystick
- **Laser Dot** — HSV + bright‑spot detection of laser dot
- **Object (YOLOv8)** — YOLOv8n TFLite, 80 COCO classes
- **Gyro Tilt** — stabilization via phone IMU

**Video:**
- PiP swap: switch main/PiP source (XIAO ↔ phone)
- Overlay joysticks in Manual mode
- YOLO/Laser analysis on XIAO stream when swapped
- Latency tracker (pipeline timing)

**Odometry:**
- Ackermann bicycle model (`headingRate = v × tan(steerAngle) / wheelBase`)
- 2D map with auto‑scale, gradient track, pinch‑to‑zoom
- Real speed from encoder RPM (m/s, km/h)

## Quick Start

1. Flash rover and turret (see `firmware/README.md`)
2. Turn on rover → turn on turret
3. Connect phone to WiFi **RoverAP** (password: `rover12345`)
4. Open app → Settings → Connect

## Building the App

```bash
cd RoverCtrl
./gradlew assembleDebug
```

Requires Android Studio + Kotlin, minSdk 24, targetSdk 34.

Model `yolov8n.tflite` must be placed in `app/src/main/assets/`.

## Project Structure

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
│   ├── rover_ap/          # Rover firmware (ESP32-S3)
│   └── turret_client/     # Turret firmware (XIAO ESP32S3 Sense)
└── README.md
```

## TODO

- [ ] OTA firmware update over WiFi (ArduinoOTA / ESP HTTP OTA)
- [ ] Raspberry Pi 5 integration (camera + AI HAT+)
- [ ] Joystick sensitivity settings in Settings
- [ ] Video / telemetry recording
- [ ] Autopilot (waypoint navigation)
