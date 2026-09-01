/**
 * rover_nano_spi.ino v10.0 — Arduino Nano: SPI slave + моторы + руль + лазерный туррет
 *
 * v10.0: HC-SR04 удалён (освобождены D6/D9).
 *        Добавлена лазерная турель: 2 сервы на D6 (pan) и D9 (tilt).
 *        Управление через SPI CmdFrame.rsvd1 (pan 0-180°) и rsvd2 (tilt 0-180°).
 *        Убрана вся логика сонара: FSM, EMA, slowdown, distanceCm.
 *        RspFrame.distance_cm всегда 0.
 *
 * v9.0: (историческая) Переработка сонара: медианный фильтр → EMA.
 * v8.7: Добавлен HC-SR04.
 * v8.3: ServoTimer2Plus → Servo.h (Timer1).
 *
 * Таймеры:
 *   Timer0: millis/micros (штатный)
 *   Timer1: сервы (Servo.h) — руль D8 + туррет pan D6 + туррет tilt D9
 *   Timer2: PWM_RIGHT (D3/OC2B, prescaler=1, 62.5 кГц)
 *   INT0:   датчик KY-032 (D2, прерывание по CHANGE)
 *   SPI:    аппаратный (D10-D13)
 */

#include <SPI.h>
#include <Servo.h>

// ── Структуры протокола ───────────────────────────────────────────────

struct CmdFrame {
  uint8_t marker;      // 0xA5
  uint8_t pwm_l;
  uint8_t pwm_r;
  uint8_t servo;
  uint8_t flags;       // bit0=laser, bit1=dir_L, bit2=dir_R, bit3=enable
  uint8_t turret_pan;  // v10.0: лазерный туррет pan  (0-180°), бывший rsvd1
  uint8_t turret_tilt; // v10.0: лазерный туррет tilt (0-180°), бывший rsvd2
  uint8_t crc;
};

struct RspFrame {
  uint8_t marker;      // 0xB5
  uint8_t pwm_l_echo;
  uint8_t pwm_r_echo;
  uint8_t servo_actual;
  uint8_t status;      // bit0=laser, bit1=wd_ok, bit2=crc_error, bit3=fault
  uint8_t bat_raw;
  uint8_t rsvd;        // v10.0: бывший distance_cm (сонар удалён, всегда 0)
  uint8_t crc;
};

// ── Пины моторов (TB6612) ────────────────────────────────────────────
#define MOTOR_IN1     5
#define MOTOR_IN2     4
#define PWM_RIGHT     3     // Timer2 OC2B — оба мотора (62.5 кГц)
#define ENBL          7
#define OBSTACLE_PIN  2     // KY-032 → INT0

// ── Сервы ─────────────────────────────────────────────────────────────
#define SERVO_STEER   8     // рулевая (Servo.h, Timer1)
#define TURRET_PAN    6     // v10.0: лазерный туррет pan  (бывший TRIG сонара)
#define TURRET_TILT   9     // v10.0: лазерный туррет tilt (бывший ECHO сонара)
#define LASER_PIN     A0

// ── Watchdog ──────────────────────────────────────────────────────────
#define MOTOR_WATCHDOG_MS  500

// ── Плавность руления ─────────────────────────────────────────────────
#define SERVO_STEP_DEG     0    // 0 = прямой write, без рампы
#define SERVO_UPDATE_MS    10   // интервал обновления

// ── SPI-буферы ───────────────────────────────────────────────────────
volatile uint8_t spi_cmd_raw[8];
volatile uint8_t spi_rsp_raw[8];
volatile bool    spi_cmd_ready = false;
volatile uint8_t spi_idx = 0;
volatile uint16_t spi_overrun = 0;
volatile bool obstacle = false;

Servo steerServo;
Servo turretPanServo;   // v10.0
Servo turretTiltServo;  // v10.0

// ── Состояние ─────────────────────────────────────────────────────────
int  leftPwm = 0, rightPwm = 0;
int  servoAngle = 90;
int  targetServoAngle = 90;
int  turretPanAngle = 90;    // v10.0
int  turretTiltAngle = 90;   // v10.0
bool laserState = false;

unsigned long lastCmdTime = 0;
unsigned long lastServoUpdate = 0;

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

// ================== SPI Slave ISR + синхронизация по SS ==================

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
    if (spi_cmd_ready) {
      spi_overrun++;
    }
    spi_cmd_ready = true;
  }
}

// ================== PWM: Timer2 @ 62.5 кГц ==================

