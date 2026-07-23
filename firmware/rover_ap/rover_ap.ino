// ============================================================
//  A4A_SPI.ino — ESP32-S3: WiFi AP + UDP приём + SPI master → Nano
//
//  v1.3: Добавлена поддержка HC-SR04: distance_cm из SPI-ответа → телеметрия.
//  v1.1: Одна SPI-транзакция на обмен (вместо двух). Ответ Nano
//        всегда на 1 фрейм позади (телеметрия от предыдущей команды) —
//        на 1 МГц это 128 мкс задержки, незаметно.
//
//  Подключение ESP32 → Nano:
//   GPIO15 (MOSI)  → D11 / ICSP-4
//   GPIO16 (MISO)  → D12 / ICSP-1
//   GPIO17 (SCK)   → D13 / ICSP-3
//   GPIO18 (SS)    → D10
//   GND            → ICSP-6
// ============================================================

#include <WiFi.h>
#include <WiFiUdp.h>
#include <Arduino.h>
#include <ESP32Encoder.h>
#include <SPI.h>

// ================== DEBUG SWITCHES ==================
static bool DBG_RX_SERIAL  = true;
static bool DBG_RPM_SERIAL = false;
static bool DBG_ACK_SERIAL = false;
static bool DBG_SPI        = true;

// ====== SPI ======
#define SPI_SS   18
#define SPI_MOSI 15
#define SPI_MISO 16
#define SPI_SCK  17
#define SPI_SPEED 1000000   // 1 МГц

// ====== Encoders ======
#define CLK_LEFT  35
#define DT_LEFT   36
#define CLK_RIGHT 37
#define DT_RIGHT  38

ESP32Encoder encoderLeft;
ESP32Encoder encoderRight;

const int PULSES_PER_REV = 220;
const unsigned long MEAS_PERIOD_MS = 200;

long lastCountLeft  = 0;
long lastCountRight = 0;
unsigned long lastTime = 0;
float rpmL = 0.0f, rpmR = 0.0f;

// ====== WiFi / UDP ======
const char* ssid     = "RoverAP";
const char* password = "Der1parol";

WiFiUDP udp;
unsigned int localUdpPort = 4210;
#define TELEM_PORT   4211
char incomingPacket[255];

IPAddress lastSenderIP;
uint16_t  lastSenderPort = 0;
bool      haveSender = false;

// Калибровка руля — симметрично относительно 90°
// Центр (str=0) = (LEFT+RIGHT)/2 должен быть 90°
const int SERVO_LEFT  = 123;  // было 128 → центр был (128+62)/2=95°
const int SERVO_RIGHT = 57;   // было 62  → теперь (123+57)/2=90°

// ====== Heartbeat ======
// Heartbeat теперь НЕ зависит от непрерывности UDP.
// Как только получена первая команда — heartbeat шлёт последнюю команду
// каждые HEARTBEAT_MS, пока ESP жив. Если ESP умирает/выключается —
// watchdog на Nano (500 мс) сам заглушит моторы.
// Это надёжнее, чем таймаут UDP — Wi-Fi может икать, телефон может
// на секунду замолчать, а ровер должен ехать плавно.
#define HEARTBEAT_MS 100

int lastSpd = 0, lastStr = 0, lastFwd = 0, lastLaser = 0;
unsigned long lastUdpRxTime     = 0;
unsigned long lastHeartbeatSent = 0;

// ================== Протокол SPI ==================

struct CmdFrame {
  uint8_t marker;      // 0xA5
  uint8_t pwm_left;
  uint8_t pwm_right;
  uint8_t servo_angle;
  uint8_t flags;       // bit0=laser, bit1=dir_L, bit2=dir_R, bit3=enable
  uint8_t rsvd1;
  uint8_t rsvd2;
  uint8_t crc;         // XOR bytes 0-6
};

struct RspFrame {
  uint8_t marker;      // 0xB5
  uint8_t pwm_l_echo;
  uint8_t pwm_r_echo;
  uint8_t servo_actual;
  uint8_t status;      // bit0=laser, bit1=wd_ok, bit2=fault
  uint8_t bat_raw;
  uint8_t distance_cm; // v1.3: HC-SR04 расстояние, 0-255 см (0 = нет данных)
  uint8_t crc;
};

