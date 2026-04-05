/**
 * turret_client.ino v2.7.1
 *
 * Changes from v2.7:
 *   FIX: VCAL now forces servo to neutral (prevents race condition jitter)
 *   FIX: TCAL no longer integrates virtualAngle during sweep (was circular!)
 *        Motor just runs 2s raw, app asks user for actual degrees → computes dps
 *   ADD: /status includes tcalDone, tcalElapsedMs, tcalStartAngle for app sync
 *
 * Kept from v2.6:
 *   - CR tilt with virtual angle + NVS persistence
 *   - TSET/TSAVE/TCAL/VCAL commands
 *   - UDP control on Core 0
 *   - Servo update ~100Hz on Core 1 (loop)
 *
 * Physical convention (confirmed):
 *   0°   = camera pointing UP
 *   180° = camera pointing DOWN
 *   angle increasing = camera tilts DOWN = with gravity = FASTER
 *   angle decreasing = camera tilts UP   = against gravity = SLOWER
 */
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Update.h>
#include <ESP32Servo.h>
#include <Preferences.h>
#include "esp_camera.h"
#include "esp_http_server.h"

const char* AP_SSID = "RoverAP";
const char* AP_PASS = "rover12345";
const IPAddress STATIC_IP(192, 168, 4, 2), GATEWAY(192, 168, 4, 1), SUBNET(255, 255, 255, 0);

#define SERVO_PAN  2
#define SERVO_TILT 4

// Camera pins (XIAO ESP32S3 Sense)
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM  10
#define SIOD_GPIO_NUM  40
#define SIOC_GPIO_NUM  39
#define Y9_GPIO_NUM    48
#define Y8_GPIO_NUM    11
#define Y7_GPIO_NUM    12
#define Y6_GPIO_NUM    14
#define Y5_GPIO_NUM    16
#define Y4_GPIO_NUM    18
#define Y3_GPIO_NUM    17
#define Y2_GPIO_NUM    15
#define VSYNC_GPIO_NUM 38
#define HREF_GPIO_NUM  47
#define PCLK_GPIO_NUM  13

#define UDP_PORT              4210
#define HTTP_PORT             81
#define RECONNECT_INTERVAL_MS 5000

// ═══ CR Tilt defaults (overridden by NVS if saved) ═══
#define DEF_TILT_NEUTRAL      90
#define DEF_TILT_DEADBAND     5.0f
#define DEF_TILT_MAX_SPEED    60
#define DEF_TILT_DPS_CAM_UP   70.0f   // camera UP (angle→0°, against gravity) = slower
#define DEF_TILT_DPS_CAM_DN   90.0f   // camera DOWN (angle→180°, with gravity) = faster
#define DEF_TILT_DRIFT_CORR   30.0f
#define TILT_MIN_ANGLE        10.0f
#define TILT_MAX_ANGLE        170.0f

// ═══ Runtime tilt config (loaded from NVS or defaults) ═══
struct TiltConfig {
    int   neutral;
    float deadband;
    int   maxSpeed;
    float dpsCamUp;    // °/s when camera moves UP   (angle decreasing)
    float dpsCamDn;    // °/s when camera moves DOWN  (angle increasing)
    float driftCorr;
} tiltCfg;

Preferences prefs;

WiFiUDP udp;
Servo servoPan, servoTilt;
httpd_handle_t httpd = NULL;

int currentPan = 90;
volatile bool panDirty = false;
volatile int targetPan = 90;

float virtualTiltAngle = 90.0f;
volatile float tiltTargetAngle = 90.0f;
int currentTiltPwm = 90;
unsigned long lastTiltLoopMs = 0;

bool wifiConnected = false;
unsigned long lastReconnectAttempt = 0, lastCmdTime = 0;

