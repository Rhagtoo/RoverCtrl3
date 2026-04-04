package org.rhanet.roverctrl.tracking

import org.rhanet.roverctrl.data.DetectionResult

/**
 * Простой 1D Kalman фильтр для сглаживания координат.
 * Используется для трекинга детектированных объектов между кадрами.
 *
 * Модель: постоянная скорость (velocity assumed constant between frames)
 * Q = process noise (как быстро может меняться реальное положение)
 * R = measurement noise (точность измерения детектора)
 */
class SimpleKalmanFilter(
    private val processNoise: Float = 0.01f,   // Q: шум процесса (0.01 = 1% изменения)
    private val measurementNoise: Float = 0.1f // R: шум измерения (0.1 = 10% ошибка)
) {
    // Состояние: [position, velocity]
    private var x = 0f      // оценка положения
    private var v = 0f      // оценка скорости
    private var p = 1f      // ковариация ошибки оценки
    
    // Время последнего обновления (мс)
    private var lastUpdateTime = System.currentTimeMillis()
    
    /**
     * Обновить фильтр с новым измерением.
     * @param measurement Новое измерение (например, cx от детектора)
     * @param dt Время с последнего обновления в секундах (если 0, используется реальное время)
     * @return Сглаженное значение
     */
    fun update(measurement: Float, dt: Float = 0f): Float {
        val actualDt = if (dt > 0f) dt else {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastUpdateTime).toFloat() / 1000f
            lastUpdateTime = now
            elapsed.coerceAtLeast(0.001f) // минимум 1 мс
        }
        
        // 1. Prediction (прогноз)
        // x = x + v * dt
        x += v * actualDt
        // p = p + Q
        p += processNoise
        
        // 2. Kalman gain
        val k = p / (p + measurementNoise)
        
        // 3. Update (коррекция)
        x += k * (measurement - x)
        v += k * (measurement - x) / actualDt
        p *= (1 - k)
        
        // Ограничиваем скорость (не более 0.5 за секунду)
        val maxVelocity = 0.5f
        v = v.coerceIn(-maxVelocity, maxVelocity)
        
        return x
    }
    
    /** Сбросить фильтр (объект потерян или новый объект) */
    fun reset() {
        x = 0f
        v = 0f
        p = 1f
        lastUpdateTime = System.currentTimeMillis()
    }
    
    /** Текущее сглаженное положение */
    val position: Float get() = x
    
    /** Текущая оценка скорости (пикселей/сек) */
    val velocity: Float get() = v
    
    /** Уверенность фильтра (обратно пропорциональна ковариации) */
    val confidence: Float get() = 1f / (1f + p)
}

/**
 * Двумерный Kalman фильтр для координат (x, y).
 * Использует два независимых 1D фильтра.
 */
class KalmanFilter2D(
    processNoise: Float = 0.01f,
    measurementNoise: Float = 0.1f
) {
    private val filterX = SimpleKalmanFilter(processNoise, measurementNoise)
    private val filterY = SimpleKalmanFilter(processNoise, measurementNoise)
    private val filterW = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 2f) // Меньше меняется
    private val filterH = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 2f)
    
    /**
     * Обновить фильтр с новым bounding box.
     * @return Сглаженный DetectionResult с координатами в пределах 0..1
     */
    fun update(detection: DetectionResult, dt: Float = 0f): DetectionResult {
        val cx = filterX.update(detection.cx, dt).coerceIn(0f, 1f)
        val cy = filterY.update(detection.cy, dt).coerceIn(0f, 1f)
        val w = filterW.update(detection.w, dt).coerceIn(0f, 1f)
        val h = filterH.update(detection.h, dt).coerceIn(0f, 1f)
        
        // Также ограничиваем размеры чтобы bbox не выходил за границы
        val maxW = 2f * minOf(cx, 1f - cx)
        val maxH = 2f * minOf(cy, 1f - cy)
        val clampedW = w.coerceAtMost(maxW)
        val clampedH = h.coerceAtMost(maxH)
        
        return detection.copy(cx = cx, cy = cy, w = clampedW, h = clampedH)
    }
    
    /** Сбросить все фильтры */
    fun reset() {
        filterX.reset()
        filterY.reset()
        filterW.reset()
        filterH.reset()
    }
    
    /** Общая уверенность фильтра (среднее по всем измерениям) */
    val confidence: Float get() = 
        (filterX.confidence + filterY.confidence + filterW.confidence + filterH.confidence) / 4f
}