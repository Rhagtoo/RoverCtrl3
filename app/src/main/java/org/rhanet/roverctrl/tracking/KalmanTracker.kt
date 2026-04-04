package org.rhanet.roverctrl.tracking

import android.graphics.RectF
import kotlin.math.pow

/**
 * Kalman Filter для трекинга объектов
 * 
 * Состояние: [x, y, vx, vy, width, height]
 * Измерение: [x, y, width, height]
 * 
 * Особенности:
 * 1. Предсказание положения между кадрами
 * 2. Сглаживание шумов измерений
 * 3. Оценка скорости движения
 * 4. Адаптация к изменению размера объекта
 */
class KalmanTracker(
    private val processNoise: Float = 0.01f,    // Шум процесса (движение)
    private val measurementNoise: Float = 0.1f, // Шум измерений (детектор)
    private val initialUncertainty: Float = 1.0f
) {
    companion object {
        private const val STATE_DIM = 6   // x, y, vx, vy, w, h
        private const val MEAS_DIM = 4    // x, y, w, h
    }
    
    data class TrackedObject(
        val x: Float,           // Нормализованная X координата
        val y: Float,           // Нормализованная Y координата
        val velocityX: Float,   // Скорость по X (норм./кадр)
        val velocityY: Float,   // Скорость по Y (норм./кадр)
        val width: Float,       // Нормализованная ширина
        val height: Float,      // Нормализованная высота
        val confidence: Float,  // Уверенность трекера (0..1)
        val age: Int = 0        // Возраст трека (кадры)
    )
    
    // Состояние фильтра Калмана
    private var state = FloatArray(STATE_DIM)  // [x, y, vx, vy, w, h]
    private var covariance = FloatArray(STATE_DIM * STATE_DIM)
    
    // Матрицы фильтра
    private val F = FloatArray(STATE_DIM * STATE_DIM)  // State transition
    private val H = FloatArray(MEAS_DIM * STATE_DIM)   // Measurement
    private val Q = FloatArray(STATE_DIM * STATE_DIM)  // Process noise
    private val R = FloatArray(MEAS_DIM * MEAS_DIM)    // Measurement noise
    private val P = FloatArray(STATE_DIM * STATE_DIM)  // Estimate covariance
    
    private var isInitialized = false
    private var framesSinceUpdate = 0
    private val maxFramesWithoutUpdate = 10
    
    init {
        initializeMatrices()
    }
    
    /**
     * Инициализация матриц фильтра
     */
    private fun initializeMatrices() {
        // State transition matrix F (линейное движение)
        // x' = x + vx*dt, y' = y + vy*dt, vx' = vx, vy' = vy, w' = w, h' = h
        for (i in 0 until STATE_DIM) {
            F[i * STATE_DIM + i] = 1.0f
        }
        F[0 * STATE_DIM + 2] = 1.0f  // x += vx
        F[1 * STATE_DIM + 3] = 1.0f  // y += vy
        
        // Measurement matrix H (измеряем только позицию и размер)
        H[0 * STATE_DIM + 0] = 1.0f  // измеряем x
        H[1 * STATE_DIM + 1] = 1.0f  // измеряем y
        H[2 * STATE_DIM + 4] = 1.0f  // измеряем width
        H[3 * STATE_DIM + 5] = 1.0f  // измеряем height
        
        // Process noise covariance Q
        for (i in 0 until STATE_DIM) {
            Q[i * STATE_DIM + i] = processNoise
        }
        
        // Measurement noise covariance R
        for (i in 0 until MEAS_DIM) {
            R[i * MEAS_DIM + i] = measurementNoise
        }
        
        // Initial estimate covariance P
        for (i in 0 until STATE_DIM) {
            P[i * STATE_DIM + i] = initialUncertainty
        }
    }
    
    /**
     * Инициализация или сброс трекера
     */
    fun initialize(measurement: FloatArray) {
        require(measurement.size == MEAS_DIM) { "Measurement must have size $MEAS_DIM" }
        
        // Инициализация состояния
        state[0] = measurement[0]  // x
        state[1] = measurement[1]  // y
        state[2] = 0f              // vx
        state[3] = 0f              // vy
        state[4] = measurement[2]  // width
        state[5] = measurement[3]  // height
        
        // Сброс ковариации
        covariance = P.copyOf()
        
        isInitialized = true
        framesSinceUpdate = 0
    }
    
    /**
     * Предсказание следующего состояния
     */
    fun predict(): TrackedObject {
        if (!isInitialized) {
            throw IllegalStateException("Kalman filter not initialized")
        }
        
        // Предсказание состояния: x' = F * x
        val predictedState = FloatArray(STATE_DIM)
        for (i in 0 until STATE_DIM) {
            var sum = 0f
            for (j in 0 until STATE_DIM) {
                sum += F[i * STATE_DIM + j] * state[j]
            }
            predictedState[i] = sum
        }
        
        // Предсказание ковариации: P' = F * P * F^T + Q
        val temp = FloatArray(STATE_DIM * STATE_DIM)
        val predictedCov = FloatArray(STATE_DIM * STATE_DIM)
        
        // temp = F * P
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += F[i * STATE_DIM + k] * covariance[k * STATE_DIM + j]
                }
                temp[i * STATE_DIM + j] = sum
            }
        }
        
        // predictedCov = temp * F^T + Q
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += temp[i * STATE_DIM + k] * F[j * STATE_DIM + k]  // F^T
                }
                predictedCov[i * STATE_DIM + j] = sum + Q[i * STATE_DIM + j]
            }
        }
        
        // Обновление состояния
        state = predictedState
        covariance = predictedCov
        framesSinceUpdate++
        
        return getCurrentEstimate()
    }
    
    /**
     * Коррекция на основе измерения
     */
    fun update(measurement: FloatArray, measurementConfidence: Float = 1.0f): TrackedObject {
        if (!isInitialized) {
            initialize(measurement)
            return getCurrentEstimate()
        }
        
        require(measurement.size == MEAS_DIM) { "Measurement must have size $MEAS_DIM" }
        
        // Адаптивный шум измерений на основе confidence
        val adaptiveR = R.copyOf()
        val confidenceFactor = 1.0f / max(0.1f, measurementConfidence)
        for (i in 0 until MEAS_DIM) {
            adaptiveR[i * MEAS_DIM + i] = R[i * MEAS_DIM + i] * confidenceFactor
        }
        
        // Вычисление innovation: y = z - H * x
        val innovation = FloatArray(MEAS_DIM)
        for (i in 0 until MEAS_DIM) {
            var sum = 0f
            for (j in 0 until STATE_DIM) {
                sum += H[i * STATE_DIM + j] * state[j]
            }
            innovation[i] = measurement[i] - sum
        }
        
        // Innovation covariance: S = H * P * H^T + R
        val S = FloatArray(MEAS_DIM * MEAS_DIM)
        
        // temp = H * P
        val tempHP = FloatArray(MEAS_DIM * STATE_DIM)
        for (i in 0 until MEAS_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += H[i * STATE_DIM + k] * covariance[k * STATE_DIM + j]
                }
                tempHP[i * STATE_DIM + j] = sum
            }
        }
        
        // S = tempHP * H^T + R
        for (i in 0 until MEAS_DIM) {
            for (j in 0 until MEAS_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += tempHP[i * STATE_DIM + k] * H[j * STATE_DIM + k]  // H^T
                }
                S[i * MEAS_DIM + j] = sum + adaptiveR[i * MEAS_DIM + j]
            }
        }
        
        // Kalman gain: K = P * H^T * S^-1
        // Для простоты используем упрощённый расчёт
        val K = FloatArray(STATE_DIM * MEAS_DIM)
        
        // Упрощённый расчёт: K = P * H^T * (S^-1 диагональная)
        for (i in 0 until STATE_DIM) {
            for (j in 0 until MEAS_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += covariance[i * STATE_DIM + k] * H[j * STATE_DIM + k]  // H^T
                }
                K[i * MEAS_DIM + j] = sum / S[j * MEAS_DIM + j]  // Диагональное S^-1
            }
        }
        
        // Обновление состояния: x = x + K * y
        for (i in 0 until STATE_DIM) {
            var sum = 0f
            for (j in 0 until MEAS_DIM) {
                sum += K[i * MEAS_DIM + j] * innovation[j]
            }
            state[i] += sum
        }
        
        // Обновление ковариации: P = (I - K * H) * P
        val IminusKH = FloatArray(STATE_DIM * STATE_DIM)
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = 0f
                for (k in 0 until MEAS_DIM) {
                    sum += K[i * MEAS_DIM + k] * H[k * STATE_DIM + j]
                }
                IminusKH[i * STATE_DIM + j] = (if (i == j) 1.0f else 0.0f) - sum
            }
        }
        
        val newCov = FloatArray(STATE_DIM * STATE_DIM)
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = 0f
                for (k in 0 until STATE_DIM) {
                    sum += IminusKH[i * STATE_DIM + k] * covariance[k * STATE_DIM + j]
                }
                newCov[i * STATE_DIM + j] = sum
            }
        }
        
        covariance = newCov
        framesSinceUpdate = 0
        
        return getCurrentEstimate()
    }
    
    /**
     * Получение текущей оценки
     */
    fun getCurrentEstimate(): TrackedObject {
        if (!isInitialized) {
            throw IllegalStateException("Kalman filter not initialized")
        }
        
        // Вычисление confidence на основе ковариации и времени без обновлений
        val positionVariance = covariance[0] + covariance[STATE_DIM + 1]
        val baseConfidence = 1.0f / (1.0f + positionVariance)
        
        val timePenalty = if (framesSinceUpdate > 0) {
            0.9f.pow(framesSinceUpdate.toFloat())
        } else {
            1.0f
        }
        
        val confidence = baseConfidence * timePenalty
        
        return TrackedObject(
            x = state[0],
            y = state[1],
            velocityX = state[2],
            velocityY = state[3],
            width = state[4],
            height = state[5],
            confidence = confidence.coerceIn(0f, 1f),
            age = framesSinceUpdate
        )
    }
    
    /**
     * Проверка, активен ли трекер
     */
    fun isActive(): Boolean {
        return isInitialized && framesSinceUpdate < maxFramesWithoutUpdate
    }
    
    /**
     * Получение ROI для следующего кадра
     */
    fun getPredictedRoi(expansion: Float = 1.5f): RectF {
        val estimate = getCurrentEstimate()
        val halfWidth = estimate.width * expansion / 2
        val halfHeight = estimate.height * expansion / 2
        
        // Предсказание позиции с учётом скорости
        val predictedX = estimate.x + estimate.velocityX
        val predictedY = estimate.y + estimate.velocityY
        
        return RectF(
            maxOf(0f, predictedX - halfWidth),
            maxOf(0f, predictedY - halfHeight),
            minOf(1f, predictedX + halfWidth),
            minOf(1f, predictedY + halfHeight)
        )
    }
    
    /**
     * Сброс трекера
     */
    fun reset() {
        isInitialized = false
        state = FloatArray(STATE_DIM)
        covariance = FloatArray(STATE_DIM * STATE_DIM)
        framesSinceUpdate = 0
    }
}