// ═══ TCAL test sweep state ═══
bool  tcalActive = false;
bool  tcalDone = false;          // true after sweep completes, until next TCAL
int   tcalDirection = 0;
float tcalStartAngle = 0.0f;
unsigned long tcalStartMs = 0;
unsigned long tcalDurationMs = 2000;
unsigned long tcalElapsedMs = 0; // actual elapsed time of last sweep

// ═══ NVS ═══
void loadTiltConfig() {
    prefs.begin("tilt", true); // read-only
    tiltCfg.neutral   = prefs.getInt("neutral",   DEF_TILT_NEUTRAL);
    tiltCfg.deadband  = prefs.getFloat("deadband", DEF_TILT_DEADBAND);
    tiltCfg.maxSpeed  = prefs.getInt("maxSpeed",   DEF_TILT_MAX_SPEED);
    tiltCfg.dpsCamUp  = prefs.getFloat("dpsUp",    DEF_TILT_DPS_CAM_UP);
    tiltCfg.dpsCamDn  = prefs.getFloat("dpsDn",    DEF_TILT_DPS_CAM_DN);
    tiltCfg.driftCorr = prefs.getFloat("drift",    DEF_TILT_DRIFT_CORR);
    prefs.end();
    Serial.printf("Tilt config: N=%d DB=%.1f S=%d UP=%.0f DN=%.0f DC=%.0f\n",
        tiltCfg.neutral, tiltCfg.deadband, tiltCfg.maxSpeed,
        tiltCfg.dpsCamUp, tiltCfg.dpsCamDn, tiltCfg.driftCorr);
}

void saveTiltConfig() {
    prefs.begin("tilt", false); // read-write
    prefs.putInt("neutral",     tiltCfg.neutral);
    prefs.putFloat("deadband",  tiltCfg.deadband);
    prefs.putInt("maxSpeed",    tiltCfg.maxSpeed);
    prefs.putFloat("dpsUp",     tiltCfg.dpsCamUp);
    prefs.putFloat("dpsDn",     tiltCfg.dpsCamDn);
    prefs.putFloat("drift",     tiltCfg.driftCorr);
    prefs.end();
    Serial.println("Tilt config saved to NVS");
}

// ═══ Camera ═══
bool setupCamera() {
    camera_config_t c;
    c.ledc_channel = LEDC_CHANNEL_0; c.ledc_timer = LEDC_TIMER_0;
    c.pin_d0 = Y2_GPIO_NUM; c.pin_d1 = Y3_GPIO_NUM; c.pin_d2 = Y4_GPIO_NUM; c.pin_d3 = Y5_GPIO_NUM;
    c.pin_d4 = Y6_GPIO_NUM; c.pin_d5 = Y7_GPIO_NUM; c.pin_d6 = Y8_GPIO_NUM; c.pin_d7 = Y9_GPIO_NUM;
    c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM;
    c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
    c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
    c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
    c.xclk_freq_hz = 20000000;
    c.pixel_format = PIXFORMAT_JPEG;
    // ── v2.7: HVGA 480×320, quality 10 (was VGA 640×480, quality 6) ──
    // HVGA is enough for YOLO (resized to 640×640 anyway)
    // quality 10 is visually fine, ~2× smaller frames → faster WiFi transfer
    c.frame_size   = FRAMESIZE_HVGA;   // 480×320
    c.jpeg_quality = 10;               // 0-63, lower = better quality
    c.fb_count     = 2;
    c.grab_mode    = CAMERA_GRAB_LATEST;
    return esp_camera_init(&c) == ESP_OK;
}

// ═══ HTTP handlers (all on port 81, async esp_httpd) ═══

#define PART_BOUNDARY "123456789000000000000987654321"
static const char* S_CT = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* S_BD = "\r\n--" PART_BOUNDARY "\r\n";
static const char* S_PT = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

// ── /stream — MJPEG ──
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

