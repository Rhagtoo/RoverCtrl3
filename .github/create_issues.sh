#!/bin/bash
# Скрипт для создания Issues через GitHub API
# Требуется GitHub Personal Access Token с scope repo

set -e

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Ошибка: GITHUB_TOKEN не установлен"
    echo "Создайте токен на https://github.com/settings/tokens (scope: repo)"
    echo "И запустите: GITHUB_TOKEN=your_token ./create_issues.sh"
    exit 1
fi

REPO="Rhagtoo/RoverCtrl3"
API_URL="https://api.github.com/repos/$REPO/issues"

create_issue() {
    local title="$1"
    local body="$2"
    local labels="$3"
    
    echo "Создание Issue: $title"
    
    curl -s -X POST "$API_URL" \
        -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        -d "{
            \"title\": \"$title\",
            \"body\": \"$body\",
            \"labels\": [$labels]
        }" | jq -r '.html_url'
}

# Issue 1: OTA прошивка по WiFi
create_issue \
    "[FEATURE] OTA firmware update over WiFi" \
    "## Описание\nРеализовать OTA (Over-The-Air) обновление прошивки по WiFi для ESP32-S3 и XIAO ESP32S3 Sense.\n\n## Задачи\n- [ ] Реализовать OTA сервер в прошивках\n- [ ] Добавить интерфейс в Android приложении\n- [ ] Тестирование обновления\n- [ ] Документация по использованию\n\n## Технические детали\nИспользовать ArduinoOTA или ESP HTTP OTA." \
    "\"enhancement\", \"firmware\""

# Issue 2: Интеграция Raspberry Pi 5
create_issue \
    "[FEATURE] Raspberry Pi 5 integration" \
    "## Описание\nИнтеграция Raspberry Pi 5 для расширенных возможностей.\n\n## Задачи\n- [ ] Исследование совместимости\n- [ ] Разработка интерфейса связи\n- [ ] Интеграция с существующей системой\n- [ ] Тестирование\n\n## Возможности\n- Камера высокого разрешения\n- AI HAT+ для улучшенного компьютерного зрения\n- Дополнительные датчики" \
    "\"enhancement\", \"hardware\", \"ai\""

# Issue 3: Настройка чувствительности джойстиков
create_issue \
    "[FEATURE] Joystick sensitivity settings" \
    "## Описание\nДобавить настройку чувствительности джойстиков в приложении.\n\n## Задачи\n- [ ] Добавить слайдеры в SettingsFragment\n- [ ] Сохранять настройки в SharedPreferences\n- [ ] Применять коэффициенты в JoystickView\n- [ ] Тестирование с разными значениями\n\n## Приоритет\nВысокий - улучшает пользовательский опыт." \
    "\"enhancement\", \"ui\", \"control\""

# Issue 4: Запись видео / телеметрии
create_issue \
    "[FEATURE] Video and telemetry recording" \
    "## Описание\nРеализовать запись видео с камеры и телеметрии для последующего анализа.\n\n## Задачи\n- [ ] Запись видео (CameraX)\n- [ ] Запись телеметрии (CSV/JSON)\n- [ ] Синхронизация видео и телеметрии\n- [ ] Интерфейс управления записью\n- [ ] Экспорт данных\n\n## Использование\nДля анализа поведения ровера, отладки, создания демонстраций." \
    "\"enhancement\", \"video\", \"data\""

# Issue 5: Автопилот (waypoint навигация)
create_issue \
    "[FEATURE] Autopilot with waypoint navigation" \
    "## Описание\nРеализовать автопилот с навигацией по точкам (waypoints).\n\n## Задачи\n- [ ] Интерфейс задания waypoints на 2D карте\n- [ ] Алгоритм навигации между точками\n- [ ] Контроль скорости и направления\n- [ ] Безопасность и аварийные остановки\n- [ ] Тестирование\n\n## Сложность\nВысокая - требует тщательного тестирования безопасности." \
    "\"enhancement\", \"autonomy\", \"advanced\""

echo "Все Issues созданы!"