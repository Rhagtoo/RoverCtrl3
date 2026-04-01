/**
 * turret_client.ino v2.5
 * + OTA прошивка: ArduinoOTA (Arduino IDE) + HTTP POST /update (Android app, port 80)
 * + Asymmetric tilt speeds: раздельные °/с вверх и вниз (гравитация)
 * + Drift correction: подтяжка virtual→target в deadband
 * + VCAL команда: прямая установка virtualTiltAngle из приложения
 * + JPEG quality 8 (было 12)
 * Virtual angle для CR tilt серво
 * PAN: позиционное. TILT: CR + позиционный контроллер с постоянной скоростью → ведёт себя как позиционное.
 * КАЛИБРОВКА: TILT_NEUTRAL (стоп), TILT_DEG_PER_SEC_UP/DOWN (скорость)
 */
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <Update.h>
#include <ArduinoOTA.h>
#include <ESP32Servo.h>
#include "esp_camera.h"
#include "esp_http_server.h"

const char* AP_SSID="RoverAP"; const char* AP_PASS="rover12345";
const IPAddress STATIC_IP(192,168,4,2),GATEWAY(192,168,4,1),SUBNET(255,255,255,0);
#define SERVO_PAN 2
#define SERVO_TILT 4
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
#define TILT_NEUTRAL         90      // PWM = стоп (подстрой 88-92)
#define TILT_DEADBAND        5.0f    // ±5° = на месте
// #define TILT_KP              1.2f    // P-gain (1.0-2.0) — не используется в позиционном режиме
#define TILT_MAX_SPEED       50      // макс отклонение от neutral
#define TILT_DEG_PER_SEC_UP  60.0f   // °/сек вверх (против гравитации — медленнее)
#define TILT_DEG_PER_SEC_DN  90.0f   // °/сек вниз  (по гравитации — быстрее)
#define TILT_MIN_ANGLE       10.0f
#define TILT_MAX_ANGLE       170.0f
// Drift correction: в deadband подтягиваем virtual к target (°/сек)
#define TILT_DRIFT_CORRECT   30.0f

WiFiUDP udp; Servo servoPan, servoTilt; httpd_handle_t httpd = NULL;
WebServer otaServer(80);
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
    c.frame_size=FRAMESIZE_VGA; c.jpeg_quality=6;
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
    char b[320];
    snprintf(b, sizeof(b),
        "{\"pan\":%d,\"tilt\":%.1f,\"tiltTarget\":%.1f,\"tiltPwm\":%d,"
        "\"dpsUp\":%.0f,\"dpsDn\":%.0f,\"heap\":%d,\"rssi\":%d}",
        currentPan, virtualTiltAngle, tiltTargetAngle, currentTiltPwm,
        TILT_DEG_PER_SEC_UP, TILT_DEG_PER_SEC_DN,
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

    // VCAL:<angle> — прямая установка виртуального угла (калибровка из приложения)
    if ((ptr = strstr(cmd, "VCAL:")) != NULL) {
        float cal = atof(ptr + 5);
        cal = constrain(cal, 0.0f, 180.0f);
        virtualTiltAngle = cal;
        tiltTargetAngle = cal;
        Serial.printf("VCAL: virtual=%.1f\n", cal);
    }

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

    // TILT — позиционный контроллер с постоянной скоростью (как PAN)
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
        // Drift correction: подтягиваем virtual к target.
        if (fabsf(error) > 0.1f) {
            float corr = (error > 0 ? 1.0f : -1.0f) * TILT_DRIFT_CORRECT * dt;
            if (fabsf(corr) > fabsf(error)) corr = error;
            virtualTiltAngle += corr;
            virtualTiltAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
        }
    } else {
        // Определяем направление и задаём постоянную скорость
        int speed = (error > 0) ? TILT_MAX_SPEED : -TILT_MAX_SPEED;
        int pwm = constrain(TILT_NEUTRAL + speed, 0, 180);
        servoTilt.write(pwm);
        currentTiltPwm = pwm;

        // Обновляем оценку позиции (open-loop интеграция).
        // Раздельные скорости: вверх (angle растёт) против гравитации — медленнее,
        // вниз (angle падает) по гравитации — быстрее.
        float speedFrac = (float)speed / (float)TILT_MAX_SPEED; // +1 или -1
        float degPerSec = (speedFrac > 0) ? TILT_DEG_PER_SEC_UP : TILT_DEG_PER_SEC_DN;
        float actualDegPerSec = speedFrac * degPerSec;
        virtualTiltAngle += actualDegPerSec * dt;
        virtualTiltAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
    }

    // Watchdog: нет команд 2с → зафиксировать позицию
    if (lastCmdTime > 0 && (now - lastCmdTime) > 2000) {
        tiltTargetAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
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
    Serial.println("\n=== Turret v2.5 CR asymmetric tilt + VCAL ===");
    ESP32PWM::allocateTimer(1); ESP32PWM::allocateTimer(2);
    servoPan.setPeriodHertz(50); servoPan.attach(SERVO_PAN, 500, 2400); servoPan.write(90);
    servoTilt.setPeriodHertz(50); servoTilt.attach(SERVO_TILT, 500, 2400); servoTilt.write(TILT_NEUTRAL);
    lastTiltLoopMs = millis();
    Serial.printf("PAN=positional TILT=CR positional(neutral=%d maxSpeed=%d up=%.0f dn=%.0f)\n",
        TILT_NEUTRAL, TILT_MAX_SPEED, TILT_DEG_PER_SEC_UP, TILT_DEG_PER_SEC_DN);
    if (!setupCamera()) Serial.println("Camera FAIL"); else Serial.println("Camera OK");
    connectWiFi();
    udp.begin(UDP_PORT);
    startHttpServer();

    // ── ArduinoOTA (прошивка через Arduino IDE) ───────────────────────────
    ArduinoOTA.setHostname("turret-client");
    ArduinoOTA.onStart([]() { Serial.println("OTA Start"); });
    ArduinoOTA.onEnd([]()   { Serial.println("\nOTA End"); });
    ArduinoOTA.onProgress([](unsigned int done, unsigned int total) {
        Serial.printf("OTA %u%%\r", done * 100 / total);
    });
    ArduinoOTA.onError([](ota_error_t e) {
        Serial.printf("OTA Error[%u]\n", e);
    });
    ArduinoOTA.begin();

    // ── HTTP OTA /update на порту 80 (прошивка через Android-приложение) ──
    otaServer.on("/update", HTTP_POST,
        []() {  // onComplete
            otaServer.send(200, "text/plain", Update.hasError() ? "FAIL" : "OK");
            if (!Update.hasError()) { delay(500); ESP.restart(); }
        },
        []() {  // onUpload
            HTTPUpload& upload = otaServer.upload();
            if (upload.status == UPLOAD_FILE_START) {
                Serial.printf("HTTP OTA: %s\n", upload.filename.c_str());
                if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
                    Update.printError(Serial);
                }
            } else if (upload.status == UPLOAD_FILE_WRITE) {
                if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
                    Update.printError(Serial);
                }
            } else if (upload.status == UPLOAD_FILE_END) {
                if (Update.end(true)) {
                    Serial.printf("HTTP OTA OK: %u bytes\n", upload.totalSize);
                } else {
                    Update.printError(Serial);
                }
            }
        }
    );
    otaServer.begin();

    xTaskCreatePinnedToCore(udpTask, "udp", 4096, NULL, 1, NULL, 0);
    Serial.println("Ready!");
}

void loop() {
    checkWiFi();
    ArduinoOTA.handle();
    otaServer.handleClient();
    updateServos();
    delay(10);
}
