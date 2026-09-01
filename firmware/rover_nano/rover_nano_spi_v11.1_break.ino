/**
 * rover_nano_spi.ino v11.1 — Arduino Nano: SPI slave + дрифт через IN1/IN2
 *
 * v11.1: Один общий PWM 62.5 кГц (D3) на все моторы.
 *        Независимое управление через IN1/IN2:
 *          Задние (оба):   D4/D5
 *          Переднее левое:  A1/A2
 *          Переднее правое: A3/A4
 *        Handbrake (bit4 flags):
 *          Руль центр (75-105°) → short-brake оба передних
 *          Руль < 75°           → short-brake только левое переднее
 *          Руль > 105°          → short-brake только правое переднее
 *        Задние всегда едут при газе.
 *
 * v10.0: (база) 3 сервы, лазер, удалён сонар.
 */

#include <SPI.h>
#include <Servo.h>

// ── Структуры протокола ───────────────────────────────────────────────

struct CmdFrame {
  uint8_t marker;      // 0xA5
  uint8_t pwm_l;
  uint8_t pwm_r;
  uint8_t servo;
  uint8_t flags;       // bit0=laser, bit1=dir(общий), bit2=rsvd, bit3=enable, bit4=handbrake
  uint8_t turret_pan;  // 0-180°
  uint8_t turret_tilt; // 0-180°
  uint8_t crc;
};

struct RspFrame {
  uint8_t marker;      // 0xB5
  uint8_t pwm_l_echo;
  uint8_t pwm_r_echo;
  uint8_t servo_actual;
  uint8_t status;      // bit0=laser, bit1=wd_ok, bit2=crc_error, bit3=fault
  uint8_t bat_raw;
  uint8_t rsvd;        // всегда 0
  uint8_t crc;
};

// ── Пины ─────────────────────────────────────────────────────────────
// Общий PWM 62.5 кГц → на все PWM-входы TB6612
#define PWM_COMMON    3     // Timer2 OC2B

// Задние колёса (оба)
#define REAR_IN1      4
#define REAR_IN2      5
a
// Переднее левое
#define FRONT_L_IN1  A1
#define FRONT_L_IN2  A2

// Переднее правое
#define FRONT_R_IN1  A3
#define FRONT_R_IN2  A4

#define ENBL          7
#define OBSTACLE_PIN  2     // KY-032 → INT0

// ── Сервы ─────────────────────────────────────────────────────────────
#define SERVO_STEER   8
#define TURRET_PAN    6
#define TURRET_TILT   9
#define LASER_PIN    A0

// ── Watchdog ──────────────────────────────────────────────────────────
#define MOTOR_WATCHDOG_MS  500

// ── Плавность ─────────────────────────────────────────────────────────
#define SERVO_STEP_DEG     0
#define SERVO_UPDATE_MS    10
#define PWM_RAMP_STEP     32

// ── Handbrake пороги руля ────────────────────────────────────────────
#define HB_CENTER_MIN     75
#define HB_CENTER_MAX    105

// ── SPI-буферы ───────────────────────────────────────────────────────
volatile uint8_t spi_cmd_raw[8];
volatile uint8_t spi_rsp_raw[8];
volatile bool    spi_cmd_ready = false;
volatile uint8_t spi_idx = 0;
volatile uint16_t spi_overrun = 0;
volatile bool obstacle = false;

Servo steerServo;
Servo turretPanServo;
Servo turretTiltServo;

// ── Состояние ─────────────────────────────────────────────────────────
int  leftPwm = 0, rightPwm = 0;
int  servoAngle = 90;
int  targetServoAngle = 90;
int  turretPanAngle = 90;
int  turretTiltAngle = 90;
bool laserState = false;

unsigned long lastCmdTime = 0;
unsigned long lastServoUpdate = 0;

// ── Состояние общего PWM / направления ────────────────────────────────
int  commonRamped = 0;
bool lastForward = true;

