package org.rhanet.roverctrl.data

// ── Телеметрия (ровер → телефон, UDP :4211, JSON каждые 500мс) ────────────
data class TelemetryData(
    val bat:   Int   = -1,
    val yaw:   Float = 0f,
    val spd:   Float = 0f,
    val pitch: Float = 0f,
    val roll:  Float = 0f,
    val rssi:  Int   = 0,
    val rpmL:  Float = Float.NaN,
    val rpmR:  Float = Float.NaN
)

// ── Настройки подключения ─────────────────────────────────────────────────
data class ConnectionConfig(
    val roverIp:        String = "192.168.88.24",
    val cmdPort:        Int    = 4210,
    val telPort:        Int    = 4211,
    val xiaoIp:         String = "192.168.88.23",
    val xiaoPort:       Int    = 4210,
    val xiaoStreamPort: Int    = 81       // MJPEG камера на XIAO Sense
) {
    /** URL для MJPEG стрима с камеры турели */
    val turretStreamUrl: String
        get() = "http://$xiaoIp:$xiaoStreamPort/stream"
}

// ── Одометрия ─────────────────────────────────────────────────────────────
data class OdometryState(
    val x:          Float = 0f,
    val y:          Float = 0f,
    val headingRad: Float = 0f,
    val distM:      Float = 0f
)

// ── Режим трекинга ────────────────────────────────────────────────────────
enum class TrackingMode {
    MANUAL,       // джойстик рулит вручную
    LASER_DOT,    // HSV-маска красной точки → PID → Pan/Tilt
    OBJECT_TRACK, // YOLOv8 TFLite → PID → Pan/Tilt
    GYRO_TILT     // наклон телефона → Pan/Tilt (FPV-управление)
}

// ── Результат детекции ────────────────────────────────────────────────────
data class DetectionResult(
    val cx:         Float,
    val cy:         Float,
    val w:          Float = 0f,
    val h:          Float = 0f,
    val confidence: Float,
    val label:      String = ""
)


