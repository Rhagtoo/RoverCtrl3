package org.rhanet.roverctrl.tracking

import org.rhanet.roverctrl.data.DetectionResult

/**
 * Простой фильтр сглаживания для координат детекции
 * 
 * Особенности:
 * 1. Экспоненциальное сглаживание (EMA)
 * 2. Адаптивная альфа на основе confidence
 * 3. Фильтрация по минимальному confidence
 * 4. Сброс при длительном отсутствии детекции
 */
class SmoothingFilter(
    private val smoothingAlpha: Float = 0.7f,  // Базовый коэффициент сглаживания
    private val minConfidence: Float = 0.3f,   // Минимальный confidence для учёта
    private val maxLostFrames: Int = 10        // Максимум кадров без детекции до сброса
) {
    
    private var lastDetection: DetectionResult? = null
    private var lostFrames = 0
    private var frameCount = 0L
    
    /**
     * Применить сглаживание к новому обнаружению
     */
    fun smooth(newDetection: DetectionResult?): DetectionResult? {
        frameCount++
        
        if (newDetection == null || newDetection.confidence < minConfidence) {
            lostFrames++
            if (lostFrames > maxLostFrames) {
                lastDetection = null
            }
            return lastDetection?.copy(confidence = lastDetection!!.confidence * 0.8f)
        }
        
        // Адаптивная альфа: больше confidence → меньше сглаживания
        val adaptiveAlpha = smoothingAlpha * (1 - newDetection.confidence * 0.5f)
        
        val smoothed = if (lastDetection != null) {
            lastDetection!!.copy(
                cx = adaptiveAlpha * newDetection.cx + (1 - adaptiveAlpha) * lastDetection!!.cx,
                cy = adaptiveAlpha * newDetection.cy + (1 - adaptiveAlpha) * lastDetection!!.cy,
                w = adaptiveAlpha * newDetection.w + (1 - adaptiveAlpha) * lastDetection!!.w,
                h = adaptiveAlpha * newDetection.h + (1 - adaptiveAlpha) * lastDetection!!.h,
                confidence = maxOf(newDetection.confidence, lastDetection!!.confidence * 0.95f),
                label = newDetection.label
            )
        } else {
            newDetection
        }
        
        lastDetection = smoothed
        lostFrames = 0
        return smoothed
    }
    
    /**
     * Получить текущее сглаженное состояние
     */
    fun getCurrentState(): DetectionResult? = lastDetection
    
    /**
     * Получить статистику
     */
    fun getStats(): String = "Frames: $frameCount, Lost: $lostFrames, Active: ${lastDetection != null}"
    
    /**
     * Сброс фильтра
     */
    fun reset() {
        lastDetection = null
        lostFrames = 0
    }
}