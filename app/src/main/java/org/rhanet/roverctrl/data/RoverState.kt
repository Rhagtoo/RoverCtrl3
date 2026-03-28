package org.rhanet.roverctrl.data

import android.content.Context

/**
 * Телеметрия (ровер → телефон, UDP :4211, JSON каждые 500мс)
 *
 * ИСПРАВЛЕНО: добавлено поле str (команда руления) для одометрии Ackermann
 */
data class TelemetryData(
    val bat: Int = -1,
    val yaw: Float = 0f,
    val spd: Float = 0f,      // мощность мотора % (abs(FWD)), НЕ реальная скорость!
    val str: Int = 0,          // ← НОВОЕ: команда руления -100..+100 (из прошивки)
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val rssi: Int = 0,
    val rpmL: Float = Float.NaN,
    val rpmR: Float = Float.NaN
) {
    companion object {
        private const val WHEEL_DIAMETER_M = 0.065f
    }

    /** Реальная линейная скорость (м/с) из RPM энкодеров */
    val speedMs: Float
        get() {
            if (rpmL.isNaN() || rpmR.isNaN()) return Float.NaN
            val avgRpm = (rpmL + rpmR) / 2f
            return avgRpm / 60f * Math.PI.toFloat() * WHEEL_DIAMETER_M
        }

    /** Реальная скорость в км/ч */
    val speedKmh: Float
        get() {
            val ms = speedMs
            return if (ms.isNaN()) Float.NaN else ms * 3.6f
        }

    /** Мощность мотора (0..100) = abs(FWD) из прошивки */
    val powerPct: Int get() = spd.toInt()
}

/**
 * Настройки подключения (AP mode)
 */
data class ConnectionConfig(
    val roverIp: String = "192.168.4.1",
    val cmdPort: Int = 4210,
    val telPort: Int = 4211,
    val xiaoIp: String = "192.168.4.2",
    val xiaoPort: Int = 4210,
    val xiaoStreamPort: Int = 81
) {
    companion object {
        private const val PREFS = "rover_connection_config"
        private const val KEY_ROVER_IP = "rover_ip"
        private const val KEY_CMD_PORT = "cmd_port"
        private const val KEY_TEL_PORT = "tel_port"
        private const val KEY_XIAO_IP = "xiao_ip"
        private const val KEY_XIAO_PORT = "xiao_port"
        private const val KEY_XIAO_STREAM_PORT = "xiao_stream_port"

        fun load(ctx: Context): ConnectionConfig {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ConnectionConfig(
                roverIp = prefs.getString(KEY_ROVER_IP, "192.168.4.1") ?: "192.168.4.1",
                cmdPort = prefs.getInt(KEY_CMD_PORT, 4210),
                telPort = prefs.getInt(KEY_TEL_PORT, 4211),
                xiaoIp = prefs.getString(KEY_XIAO_IP, "192.168.4.2") ?: "192.168.4.2",
                xiaoPort = prefs.getInt(KEY_XIAO_PORT, 4210),
                xiaoStreamPort = prefs.getInt(KEY_XIAO_STREAM_PORT, 81)
            )
        }

        fun save(ctx: Context, config: ConnectionConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ROVER_IP, config.roverIp)
                .putInt(KEY_CMD_PORT, config.cmdPort)
                .putInt(KEY_TEL_PORT, config.telPort)
                .putString(KEY_XIAO_IP, config.xiaoIp)
                .putInt(KEY_XIAO_PORT, config.xiaoPort)
                .putInt(KEY_XIAO_STREAM_PORT, config.xiaoStreamPort)
                .apply()
        }

        fun reset(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    val turretStreamUrl: String get() = "http://$xiaoIp:$xiaoStreamPort/stream"
    val turretCaptureUrl: String get() = "http://$xiaoIp/capture"
    val turretStatusUrl: String get() = "http://$xiaoIp/status"

    fun isValid(): Boolean {
        val ipRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        return ipRegex.matches(roverIp) && ipRegex.matches(xiaoIp) &&
               cmdPort in 1..65535 && telPort in 1..65535 &&
               xiaoPort in 1..65535 && xiaoStreamPort in 1..65535
    }
}

data class OdometryState(val x: Float = 0f, val y: Float = 0f, val headingRad: Float = 0f, val distM: Float = 0f)

object GearConfig { val MAX_SPEED = mapOf(1 to 50, 2 to 100) }

enum class TrackingMode { MANUAL, LASER_DOT, OBJECT_TRACK, GYRO_TILT }

data class DetectionResult(
    val cx: Float, val cy: Float,
    val w: Float = 0f, val h: Float = 0f,
    val confidence: Float, val label: String = ""
)
