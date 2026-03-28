/**
 * turret_client.ino v2.3 — Virtual angle для CR tilt серво
 * PAN: позиционное. TILT: CR + PID → ведёт себя как позиционное.
 * КАЛИБРОВКА: TILT_NEUTRAL (стоп), TILT_DEG_PER_SEC (скорость)
 */
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ESP32Servo.h>
#include "esp_camera.h"
#include "esp_http_server.h"

const char* AP_SSID="RoverAP"; const char* AP_PASS="rover12345";
const IPAddress STATIC_IP(192,168,4,2),GATEWAY(192,168,4,1),SUBNET(255,255,255,0);
#define SERVO_PAN 4
#define SERVO_TILT 2
#define PWDN_GPIO_NUM -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 10
#define SIOD_GPIO_NUM 40
#define SIOC_GPIO_NUM 39
#define Y9_GPIO_NUM 48
#define Y8_GPIO_NUM 11
#define Y7_GPIO_NUM 12
#define Y6_GPIO_NUM 14
#define Y5_GPIO_NUM 16
#define Y4_GPIO_NUM 18
#define Y3_GPIO_NUM 17
#define Y2_GPIO_NUM 15
#define VSYNC_GPIO_NUM 38
#define HREF_GPIO_NUM 47
#define PCLK_GPIO_NUM 13
#define UDP_PORT 4210
#define HTTP_PORT 81
#define RECONNECT_INTERVAL_MS 5000

// ═══ CR Tilt — КАЛИБРОВАТЬ! ═══
#define TILT_NEUTRAL      90     // PWM = стоп (подстрой 88-92)
#define TILT_DEADBAND     3.0f   // ±3° = на месте
#define TILT_KP           1.2f   // P-gain (1.0-2.0)
#define TILT_MAX_SPEED    50     // макс отклонение от neutral
#define TILT_DEG_PER_SEC  90.0f  // °/сек при макс скорости (замерить!)
#define TILT_MIN_ANGLE    10.0f
#define TILT_MAX_ANGLE    170.0f

WiFiUDP udp; Servo servoPan, servoTilt; httpd_handle_t httpd = NULL;
int currentPan = 90; volatile bool panDirty = false; volatile int targetPan = 90;
float virtualTiltAngle = 90.0f; volatile float tiltTargetAngle = 90.0f;
int currentTiltPwm = TILT_NEUTRAL; unsigned long lastTiltLoopMs = 0;
bool wifiConnected = false; unsigned long lastReconnectAttempt = 0, lastCmdTime = 0;

bool setupCamera() {
    camera_config_t c;
    c.ledc_channel=LEDC_CHANNEL_0; c.ledc_timer=LEDC_TIMER_0;
    c.pin_d0=Y2_GPIO_NUM; c.pin_d1=Y3_GPIO_NUM; c.pin_d2=Y4_GPIO_NUM; c.pin_d3=Y5_GPIO_NUM;
    c.pin_d4=Y6_GPIO_NUM; c.pin_d5=Y7_GPIO_NUM; c.pin_d6=Y8_GPIO_NUM; c.pin_d7=Y9_GPIO_NUM;
    c.pin_xclk=XCLK_GPIO_NUM; c.pin_pclk=PCLK_GPIO_NUM;
    c.pin_vsync=VSYNC_GPIO_NUM; c.pin_href=HREF_GPIO_NUM;
    c.pin_sccb_sda=SIOD_GPIO_NUM; c.pin_sccb_scl=SIOC_GPIO_NUM;
    c.pin_pwdn=PWDN_GPIO_NUM; c.pin_reset=RESET_GPIO_NUM;
    c.xclk_freq_hz=20000000; c.pixel_format=PIXFORMAT_JPEG;
    c.frame_size=FRAMESIZE_QVGA; c.jpeg_quality=12;
    c.fb_count=2; c.grab_mode=CAMERA_GRAB_LATEST;
    return esp_camera_init(&c) == ESP_OK;
}

