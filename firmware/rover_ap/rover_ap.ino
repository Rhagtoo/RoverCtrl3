/**
 * rover_ap.ino v2.3 — Ровер XIAO ESP32S3, WiFi AP
 * v2.3: добавлен "str" в телеметрию для одометрии Ackermann
 */
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ESP32Servo.h>
#include "driver/pcnt.h"

const char* AP_SSID = "RoverAP";
const char* AP_PASS = "rover12345";
const IPAddress AP_IP(192, 168, 4, 1);
const IPAddress AP_GATEWAY(192, 168, 4, 1);
const IPAddress AP_SUBNET(255, 255, 255, 0);

#define MOTOR_IN1 6
#define MOTOR_IN2 7
#define PWM_LEFT  4
#define PWM_RIGHT 5
#define ENC_L_A 12
#define ENC_L_B 11
#define ENC_R_A 38
#define ENC_R_B 37
#define SERVO_STEER 13
#define LASER_PIN   17
#define CMD_PORT           4210
#define TELEM_PORT         4211
#define MOTOR_WATCHDOG_MS  500
#define TELEM_INTERVAL_MS  500
#define ENCODER_PPR        440
#define RPM_SAMPLE_MS      200

WiFiUDP udp;
WiFiUDP udpTelem;
Servo steerServo;
volatile int16_t encCountL = 0, encCountR = 0;
float rpmL = 0.0f, rpmR = 0.0f;
int lastFwd = 0, lastStr = 0;
bool laserOn = false;
IPAddress remoteIP;
uint16_t remotePort = 0;
bool haveRemote = false;
unsigned long lastCmdTime = 0, lastTelemTime = 0, lastRpmSampleTime = 0;
int16_t prevEncL = 0, prevEncR = 0;

void setupEncoder(pcnt_unit_t unit, int pinA, int pinB) {
    pcnt_config_t cfg = {};
    cfg.pulse_gpio_num = pinA; cfg.ctrl_gpio_num = pinB;
    cfg.channel = PCNT_CHANNEL_0; cfg.unit = unit;
    cfg.pos_mode = PCNT_COUNT_INC; cfg.neg_mode = PCNT_COUNT_DEC;
    cfg.lctrl_mode = PCNT_MODE_REVERSE; cfg.hctrl_mode = PCNT_MODE_KEEP;
    cfg.counter_h_lim = 32767; cfg.counter_l_lim = -32768;
    pcnt_unit_config(&cfg);
    pcnt_set_filter_value(unit, 100); pcnt_filter_enable(unit);
    pcnt_counter_pause(unit); pcnt_counter_clear(unit); pcnt_counter_resume(unit);
}

void readEncoders() {
    int16_t cL, cR;
    pcnt_get_counter_value(PCNT_UNIT_0, &cL);
    pcnt_get_counter_value(PCNT_UNIT_1, &cR);
    encCountL = cL; encCountR = cR;
}

void updateRpm() {
    unsigned long now = millis();
    unsigned long dt = now - lastRpmSampleTime;
    if (dt >= RPM_SAMPLE_MS) {
        readEncoders();
        float factor = 60000.0f / (ENCODER_PPR * dt);
        rpmL = (encCountL - prevEncL) * factor;
        rpmR = (encCountR - prevEncR) * factor;
        prevEncL = encCountL; prevEncR = encCountR;
        lastRpmSampleTime = now;
    }
}

void setMotor(int fwd) {
    int pwm = constrain(abs(fwd) * 255 / 100, 0, 255);
    if (fwd > 0) { digitalWrite(MOTOR_IN1, HIGH); digitalWrite(MOTOR_IN2, LOW); }
    else if (fwd < 0) { digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, HIGH); }
    else { digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW); }
    analogWrite(PWM_LEFT, pwm); analogWrite(PWM_RIGHT, pwm);
}

void stopMotors() {
    digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
    analogWrite(PWM_LEFT, 0); analogWrite(PWM_RIGHT, 0); lastFwd = 0;
}