// ── /capture — single JPEG ──
esp_err_t capture_handler(httpd_req_t* r) {
    camera_fb_t* f = esp_camera_fb_get();
    if (!f) { httpd_resp_send_500(r); return ESP_FAIL; }
    httpd_resp_set_type(r, "image/jpeg");
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(r, (const char*)f->buf, f->len);
    esp_camera_fb_return(f);
    return ESP_OK;
}

// ── /status — JSON telemetry ──
esp_err_t status_handler(httpd_req_t* r) {
    char b[640];
    snprintf(b, sizeof(b),
        "{\"pan\":%d,\"tilt\":%.1f,\"tiltTarget\":%.1f,\"tiltPwm\":%d,"
        "\"neutral\":%d,\"maxSpeed\":%d,\"deadband\":%.1f,"
        "\"dpsUp\":%.0f,\"dpsDn\":%.0f,\"driftCorr\":%.0f,"
        "\"tcalActive\":%s,\"tcalDone\":%s,\"tcalElapsedMs\":%lu,\"tcalStartAngle\":%.1f,"
        "\"heap\":%lu,\"rssi\":%d}",
        currentPan, virtualTiltAngle, tiltTargetAngle, currentTiltPwm,
        tiltCfg.neutral, tiltCfg.maxSpeed, tiltCfg.deadband,
        tiltCfg.dpsCamUp, tiltCfg.dpsCamDn, tiltCfg.driftCorr,
        tcalActive ? "true" : "false",
        tcalDone ? "true" : "false",
        (unsigned long)tcalElapsedMs,
        tcalStartAngle,
        (unsigned long)ESP.getFreeHeap(), WiFi.RSSI());
    httpd_resp_set_type(r, "application/json");
    httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(r, b, strlen(b));
    return ESP_OK;
}

// ── /update GET — HTML upload form ──
static const char OTA_HTML[] PROGMEM = R"rawhtml(
<!DOCTYPE html><html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Turret OTA</title>
<style>
body{font-family:monospace;margin:2em;background:#111;color:#0f0}
button{background:#0f0;color:#111;border:none;padding:8px 24px;cursor:pointer;font-size:16px}
#bar{width:100%;height:20px;background:#333;margin:8px 0;display:none}
#fill{height:100%;width:0%;background:#0f0;transition:width 0.2s}
</style></head><body>
<h2>Turret OTA v2.7.1</h2>
<input type='file' id='fw' accept='.bin'>
<button onclick='upload()'>Flash</button>
<div id='bar'><div id='fill'></div></div>
<pre id='log'></pre>
<script>
async function upload(){
  const f=document.getElementById('fw').files[0];
  if(!f)return;
  const log=document.getElementById('log');
  const bar=document.getElementById('bar');
  const fill=document.getElementById('fill');
  bar.style.display='block';
  log.textContent='Uploading '+f.name+' ('+f.size+' bytes)...\n';
  try{
    const xhr=new XMLHttpRequest();
    xhr.open('POST','/update');
    xhr.setRequestHeader('Content-Type','application/octet-stream');
    xhr.upload.onprogress=e=>{
      if(e.lengthComputable){
        const pct=Math.round(e.loaded/e.total*100);
        fill.style.width=pct+'%';
        log.textContent='Uploading... '+pct+'%\n';
      }
    };
    xhr.onload=()=>{
      log.textContent+=xhr.responseText+'\n';
      if(xhr.status===200)log.textContent+='Rebooting in 1s...';
    };
    xhr.onerror=()=>log.textContent+='Upload failed!\n';
    xhr.send(f);
  }catch(e){log.textContent+='Error: '+e+'\n';}
}
</script></body></html>
)rawhtml";

esp_err_t update_get_handler(httpd_req_t* r) {
    httpd_resp_set_type(r, "text/html");
    httpd_resp_send(r, OTA_HTML, strlen(OTA_HTML));
    return ESP_OK;
}

// ── /update POST — receive raw firmware binary ──
esp_err_t update_post_handler(httpd_req_t* r) {
    int remaining = r->content_len;
    if (remaining <= 0) {
        httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, "No content");
        return ESP_FAIL;
    }

    Serial.printf("OTA: receiving %d bytes\n", remaining);

    if (!Update.begin(remaining)) {
        Update.printError(Serial);
        httpd_resp_send_err(r, HTTPD_500_INTERNAL_SERVER_ERROR, "Update.begin failed");
        return ESP_FAIL;
    }

    char buf[1024];
    int received = 0;
    while (remaining > 0) {
        int toRead = remaining < (int)sizeof(buf) ? remaining : (int)sizeof(buf);
        int len = httpd_req_recv(r, buf, toRead);
        if (len <= 0) {
            if (len == HTTPD_SOCK_ERR_TIMEOUT) continue;  // retry on timeout
            Update.abort();
            Serial.println("OTA: receive error");
            httpd_resp_send_err(r, HTTPD_500_INTERNAL_SERVER_ERROR, "Receive failed");
            return ESP_FAIL;
        }
        if (Update.write((uint8_t*)buf, len) != (size_t)len) {
            Update.abort();
            Update.printError(Serial);
            httpd_resp_send_err(r, HTTPD_500_INTERNAL_SERVER_ERROR, "Write failed");
            return ESP_FAIL;
        }
        remaining -= len;
        received += len;
        if (received % 32768 == 0) {
            Serial.printf("OTA: %d / %d\n", received, r->content_len);
        }
    }

    if (Update.end(true)) {
        Serial.printf("OTA OK: %d bytes\n", received);
        httpd_resp_sendstr(r, "OK — Rebooting...");
        delay(1000);
        ESP.restart();
        return ESP_OK;  // unreachable
    }

    Update.printError(Serial);
    httpd_resp_send_err(r, HTTPD_500_INTERNAL_SERVER_ERROR, "Update.end failed");
    return ESP_FAIL;
}

