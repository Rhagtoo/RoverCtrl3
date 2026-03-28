# Прошивки RoverCtrl v2.0

## Обзор

Эта директория содержит прошивки для двух микроконтроллеров:

| Модуль | Директория | Плата | WiFi Mode |
|--------|------------|-------|-----------|
| Ровер | `rover_ap/` | XIAO ESP32S3 | **AP** (192.168.4.1) |
| Турель | `turret_client/` | XIAO ESP32S3 Sense | Station (192.168.4.2) |

## Архитектура v2.0

```
┌─────────────────────────────────────┐
│  rover_ap.ino                       │
│  XIAO ESP32S3                       │
│  WiFi AP "RoverAP"                  │
│  IP: 192.168.4.1                    │
│                                     │
│  • Моторы + серво руля              │
│  • Энкодеры PCNT                    │
│  • Лазер                            │
│  • Motor watchdog 500ms             │
│  • Телеметрия UDP :4211             │
└─────────────────────────────────────┘
                │
    WiFi "RoverAP" / rover12345
                │
       ┌────────┴────────┐
       │                 │
       ▼                 ▼
┌─────────────┐    ┌─────────────┐
│turret_client│    │  Android    │
│XIAO Sense   │    │  телефон    │
│192.168.4.2  │    │  DHCP       │
│             │    │             │
│• Pan/Tilt   │    │• Управление │
│• Камера     │    │• Трекинг    │
│• MJPEG :81  │    │• PiP        │
└─────────────┘    └─────────────┘
```

## Порядок запуска

1. **Включить ровер** — создаст WiFi точку доступа
2. **Включить турель** — автоматически подключится к AP
3. **Подключить телефон** к WiFi "RoverAP" (пароль: rover12345)
4. **Запустить приложение** → Connect

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
2. Tools → Board → **XIAO ESP32S3**
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
| Turret IP | 192.168.4.2 |

Чтобы изменить, отредактируйте в обеих прошивках:
```cpp
const char* AP_SSID = "RoverAP";
const char* AP_PASS = "rover12345";
```

## Отладка

Подключите USB и откройте Serial Monitor (115200 baud).

**Ровер:**
```
=== RoverCtrl ESP32S3 AP Mode ===
AP SSID: RoverAP
AP IP: 192.168.4.1
Ready!
```

**Турель:**
```
=== Turret XIAO ESP32S3 Sense ===
Connecting to RoverAP...
Connected! IP: 192.168.4.2
Ready!
```

## Известные проблемы

1. **Турель не подключается**: убедитесь что ровер уже включен
2. **Медленный стрим**: уменьшите jpeg_quality в turret_client.ino
3. **Дальность WiFi**: ~30-50м на открытом пространстве