// ================== CRC ==================

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

// ================== SPI Slave ISR ==================

ISR(PCINT0_vect) {
  if (digitalRead(SS) == HIGH) {
    spi_idx = 0;
  }
}

ISR(SPI_STC_vect) {
  uint8_t b = SPDR;
  spi_cmd_raw[spi_idx] = b;
  SPDR = spi_rsp_raw[spi_idx];
  spi_idx++;
  if (spi_idx >= 8) {
    spi_idx = 0;
    if (spi_cmd_ready) spi_overrun++;
    spi_cmd_ready = true;
  }
}

// ================== PWM: Timer2 @ 62.5 кГц ==================

void setupPWMHighFreq() {
  TCCR2A = (1 << COM2B1) | (1 << WGM21) | (1 << WGM20);
  TCCR2B = (1 << CS20);
}

// ================== Моторы: общий PWM + независимые IN1/IN2 ==================

void applyMotors(int gas, bool forward, bool handbrake) {
  gas = constrain(gas, 0, 255);

  // KY-032 блокирует только движение ВПЕРЁД
  if (obstacle && forward && gas > 0) {
    gas = 0;
  }

  // Dead-time при смене направления
  if (gas > 0 && lastForward != forward) {
    digitalWrite(REAR_IN1, LOW);    digitalWrite(REAR_IN2, LOW);
    digitalWrite(FRONT_L_IN1, LOW); digitalWrite(FRONT_L_IN2, LOW);
    digitalWrite(FRONT_R_IN1, LOW); digitalWrite(FRONT_R_IN2, LOW);
    OCR2B = 0;
    delayMicroseconds(3000);
    lastForward = forward;
  }

  if (gas == 0) {
    digitalWrite(REAR_IN1, LOW);    digitalWrite(REAR_IN2, LOW);
    digitalWrite(FRONT_L_IN1, LOW); digitalWrite(FRONT_L_IN2, LOW);
    digitalWrite(FRONT_R_IN1, LOW); digitalWrite(FRONT_R_IN2, LOW);
    commonRamped = 0;
    OCR2B = 0;
    leftPwm = 0; rightPwm = 0;
    return;
  }

  // Задние всегда едут
  if (forward) {
    digitalWrite(REAR_IN1, HIGH); digitalWrite(REAR_IN2, LOW);
  } else {
    digitalWrite(REAR_IN1, LOW);  digitalWrite(REAR_IN2, HIGH);
  }

  // Передние по умолчанию едут в том же направлении
  int fl1 = forward ? HIGH : LOW;
  int fl2 = forward ? LOW  : HIGH;
  int fr1 = forward ? HIGH : LOW;
  int fr2 = forward ? LOW  : HIGH;

  if (handbrake) {
    int sa = targetServoAngle;
    if (sa < HB_CENTER_MIN) {
      // руль влево → short brake только левое переднее
      fl1 = HIGH; fl2 = HIGH;
    } else if (sa > HB_CENTER_MAX) {
      // руль вправо → short brake только правое переднее
      fr1 = HIGH; fr2 = HIGH;
    } else {
      // руль по центру → short brake оба передних
      fl1 = HIGH; fl2 = HIGH;
      fr1 = HIGH; fr2 = HIGH;
    }
  }

  digitalWrite(FRONT_L_IN1, fl1); digitalWrite(FRONT_L_IN2, fl2);
  digitalWrite(FRONT_R_IN1, fr1); digitalWrite(FRONT_R_IN2, fr2);

  // Рампа общего PWM
  if (gas > commonRamped) commonRamped = min(gas, commonRamped + PWM_RAMP_STEP);
  else                    commonRamped = max(gas, commonRamped - PWM_RAMP_STEP);
  OCR2B = commonRamped;

  int signedGas = forward ? commonRamped : -commonRamped;
  leftPwm = signedGas;
  rightPwm = signedGas;
}

// ================== Сервы и лазер ==================

