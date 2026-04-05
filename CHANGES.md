# v2.8.1 — XIAO Landscape Fix

Target branch: bugfixes-2026-04-05 (поверх v2.8)

## Проблема
Видео с XIAO отображается как узкая вертикальная полоса.

## Причина
Двойная ротация:
1. ViewModel: TURRET_ROTATION_DEG=90 → 480×320 → 320×480 (portrait)
2. VideoFragment PiP: отображал 320×480 as-is → вертикальная полоса

## Исправления

### RoverViewModel.kt
- TURRET_ROTATION_DEG = 0 (убрана ротация)
- OV2640 HVGA выдаёт 480×320 landscape — ротация не нужна
- Не recycle bitmap от MjpegDecoder (нужен для inBitmap reuse из v2.8)

### firmware/turret_client.ino
- Добавлен sensor config после esp_camera_init:
  set_vflip(0), set_hmirror(0)
- Если изображение перевёрнуто → поменять на 1 в коде и перепрошить

## Установка
1. Распаковать в корень репы
2. Если изображение перевёрнуто/зеркальное — поменять vflip/hmirror в
   firmware/turret_client/turret_client.ino, перепрошить
3. Собрать APK