void parseCommand(const char* cmd) {
    int spd = 0, str = 0, fwd = 0, laser = 0, gear = 2;
    char* ptr = (char*)cmd;
    if (strstr(ptr, "SPD:")) spd = atoi(strstr(ptr, "SPD:") + 4);
    if (strstr(ptr, "STR:")) str = atoi(strstr(ptr, "STR:") + 4);
    if (strstr(ptr, "FWD:")) fwd = atoi(strstr(ptr, "FWD:") + 4);
    if (strstr(ptr, "LASER:")) laser = atoi(strstr(ptr, "LASER:") + 6);
    if (strstr(ptr, "GEAR:")) gear = constrain(atoi(strstr(ptr, "GEAR:") + 5), 1, 2);

    str = constrain(str, -100, 100);
    steerServo.write(map(str, -100, 100, 40, 140));
    lastStr = str;

    int maxFwd = (gear == 1) ? 50 : 100;
    fwd = constrain(fwd, -maxFwd, maxFwd);
    setMotor(fwd); lastFwd = fwd;

    laserOn = (laser == 1);
    digitalWrite(LASER_PIN, laserOn ? HIGH : LOW);
    lastCmdTime = millis();
}

void checkWatchdog() {
    if (lastCmdTime > 0 && (millis() - lastCmdTime) > MOTOR_WATCHDOG_MS) {
        if (lastFwd != 0) { stopMotors(); laserOn = false; digitalWrite(LASER_PIN, LOW); }
    }
}

// v2.3: добавлен "str" для одометрии Ackermann
void sendTelemetry() {
    if (!haveRemote) return;
    if (millis() - lastTelemTime < TELEM_INTERVAL_MS) return;
    lastTelemTime = millis();
    char buf[256];
    snprintf(buf, sizeof(buf),
        "{\"bat\":100,\"yaw\":0.0,\"spd\":%d,\"str\":%d,"
        "\"pit\":0.0,\"rol\":0.0,\"rssi\":%d,\"rpmL\":%.1f,\"rpmR\":%.1f}",
        abs(lastFwd), lastStr, WiFi.RSSI(), rpmL, rpmR);
    udpTelem.beginPacket(remoteIP, TELEM_PORT);
    udpTelem.write((const uint8_t*)buf, strlen(buf));
    udpTelem.endPacket();
}

void setup() {
    Serial.begin(115200); Serial.println("\n=== Rover AP v2.3 ===");
    pinMode(MOTOR_IN1, OUTPUT); pinMode(MOTOR_IN2, OUTPUT);
    pinMode(PWM_LEFT, OUTPUT); pinMode(PWM_RIGHT, OUTPUT);
    pinMode(LASER_PIN, OUTPUT); stopMotors(); digitalWrite(LASER_PIN, LOW);
    ESP32PWM::allocateTimer(0);
    steerServo.setPeriodHertz(50); steerServo.attach(SERVO_STEER, 500, 2400); steerServo.write(90);
    setupEncoder(PCNT_UNIT_0, ENC_L_A, ENC_L_B);
    setupEncoder(PCNT_UNIT_1, ENC_R_A, ENC_R_B);
    WiFi.mode(WIFI_AP); WiFi.softAPConfig(AP_IP, AP_GATEWAY, AP_SUBNET);
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("AP:%s IP:%s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
    udp.begin(CMD_PORT); udpTelem.begin(TELEM_PORT); Serial.println("Ready!");
}

void loop() {
    int s = udp.parsePacket();
    if (s > 0) {
        char buf[256]; int len = udp.read(buf, sizeof(buf) - 1); buf[len] = '\0';
        remoteIP = udp.remoteIP(); remotePort = udp.remotePort(); haveRemote = true;
        parseCommand(buf);
    }
    updateRpm(); checkWatchdog(); sendTelemetry(); delay(5);
}

void serialEvent() {
    while (Serial.available()) {
        String cmd = Serial.readStringUntil('\n'); cmd.trim();
        if (cmd == "status") Serial.printf("RPM L=%.1f R=%.1f FWD=%d STR=%d\n", rpmL, rpmR, lastFwd, lastStr);
        else if (cmd == "stop") { stopMotors(); Serial.println("Stopped"); }
    }
}
