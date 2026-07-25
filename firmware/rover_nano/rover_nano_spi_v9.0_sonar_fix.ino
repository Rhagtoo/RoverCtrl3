/**
 * rover_nano_spi.ino v9.0 — Arduino Nano: SPI slave + моторы + серва + HC-SR04
 *
 * v9.0: Переработка сонара:
 *       - Медианный фильтр (5 замеров, 500 мс, bubble sort) → EMA (скользящее среднее)
 *         измерения обновляются КАЖДЫЙ цикл (60 мс), а не раз в 500 мс.
 *       - SONAR_INTERVAL_MS = 60 мс (теоретический минимум HC-SR04 для 4м)
 *       - SONAR_SLOWDOWN_CM = 30 см — снижение скорости до 15% (не полный стоп!)
 *       - distanceCm обновляется на каждом успешном замере (не ждёт N семплов)
 *       - Статус замедления передаётся в RspFrame (bit4=sonar_slow)
 *
 * v8.7: Добавлен HC-SR04 (Trig=D6, Echo=D9). Расстояние в см передаётся
 *       в SPI-ответе (rsvd). Измерение раз в 100 мс через pulseIn.
 * v8.3: ServoTimer2Plus → стандартный Servo.h (Timer1).
 * v8.4: Исправлен deadlock после программного watchdog.
 * v8.5: Исправлены критические баги SPI и моторов (code review).
 * v8.6: Timer2 (D3) — 62.5 кГц (бесшумные моторы).
 *
 * Таймеры:
 *   Timer0: millis/micros (штатный)
 *   Timer1: серва (Servo.h, D8)
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
  uint8_t rsvd1;
  uint8_t rsvd2;
  uint8_t crc;
};

struct RspFrame {
  uint8_t marker;      // 0xB5
  uint8_t pwm_l_echo;
  uint8_t pwm_r_echo;
  uint8_t servo_actual;
  uint8_t status;      // bit0=laser, bit1=wd_ok, bit2=crc_error, bit3=fault, bit4=sonar_slow
  uint8_t bat_raw;
  uint8_t distance_cm; // v9.0: HC-SR04 расстояние, 0-255 см (0 = нет данных), EMA-сглаженное
  uint8_t crc;
};

// ── Пины моторов (TB6612) ────────────────────────────────────────────
#define MOTOR_IN1     5  // v9.0.1: порты свапнуты — провода перепутаны
#define MOTOR_IN2     4  // v9.0.1: (газ вперёд → едет назад)
#define PWM_RIGHT     3     // Timer2 OC2B — оба мотора (62.5 кГц)
#define ENBL          7
#define OBSTACLE_PIN   2    // KY-032 → INT0

// ── Серва и лазер ─────────────────────────────────────────────────────
#define SERVO_STEER   8     // Servo.h использует Timer1
#define LASER_PIN     A0

// ── HC-SR04 ультразвуковой дальномер ─────────────────────────────────
#define TRIG_PIN           6   // D6 → Trig
#define ECHO_PIN           9   // D9 → Echo
#define SONAR_INTERVAL_MS 60    // v9.0: 60 мс (мин. теоретический для HC-SR04, 4м макс)
#define SONAR_SLOWDOWN_CM   30  // v9.0.1: порог снижения скорости до 15%
#define SONAR_SLOWDOWN_PCT  15  // v9.0.1: процент скорости при препятствии

volatile uint8_t distanceCm = 0;          // v9.0: последнее EMA-сглаженное измерение
volatile bool    sonarSlowdown = false;    // v9.0.1: флаг замедления (не стоп!)

unsigned long lastSonarMeasureMs = 0;

// v9.0: EMA (exponential moving average) вместо медианного фильтра
// α = 0.25 → ~4 замера для 63% веса нового значения
// Формула: EMA = α × new + (1-α) × old
// Все в fixed-point: α = 64/256 = 0.25
#define SONAR_EMA_ALPHA_NUM  64
#define SONAR_EMA_ALPHA_DEN  256
uint16_t sonarEma = 0;       // v9.0: EMA в fixed-point (0..255 * DEN)
bool     sonarEmaInit = false;

// Неблокирующий конечный автомат HC-SR04 (без pulseIn, не тормозит SPI)
enum SonarState { SONAR_IDLE, SONAR_TRIG, SONAR_WAIT_ECHO };
SonarState sonarState = SONAR_IDLE;
unsigned long sonarTrigStart = 0;
unsigned long sonarEchoStart = 0;

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
volatile uint16_t spi_overrun = 0;   // v8.5: счётчик потерянных фреймов
volatile bool obstacle = false;        // v8.6: флаг препятствия (INT0)

Servo steerServo;

// ── Состояние ─────────────────────────────────────────────────────────
int  leftPwm = 0, rightPwm = 0;
int  servoAngle = 90;
int  targetServoAngle = 90;
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

  // v9.0.1: сонар НЕ делает полный стоп — снижает скорость до SONAR_SLOWDOWN_PCT%
  // Это позволяет роверу продолжать движение (объезд препятствия),
  // а не вставать намертво. Полный стоп — только от KY-032 (obstacle).
  // Задний ход — всегда полная скорость (отъезд от препятствия).
  float sonarScale = sonarSlowdown ? (SONAR_SLOWDOWN_PCT / 100.0f) : 1.0f;

  // v8.6: датчик препятствий (optical KY-032) — блокирует только движение ВПЕРЁД
  if (obstacle && dominant > 0) { left = 0; right = 0; dominant = 0; }

  // v9.0.1: сонарное замедление — только вперёд, назад = полная скорость
  if (sonarSlowdown && dominant > 0) {
    left  = (int)(left  * sonarScale);
    right = (int)(right * sonarScale);
    dominant = (abs(left) >= abs(right)) ? left : right;
  }

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

  // v9.0: bit4 = sonar_stop (экстренное торможение от сонара)
  uint8_t statusFlags = (laserState               ? 0x01 : 0x00)
                      | ((lastCmdTime > 0)        ? 0x02 : 0x00)
                      | (sonarSlowdown             ? 0x10 : 0x00);

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
  rsp.distance_cm  = distanceCm;
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
  pinMode(TRIG_PIN, OUTPUT); pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);

  digitalWrite(ENBL, HIGH);
  digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
  OCR2B = 0;
  digitalWrite(LASER_PIN, LOW);

  steerServo.attach(SERVO_STEER);
  attachInterrupt(digitalPinToInterrupt(OBSTACLE_PIN), onObstacle, CHANGE);
  writeServoNow(90);
  targetServoAngle = 90;

  Serial.begin(115200);
  Serial.println("\n=== Nano SPI Driver v9.0.1 (fast sonar, EMA filter, 15% slowdown) ===");

  pinMode(SS, INPUT_PULLUP);
  pinMode(MISO, OUTPUT);

  PCICR  |= _BV(PCIE0);
  PCMSK0 |= _BV(PCINT2);

  RspFrame initRsp = {0xB5, 0, 0, 90, 0x02, 0, 0, 0};
  initRsp.crc = crc8((uint8_t*)&initRsp, 7);
  memcpy((void*)spi_rsp_raw, &initRsp, 8);

  SPCR |= _BV(SPE);
  SPCR |= _BV(SPIE);

  Serial.println("SPI slave ready (Servo.h Timer1, PCINT SS sync)");
}

// ================== HC-SR04 v9.0 (EMA + emergency stop) ==================

void measureDistance() {
  switch (sonarState) {

    // ── IDLE: ждём интервала, запускаем триггер ─────────────────────────
    case SONAR_IDLE: {
      unsigned long now = millis();
      if (now - lastSonarMeasureMs < SONAR_INTERVAL_MS) return;
      lastSonarMeasureMs = now;

      digitalWrite(TRIG_PIN, HIGH);
      sonarTrigStart = micros();
      sonarState = SONAR_TRIG;
      break;
    }

    // ── TRIG: держим 10 мкс, потом отпускаем ────────────────────────────
    case SONAR_TRIG: {
      if (micros() - sonarTrigStart >= 10) {
        digitalWrite(TRIG_PIN, LOW);
        sonarState = SONAR_WAIT_ECHO;
      }
      break;
    }

    // ── WAIT_ECHO: ловим фронты эха ─────────────────────────────────────
    case SONAR_WAIT_ECHO: {
      int echo = digitalRead(ECHO_PIN);

      // Передний фронт: засекаем время
      if (echo == HIGH && sonarEchoStart == 0) {
        sonarEchoStart = micros();

      // Задний фронт: вычисляем расстояние
      } else if (echo == LOW && sonarEchoStart != 0) {
        unsigned long duration = micros() - sonarEchoStart;
        sonarEchoStart = 0;

        if (duration > 0 && duration < 30000) {
          uint16_t rawCm = (uint16_t)(duration / 58);

          // ── v9.0: EMA (exponential moving average) ───────────────────
          // EMA = α × new + (1-α) × old, α = 0.25
          // Обновление КАЖДЫЙ замер (60 мс), без ожидания N семплов
          if (!sonarEmaInit) {
            sonarEma = (uint16_t)rawCm * SONAR_EMA_ALPHA_DEN;
            sonarEmaInit = true;
            distanceCm = (rawCm > 255) ? 255 : (uint8_t)rawCm;
          } else {
            // fixed-point EMA: ema = ema + α × (sample - ema/scale)
            sonarEma = sonarEma
                     + (SONAR_EMA_ALPHA_NUM * ((int32_t)rawCm * SONAR_EMA_ALPHA_DEN - (int32_t)sonarEma))
                       / SONAR_EMA_ALPHA_DEN;

            uint16_t smoothedCm = sonarEma / SONAR_EMA_ALPHA_DEN;
            distanceCm = (smoothedCm > 255) ? 255 : (uint8_t)smoothedCm;
          }

        } else {
          // Некорректная длительность — оставляем предыдущее значение
          // (не обнуляем — это не ошибка измерения, а возможно дальний объект)
        }

        // ── v9.0.1: проверка порога замедления (не стоп!) ────────────
        sonarSlowdown = (distanceCm > 0 && distanceCm < SONAR_SLOWDOWN_CM);

        sonarState = SONAR_IDLE;

      // ECHO всё ещё LOW после TRIG: ждём таймаута
      } else if (echo == LOW && sonarEchoStart == 0) {
        if (micros() - sonarTrigStart > 30000) {
          // Таймаут 30 мс (~5 метров) — нет объекта в радиусе действия
          sonarSlowdown = false;  // препятствия нет — полная скорость
          sonarState = SONAR_IDLE;
        }
      }
      break;
    }
  }
}

// ================== LOOP ==================

void loop() {
  if (spi_cmd_ready) {
    processSpiCommand();
  }

  checkWatchdog();
  updateServoSmoothing();
  measureDistance();

  static unsigned long lastDiag = 0;
  if (millis() - lastDiag > 2000) {
    lastDiag = millis();
    Serial.print("Nano: L="); Serial.print(leftPwm);
    Serial.print(" R="); Serial.print(rightPwm);
    Serial.print(" PWM="); Serial.print(OCR2B);
    Serial.print(" SV="); Serial.print(servoAngle);
    Serial.print("/"); Serial.print(targetServoAngle);
    Serial.print(" Laser="); Serial.print(laserState ? "ON" : "OFF");
    Serial.print(" OBS="); Serial.print(obstacle ? "YES" : "no");
    Serial.print(" DIST="); Serial.print(distanceCm); Serial.print("cm");
    Serial.print(" SONAR_SLOW="); Serial.print(sonarSlowdown ? "YES" : "no");
    Serial.print(" WD="); Serial.print(lastCmdTime > 0 ? millis() - lastCmdTime : 0);
    if (spi_overrun > 0) {
      Serial.print(" OVR="); Serial.print(spi_overrun);
    }
    Serial.println("ms");
  }
}