// CRC-8 (полином 0x07) — должен совпадать с Nano
uint8_t crc8(const uint8_t* data, int len) {
  uint8_t crc = 0;
  while (len--) {
    crc ^= *data++;
    for (uint8_t i = 0; i < 8; i++) {
      crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : (crc << 1);
    }
  }
  return crc;
}

struct NanoTelem {
  uint8_t pwm_l_echo, pwm_r_echo;
  uint8_t servo_actual;
  uint8_t status;
  uint8_t bat_raw;
  uint8_t distance_cm;   // v1.3: HC-SR04
  bool valid;
} nanoTelem = {0};

// Одна транзакция: отправляем команду, одновременно читаем ответ Nano.
// Ответ содержит телеметрию от ПРЕДЫДУЩЕЙ команды (Nano заполняет spi_rsp
// после обработки очередного фрейма). Задержка в 1 фрейм (128 мкс) — незаметна.
bool spiExchange(int pwm_l, int pwm_r, int servo, bool laser, bool dir_l, bool dir_r, bool enable) {
  CmdFrame cmd;
  cmd.marker      = 0xA5;
  cmd.pwm_left    = (uint8_t)constrain(abs(pwm_l), 0, 255);
  cmd.pwm_right   = (uint8_t)constrain(abs(pwm_r), 0, 255);
  cmd.servo_angle = (uint8_t)constrain(servo, 0, 180);
  cmd.flags       = (laser   ? 0x01 : 0x00)
                  | (dir_l   ? 0x02 : 0x00)
                  | (dir_r   ? 0x04 : 0x00)
                  | (enable  ? 0x08 : 0x00);
  cmd.rsvd1 = 0;
  cmd.rsvd2 = 0;
  cmd.crc   = crc8((uint8_t*)&cmd, 7);

  RspFrame rsp;
  uint8_t *cmdPtr = (uint8_t*)&cmd;
  uint8_t *rspPtr = (uint8_t*)&rsp;

  SPI.beginTransaction(SPISettings(SPI_SPEED, MSBFIRST, SPI_MODE0));
  digitalWrite(SPI_SS, LOW);

  for (int i = 0; i < 8; i++) {
    rspPtr[i] = SPI.transfer(cmdPtr[i]);
  }

  digitalWrite(SPI_SS, HIGH);
  SPI.endTransaction();

  // Валидация ответа (v1.2: диагностика ошибок)
  static int spiErrCount = 0;
  if (rsp.marker != 0xB5) {
    spiErrCount++;
    if (spiErrCount <= 3 || spiErrCount % 50 == 0) {
      Serial.printf("[SPI ERR] bad marker: 0x%02X errs=%d\n", rsp.marker, spiErrCount);
    }
    return false;
  }
  uint8_t calcCrc = crc8(rspPtr, 7);
  if (rsp.crc != calcCrc) {
    spiErrCount++;
    if (spiErrCount <= 3 || spiErrCount % 50 == 0) {
      Serial.printf("[SPI ERR] bad CRC: got=0x%02X calc=0x%02X errs=%d\n", rsp.crc, calcCrc, spiErrCount);
    }
    return false;
  }
  spiErrCount = 0;

  nanoTelem.pwm_l_echo   = rsp.pwm_l_echo;
  nanoTelem.pwm_r_echo   = rsp.pwm_r_echo;
  nanoTelem.servo_actual = rsp.servo_actual;
  nanoTelem.status       = rsp.status;
  nanoTelem.bat_raw      = rsp.bat_raw;
  nanoTelem.distance_cm   = rsp.distance_cm;   // v1.3
  nanoTelem.valid        = true;

  if (DBG_SPI) {
    Serial.printf("[SPI] CMD: pwm=%d,%d sv=%d fl=0x%02X | "
                  "RSP: mk=0x%02X echo=%d,%d sv=%d st=0x%02X bat=%d dist=%d\n",
                  pwm_l, pwm_r, servo, cmd.flags,
                  rsp.marker, rsp.pwm_l_echo, rsp.pwm_r_echo,
                  rsp.servo_actual, rsp.status, rsp.bat_raw, rsp.distance_cm);
  }

  return true;
}

// ================== Вспомогательные функции ==================

void sendUdpReply(const char* payload) {
  if (!haveSender || lastSenderPort == 0) return;
  udp.beginPacket(lastSenderIP, lastSenderPort);
  udp.write((const uint8_t*)payload, strlen(payload));
  udp.endPacket();
  if (DBG_ACK_SERIAL) {
    Serial.printf("[TX->%s:%u] %s\n", lastSenderIP.toString().c_str(), lastSenderPort, payload);
  }
}

