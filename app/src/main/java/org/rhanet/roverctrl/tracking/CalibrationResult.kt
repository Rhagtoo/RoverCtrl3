package org.rhanet.roverctrl.tracking

import android.content.Context
import android.util.Log

/**
 * CalibrationResult — результат калибровки лазера → PID gains
 *
 * Хранит визуальные коэффициенты (visual degrees / servo degrees) и
 * рассчитывает оптимальные Kp для PID контроллера.
 *
 * ИСПРАВЛЕНИЕ: Этот класс отсутствовал в проекте, что приводило к ошибке компиляции.
 */
data class CalibrationResult(
    val panVisualPerServo: Float = 1.0f,
    val tiltVisualPerServo: Float = 1.0f,
    val cameraHFov: Float = 70f,
    val cameraVFov: Float = 55f,
    val timestamp: Long = 0L
) {
    companion object {
        private const val TAG = "CalibResult"
        private const val PREFS = "rover_calibration_result"

        // Диапазоны серво из прошивки XIAO
        const val PAN_SERVO_RANGE = 180f
        const val TILT_SERVO_RANGE = 180f

        // Базовые Kp (используются при отсутствии калибровки)
        const val DEFAULT_KP = 120f

        fun save(ctx: Context, result: CalibrationResult) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("panVPS", result.panVisualPerServo)
                .putFloat("tiltVPS", result.tiltVisualPerServo)
                .putFloat("hFov", result.cameraHFov)
                .putFloat("vFov", result.cameraVFov)
                .putLong("ts", result.timestamp)
                .apply()
            Log.i(TAG, "Saved: pan=${result.panVisualPerServo}, tilt=${result.tiltVisualPerServo}")
        }

        fun load(ctx: Context): CalibrationResult? {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ts = prefs.getLong("ts", 0L)
            if (ts == 0L) return null

            return CalibrationResult(
                panVisualPerServo = prefs.getFloat("panVPS", 1.0f),
                tiltVisualPerServo = prefs.getFloat("tiltVPS", 1.0f),
                cameraHFov = prefs.getFloat("hFov", 70f),
                cameraVFov = prefs.getFloat("vFov", 55f),
                timestamp = ts
            )
        }
    }

    enum class Axis { PAN, TILT }

    /**
     * Проверка валидности калибровки
     */
    val isValid: Boolean
        get() = timestamp > 0L &&
                panVisualPerServo > 0.01f && panVisualPerServo < 10f &&
                tiltVisualPerServo > 0.01f && tiltVisualPerServo < 10f

    /**
     * Рассчитывает рекомендуемый Kp для PID на основе калибровки.
     *
     * Логика: чем больше visual degrees на 1° серво, тем меньший Kp нужен
     * (движение серво вызывает большее визуальное смещение).
     *
     * Формула: Kp = baseKp * (1.0 / visualPerServo)
     */
    fun recommendedKp(axis: Axis): Float {
        return when (axis) {
            Axis.PAN -> {
                if (panVisualPerServo > 0.1f) {
                    // Чем выше visual/servo ratio, тем меньше Kp нужен
                    (DEFAULT_KP / panVisualPerServo).coerceIn(30f, 300f)
                } else DEFAULT_KP
            }
            Axis.TILT -> {
                if (tiltVisualPerServo > 0.1f) {
                    (DEFAULT_KP / tiltVisualPerServo).coerceIn(30f, 300f)
                } else DEFAULT_KP
            }
        }
    }

    /**
     * Создаёт CalibrationResult из CalibrationData (для совместимости)
     */
    fun fromCalibrationData(data: CalibrationData): CalibrationResult {
        return CalibrationResult(
            panVisualPerServo = data.panVisualPerServo,
            tiltVisualPerServo = data.tiltVisualPerServo,
            cameraHFov = data.cameraHFov,
            cameraVFov = data.cameraVFov,
            timestamp = data.timestamp
        )
    }

    override fun toString(): String {
        return "CalibrationResult(pan=${panVisualPerServo}°/servo°, " +
               "tilt=${tiltVisualPerServo}°/servo°, valid=$isValid)"
    }
}
