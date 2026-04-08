package org.rhanet.roverctrl.tracking

import org.rhanet.roverctrl.data.DetectionResult

/**
 * Простой 1D Kalman фильтр для сглаживания координат.
 * Используется для трекинга детектированных объектов между кадрами.
 *
 * Модель: постоянная скорость (velocity assumed constant between frames)
 * Q = process noise (как быстро может меняться реальное положение)
 * R = measurement noise (точность измерения детектора)
 *
 * v3.0 FIXES:
 *   - Fix #4: velocity update now uses pre-update innovation (was using post-update residual)
 *   - Fix #5: predict() — prediction step only (no measurement), for inter-detection tracking
 *   - Fix #9: initialize(value) — set state directly without high-gain transient
 */
class SimpleKalmanFilter(
    private val processNoise: Float = 0.01f,   // Q: шум процесса
    private val measurementNoise: Float = 0.1f // R: шум измерения
) {
    private var x = 0f      // оценка положения
    private var v = 0f      // оценка скорости
    private var p = 1f      // ковариация ошибки оценки
    private var lastUpdateTime = System.currentTimeMillis()

    /**
     * Обновить фильтр с новым измерением.
     * @param measurement Новое измерение (например, cx от детектора)
     * @param dt Время с последнего обновления в секундах (если 0, используется реальное время)
     * @return Сглаженное значение
     */
    fun update(measurement: Float, dt: Float = 0f): Float {
        val actualDt = computeDt(dt)

        // 1. Prediction
        x += v * actualDt
        p += processNoise

        // 2. Kalman gain
        val k = p / (p + measurementNoise)

        // 3. Update
        // Fix #4: compute innovation BEFORE updating x — old code used post-update
        // residual which attenuated velocity by factor (1-K)
        val innovation = measurement - x
        x += k * innovation
        v += k * innovation / actualDt
        p *= (1 - k)

        v = v.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        return x
    }

    /**
     * Prediction-only step — no measurement available.
     * Extrapolates position using current velocity estimate.
     * Process noise increases uncertainty (confidence drops).
     * @return Predicted position
     */
    fun predict(dt: Float = 0f): Float {
        val actualDt = computeDt(dt)
        x += v * actualDt
        p += processNoise
        // Velocity decays slightly during prediction (object might change direction)
        v *= VELOCITY_DECAY
        return x
    }

    /**
     * Initialize filter state directly (no transient from 0,0).
     * Use when a new detection arrives after reset.
     */
    fun initialize(value: Float) {
        x = value
        v = 0f
        p = measurementNoise  // reasonable initial uncertainty
        lastUpdateTime = System.currentTimeMillis()
    }

    /** Сбросить фильтр (объект потерян или новый объект) */
    fun reset() {
        x = 0f
        v = 0f
        p = 1f
        lastUpdateTime = System.currentTimeMillis()
    }

    val position: Float get() = x
    val velocity: Float get() = v
    val confidence: Float get() = 1f / (1f + p)

    private fun computeDt(dt: Float): Float {
        return if (dt > 0f) dt else {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastUpdateTime).toFloat() / 1000f
            lastUpdateTime = now
            elapsed.coerceAtLeast(0.001f)
        }
    }

    companion object {
        private const val MAX_VELOCITY = 0.5f
        private const val VELOCITY_DECAY = 0.98f
    }
}

/**
 * Двумерный Kalman фильтр для координат (x, y) + размеры (w, h).
 * Использует четыре независимых 1D фильтра.
 *
 * v3.0: added predict() and initialize() methods.
 */
class KalmanFilter2D(
    processNoise: Float = 0.01f,
    measurementNoise: Float = 0.1f
) {
    private val filterX = SimpleKalmanFilter(processNoise, measurementNoise)
    private val filterY = SimpleKalmanFilter(processNoise, measurementNoise)
    private val filterW = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 2f)
    private val filterH = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 2f)

    /** Cached label/confidence for predict() to return a complete DetectionResult */
    private var lastLabel = ""
    private var lastConfidence = 0f

    fun update(detection: DetectionResult, dt: Float = 0f): DetectionResult {
        lastLabel = detection.label
        lastConfidence = detection.confidence

        val cx = filterX.update(detection.cx, dt).coerceIn(0f, 1f)
        val cy = filterY.update(detection.cy, dt).coerceIn(0f, 1f)
        val w = filterW.update(detection.w, dt).coerceIn(0f, 1f)
        val h = filterH.update(detection.h, dt).coerceIn(0f, 1f)

        val maxW = 2f * minOf(cx, 1f - cx)
        val maxH = 2f * minOf(cy, 1f - cy)

        return detection.copy(cx = cx, cy = cy, w = w.coerceAtMost(maxW), h = h.coerceAtMost(maxH))
    }

    /**
     * Prediction-only step — extrapolate using velocity, no measurement.
     */
    fun predict(dt: Float = 0f): DetectionResult {
        val cx = filterX.predict(dt).coerceIn(0f, 1f)
        val cy = filterY.predict(dt).coerceIn(0f, 1f)
        val w = filterW.predict(dt).coerceIn(0f, 1f)
        val h = filterH.predict(dt).coerceIn(0f, 1f)

        val maxW = 2f * minOf(cx, 1f - cx)
        val maxH = 2f * minOf(cy, 1f - cy)

        return DetectionResult(
            cx = cx, cy = cy,
            w = w.coerceAtMost(maxW), h = h.coerceAtMost(maxH),
            confidence = lastConfidence,
            label = lastLabel
        )
    }

    /**
     * Initialize all filters with detection values (no transient from 0,0).
     */
    fun initialize(detection: DetectionResult) {
        lastLabel = detection.label
        lastConfidence = detection.confidence
        filterX.initialize(detection.cx)
        filterY.initialize(detection.cy)
        filterW.initialize(detection.w)
        filterH.initialize(detection.h)
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
        filterW.reset()
        filterH.reset()
        lastLabel = ""
        lastConfidence = 0f
    }

    val confidence: Float get() =
        (filterX.confidence + filterY.confidence + filterW.confidence + filterH.confidence) / 4f
}