// ================== SETUP ==================

void setup() {
  Serial.begin(115200);
  delay(300);

  // SPI master
  pinMode(SPI_SS, OUTPUT);
  digitalWrite(SPI_SS, HIGH);
  SPI.begin(SPI_SCK, SPI_MISO, SPI_MOSI, SPI_SS);
  Serial.printf("SPI master: SCK=%d MISO=%d MOSI=%d SS=%d @ %d Hz\n",
                SPI_SCK, SPI_MISO, SPI_MOSI, SPI_SS, SPI_SPEED);

  // Encoders
  encoderLeft.attachHalfQuad(DT_LEFT, CLK_LEFT);
  encoderLeft.setCount(0);
  encoderRight.attachHalfQuad(DT_RIGHT, CLK_RIGHT);
  encoderRight.setCount(0);
  lastTime = millis();

  // WiFi AP
  Serial.print("Starting AP 'RoverAP'...");
  if (WiFi.softAP(ssid, password)) {
    Serial.println(" OK");
    Serial.print("SSID: "); Serial.println(ssid);
    Serial.print("IP: "); Serial.println(WiFi.softAPIP());
  } else {
    Serial.println(" FAILED!");
  }

  udp.begin(localUdpPort);
  Serial.printf("Listening UDP on %s:%u\n", WiFi.softAPIP().toString().c_str(), localUdpPort);

  // Первый обмен: инициализируем Nano, телеметрия будет с нулями (нормально)
  spiExchange(0, 0, 90, false, true, true, true);
}

// ================== LOOP ==================

