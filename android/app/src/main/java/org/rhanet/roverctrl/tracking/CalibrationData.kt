package org.rhanet.roverctrl.tracking

import android.content.Context
import android.util.Log

/**
 * Результат калибровки лазера.
 *
 * Процедура: турель ставится в несколько позиций (pan/tilt),
 * пользователь каждый раз совмещает центр камеры с лазерной точкой.
 * По разнице ориентации телефона вычисляем:
 *   panVisualPerServo  — сколько визуальных градусов на 1° серво pan
 *   tiltVisualPerServo — сколько визуальных градусов на 1° серво tilt
 *
 * Из этого PID kp = servoRange / (0.5 * cameraFOV / visualPerServo)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * РЕАЛЬНАЯ ПРОШИВКА XIAO (sketch_mar16a.ino):
 *
 *   PAN:  constrain(-90,90) → map(-90,90, 180,0)   ИНВЕРТИРОВАН
 *         cmd -100 → servo 180°,  cmd 0 → 90°,  cmd +100 → 0°
 *
 *   TILT: constrain(-90,90) → map(-90,90, 0,180)
 *         cmd -100 → servo 0°,   cmd 0 → 90°,  cmd +100 → 180°
 *
 *   Оба: полный ход серво = 180° (0..180), физический range = 180°
 *   Android отправляет -100..+100, CommandSender масштабирует в -90..+90
 * ═══════════════════════════════════════════════════════════════════════
 */
data class CalibrationData(
    val panVisualPerServo:  Float = 1.0f,   // визуальных° / серво°
    val tiltVisualPerServo: Float = 1.0f,
    val cameraHFov:         Float = 70f,    // горизонтальный FOV камеры (°)
    val cameraVFov:         Float = 55f,    // вертикальный FOV камеры (°)
    val timestamp:          Long  = 0L
) {
    companion object {
        private const val TAG = "CalibData"
        private const val PREFS = "rover_calibration"

        // ── Реальные диапазоны серво из прошивки XIAO ──────────────────
        // PAN:  map(-90,90, 180,0) → полный ход 0°..180°, ИНВЕРТИРОВАН
        // TILT: map(-90,90, 0,180) → полный ход 0°..180°
        const val PAN_SERVO_RANGE  = 180f   // 0°..180° = 180° total
        const val TILT_SERVO_RANGE = 180f   // 0°..180° = 180° total

        /**
         * Pan command (-100..100) → серво градусы.
         * Прошивка: PAN инвертирован. cmd +100 → servo 0°, cmd -100 → servo 180°
         * Формула: servoDeg = 90 - cmd * 0.9
         */
        fun panCmdToDeg(cmd: Int): Float = 90f - cmd * 0.9f

        /**
         * Tilt command (-100..100) → серво градусы.
         * Прошивка: TILT прямой. cmd -100 → servo 0°, cmd +100 → servo 180°
         * Формула: servoDeg = cmd * 0.9 + 90
         */
        fun tiltCmdToDeg(cmd: Int): Float = cmd * 0.9f + 90f

        /**
         * Вычисляет калибровку из набора точек.
         *
         * @param panPoints  список (servoDegrees, phoneAzimuthDegrees)
         * @param tiltPoints список (servoDegrees, phonePitchDegrees)
         */
        fun compute(
            panPoints:  List<Pair<Float, Float>>,
            tiltPoints: List<Pair<Float, Float>>,
            hFov: Float = 70f,
            vFov: Float = 55f
        ): CalibrationData {
            val panSlope  = linearSlope(panPoints)
            val tiltSlope = linearSlope(tiltPoints)

            Log.i(TAG, "Pan: ${panPoints.size} points, slope=$panSlope vis°/servo°")
            Log.i(TAG, "Tilt: ${tiltPoints.size} points, slope=$tiltSlope vis°/servo°")

            return CalibrationData(
                panVisualPerServo  = if (panSlope.isFinite() && panSlope > 0.01f) panSlope else 1.0f,
                tiltVisualPerServo = if (tiltSlope.isFinite() && tiltSlope > 0.01f) tiltSlope else 1.0f,
                cameraHFov = hFov,
                cameraVFov = vFov,
                timestamp  = System.currentTimeMillis()
            )
        }

        /** Линейная регрессия: slope = |Δ(phone°)| / |Δ(servo°)| */
        private fun linearSlope(points: List<Pair<Float, Float>>): Float {
            if (points.size < 2) return 1.0f

            val n = points.size
            var sumX = 0f; var sumY = 0f; var sumXY = 0f; var sumX2 = 0f
            for ((x, y) in points) {
                sumX += x; sumY += y; sumXY += x * y; sumX2 += x * x
            }
            val denom = n * sumX2 - sumX * sumX
            if (denom == 0f) return 1.0f

            return kotlin.math.abs((n * sumXY - sumX * sumY) / denom)
        }

        fun save(ctx: Context, data: CalibrationData) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("panVPS", data.panVisualPerServo)
                .putFloat("tiltVPS", data.tiltVisualPerServo)
                .putFloat("hFov", data.cameraHFov)
                .putFloat("vFov", data.cameraVFov)
                .putLong("ts", data.timestamp)
                .apply()
            Log.i(TAG, "Saved calibration: pan=${data.panVisualPerServo}, tilt=${data.tiltVisualPerServo}")
        }

        fun load(ctx: Context): CalibrationData? {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ts = prefs.getLong("ts", 0L)
            if (ts == 0L) return null
            return CalibrationData(
                panVisualPerServo  = prefs.getFloat("panVPS", 1.0f),
                tiltVisualPerServo = prefs.getFloat("tiltVPS", 1.0f),
                cameraHFov         = prefs.getFloat("hFov", 70f),
                cameraVFov         = prefs.getFloat("vFov", 55f),
                timestamp          = ts
            )
        }
    }

    /**
     * Оптимальный kp для PID pan.
     *
     * Логика: ошибка 0.5 (край кадра) = cameraHFov/2 визуальных градусов.
     * Нужно серво-команду = visualError / panVisualPerServo.
     * В единицах -100..100: servo_cmd = servo_degrees / (range/2) * 100
     *
     * Серво range = 180° (полный ход 0..180° из прошивки).
     */
    fun optimalPanKp(): Float {
        // error=1.0 (полный кадр) → cameraHFov visual degrees
        // → cameraHFov / panVisualPerServo servo degrees
        // → / (servoRange/2) * 100 command units
        val kp = (cameraHFov / panVisualPerServo) / (PAN_SERVO_RANGE / 2f) * 100f
        return kp.coerceIn(10f, 300f)
    }

    fun optimalTiltKp(): Float {
        val kp = (cameraVFov / tiltVisualPerServo) / (TILT_SERVO_RANGE / 2f) * 100f
        return kp.coerceIn(10f, 300f)
    }
}
