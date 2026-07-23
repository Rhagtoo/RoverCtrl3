/**
 * rover_nano_spi.ino v8.7 — Arduino Nano: SPI slave + моторы + серва + HC-SR04
 *
 * v8.7: Добавлен HC-SR04 (Trig=D6, Echo=D9). Расстояние в см передаётся
 *       в SPI-ответе (rsvd). Измерение раз в 100 мс через pulseIn.
 * v8.3: ServoTimer2Plus → стандартный Servo.h (Timer1).
 *       ServoTimer2Plus (Timer2) конфликтовал со SPI, вызывая зависания
 *       в крайних положениях сервы.
 * v8.4: Исправлен deadlock после программного watchdog.
 *       stopAll() больше не сбрасывает lastCmdTime и не дёргает ENBL.
 * v8.5: Исправлены критические баги SPI и моторов (code review):
 *       - SPI: сброс spi_idx по подъёму SS (PCINT на D10) — рассинхрон больше невозможен
 *       - SPI: бит CRC_ERROR в статусе ответа — ESP знает что пакет битый
 *       - SPI: счётчик overrun для диагностики
 *       - Моторы: IN1/IN2 выставляются ОДИН раз, а не дважды (баг перезаписи)
 *       - AVR: правильный шаблон cli/sei (SREG до cli, восстанавливать SREG после)
 *       - Убран delay(1)
 * v8.6: Timer2 (D3) — 62.5 кГц (бесшумные моторы).
 *       Timer0 не тронут (millis штатный). KY-032 на INT0 (D2).
 *       Оба канала TB6612 запитаны от PWM_RIGHT (дифференциала нет).
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
  uint8_t status;      // bit0=laser, bit1=wd_ok, bit2=crc_error, bit3=fault
  uint8_t bat_raw;
  uint8_t distance_cm; // v8.7: HC-SR04 расстояние, 0-255 см (0 = нет данных)
  uint8_t crc;
};

// ── Пины моторов (TB6612) ────────────────────────────────────────────
// IN1/IN2 (D4,D5) запараллелены на оба канала TB6612 (AIN1+AIN2 и BIN1+BIN2).
// Направление — ОБЩЕЕ для обоих моторов. Оба канала TB6612 на PWM_RIGHT.
#define MOTOR_IN1     4
#define MOTOR_IN2     5
#define PWM_RIGHT     3     // Timer2 OC2B — оба мотора (62.5 кГц)
#define ENBL          7
#define OBSTACLE_PIN   2    // KY-032 → INT0

// ── Серва и лазер ─────────────────────────────────────────────────────
#define SERVO_STEER   8     // Servo.h использует Timer1
#define LASER_PIN     A0

// ── HC-SR04 ультразвуковой дальномер ─────────────────────────────────
#define TRIG_PIN      6     // D6 → Trig
#define ECHO_PIN      9     // D9 → Echo
#define SONAR_INTERVAL_MS 100  // период измерения (не чаще 60 мс, HC-SR04)

volatile uint8_t distanceCm = 0;        // v8.7: последнее измерение (0-255 см)
unsigned long lastSonarMeasureMs = 0;

// Медианный фильтр (3 последних замера) — убирает выбросы HC-SR04
#define SONAR_MEDIAN_N 5
uint16_t sonarBuf[SONAR_MEDIAN_N] = {0};
uint8_t sonarBufIdx = 0;

// Неблокирующий конечный автомат HC-SR04 (без pulseIn, не тормозит SPI)
enum SonarState { SONAR_IDLE, SONAR_TRIG, SONAR_WAIT_ECHO };
SonarState sonarState = SONAR_IDLE;
unsigned long sonarTrigStart = 0;
unsigned long sonarEchoStart = 0;

// ── Watchdog ──────────────────────────────────────────────────────────
#define MOTOR_WATCHDOG_MS  500

// ── Плавность руления (SERVO_STEP_DEG по желанию, 0 = без сглаживания) ───
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

// CRC-8 (полином 0x07) — ловит 99.6% много-битовых ошибок против ~0% у XOR
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

// v8.5: Pin Change Interrupt на D10 (SS = PB2 = PCINT2).
// При подъёме SS (конец транзакции) сбрасываем счётчик байт.
// Теперь даже при помехе/потере байта следующий фрейм начнётся с 0.
ISR(PCINT0_vect) {
  if (digitalRead(SS) == HIGH) {
    spi_idx = 0;  // жёсткий сброс — конец транзакции
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
      spi_overrun++;  // v8.5: предыдущий фрейм не обработан — потеря
    }
    spi_cmd_ready = true;
  }
}

// ================== PWM: Timer2 @ 62.5 кГц (бесшумные моторы) ==================

void setupPWMHighFreq() {
  // Timer2 (пин 3/OC2B): Fast PWM, prescaler=1 → 62.5 кГц
  TCCR2A = (1 << COM2B1) | (1 << WGM21) | (1 << WGM20);
  TCCR2B = (1 << CS20);   // prescaler=1, f = 16МГц / 256 = 62.5 кГц
}

// ================== Применение команд к железу ==================

// IN1/IN2 запараллелены на оба канала TB6612 → направление общее.
// v8.6: оба канала TB6612 на PWM_RIGHT (дифференциала нет).
#define PWM_RAMP_STEP  32   // макс изменение PWM за 1 вызов (0→255 за ~800 мс)

void applyMotors(int left, int right) {
  int dominant = (abs(left) >= abs(right)) ? left : right;

  // v8.6: датчик препятствий — блокирует только движение ВПЕРЁД
  // Назад можно всегда (отъезжаем от препятствия)
  if (obstacle && dominant > 0) { left = 0; right = 0; dominant = 0; }

  static int lastDominant = 0;

  // При смене направления — сбрасываем PWM в 0 на 1 цикл (анти-спайк)
  if (lastDominant != 0 && dominant != 0 && (lastDominant > 0) != (dominant > 0)) {
    OCR2B = 0;  // прямая запись в регистр таймера (не analogWrite — ломает 62.5 кГц!)
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

  // v8.6: один PWM-канал — скорость по максимуму из левого и правого
  static int ramped = 0;
  int target = max(constrain(abs(left), 0, 255), constrain(abs(right), 0, 255));

  if (target > ramped) ramped = min(target, ramped + PWM_RAMP_STEP);
  else                 ramped = max(target, ramped - PWM_RAMP_STEP);

  OCR2B = ramped;  // прямая запись в OCR2B (не analogWrite — ломает кастомный 62.5 кГц PWM!)

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
  writeServoNow(targetServoAngle);   // без сглаживания — сразу в цель
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

// v8.6: прерывание датчика препятствий (INT0, пин 2)
void onObstacle() {
  obstacle = !digitalRead(OBSTACLE_PIN);   // KY-032: LOW = препятствие
}

void stopAll() {
  digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
  OCR2B = 0;  // прямая запись (не analogWrite!)
  leftPwm = 0; rightPwm = 0;
  targetServoAngle = 90;
  writeServoNow(90);
  applyLaser(false);
}

// ================== Обработка SPI-команды ==================

void processSpiCommand() {
  // v8.5: правильный AVR-шаблон — сохраняем SREG ДО cli
  uint8_t savedSREG = SREG;
  cli();

  CmdFrame cmd;
  memcpy(&cmd, (const void*)spi_cmd_raw, 8);

  SREG = savedSREG;  // восстанавливаем флаги прерываний как были

  uint8_t statusFlags = (laserState   ? 0x01 : 0x00)
                      | ((lastCmdTime > 0) ? 0x02 : 0x00);

  if (cmd.marker != 0xA5) {
    statusFlags |= 0x04;  // CRC_ERROR — маркер не совпал
    spi_cmd_ready = false;
  } else if (cmd.crc != crc8((uint8_t*)&cmd, 7)) {
    statusFlags |= 0x04;  // CRC_ERROR — контрольная сумма не сошлась
    spi_cmd_ready = false;
  } else {
    // Команда валидна — применяем
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

  // Формируем ответ
  RspFrame rsp;
  rsp.marker       = 0xB5;
  rsp.pwm_l_echo   = cmd.pwm_l;
  rsp.pwm_r_echo   = cmd.pwm_r;
  rsp.servo_actual = servoAngle;
  rsp.status       = statusFlags;
  rsp.bat_raw      = 0x00;
  rsp.distance_cm  = distanceCm;  // v8.7: HC-SR04
  rsp.crc          = crc8((uint8_t*)&rsp, 7);

  // v8.5: атомарная запись ответа в буфер ISR
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
  setupPWMHighFreq();  // v8.6: Timer2 @ 62.5 кГц

  pinMode(MOTOR_IN1, OUTPUT); pinMode(MOTOR_IN2, OUTPUT);
  pinMode(PWM_RIGHT, OUTPUT);
  pinMode(LASER_PIN, OUTPUT); pinMode(ENBL, OUTPUT);
  pinMode(OBSTACLE_PIN, INPUT);             // v8.6: KY-032
  pinMode(TRIG_PIN, OUTPUT); pinMode(ECHO_PIN, INPUT);  // v8.7: HC-SR04
  digitalWrite(TRIG_PIN, LOW);

  digitalWrite(ENBL, HIGH);
  digitalWrite(MOTOR_IN1, LOW); digitalWrite(MOTOR_IN2, LOW);
  OCR2B = 0;  // прямая запись (не analogWrite — ломает 62.5 кГц!)
  digitalWrite(LASER_PIN, LOW);

  steerServo.attach(SERVO_STEER);
  attachInterrupt(digitalPinToInterrupt(OBSTACLE_PIN), onObstacle, CHANGE); // v8.6
  writeServoNow(90);
  targetServoAngle = 90;

  Serial.begin(115200);
  Serial.println("\n=== Nano SPI Driver v8.6 ===");

  // ── SPI slave ──
  pinMode(SS, INPUT_PULLUP);
  pinMode(MISO, OUTPUT);

  // v8.5: Pin Change Interrupt на D10 (SS) — сброс spi_idx по подъёму SS
  PCICR  |= _BV(PCIE0);   // разрешаем PCINT[7:0]
  PCMSK0 |= _BV(PCINT2);  // D10 = PB2 = PCINT2

  // Инициализация ответного буфера
  RspFrame initRsp = {0xB5, 0, 0, 90, 0x02, 0, 0, 0};
  initRsp.crc = crc8((uint8_t*)&initRsp, 7);
  memcpy((void*)spi_rsp_raw, &initRsp, 8);

  SPCR |= _BV(SPE);   // включаем SPI
  SPCR |= _BV(SPIE);  // разрешаем прерывание по завершению байта

  Serial.println("SPI slave ready (Servo.h Timer1, PCINT SS sync)");
}

// ================== HC-SR04 (неблокирующий конечный автомат) ==================

void measureDistance() {
  switch (sonarState) {
    case SONAR_IDLE: {
      unsigned long now = millis();
      if (now - lastSonarMeasureMs < SONAR_INTERVAL_MS) return;
      lastSonarMeasureMs = now;
      // Запускаем Trig-импульс (10 мкс HIGH)
      digitalWrite(TRIG_PIN, HIGH);
      sonarTrigStart = micros();
      sonarState = SONAR_TRIG;
      break;
    }

    case SONAR_TRIG: {
      if (micros() - sonarTrigStart >= 10) {
        digitalWrite(TRIG_PIN, LOW);
        sonarState = SONAR_WAIT_ECHO;
        // sonarEchoStart не устанавливаем — ждём пока Echo станет HIGH
      }
      break;
    }

    case SONAR_WAIT_ECHO: {
      int echo = digitalRead(ECHO_PIN);
      if (echo == HIGH && sonarEchoStart == 0) {
        sonarEchoStart = micros();  // засекли начало импульса
      } else if (echo == LOW && sonarEchoStart != 0) {
        // Конец импульса — вычисляем расстояние
        unsigned long duration = micros() - sonarEchoStart;
        sonarEchoStart = 0;
        if (duration > 0 && duration < 30000) {
          uint16_t cm = (uint16_t)(duration / 58);
          // Медианный фильтр — убирает случайные выбросы
          sonarBuf[sonarBufIdx % SONAR_MEDIAN_N] = cm;
          sonarBufIdx++;
          if (sonarBufIdx >= SONAR_MEDIAN_N) {
            // Сортируем копию буфера, берём медиану
            uint16_t tmp[SONAR_MEDIAN_N];
            memcpy(tmp, sonarBuf, sizeof(tmp));
            for (int i = 0; i < SONAR_MEDIAN_N-1; i++)
              for (int j = i+1; j < SONAR_MEDIAN_N; j++)
                if (tmp[i] > tmp[j]) { uint16_t t = tmp[i]; tmp[i] = tmp[j]; tmp[j] = t; }
            uint16_t med = tmp[SONAR_MEDIAN_N/2];
            distanceCm = (med > 255) ? 255 : (uint8_t)med;
          }
        } else {
          distanceCm = 0;
        }
        sonarState = SONAR_IDLE;
      } else if (echo == LOW && sonarEchoStart == 0) {
        // Таймаут: если прошло >30 мс с начала TRIG и эха нет
        if (micros() - sonarTrigStart > 30000) {
          distanceCm = 0;  // нет объекта
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
  measureDistance();  // v8.7: HC-SR04 раз в 100 мс

  static unsigned long lastDiag = 0;
  if (millis() - lastDiag > 2000) {
    lastDiag = millis();
    Serial.print("Nano: L="); Serial.print(leftPwm);
    Serial.print(" R="); Serial.print(rightPwm);
    Serial.print(" SV="); Serial.print(servoAngle);
    Serial.print("/"); Serial.print(targetServoAngle);
    Serial.print(" Laser="); Serial.print(laserState ? "ON" : "OFF");
    Serial.print(" OBS="); Serial.print(obstacle ? "YES" : "no");    // v8.6
    Serial.print(" DIST="); Serial.print(distanceCm); Serial.print("cm");  // v8.7
    Serial.print(" WD="); Serial.print(lastCmdTime > 0 ? millis() - lastCmdTime : 0);
    if (spi_overrun > 0) {
      Serial.print(" OVR="); Serial.print(spi_overrun);
    }
    Serial.println("ms");
  }
}
