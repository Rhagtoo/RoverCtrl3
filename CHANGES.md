# v2.7 Update — XIAO Performance Fix

## Что изменено

### firmware/turret_client/turret_client.ino
- Камера: HVGA 480×320 (было VGA 640×480), quality 10 (было 6)
- Убран ArduinoOTA (mDNS overhead)
- Убран Arduino WebServer на порту 80 (синхронный, блокировал loop)
- Все HTTP-эндпоинты на одном async esp_httpd (порт 81):
  `/stream`, `/capture`, `/status`, `/update` (GET=форма, POST=бинарь)
- Исправлен /status JSON (были ссылки на несуществующие переменные)
- loop() теперь: checkWiFi + updateServos + delay(10) — ничего лишнего
- OTA через браузер: http://192.168.4.2:81/update

### app/.../network/OtaUploader.kt
- Добавлен параметр `port` (80 для ровера, 81 для турели)
- Добавлен параметр `rawBinary` (true = raw POST, false = multipart)
- Турель v2.7+: raw binary на порт 81
- Ровер: без изменений (multipart на порт 80)

### app/.../ui/cfg/CfgFragment.kt
- Поллинг /status только на порту 81 (убрана попытка порта 80)
- OTA upload: автоопределение порта и формата по цели (ровер/турель)

## Установка
1. Распаковать архив в корень репы (файлы заменятся)
2. Прошить турель через Arduino IDE
3. Собрать и установить Android-приложение
