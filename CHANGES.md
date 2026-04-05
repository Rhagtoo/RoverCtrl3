# v2.8 — Performance Optimization (XIAO + YOLO pipeline)

Target branch: bugfixes-2026-04-05

## Симптом
Видео с XIAO в YOLO-режиме периодически замирает на 2-5 секунд.

## Корневая причина
1. MjpegDecoder: O(n²) ByteArray конкатенация → GC-шторм → фриз
2. 4× копирование bitmap на кадр (decode→VM→Fragment→YOLO) = 30MB/с GC
3. Нет backpressure: executor queue растёт → память → GC → фриз

## Исправления

### MjpegDecoder.kt
- ByteArrayOutputStream вместо data += buf (amortized O(1))
- BitmapFactory.Options.inBitmap — переиспользование bitmap
- Быстрый 2-byte SOI/EOI поиск

### RoverViewModel.kt
- Убран Log.d() на каждый кадр турели (10-15fps string format + IPC)
- Не recycle bitmap от MjpegDecoder (reused через inBitmap)

### VideoFragment.kt
- Inference gate (AtomicBoolean): YOLO занят → кадр пропускается
- Счётчик d:N в HUD для диагностики
- 1 copy вместо 2

### ObjectTracker.kt
- Cached ByteBuffer (5MB → 0 аллок/кадр)
- Cached IntArray (1.6MB → 0 аллок/кадр)
- NNAPI delegate (try→GPU→CPU fallback)
- Убран per-detection Log.d()

## Диагностика
HUD: `lat: 85 ms · fps: 30 d:142`
d:N = пропущенные кадры анализа (нормально, gate работает)

## Установка
1. Распаковать в корень репы (ветка bugfixes-2026-04-05)
2. Собрать APK — изменения только на стороне телефона
