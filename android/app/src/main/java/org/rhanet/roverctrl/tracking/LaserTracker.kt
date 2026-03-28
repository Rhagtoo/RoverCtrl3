package org.rhanet.roverctrl.tracking

import android.graphics.Bitmap
import android.graphics.Color
import org.rhanet.roverctrl.data.DetectionResult

// ──────────────────────────────────────────────────────────────────────────
// LaserTracker
//
// Детектирует лазерную точку (красную или ярко-белую при bloom) в кадре камеры телефона.
// Детектирует лазерную точку (красную или ярко-белую) в кадре:
//   1. Скалирует кадр до procW×procH
//   2. HSV-маска красного диапазона + fallback на сверхъяркие пиксели
//   3. Взвешенный центроид по яркости
//   4. PID → pan/tilt delta (-100..100)
//
// Отличия от предыдущей версии:
//   - Разрешение 320×240 (вместо 160×120) — ловит точку на 7+ метров
//   - satMin снижен до 0.20 — лазер на расстоянии почти белый
//   - Fallback: пиксель с V > 0.95 и R-channel доминирует → считается лазером
//   - Порог sumW снижен до 1.0 — достаточно 1-2 ярких пикселей
// ──────────────────────────────────────────────────────────────────────────

class LaserTracker(
    // HSV диапазоны красного (Android Color.colorToHSV: hue 0..360)
    private val hueLo1: Float = 0f,   private val hueHi1: Float = 35f,
    private val hueLo2: Float = 315f, private val hueHi2: Float = 360f,
    private val satMin: Float = 0.20f,   // снижено: лазер на расстоянии → низкая насыщенность
    private val valMin: Float = 0.50f,   // снижено: лазер на расстоянии бледнеет
    // Рабочий размер — 320×240 ловит точку на 7+ метров
    private val procW: Int = 320,
    private val procH: Int = 240,
    // PID
    pidKp: Float = 120f, pidKi: Float = 0.5f, pidKd: Float = 8f
) {
    private val pidPan  = PidController(kp = pidKp, ki = pidKi, kd = pidKd, outMax = 100f)
    private val pidTilt = PidController(kp = pidKp, ki = pidKi, kd = pidKd, outMax = 100f)

    data class TrackResult(
        val found:     Boolean,
        val panDelta:  Float,
        val tiltDelta: Float,
        val detection: DetectionResult?
    )

    fun process(frame: Bitmap): TrackResult {
        val small = Bitmap.createScaledBitmap(frame, procW, procH, false)

        var sumX = 0.0; var sumY = 0.0; var sumW = 0.0
        val hsv = FloatArray(3)

        for (y in 0 until procH) {
            for (x in 0 until procW) {
                val px = small.getPixel(x, y)
                Color.colorToHSV(px, hsv)

                val isLaser = isRedHsv(hsv) || isBrightRedRgb(px, hsv[2]) || isBrightSpot(px)

                if (isLaser) {
                    val w = hsv[2].toDouble()   // weight by brightness
                    sumX += x * w
                    sumY += y * w
                    sumW += w
                }
            }
        }
        small.recycle()

        // Порог: 1 яркий пиксель достаточно (V~1.0 → sumW ≥ 1.0)
        if (sumW < 1.0) {
            pidPan.reset(); pidTilt.reset()
            return TrackResult(false, 0f, 0f, null)
        }

        val cx = (sumX / sumW / procW).toFloat()  // 0..1
        val cy = (sumY / sumW / procH).toFloat()

        val errX = cx - 0.5f
        val errY = cy - 0.5f

        val panOut  = pidPan.updateWithDeadband(errX)
        val tiltOut = pidTilt.updateWithDeadband(errY)

        return TrackResult(
            found     = true,
            panDelta  = panOut,
            tiltDelta = tiltOut,
            detection = DetectionResult(cx = cx, cy = cy, confidence = 1f, label = "laser")
        )
    }

    /** Классический HSV-маска красного */
    private fun isRedHsv(hsv: FloatArray): Boolean {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (s < satMin || v < valMin) return false
        return (h in hueLo1..hueHi1) || (h in hueLo2..hueHi2)
    }

    /**
     * Fallback для далёкой лазерной точки:
     * На тёмном фоне камера задирает ISO → лазер на 7м может иметь R=60-130.
     * Проверяем что R доминирует по СООТНОШЕНИЮ (не абсолютной разнице).
     */
    private fun isBrightRedRgb(pixel: Int, value: Float): Boolean {
        if (value < 0.25f) return false   // совсем чёрный — не лазер
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // R доминирует: R > G*1.6 и R > B*1.6, абсолютный минимум R > 50
        return r > 50 && (g < 5 || r.toFloat() / g > 1.6f) && (b < 5 || r.toFloat() / b > 1.6f)
    }

    /**
     * Третий путь — для лазера на 5-10 метрах на тёмном фоне:
     * Точка bloom'ится и становится почти белой.
     * Порог снижен до 180 — auto-exposure давит пиковую яркость.
     */
    private fun isBrightSpot(pixel: Int): Boolean {
        return Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180
    }

    fun resetPid() { pidPan.reset(); pidTilt.reset() }

    /** Обновить kp после калибровки */
    fun updatePidGains(kpPan: Float, kpTilt: Float) {
        pidPan.kp = kpPan
        pidTilt.kp = kpTilt
        pidPan.reset(); pidTilt.reset()
    }
}