void loop() {

  // ── UDP приём ──
  int packetSize = udp.parsePacket();
  if (packetSize) {
    lastSenderIP   = udp.remoteIP();
    lastSenderPort = udp.remotePort();
    haveSender     = true;

    int len = udp.read(incomingPacket, sizeof(incomingPacket) - 1);
    if (len > 0) incomingPacket[len] = 0;

    int spd = 0, str = 0, fwd = 0, laser = 0, gear = 0;

    // v1.2: RAW пакет ДО парсинга — видим что реально шлёт телефон
    Serial.printf("[PKT RAW] '%s' (%d bytes)\n", incomingPacket, len);

    sscanf(incomingPacket, "SPD:%d;STR:%d;FWD:%d;LASER:%d;GEAR:%d", &spd, &str, &fwd, &laser, &gear);

    str   = constrain(str, -100, 100);
    laser = (laser != 0) ? 1 : 0;

    if (DBG_RX_SERIAL) {
      Serial.printf("[RX %s:%u] %s\n  parsed: SPD=%d STR=%d FWD=%d LASER=%d GEAR=%d\n",
                     lastSenderIP.toString().c_str(), lastSenderPort, incomingPacket, spd, str, fwd, laser, gear);
    }

    bool dir_forward = (spd >= 0);
    int pwm_val = map(constrain(abs(spd), 0, 100), 0, 100, 0, 255);
    int servoAngle = map(str, -100, 100, SERVO_RIGHT, SERVO_LEFT);

    spiExchange(pwm_val, pwm_val, servoAngle, laser, dir_forward, dir_forward, true);

    lastSpd = spd; lastStr = str; lastFwd = fwd; lastLaser = laser;
    lastUdpRxTime = millis();
    lastHeartbeatSent = lastUdpRxTime;

    // ACK + телеметрия
    int bat_pct = (nanoTelem.bat_raw > 0) ? map(nanoTelem.bat_raw, 0, 128, 0, 100) : 100;
    char ackBuf[200];
    snprintf(ackBuf, sizeof(ackBuf),
      "{\"ack\":1,\"cmd\":1,\"spd\":%d,\"str\":%d,\"fwd\":%d,"
      "\"bat\":%d,\"rpmL\":%.1f,\"rpmR\":%.1f,"
      "\"sv\":%d,\"st\":%d}",
      abs(spd), str, fwd,
      bat_pct, rpmL, rpmR,
      nanoTelem.servo_actual, nanoTelem.status);
    sendUdpReply(ackBuf);
  }

  // ── Замер RPM ──
  unsigned long now1 = millis();
  if (now1 - lastTime >= MEAS_PERIOD_MS) {
    long deltaL = encoderLeft.getCount() - lastCountLeft;
    long deltaR = encoderRight.getCount() - lastCountRight;
    lastCountLeft = encoderLeft.getCount();
    lastCountRight = encoderRight.getCount();

    float dt = (now1 - lastTime) / 1000.0f;
    if (dt <= 0) dt = MEAS_PERIOD_MS / 1000.0f;
    lastTime = now1;

    float rpsL = (float)deltaL / PULSES_PER_REV / dt;
    float rpsR = (float)deltaR / PULSES_PER_REV / dt;
    rpmL = rpsL * 60.0f;
    rpmR = rpsR * 60.0f;

    if (DBG_RPM_SERIAL) Serial.printf("RPM: Left=%.2f  Right=%.2f\n", rpmL, rpmR);
  }

  // ── Heartbeat ──
  static unsigned long hbCount = 0;
  unsigned long nowHb = millis();
  if (lastUdpRxTime > 0 && nowHb - lastHeartbeatSent >= HEARTBEAT_MS) {
    int pwm_val = map(constrain(abs(lastSpd), 0, 100), 0, 100, 0, 255);
    bool dir_fwd = (lastSpd >= 0);
    int servoAngle = map(lastStr, -100, 100, SERVO_RIGHT, SERVO_LEFT);

    bool ok = spiExchange(pwm_val, pwm_val, servoAngle, lastLaser, dir_fwd, dir_fwd, true);
    lastHeartbeatSent = nowHb;
    hbCount++;

    // v1.2: каждые 10 heartbeat'ов — диагностика
    if (hbCount % 10 == 0) {
      Serial.printf("[HB #%lu] spd=%d pwm=%d dir=%s sv=%d spi_ok=%d nano_st=0x%02X\n",
                    hbCount, lastSpd, pwm_val, dir_fwd ? "FWD" : "REV",
                    servoAngle, ok, nanoTelem.status);
    }
  }

  // v1.2: безусловная диагностика каждые 2 сек — следим за переменными heartbeat
  static unsigned long lastHBDiag = 0;
  if (millis() - lastHBDiag >= 2000) {
    lastHBDiag = millis();
    Serial.printf("[HB DIAG] udpRx=%lu now=%lu hbSent=%lu hbCnt=%lu lastSpd=%d\n",
                  lastUdpRxTime, nowHb, lastHeartbeatSent, hbCount, lastSpd);
  }

  // ── Телеметрия на порт 4211 (каждые 200 мс) ──
  static unsigned long lastTelem = 0;
  if (haveSender && millis() - lastTelem >= 200) {
    lastTelem = millis();
    char telBuf[200];
    int bat_pct = (nanoTelem.bat_raw > 0) ? map(nanoTelem.bat_raw, 0, 128, 0, 100) : 100;

    // rpmL/rpmR + distance (v1.3: HC-SR04)
    if (abs(rpmL) > 0.01f || abs(rpmR) > 0.01f) {
      snprintf(telBuf, sizeof(telBuf),
        "{\"bat\":%d,\"yaw\":0.0,\"spd\":%d,\"str\":%d,"
        "\"pit\":0.0,\"rol\":0.0,\"rssi\":%d,"
        "\"rpmL\":%.1f,\"rpmR\":%.1f,\"dist\":%d}",
        bat_pct, abs(lastSpd), lastStr, WiFi.RSSI(), rpmL, rpmR,
        nanoTelem.distance_cm);
    } else {
      snprintf(telBuf, sizeof(telBuf),
        "{\"bat\":%d,\"yaw\":0.0,\"spd\":%d,\"str\":%d,"
        "\"pit\":0.0,\"rol\":0.0,\"rssi\":%d,"
        "\"dist\":%d}",
        bat_pct, abs(lastSpd), lastStr, WiFi.RSSI(),
        nanoTelem.distance_cm);
    }

    // v1.3: отладка — видим что реально шлём
    static unsigned long telemCount = 0;
    telemCount++;
    if (telemCount % 10 == 0) {  // каждые 10-й пакет (~2 сек)
      Serial.printf("[TELEM #%lu] dist=%d bat=%d spd=%d\n",
                    telemCount, nanoTelem.distance_cm, bat_pct, abs(lastSpd));
    }

    udp.beginPacket(lastSenderIP, TELEM_PORT);
    udp.write((uint8_t*)telBuf, strlen(telBuf));
    udp.endPacket();
  }
}