// ═══ HTTP ═══
#define PART_BOUNDARY "123456789000000000000987654321"
static const char* S_CT = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* S_BD = "\r\n--" PART_BOUNDARY "\r\n";
static const char* S_PT = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

esp_err_t stream_handler(httpd_req_t* r) {
    esp_err_t s = httpd_resp_set_type(r, S_CT);
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
    while (true) {
        camera_fb_t* f = esp_camera_fb_get();
        if (!f) { s = ESP_FAIL; break; }
        char p[64]; size_t h = snprintf(p, 64, S_PT, f->len);
        s = httpd_resp_send_chunk(r, S_BD, strlen(S_BD));
        if (s == ESP_OK) s = httpd_resp_send_chunk(r, p, h);
        if (s == ESP_OK) s = httpd_resp_send_chunk(r, (const char*)f->buf, f->len);
        esp_camera_fb_return(f);
        if (s != ESP_OK) break;
    }
    return s;
}

esp_err_t capture_handler(httpd_req_t* r) {
    camera_fb_t* f = esp_camera_fb_get();
    if (!f) { httpd_resp_send_500(r); return ESP_FAIL; }
    httpd_resp_set_type(r, "image/jpeg");
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(r, (const char*)f->buf, f->len);
    esp_camera_fb_return(f); return ESP_OK;
}

esp_err_t status_handler(httpd_req_t* r) {
    char b[256];
    snprintf(b, sizeof(b),
        "{\"pan\":%d,\"tilt\":%.1f,\"tiltTarget\":%.1f,\"tiltPwm\":%d,\"heap\":%d,\"rssi\":%d}",
        currentPan, virtualTiltAngle, tiltTargetAngle, currentTiltPwm,
        ESP.getFreeHeap(), WiFi.RSSI());
    httpd_resp_set_type(r, "application/json");
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(r, b, strlen(b)); return ESP_OK;
}

void startHttpServer() {
    httpd_config_t c = HTTPD_DEFAULT_CONFIG(); c.server_port = HTTP_PORT;
    if (httpd_start(&httpd, &c) == ESP_OK) {
        httpd_uri_t u1 = {"/stream", HTTP_GET, stream_handler, NULL};
        httpd_uri_t u2 = {"/capture", HTTP_GET, capture_handler, NULL};
        httpd_uri_t u3 = {"/status", HTTP_GET, status_handler, NULL};
        httpd_register_uri_handler(httpd, &u1);
        httpd_register_uri_handler(httpd, &u2);
        httpd_register_uri_handler(httpd, &u3);
    }
}

// ═══ Commands ═══
void parseCommand(const char* cmd) {
    int pan = 0, tilt = 0; bool hp = false, ht = false; char* ptr;
    if ((ptr = strstr(cmd, "PAN:")) != NULL) { pan = atoi(ptr + 4); hp = true; }
    if ((ptr = strstr(cmd, "TILT:")) != NULL) { tilt = atoi(ptr + 5); ht = true; }
    if (hp) { pan = constrain(pan, -90, 90); targetPan = map(pan, -90, 90, 180, 0); panDirty = true; }
    if (ht) {
        tilt = constrain(tilt, -90, 90);
        tiltTargetAngle = constrain((float)map(tilt, -90, 90, 0, 180), TILT_MIN_ANGLE, TILT_MAX_ANGLE);
    }
    lastCmdTime = millis();
}

