package org.rhanet.roverctrl.tracking

import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────────
// PidController — полный PID-регулятор (P + I + D)
//
// Полный PID-регулятор: P + I + D
// error = setpoint - measurement (обычно setpoint = 0, т.е. центр кадра)
// output ограничен [-outMax, +outMax]
//
// Используется для pan/tilt камеры в LaserTracker и ObjectTracker.
// ──────────────────────────────────────────────────────────────────────────

class PidController(
    var kp:     Float = 0.5f,
    var ki:     Float = 0.01f,
    var kd:     Float = 0.1f,
    var outMax: Float = 100f
) {
    private var integral  = 0f
    private var lastError = 0f
    private var lastMs    = 0L

    fun reset() { integral = 0f; lastError = 0f; lastMs = 0L }

    fun update(error: Float): Float {
        val now = System.currentTimeMillis()
        val dt  = if (lastMs == 0L) 0.05f else (now - lastMs) / 1000f
        lastMs  = now

        integral  = (integral + error * dt).coerceIn(-outMax / ki.coerceAtLeast(0.001f),
                                                       outMax / ki.coerceAtLeast(0.001f))
        val deriv = if (dt > 0f) (error - lastError) / dt else 0f
        lastError = error

        val out = kp * error + ki * integral + kd * deriv
        return out.coerceIn(-outMax, outMax)
    }

    /** Мёртвая зона — не двигаем серво если ошибка мала */
    fun updateWithDeadband(error: Float, deadband: Float = 0.02f): Float =
        if (abs(error) < deadband) 0f else update(error)

    /** Упрощённый вызов, возвращает Int для совместимости с CommandSender */
    fun updateInt(error: Float): Int = update(error).toInt()
}
