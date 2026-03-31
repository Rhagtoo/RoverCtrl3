# Прошивки RoverCtrl v2.4

## Обзор

Эта директория содержит прошивки для двух микроконтроллеров:

| Модуль | Директория | Плата | Версия | WiFi Mode |
|--------|------------|-------|--------|-----------|
| Ровер | `rover_ap/` | ESP32-S3 | v2.4 | **AP** (192.168.4.1) |
| Турель | `turret_client/` | XIAO ESP32S3 Sense | v2.5 | Station (192.168.4.2) |

## Архитектура

```
┌─────────────────────────────────────────┐
│  rover_ap.ino v2.4                      │
│  ESP32-S3                               │
│  WiFi AP "RoverAP"                      │
│  IP: 192.168.4.1                        │
│                                         │
│  • DC мотор (IN1/IN2 + PWM L/R)        │
│  • Серво руля (Ackermann)               │
│  • Энкодеры PCNT (440 CPR, 200ms)       │
│  • Лазер                                │
│  • 2 передачи (GEAR: 1=50%, 2=100%)     │
│  • Motor watchdog 500ms                 │
│  • Телеметрия UDP :4211 (каждые 500ms)  │
│  • Команды UDP :4210                    │
└─────────────────────────────────────────┘
                │
    WiFi "RoverAP" / rover12345
                │
       ┌────────┴────────┐
       │                 │
       ▼                 ▼
┌──────────────────┐  ┌──────────────────┐
│ turret_client    │  │  Android App      │
│ v2.3             │  │  (DHCP)           │
│ XIAO ESP32S3    │  │                    │
│ Sense            │  │  • Управление     │
│ 192.168.4.2     │  │  • 4 режима        │
│                  │  │    трекинга        │
│ • Pan (позиц.)  │  │  • PiP swap        │
│ • Tilt (позиционный) │  │  • Одометрия       │
│ • OV2640 HVGA   │  │  • Калибровка      │
│ • MJPEG :81     │  │                    │
│ • Watchdog 2с   │  │                    │
└──────────────────┘  └──────────────────┘
```

## Порядок запуска

1. **Включить ровер** — создаст WiFi точку доступа
2. **Включить турель** — автоматически подключится к AP
3. **Подключить телефон** к WiFi "RoverAP" (пароль: `rover12345`)
4. **Запустить приложение** → Settings → Connect

## Установка

### Arduino IDE

1. Установить ESP32 Board Package:
   - File → Preferences → Additional Board Manager URLs:
   - `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
   - Tools → Board → Boards Manager → esp32 → Install

2. Установить библиотеку ESP32Servo:
   - Tools → Manage Libraries → ESP32Servo → Install

### Прошивка ровера

1. Открыть `rover_ap/rover_ap.ino`
2. Tools → Board → **ESP32S3 Dev Module** (или XIAO ESP32S3 если используется)
3. Upload

### Прошивка турели

1. Открыть `turret_client/turret_client.ino`
2. Tools → Board → **XIAO ESP32S3** (или XIAO ESP32S3 Sense)
3. Upload

## WiFi параметры

| Параметр | Значение |
|----------|----------|
| SSID | RoverAP |
| Password | rover12345 |
| Rover IP | 192.168.4.1 |
| Turret IP | 192.168.4.2 (static) |

Чтобы изменить, отредактируйте в обеих прошивках:
```cpp
const char* AP_SSID = "RoverAP";
const char* AP_PASS = "rover12345";
```

## Отладка

Подключите USB и откройте Serial Monitor (115200 baud).

**Ровер:**
```
=== Rover AP v2.4 ===
AP:RoverAP IP:192.168.4.1
Ready!
```

**Турель:**
```
=== Turret v2.5 CR positional tilt ===
PAN=positional TILT=CR positional(neutral=90 maxSpeed=50 up=60 dn=90)
Camera OK
Connected: 192.168.4.2
Ready!
```

## Известные проблемы

1. **Турель не подключается** — убедитесь что ровер уже включен
2. **Медленный стрим** — уменьшите `jpeg_quality` в turret_client.ino (меньше = лучше)
3. **Дальность WiFi** — ~30-50м на открытом пространстве
4. **RSSI в телеметрии всегда 0** — ESP32 в AP-режиме не имеет базовой станции, телефон читает своё RSSI через WifiManager
5. **GPIO 35-38** — на ESP32-S3 могут быть заняты PSRAM/Flash, осторожно с энкодерами (ENC_R_A=38, ENC_R_B=37 работают, но проверяйте на конкретной плате)
6. **CR tilt ползёт** — подстройте `TILT_NEUTRAL` (88-92) в turret_client.ino