void writeServoNow(int angle) {
  angle = constrain(angle, 0, 180);
  steerServo.write(angle);
  servoAngle = angle;
}

void applyServo(int angle) {
  targetServoAngle = constrain(angle, 0, 180);
}

void updateServoSmoothing() {
  if (millis() - lastServoUpdate < SERVO_UPDATE_MS) return;
  lastServoUpdate = millis();
  if (servoAngle == targetServoAngle) return;
#if SERVO_STEP_DEG == 0
  writeServoNow(targetServoAngle);
#else
  int diff = targetServoAngle - servoAngle;
  int step = constrain(diff, -SERVO_STEP_DEG, SERVO_STEP_DEG);
  writeServoNow(servoAngle + step);
#endif
}

void applyTurret(int pan, int tilt) {
  pan  = constrain(pan,  0, 180);
  tilt = constrain(tilt, 0, 180);
  turretPanServo.write(pan);
  turretTiltServo.write(tilt);
  turretPanAngle  = pan;
  turretTiltAngle = tilt;
}

void applyLaser(bool on) {
  digitalWrite(LASER_PIN, on ? HIGH : LOW);
  laserState = on;
}

void onObstacle() {
  obstacle = !digitalRead(OBSTACLE_PIN);
}

void stopAll() {
  digitalWrite(REAR_IN1, LOW);    digitalWrite(REAR_IN2, LOW);
  digitalWrite(FRONT_L_IN1, LOW); digitalWrite(FRONT_L_IN2, LOW);
  digitalWrite(FRONT_R_IN1, LOW); digitalWrite(FRONT_R_IN2, LOW);
  OCR2B = 0;
  commonRamped = 0;
  leftPwm = 0; rightPwm = 0;
  targetServoAngle = 90;
  writeServoNow(90);
  applyLaser(false);
}

// ================== Обработка SPI-команды ==================

void processSpiCommand() {
  uint8_t savedSREG = SREG;
  cli();

  CmdFrame cmd;
  memcpy(&cmd, (const void*)spi_cmd_raw, 8);

  SREG = savedSREG;

  uint8_t statusFlags = (laserState        ? 0x01 : 0x00)
                      | ((lastCmdTime > 0) ? 0x02 : 0x00);

  if (cmd.marker != 0xA5) {
    statusFlags |= 0x04;
    spi_cmd_ready = false;
  } else if (cmd.crc != crc8((uint8_t*)&cmd, 7)) {
    statusFlags |= 0x04;
    spi_cmd_ready = false;
  } else {
    bool laser     = (cmd.flags & 0x01) != 0;
    bool forward   = (cmd.flags & 0x02) != 0;   // общее направление
    bool enable    = (cmd.flags & 0x08) != 0;
    bool handbrake = (cmd.flags & 0x10) != 0;   // bit4 = ручник

    if (enable) {
      digitalWrite(ENBL, HIGH);

      // Общий газ — берём максимум из двух каналов для совместимости
      int gas = max((int)cmd.pwm_l, (int)cmd.pwm_r);

      // Сначала руль, потом моторы (чтобы handbrake знал куда повернуто)
      applyServo(cmd.servo);
      applyMotors(gas, forward, handbrake);
      applyLaser(laser);
      applyTurret(cmd.turret_pan, cmd.turret_tilt);

      lastCmdTime = millis();
    }
    spi_cmd_ready = false;
  }

  RspFrame rsp;
  rsp.marker       = 0xB5;
  rsp.pwm_l_echo   = cmd.pwm_l;
  rsp.pwm_r_echo   = cmd.pwm_r;
  rsp.servo_actual = servoAngle;
  rsp.status       = statusFlags;
  rsp.bat_raw      = 0x00;
  rsp.rsvd         = 0;
  rsp.crc          = crc8((uint8_t*)&rsp, 7);

  uint8_t sreg2 = SREG;
  cli();
  memcpy((void*)spi_rsp_raw, &rsp, 8);
  SREG = sreg2;
}