void setupPWMHighFreq() {
  TCCR2A = (1 << COM2B1) | (1 << WGM21) | (1 << WGM20);
  TCCR2B = (1 << CS20);
}

// ================== Применение команд к железу ==================

#define PWM_RAMP_STEP  32

void applyMotors(int left, int right) {
  int dominant = (abs(left) >= abs(right)) ? left : right;

  // KY-032 — блокирует только движение ВПЕРЁД
  if (obstacle && dominant > 0) { left = 0; right = 0; dominant = 0; }

  static int lastDominant = 0;

  if (lastDominant != 0 && dominant != 0 && (lastDominant > 0) != (dominant > 0)) {
    OCR2B = 0;
    delayMicroseconds(3000);
  }
  lastDominant = dominant;

  if (dominant > 0) {
    digitalWrite(MOTOR_IN1, HIGH); digitalWrite(MOTOR_IN2, LOW);
  } else if (dominant < 0) {
    digitalWrite(MOTOR_IN1, LOW);  digitalWrite(MOTOR_IN2, HIGH);
  } else {
    digitalWrite(MOTOR_IN1, LOW);  digitalWrite(MOTOR_IN2, LOW);
  }

  static int ramped = 0;
  int target = max(constrain(abs(left), 0, 255), constrain(abs(right), 0, 255));

  if (target > ramped) ramped = min(target, ramped + PWM_RAMP_STEP);
  else                 ramped = max(target, ramped - PWM_RAMP_STEP);

  OCR2B = ramped;

  leftPwm = left; rightPwm = right;
}

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

// v10.0: прямое управление сервами турели (без рампы — позиционные сервы)
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
  digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
  OCR2B = 0;
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

  uint8_t statusFlags = (laserState               ? 0x01 : 0x00)
                      | ((lastCmdTime > 0)        ? 0x02 : 0x00);

  if (cmd.marker != 0xA5) {
    statusFlags |= 0x04;
    spi_cmd_ready = false;
  } else if (cmd.crc != crc8((uint8_t*)&cmd, 7)) {
    statusFlags |= 0x04;
    spi_cmd_ready = false;
  } else {
    bool laser     = (cmd.flags & 0x01) != 0;
    bool dir_left  = (cmd.flags & 0x02) != 0;
    bool dir_right = (cmd.flags & 0x04) != 0;
    bool enable    = (cmd.flags & 0x08) != 0;

    if (enable) {
      digitalWrite(ENBL, HIGH);

      int signed_l = dir_left  ? (int)cmd.pwm_l : -(int)cmd.pwm_l;
      int signed_r = dir_right ? (int)cmd.pwm_r : -(int)cmd.pwm_r;

      applyMotors(signed_l, signed_r);
      applyServo(cmd.servo);
      applyLaser(laser);

      // v10.0: туррет
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
  rsp.rsvd         = 0;   // v10.0: сонар удалён
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

  pinMode(MOTOR_IN1, OUTPUT); pinMode(MOTOR_IN2, OUTPUT);
  pinMode(PWM_RIGHT, OUTPUT);
  pinMode(LASER_PIN, OUTPUT); pinMode(ENBL, OUTPUT);
  pinMode(OBSTACLE_PIN, INPUT);

  digitalWrite(ENBL, HIGH);
  digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
  OCR2B = 0;
  digitalWrite(LASER_PIN, LOW);

  // Сервы
  steerServo.attach(SERVO_STEER);
  turretPanServo.attach(TURRET_PAN);     // v10.0: D6
  turretTiltServo.attach(TURRET_TILT);   // v10.0: D9
  writeServoNow(90);
  turretPanServo.write(90);
  turretTiltServo.write(90);

  attachInterrupt(digitalPinToInterrupt(OBSTACLE_PIN), onObstacle, CHANGE);

  Serial.begin(115200);
  Serial.println("\n=== Nano SPI Driver v10.0 (3 сервы: руль + турель pan/tilt) ===");

  pinMode(SS, INPUT_PULLUP);
  pinMode(MISO, OUTPUT);

  PCICR  |= _BV(PCIE0);
  PCMSK0 |= _BV(PCINT2);

  RspFrame initRsp = {0xB5, 0, 0, 90, 0x02, 0, 0, 0};
  initRsp.crc = crc8((uint8_t*)&initRsp, 7);
  memcpy((void*)spi_rsp_raw, &initRsp, 8);

  SPCR |= _BV(SPE);
  SPCR |= _BV(SPIE);

  Serial.println("SPI slave ready (Timer1: 3 servos, Timer2: 62.5kHz PWM)");
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
