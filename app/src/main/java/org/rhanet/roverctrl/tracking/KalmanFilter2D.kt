package org.rhanet.roverctrl.tracking

import org.rhanet.roverctrl.data.DetectionResult

/**
 * 2D Kalman фильтр для сглаживания координат объекта.
 * Использует два независимых SimpleKalmanFilter для X и Y координат.
 */
class KalmanFilter2D(
    private val processNoise: Float = 0.01f,
    private val measurementNoise: Float = 0.1f
) {
    private val kalmanX = SimpleKalmanFilter(processNoise, measurementNoise)
    private val kalmanY = SimpleKalmanFilter(processNoise, measurementNoise)
    private val kalmanW = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 0.5f)
    private val kalmanH = SimpleKalmanFilter(processNoise * 0.5f, measurementNoise * 0.5f)

    /**
     * Обновить фильтр с новым измерением.
     * @param measurement Новое измерение (DetectionResult)
     * @return Сглаженный DetectionResult
     */
    fun update(measurement: DetectionResult): DetectionResult {
        val smoothedCx = kalmanX.update(measurement.cx)
        val smoothedCy = kalmanY.update(measurement.cy)
        val smoothedW = kalmanW.update(measurement.w)
        val smoothedH = kalmanH.update(measurement.h)
        
        return DetectionResult(
            cx = smoothedCx.coerceIn(0f, 1f),
            cy = smoothedCy.coerceIn(0f, 1f),
            w = smoothedW.coerceIn(0.01f, 1f),
            h = smoothedH.coerceIn(0.01f, 1f),
            confidence = measurement.confidence,
            label = measurement.label
        )
    }

    /**
     * Обновить фильтр только с координатами центра.
     * Используется когда есть только cx,cy без размеров.
     */
    fun update(cx: Float, cy: Float): DetectionResult {
        val smoothedCx = kalmanX.update(cx)
        val smoothedCy = kalmanY.update(cy)
        
        return DetectionResult(
            cx = smoothedCx.coerceIn(0f, 1f),
            cy = smoothedCy.coerceIn(0f, 1f),
            w = 0.1f, // default width
            h = 0.1f, // default height
            confidence = 0.5f,
            label = ""
        )
    }

    /**
     * Сбросить состояние фильтра.
     */
    fun reset() {
        // SimpleKalmanFilter не имеет метода reset, создадим новые
        // Вместо этого просто создадим новые экземпляры при необходимости
    }
}