// ================== Watchdog ==================

void checkWatchdog() {
  if (lastCmdTime > 0 && (millis() - lastCmdTime) > MOTOR_WATCHDOG_MS) {
    if (leftPwm != 0 || rightPwm != 0) {
      stopAll();
      lastCmdTime = millis();
      Serial.println("NANO: WATCHDOG STOP");
    }
  }
}

// ================== SETUP ==================

void setup() {
  setupPWMHighFreq();

  // Моторы
  pinMode(REAR_IN1, OUTPUT);    pinMode(REAR_IN2, OUTPUT);
  pinMode(FRONT_L_IN1, OUTPUT); pinMode(FRONT_L_IN2, OUTPUT);
  pinMode(FRONT_R_IN1, OUTPUT); pinMode(FRONT_R_IN2, OUTPUT);
  pinMode(PWM_COMMON, OUTPUT);
  pinMode(ENBL, OUTPUT);
  pinMode(LASER_PIN, OUTPUT);
  pinMode(OBSTACLE_PIN, INPUT);

  digitalWrite(ENBL, HIGH);
  digitalWrite(REAR_IN1, LOW);    digitalWrite(REAR_IN2, LOW);
  digitalWrite(FRONT_L_IN1, LOW);  digitalWrite(FRONT_L_IN2, LOW);
  digitalWrite(FRONT_R_IN1, LOW);  digitalWrite(FRONT_R_IN2, LOW);
  OCR2B = 0;
  digitalWrite(LASER_PIN, LOW);

  // Сервы
  steerServo.attach(SERVO_STEER);
  turretPanServo.attach(TURRET_PAN);
  turretTiltServo.attach(TURRET_TILT);
  writeServoNow(90);
  turretPanServo.write(90);
  turretTiltServo.write(90);

  attachInterrupt(digitalPinToInterrupt(OBSTACLE_PIN), onObstacle, CHANGE);

  Serial.begin(115200);
  Serial.println("\n=== Nano SPI Driver v11.1 (handbrake drift, 1 PWM) ===");

  pinMode(SS, INPUT_PULLUP);
  pinMode(MISO, OUTPUT);

  PCICR  |= _BV(PCIE0);
  PCMSK0 |= _BV(PCINT2);

  RspFrame initRsp = {0xB5, 0, 0, 90, 0x02, 0, 0, 0};
  initRsp.crc = crc8((uint8_t*)&initRsp, 7);
  memcpy((void*)spi_rsp_raw, &initRsp, 8);

  SPCR |= _BV(SPE);
  SPCR |= _BV(SPIE);

  Serial.println("SPI slave ready (1xPWM D3, 3xIN pairs, handbrake bit4)");
}

// ================== LOOP ==================

void loop() {
  if (spi_cmd_ready) {
    processSpiCommand();
  }

  checkWatchdog();
  updateServoSmoothing();

  static unsigned long lastDiag = 0;
  if (millis() - lastDiag > 2000) {
    lastDiag = millis();
    Serial.print("Nano: L="); Serial.print(leftPwm);
    Serial.print(" R="); Serial.print(rightPwm);
    Serial.print(" PWM="); Serial.print(OCR2B);
    Serial.print(" STEER="); Serial.print(servoAngle);
    Serial.print("/"); Serial.print(targetServoAngle);
    Serial.print(" TURRET="); Serial.print(turretPanAngle);
    Serial.print("/"); Serial.print(turretTiltAngle);
    Serial.print(" Laser="); Serial.print(laserState ? "ON" : "OFF");
    Serial.print(" OBS="); Serial.print(obstacle ? "YES" : "no");
    Serial.print(" WD="); Serial.print(lastCmdTime > 0 ? millis() - lastCmdTime : 0);
    if (spi_overrun > 0) {
      Serial.print(" OVR="); Serial.print(spi_overrun);
    }
    Serial.println("ms");
  }
}