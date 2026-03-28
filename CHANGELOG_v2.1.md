# RoverCtrl v2.1 — PiP Swap + CR Tilt Servo + Video Overlay Controls

## Что изменилось

### 1. PiP Swap (Video tab)
- **Кнопка SWAP** в нижней панели — переключает основной/PiP источник
- **Тап по PiP** — тоже свапает (раньше был recenter гиро на Control)
- Когда main = XIAO: fullscreen ImageView показывает MJPEG стрим, CameraX preview скрывается (но продолжает работать для трекинга)
- Метки источника: "📡 XIAO" / "📷 PHONE", label на PiP тоже меняется
- Крестик лазера скрывается когда PiP = phone (нет смысла)

### 2. Overlay Controls на Video (Manual mode)
- Полупрозрачные (alpha 0.45) джойстики поверх видео:
  - Левый = DRIVE (fwd + steering)
  - Правый = CAMERA (pan/tilt)
- Кнопка лазера между джойстиками
- Появляются только в Manual mode, скрываются в Laser/YOLO/Gyro
- Теперь можно управлять ровером прямо с вкладки Video!

### 3. Continuous Rotation Tilt Servo (firmware)
- TILT серво теперь работает как CR (continuous rotation)
- `write(90)` = стоп, отклонение = скорость вращения
- Deadzone ±8 — мелкие отклонения джойстика не вызывают drift
- Max speed ±60 от нейтрали (диапазон 30..150, не бешеное вращение)
- **Tilt watchdog 250ms** — если нет команд, серво останавливается
- `TILT_NEUTRAL = 90` — подстрой если серво ползёт при 90

### 4. CommandSender (Android)
- Tilt deadzone ±8/100 на стороне Android
- Мелкий drift джойстика от центра не вызывает вращение серво

## Изменённые файлы

```
# Скопировать в репу, заменив существующие:
app/src/main/res/layout/fragment_video.xml          ← НОВЫЙ layout
app/src/main/java/.../ui/video/VideoFragment.kt     ← ПЕРЕПИСАН
app/src/main/java/.../network/CommandSender.kt      ← ПАТЧ (tilt deadzone)
firmware/turret_client/turret_client.ino             ← ПАТЧ (CR серво)
```

## Как применить

```bash
# В корне репы RoverCtrl:
cp -f patch/app/src/main/res/layout/fragment_video.xml \
      app/src/main/res/layout/fragment_video.xml

cp -f patch/app/src/main/java/org/rhanet/roverctrl/ui/video/VideoFragment.kt \
      app/src/main/java/org/rhanet/roverctrl/ui/video/VideoFragment.kt

cp -f patch/app/src/main/java/org/rhanet/roverctrl/network/CommandSender.kt \
      app/src/main/java/org/rhanet/roverctrl/network/CommandSender.kt

cp -f patch/firmware/turret_client/turret_client.ino \
      firmware/turret_client/turret_client.ino
```

## Подстройка tilt серво

Если серво ползёт при 90:
1. Открой Serial Monitor (115200 baud)
2. Отправь `TILT:0` — серво должно стоять
3. Если ползёт, меняй `TILT_NEUTRAL` в прошивке: 88, 89, 91, 92...
4. Перепрошей, повтори

## Git commit

```bash
git add -A
git commit -m "v2.1: PiP swap, video overlay controls, CR tilt servo

- SWAP button + tap-to-swap PiP on Video tab
- Overlay joysticks (drive + camera) in Manual mode on Video
- Continuous rotation tilt servo support in firmware
- Tilt deadzone + watchdog (firmware + Android)
- CommandSender: tilt deadzone for CR servo"

git push
```