// ── / — root info page ──
esp_err_t root_handler(httpd_req_t* r) {
    char b[256];
    snprintf(b, sizeof(b),
        "XIAO Turret v2.7.1\n"
        "Heap: %lu\n"
        "Endpoints: /stream /capture /status /update\n",
        (unsigned long)ESP.getFreeHeap());
    httpd_resp_set_type(r, "text/plain");
    httpd_resp_send(r, b, strlen(b));
    return ESP_OK;
}

// ── Start HTTP server ──
void startHttpServer() {
    httpd_config_t c = HTTPD_DEFAULT_CONFIG();
    c.server_port   = HTTP_PORT;
    c.max_uri_handlers = 8;
    c.stack_size    = 8192;   // extra stack for OTA flash writes
    c.recv_wait_timeout  = 30; // 30s timeout for OTA uploads
    c.send_wait_timeout  = 10;

    if (httpd_start(&httpd, &c) == ESP_OK) {
        httpd_uri_t u_root    = { "/",        HTTP_GET,  root_handler,        NULL };
        httpd_uri_t u_stream  = { "/stream",  HTTP_GET,  stream_handler,      NULL };
        httpd_uri_t u_capture = { "/capture", HTTP_GET,  capture_handler,     NULL };
        httpd_uri_t u_status  = { "/status",  HTTP_GET,  status_handler,      NULL };
        httpd_uri_t u_ota_get = { "/update",  HTTP_GET,  update_get_handler,  NULL };
        httpd_uri_t u_ota_post= { "/update",  HTTP_POST, update_post_handler, NULL };
        httpd_register_uri_handler(httpd, &u_root);
        httpd_register_uri_handler(httpd, &u_stream);
        httpd_register_uri_handler(httpd, &u_capture);
        httpd_register_uri_handler(httpd, &u_status);
        httpd_register_uri_handler(httpd, &u_ota_get);
        httpd_register_uri_handler(httpd, &u_ota_post);
        Serial.printf("HTTP server on port %d (6 handlers)\n", HTTP_PORT);
    } else {
        Serial.println("HTTP server FAILED");
    }
}