// ═══ Servo Update — ~100Hz ═══
void updateServos() {
    // PAN — обычное позиционное серво
    if (panDirty) { servoPan.write(targetPan); currentPan = targetPan; panDirty = false; }

    // TILT — PID регулятор для CR серво с виртуальным углом
    unsigned long now = millis();
    float dt = (now - lastTiltLoopMs) / 1000.0f;
    lastTiltLoopMs = now;
    if (dt <= 0 || dt > 0.5f) dt = 0.01f;

    float error = tiltTargetAngle - virtualTiltAngle;

    if (fabsf(error) < TILT_DEADBAND) {
        // Достигли цели — стоп
        if (currentTiltPwm != TILT_NEUTRAL) {
            servoTilt.write(TILT_NEUTRAL);
            currentTiltPwm = TILT_NEUTRAL;
        }
    } else {
        // P-регулятор: скорость ∝ ошибке
        float speed = constrain(error * TILT_KP, -(float)TILT_MAX_SPEED, (float)TILT_MAX_SPEED);
        // Мин скорость чтобы серво реально крутилось
        if (speed > 0 && speed < 8) speed = 8;
        if (speed < 0 && speed > -8) speed = -8;

        int pwm = constrain(TILT_NEUTRAL + (int)speed, 0, 180);
        servoTilt.write(pwm);
        currentTiltPwm = pwm;

        // Обновляем оценку позиции (open-loop интеграция)
        float actualDegPerSec = (float)(pwm - TILT_NEUTRAL) / (float)TILT_MAX_SPEED * TILT_DEG_PER_SEC;
        virtualTiltAngle += actualDegPerSec * dt;
        virtualTiltAngle = constrain(virtualTiltAngle, 0.0f, 180.0f);
    }

    // Watchdog: нет команд 2с → зафиксировать позицию
    if (lastCmdTime > 0 && (now - lastCmdTime) > 2000) {
        tiltTargetAngle = virtualTiltAngle;
    }
}

// ═══ WiFi ═══
void connectWiFi() {
    Serial.printf("Connecting to %s...\n", AP_SSID);
    WiFi.mode(WIFI_STA); WiFi.config(STATIC_IP, GATEWAY, SUBNET);
    WiFi.begin(AP_SSID, AP_PASS);
    int a = 0; while (WiFi.status() != WL_CONNECTED && a < 30) { delay(500); a++; }
    wifiConnected = (WiFi.status() == WL_CONNECTED);
    if (wifiConnected) Serial.printf("Connected: %s\n", WiFi.localIP().toString().c_str());
    else Serial.println("WiFi FAILED");
}

void checkWiFi() {
    if (WiFi.status() != WL_CONNECTED) {
        if (wifiConnected) wifiConnected = false;
        if (millis() - lastReconnectAttempt > RECONNECT_INTERVAL_MS) {
            lastReconnectAttempt = millis(); WiFi.reconnect();
        }
    } else if (!wifiConnected) wifiConnected = true;
}

// ═══ UDP Task (Core 0) ═══
void udpTask(void* p) {
    while (true) {
        if (wifiConnected) {
            int s = udp.parsePacket();
            if (s > 0) { char b[128]; int l = udp.read(b, 127); b[l] = 0; parseCommand(b); }
        }
        vTaskDelay(5 / portTICK_PERIOD_MS);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.println("\n=== Turret v2.3 CR virtual angle ===");
    ESP32PWM::allocateTimer(1); ESP32PWM::allocateTimer(2);
    servoPan.setPeriodHertz(50); servoPan.attach(SERVO_PAN, 500, 2400); servoPan.write(90);
    servoTilt.setPeriodHertz(50); servoTilt.attach(SERVO_TILT, 500, 2400); servoTilt.write(TILT_NEUTRAL);
    lastTiltLoopMs = millis();
    Serial.printf("PAN=positional TILT=CR(neutral=%d Kp=%.1f deg/s=%.0f)\n", TILT_NEUTRAL, TILT_KP, TILT_DEG_PER_SEC);
    if (!setupCamera()) Serial.println("Camera FAIL"); else Serial.println("Camera OK");
    connectWiFi();
    udp.begin(UDP_PORT);
    startHttpServer();
    xTaskCreatePinnedToCore(udpTask, "udp", 4096, NULL, 1, NULL, 0);
    Serial.println("Ready!");
}

void loop() { checkWiFi(); updateServos(); delay(10); }
