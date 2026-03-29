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
    val camTiltSens:    Float = 1.0f   // 0.1..2.0  множитель тилта камеры
) {
    companion object {
        private const val PREFS          = "app_settings"
        private const val KEY_DRIVE_SPD  = "drive_speed_sens"
        private const val KEY_DRIVE_STR  = "drive_steer_sens"
        private const val KEY_CAM_PAN    = "cam_pan_sens"
        private const val KEY_CAM_TILT   = "cam_tilt_sens"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                driveSpeedSens = p.getFloat(KEY_DRIVE_SPD, 1.0f),
                driveSteerSens = p.getFloat(KEY_DRIVE_STR, 1.0f),
                camPanSens     = p.getFloat(KEY_CAM_PAN,   1.0f),
                camTiltSens    = p.getFloat(KEY_CAM_TILT,  1.0f)
            )
        }

        fun save(ctx: Context, s: AppSettings) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_DRIVE_SPD, s.driveSpeedSens)
                .putFloat(KEY_DRIVE_STR, s.driveSteerSens)
                .putFloat(KEY_CAM_PAN,   s.camPanSens)
                .putFloat(KEY_CAM_TILT,  s.camTiltSens)
                .apply()
        }

        /** Прогресс SeekBar (0..19) → значение чувствительности (0.1..2.0, шаг 0.1) */
        fun progressToSens(progress: Int): Float = 0.1f + progress * 0.1f
        fun sensToProgress(sens: Float): Int = ((sens - 0.1f) / 0.1f).toInt().coerceIn(0, 19)
        fun format(sens: Float): String = String.format("%.1fx", sens)
    }
}