// ═══ Commands (UDP) ═══
void parseCommand(const char* cmd) {
    int pan = 0, tilt = 0;
    bool hp = false, ht = false;
    char* ptr;

    if ((ptr = strstr(cmd, "PAN:")) != NULL)  { pan = atoi(ptr + 4); hp = true; }
    if ((ptr = strstr(cmd, "TILT:")) != NULL)  { tilt = atoi(ptr + 5); ht = true; }

    // VCAL:<angle> — set virtual angle reference (calibration)
    // Sets BOTH virtual and target to same value → error=0 → no movement
    // Also forces servo to neutral to prevent any race conditions
    if ((ptr = strstr(cmd, "VCAL:")) != NULL) {
        float cal = atof(ptr + 5);
        cal = constrain(cal, 0.0f, 180.0f);
        virtualTiltAngle = cal;
        tiltTargetAngle = cal;
        servoTilt.write(tiltCfg.neutral);  // force stop
        currentTiltPwm = tiltCfg.neutral;
        Serial.printf("VCAL: virtual=%.1f (servo stopped)\n", cal);
    }

    // TSET:N:<neutral>;S:<maxSpeed>;U:<dpsUp>;D:<dpsDn>;DB:<deadband>;DC:<driftCorr>
    // All fields optional — only updates what's present
    if (strstr(cmd, "TSET:") != NULL) {
        if ((ptr = strstr(cmd, "N:")) != NULL)  tiltCfg.neutral   = constrain(atoi(ptr + 2), 70, 110);
        if ((ptr = strstr(cmd, "S:")) != NULL)  tiltCfg.maxSpeed  = constrain(atoi(ptr + 2), 10, 90);
        if ((ptr = strstr(cmd, "U:")) != NULL)  tiltCfg.dpsCamUp  = constrain(atof(ptr + 2), 10.0f, 200.0f);
        if ((ptr = strstr(cmd, "D:")) != NULL)  tiltCfg.dpsCamDn  = constrain(atof(ptr + 2), 10.0f, 200.0f);
        if ((ptr = strstr(cmd, "DB:")) != NULL) tiltCfg.deadband  = constrain(atof(ptr + 3), 1.0f, 20.0f);
        if ((ptr = strstr(cmd, "DC:")) != NULL) tiltCfg.driftCorr = constrain(atof(ptr + 3), 0.0f, 100.0f);
        Serial.printf("TSET: N=%d S=%d U=%.0f D=%.0f DB=%.1f DC=%.0f\n",
            tiltCfg.neutral, tiltCfg.maxSpeed, tiltCfg.dpsCamUp, tiltCfg.dpsCamDn,
            tiltCfg.deadband, tiltCfg.driftCorr);
    }

    // TSAVE — persist to NVS
    if (strstr(cmd, "TSAVE") != NULL) {
        saveTiltConfig();
    }

    // TCAL:UP / TCAL:DN / TCAL:STOP — timed test sweep for speed calibration
    // During sweep: servo runs at maxSpeed, virtual angle is NOT updated (raw motor test)
    // After sweep: app asks user for actual degrees → computes real dps
    if ((ptr = strstr(cmd, "TCAL:")) != NULL) {
        char dir[8];
        sscanf(ptr + 5, "%7s", dir);
        if (strcmp(dir, "UP") == 0) {
            tcalActive = true;
            tcalDone = false;
            tcalDirection = -1;  // angle decreasing = camera up
            tcalStartAngle = virtualTiltAngle;
            tcalStartMs = millis();
            Serial.printf("TCAL UP: start=%.1f\n", tcalStartAngle);
        } else if (strcmp(dir, "DN") == 0) {
            tcalActive = true;
            tcalDone = false;
            tcalDirection = +1;  // angle increasing = camera down
            tcalStartAngle = virtualTiltAngle;
            tcalStartMs = millis();
            Serial.printf("TCAL DN: start=%.1f\n", tcalStartAngle);
        } else if (strcmp(dir, "STOP") == 0) {
            tcalActive = false;
            servoTilt.write(tiltCfg.neutral);
            currentTiltPwm = tiltCfg.neutral;
            tiltTargetAngle = virtualTiltAngle;
            Serial.println("TCAL STOP");
        }
    }

    if (hp) {
        pan = constrain(pan, -90, 90);
        targetPan = map(pan, -90, 90, 180, 0);  // inverted
        panDirty = true;
    }
    if (ht) {
        tilt = constrain(tilt, -90, 90);
        tiltTargetAngle = constrain((float)map(tilt, -90, 90, 0, 180), TILT_MIN_ANGLE, TILT_MAX_ANGLE);
    }
    lastCmdTime = millis();
}

