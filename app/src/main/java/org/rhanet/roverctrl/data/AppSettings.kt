package org.rhanet.roverctrl.data

import android.content.Context

/**
 * Настройки приложения (чувствительность джойстиков).
 * Хранятся независимо от ConnectionConfig в SharedPreferences "app_settings".
 */
data class AppSettings(
    val driveSpeedSens: Float = 1.0f,  // 0.1..2.0  множитель скорости движения
    val driveSteerSens: Float = 1.0f,  // 0.1..2.0  множитель руления
    val camPanSens:     Float = 1.0f,  // 0.1..2.0  множитель пана камеры
    val camTiltSens:    Float = 1.0f,  // 0.1..2.0  множитель тилта камеры
    // Tracking tuning (ObjectTracker anti-jitter)
    val trackDeadzone:  Float = 0.04f, // 0.01..0.15 center deadzone (fraction of frame)
    val trackExpo:      Float = 2.0f,  // 1.0..3.0  exponential curve power
    val trackRateLimit: Float = 8.0f,  // 2..30     max command change per frame
    // Model selection
    val modelName:      String = "yolov8n.tflite" // TFLite model filename in assets
) {
    companion object {
        private const val PREFS          = "app_settings"
        private const val KEY_DRIVE_SPD  = "drive_speed_sens"
        private const val KEY_DRIVE_STR  = "drive_steer_sens"
        private const val KEY_CAM_PAN    = "cam_pan_sens"
        private const val KEY_CAM_TILT   = "cam_tilt_sens"
        private const val KEY_TRACK_DZ   = "track_deadzone"
        private const val KEY_TRACK_EXPO = "track_expo"
        private const val KEY_TRACK_RATE = "track_rate_limit"
        private const val KEY_MODEL_NAME = "model_name"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                driveSpeedSens = p.getFloat(KEY_DRIVE_SPD, 1.0f),
                driveSteerSens = p.getFloat(KEY_DRIVE_STR, 1.0f),
                camPanSens     = p.getFloat(KEY_CAM_PAN,   1.0f),
                camTiltSens    = p.getFloat(KEY_CAM_TILT,  1.0f),
                trackDeadzone  = p.getFloat(KEY_TRACK_DZ,  0.04f),
                trackExpo      = p.getFloat(KEY_TRACK_EXPO, 2.0f),
                trackRateLimit = p.getFloat(KEY_TRACK_RATE, 8.0f),
                modelName      = p.getString(KEY_MODEL_NAME, "yolov8n.tflite") ?: "yolov8n.tflite"
            )
        }

        fun save(ctx: Context, s: AppSettings) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_DRIVE_SPD, s.driveSpeedSens)
                .putFloat(KEY_DRIVE_STR, s.driveSteerSens)
                .putFloat(KEY_CAM_PAN,   s.camPanSens)
                .putFloat(KEY_CAM_TILT,  s.camTiltSens)
                .putFloat(KEY_TRACK_DZ,  s.trackDeadzone)
                .putFloat(KEY_TRACK_EXPO, s.trackExpo)
                .putFloat(KEY_TRACK_RATE, s.trackRateLimit)
                .putString(KEY_MODEL_NAME, s.modelName)
                .apply()
        }

        /** Прогресс SeekBar (0..19) → значение чувствительности (0.1..2.0, шаг 0.1) */
        fun progressToSens(progress: Int): Float = 0.1f + progress * 0.1f
        fun sensToProgress(sens: Float): Int = ((sens - 0.1f) / 0.1f).toInt().coerceIn(0, 19)
        fun format(sens: Float): String = String.format("%.1fx", sens)
    }
}
