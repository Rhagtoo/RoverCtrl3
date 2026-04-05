# v2.7.1 — Tilt Calibration Redesign

Target branch: bugfixes-2026-04-05

## Проблемы в v2.7
- VCAL кнопки (-5/-1/90/+1/+5) двигали камеру из-за race condition
  между Core 0 (UDP) и Core 1 (servo loop)
- TCAL интегрировал виртуальный угол теми же dps, которые калибруются (порочный круг)
- Непонятный UX: "сравни виртуальный Δ с физическим, подкрути слайдер, повтори"

## Новый flow калибровки
1. Поставь камеру вперёд, введи 90° → Set (VCAL:90, камера НЕ двигается)
2. Нажми Test ↑ (или ↓) → мотор крутится 2с, виртуальный угол НЕ меняется
3. Введи сколько градусов камера реально прошла → Apply
4. Приложение считает: dps = градусы / 2с, отправляет TSET + VCAL синхронизацию
5. Save ESP → NVS

## Изменённые файлы

### firmware/turret_client/turret_client.ino (v2.7.1)
- VCAL: принудительно servo=neutral после установки (исключает jitter)
- TCAL: убрана интеграция virtualAngle во время sweep (raw motor test)
- /status: добавлены tcalDone, tcalElapsedMs, tcalStartAngle

### app/.../res/layout/fragment_cfg.xml
- Убраны кнопки -5°/-1°/90°/+1°/+5°
- Добавлен EditText "Virtual °:" + кнопка Set
- Добавлен блок "Actual °:" + Apply/Cancel (появляется после теста)
- Обновлена инструкция

### app/.../ui/cfg/CfgFragment.kt
- setupTiltCalibration(): новый EditText + Set вместо ±5 кнопок
- startTcal(): после 2.5с показывает поле ввода реальных градусов
- applyTcalResult(): считает dps, отправляет TSET + VCAL, обновляет слайдеры

## Установка
1. Распаковать в корень репы (ветка bugfixes-2026-04-05)
2. Прошить турель
3. Собрать APK