// ═══ Servo Update — ~100Hz ═══
void updateServos() {
    // PAN — standard positional servo
    if (panDirty) {
        servoPan.write(targetPan);
        currentPan = targetPan;
        panDirty = false;
    }

    // Time delta
    unsigned long now = millis();
    float dt = (now - lastTiltLoopMs) / 1000.0f;
    lastTiltLoopMs = now;
    if (dt <= 0 || dt > 0.5f) dt = 0.01f;

    // ── TCAL test sweep mode ────────────────────────────────────────
    // Raw motor test: servo runs at maxSpeed, NO virtual angle integration
    // This lets us measure actual physical movement independently
    if (tcalActive) {
        if (now - tcalStartMs >= tcalDurationMs) {
            // Test done — stop servo
            servoTilt.write(tiltCfg.neutral);
            currentTiltPwm = tiltCfg.neutral;
            tcalActive = false;
            tcalDone = true;
            tcalElapsedMs = now - tcalStartMs;
            // Do NOT touch virtualTiltAngle — it stays at startAngle
            // App will ask user for actual degrees and sync via VCAL
            tiltTargetAngle = virtualTiltAngle;  // prevent drift after stop
            Serial.printf("TCAL done: %lums (virtual unchanged at %.1f°)\n",
                (unsigned long)tcalElapsedMs, virtualTiltAngle);
        } else {
            // Move at constant speed in requested direction (no integration!)
            int speed = tcalDirection * tiltCfg.maxSpeed;
            int pwm = constrain(tiltCfg.neutral + speed, 0, 180);
            servoTilt.write(pwm);
            currentTiltPwm = pwm;
        }
        return;  // skip normal control during TCAL
    }

    // ── Normal tilt control ─────────────────────────────────────────
    float error = tiltTargetAngle - virtualTiltAngle;

    if (fabsf(error) < tiltCfg.deadband) {
        // Reached target — stop
        if (currentTiltPwm != tiltCfg.neutral) {
            servoTilt.write(tiltCfg.neutral);
            currentTiltPwm = tiltCfg.neutral;
        }
        // Drift correction: pull virtual toward target
        if (fabsf(error) > 0.1f) {
            float corr = (error > 0 ? 1.0f : -1.0f) * tiltCfg.driftCorr * dt;
            if (fabsf(corr) > fabsf(error)) corr = error;
            virtualTiltAngle += corr;
            virtualTiltAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
        }
    } else {
        // Constant speed toward target
        int speed = (error > 0) ? tiltCfg.maxSpeed : -tiltCfg.maxSpeed;
        int pwm = constrain(tiltCfg.neutral + speed, 0, 180);
        servoTilt.write(pwm);
        currentTiltPwm = pwm;

        // Open-loop integration with CORRECT speed mapping:
        //   speed > 0: angle INCREASING = camera moves DOWN = with gravity = FASTER (dpsCamDn)
        //   speed < 0: angle DECREASING = camera moves UP   = against gravity = SLOWER (dpsCamUp)
        float speedFrac = (float)speed / (float)tiltCfg.maxSpeed; // +1 or -1
        float dps = (speedFrac > 0) ? tiltCfg.dpsCamDn : tiltCfg.dpsCamUp;
        float actualDps = speedFrac * dps;
        virtualTiltAngle += actualDps * dt;
        virtualTiltAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
    }

    // Watchdog: no commands for 2s → freeze position
    if (lastCmdTime > 0 && (now - lastCmdTime) > 2000) {
        tiltTargetAngle = constrain(virtualTiltAngle, TILT_MIN_ANGLE, TILT_MAX_ANGLE);
    }
}

