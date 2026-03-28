# RoverCtrl v2.2 — полный патч

## Шаг 1: Распаковать drop-in файлы

```bash
cd ~/path/to/RoverCtrl   # корень репы

# Скопировать с заменой (8 файлов)
cp -f archive/app/src/main/res/layout/fragment_video.xml \
      app/src/main/res/layout/fragment_video.xml

cp -f archive/app/src/main/java/org/rhanet/roverctrl/ui/video/VideoFragment.kt \
      app/src/main/java/org/rhanet/roverctrl/ui/video/VideoFragment.kt

cp -f archive/app/src/main/java/org/rhanet/roverctrl/network/CommandSender.kt \
      app/src/main/java/org/rhanet/roverctrl/network/CommandSender.kt

cp -f archive/app/src/main/java/org/rhanet/roverctrl/network/TelemetryReceiver.kt \
      app/src/main/java/org/rhanet/roverctrl/network/TelemetryReceiver.kt

cp -f archive/app/src/main/java/org/rhanet/roverctrl/data/RoverState.kt \
      app/src/main/java/org/rhanet/roverctrl/data/RoverState.kt

cp -f archive/app/src/main/java/org/rhanet/roverctrl/tracking/OdometryTracker.kt \
      app/src/main/java/org/rhanet/roverctrl/tracking/OdometryTracker.kt

cp -f archive/app/src/main/java/org/rhanet/roverctrl/ui/control/RoverVizView.kt \
      app/src/main/java/org/rhanet/roverctrl/ui/control/RoverVizView.kt

cp -f archive/firmware/turret_client/turret_client.ino \
      firmware/turret_client/turret_client.ino
```

## Шаг 2: Ручные правки (3 файла)

### 2a. rover_ap.ino — добавить STR в телеметрию

Открой `firmware/rover_ap/rover_ap.ino`, найди `sendTelemetry()`.
Замени snprintf на:

```c
snprintf(buf, sizeof(buf),
    "{\"bat\":100,\"yaw\":0.0,\"spd\":%d,\"str\":%d,"
    "\"pit\":0.0,\"rol\":0.0,"
    "\"rssi\":%d,\"rpmL\":%.1f,\"rpmR\":%.1f}",
    abs(lastFwd),
    lastStr,          // ← НОВОЕ
    WiFi.RSSI(),
    rpmL, rpmR
);
```

### 2b. RoverViewModel.kt — использовать t.str из телеметрии

Найди вызов `odometry.update(...)` (в `connect()` или `updateOdometry()`).
Замени `str.toFloat()` на `data.str.toFloat()` (или `t.str.toFloat()`):

```kotlin
// БЫЛО:
odometry.update(t.rpmL, t.rpmR, t.spd, str.toFloat())
//                                      ^^^^^^^^^^^^^ локальная команда

// СТАЛО:
odometry.update(t.rpmL, t.rpmR, t.spd, t.str.toFloat())
//                                      ^^^^^^^^^^^^^ из телеметрии ровера
```

### 2c. ControlFragment.kt — скорость + карта

Найди `observeTelemetry()`, замени строку `tvSpd.text`:

```kotlin
// БЫЛО:
tvSpd.text = String.format("%.1f", t.spd)

// СТАЛО:
val realSpeed = t.speedMs
tvSpd.text = if (!realSpeed.isNaN()) {
    String.format("%.2f m/s (%d%%)", kotlin.math.abs(realSpeed), t.powerPct)
} else {
    String.format("%d%%", t.powerPct)
}
```

В `onViewCreated()` добавь:
```kotlin
val roverViz: RoverVizView? = view.findViewById(R.id.rover_viz)
roverViz?.setOdometry(vm.getOdometry())
```

В `observeTelemetry()` в конце pose observer добавь `roverViz?.refresh()`.

(Опционально: добавь `<RoverVizView>` в fragment_control.xml — см. patches/control_fragment_patch.kt)

## Шаг 3: Git

```bash
git add -A
git commit -m "v2.2: Ackermann odometry, 2D map, PiP swap, CR tilt, real speed

Odometry:
- Fixed: was using differential drive model, now Ackermann bicycle model
- heading = v * tan(steerAngle) / wheelBase
- Mid-point integration, auto-thinning track (5000 pts max)

Map (RoverVizView):
- Auto-scale grid with distance labels
- Color gradient track (old→new)
- Rover triangle with heading arrow
- Scale bar, stats overlay (X,Y,heading,distance,speed)
- Pinch-to-zoom + drag

Telemetry:
- Firmware: added 'str' field to JSON telemetry
- Android: parse str, use t.str for odometry (not local cmd)
- Real speed from RPM: 0.32 m/s (75%) instead of just 75.0

PiP swap (Video tab):
- SWAP button + tap PiP to swap main/pip source
- Fullscreen XIAO or phone camera
- Source labels (TURRET/PHONE)

Video overlay controls:
- Semi-transparent joysticks (drive + camera) in Manual mode
- Laser button overlay

Continuous rotation tilt servo:
- Firmware: deadzone, watchdog 250ms, TILT_NEUTRAL trim
- Android: tilt deadzone ±8 in CommandSender"

git push
```

## Параметры для калибровки

После заливки замерь и подстрой в OdometryTracker.kt:
- `wheelDiameter = 0.065f` — реальный диаметр колеса (м)
- `wheelBase = 0.160f` — расстояние между осями (м)
- `maxSteerAngleDeg = 30f` — угол поворота колёс при STR=100

В turret_client.ino:
- `TILT_NEUTRAL = 90` — если CR серво ползёт, подстрой (88-92)
