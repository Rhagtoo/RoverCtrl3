# Issues для TODO из README

## 1. OTA прошивка по WiFi
**Title:** [FEATURE] OTA firmware update over WiFi  
**Labels:** enhancement, firmware  
**Description:**  
Реализовать OTA (Over-The-Air) обновление прошивки по WiFi для ESP32-S3 и XIAO ESP32S3 Sense.  
Использовать ArduinoOTA или ESP HTTP OTA.  
Требуется:  
- [ ] Реализовать OTA сервер в прошивках  
- [ ] Добавить интерфейс в Android приложении  
- [ ] Тестирование обновления  
- [ ] Документация по использованию  

## 2. Интеграция Raspberry Pi 5
**Title:** [FEATURE] Raspberry Pi 5 integration  
**Labels:** enhancement, hardware, ai  
**Description:**  
Интеграция Raspberry Pi 5 для расширенных возможностей:  
- Камера высокого разрешения  
- AI HAT+ для улучшенного компьютерного зрения  
- Дополнительные датчики  
Требуется:  
- [ ] Исследование совместимости  
- [ ] Разработка интерфейса связи  
- [ ] Интеграция с существующей системой  
- [ ] Тестирование  

## 3. Настройка чувствительности джойстиков
**Title:** [FEATURE] Joystick sensitivity settings  
**Labels:** enhancement, ui, control  
**Description:**  
Добавить настройку чувствительности джойстиков в приложении.  
Требуется:  
- [ ] Добавить слайдеры в SettingsFragment  
- [ ] Сохранять настройки в SharedPreferences  
- [ ] Применять коэффициенты в JoystickView  
- [ ] Тестирование с разными значениями  

## 4. Запись видео / телеметрии
**Title:** [FEATURE] Video and telemetry recording  
**Labels:** enhancement, video, data  
**Description:**  
Реализовать запись видео с камеры и телеметрии для последующего анализа.  
Требуется:  
- [ ] Запись видео (CameraX)  
- [ ] Запись телеметрии (CSV/JSON)  
- [ ] Синхронизация видео и телеметрии  
- [ ] Интерфейс управления записью  
- [ ] Экспорт данных  

## 5. Автопилот (waypoint навигация)
**Title:** [FEATURE] Autopilot with waypoint navigation  
**Labels:** enhancement, autonomy, advanced  
**Description:**  
Реализовать автопилот с навигацией по точкам (waypoints).  
Требуется:  
- [ ] Интерфейс задания waypoints на 2D карте  
- [ ] Алгоритм навигации между точками  
- [ ] Контроль скорости и направления  
- [ ] Безопасность и аварийные остановки  
- [ ] Тестирование