// ═══ WiFi ═══
void connectWiFi() {
    Serial.printf("Connecting to %s...\n", AP_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.config(STATIC_IP, GATEWAY, SUBNET);
    WiFi.begin(AP_SSID, AP_PASS);
    int a = 0;
    while (WiFi.status() != WL_CONNECTED && a < 30) { delay(500); a++; }
    wifiConnected = (WiFi.status() == WL_CONNECTED);
    if (wifiConnected) Serial.printf("Connected: %s\n", WiFi.localIP().toString().c_str());
    else Serial.println("WiFi FAILED");
}

void checkWiFi() {
    if (WiFi.status() != WL_CONNECTED) {
        if (wifiConnected) wifiConnected = false;
        if (millis() - lastReconnectAttempt > RECONNECT_INTERVAL_MS) {
            lastReconnectAttempt = millis();
            WiFi.reconnect();
        }
    } else if (!wifiConnected) {
        wifiConnected = true;
    }
}

// ═══ UDP Task (Core 0) ═══
void udpTask(void* p) {
    while (true) {
        if (wifiConnected) {
            int s = udp.parsePacket();
            if (s > 0) {
                char b[256];
                int l = udp.read(b, sizeof(b) - 1);
                b[l] = 0;
                parseCommand(b);
            }
        }
        vTaskDelay(5 / portTICK_PERIOD_MS);
    }
}

// ═══ Setup & Loop ═══
void setup() {
    Serial.begin(115200);
    Serial.println("\n=== Turret v2.7.1 — lean & fast ===");

    // Load tilt config from NVS (or defaults)
    loadTiltConfig();

    ESP32PWM::allocateTimer(1);
    ESP32PWM::allocateTimer(2);
    servoPan.setPeriodHertz(50);
    servoPan.attach(SERVO_PAN, 500, 2400);
    servoPan.write(90);
    servoTilt.setPeriodHertz(50);
    servoTilt.attach(SERVO_TILT, 500, 2400);
    servoTilt.write(tiltCfg.neutral);
    currentTiltPwm = tiltCfg.neutral;
    lastTiltLoopMs = millis();

    Serial.printf("PAN=positional TILT=CR(neutral=%d speed=%d camUp=%.0f camDn=%.0f)\n",
        tiltCfg.neutral, tiltCfg.maxSpeed, tiltCfg.dpsCamUp, tiltCfg.dpsCamDn);

    if (!setupCamera()) Serial.println("Camera FAIL");
    else Serial.println("Camera OK (HVGA 480x320 q10)");

    connectWiFi();
    udp.begin(UDP_PORT);
    startHttpServer();

    // v2.7: NO ArduinoOTA, NO Arduino WebServer
    // OTA available via POST /update on port 81 (same async httpd)
    // or browser: http://192.168.4.2:81/update

    xTaskCreatePinnedToCore(udpTask, "udp", 4096, NULL, 1, NULL, 0);
    Serial.printf("Heap: %lu bytes free\n", (unsigned long)ESP.getFreeHeap());
    Serial.println("Ready!");
}

void loop() {
    // v2.7: lean loop — no ArduinoOTA.handle(), no otaServer.handleClient()
    checkWiFi();
    updateServos();
    delay(10);
}
