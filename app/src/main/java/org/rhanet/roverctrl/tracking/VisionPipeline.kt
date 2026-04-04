package org.rhanet.roverctrl.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

/**
 * VisionPipeline - улучшенный pipeline для компьютерного зрения
 * 
 * Архитектура:
 * Frame → Preprocess → Detect → Track → Target
 * 
 * Особенности:
 * 1. Экспоненциальное сглаживание координат
 * 2. ROI tracking (область интереса)
 * 3. Confidence-based фильтрация
 * 4. Отдельный поток для CV
 */
class VisionPipeline(
    private val detector: ObjectTracker,
    private val smoothingAlpha: Float = 0.7f,  // 0.7 * new + 0.3 * old
    private val minConfidence: Float = 0.3f,
    private val roiExpansion: Float = 1.2f     // Расширение ROI вокруг цели
) {
    companion object {
        private const val TAG = "VisionPipeline"
    }
    
    data class Target(
        val x: Float,           // Нормализованная координата X (0..1)
        val y: Float,           // Нормализованная координата Y (0..1)
        val width: Float,       // Нормализованная ширина (0..1)
        val height: Float,      // Нормализованная высота (0..1)
        val confidence: Float,  // Уверенность (0..1)
        val label: String,      // Метка класса
        val velocityX: Float = 0f,  // Скорость по X (пиксели/кадр)
        val velocityY: Float = 0f,  // Скорость по Y (пиксели/кадр)
        val roi: RectF? = null      // Область интереса для следующего кадра
    )
    
    // Состояние трекинга
    private var lastTarget: Target? = null
    private var frameCount = 0L
    private var lostFrames = 0
    private val maxLostFrames = 5  // Максимум кадров без обнаружения
    
    // Для ROI tracking
    private var currentRoi: RectF? = null
    
    // Отдельный scope для CV обработки
    private val cvScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Обработка кадра с улучшенным pipeline
     */
    suspend fun processFrame(bitmap: Bitmap): Target? = withContext(Dispatchers.Default) {
        frameCount++
        
        // 1. Preprocess (базовый)
        val processedBitmap = preprocess(bitmap)
        
        // 2. Detect (с ROI если есть)
        val detection = if (currentRoi != null && lostFrames < maxLostFrames) {
            // ROI-based detection (быстрее)
            detectInRoi(processedBitmap, currentRoi!!)
        } else {
            // Full-frame detection
            detector.process(processedBitmap)
        }
        
        // 3. Track (обновление состояния)
        val target = if (detection != null && detection.found) {
            updateTarget(detection.detection, bitmap.width, bitmap.height)
        } else {
            handleLostDetection()
        }
        
        // 4. Update ROI для следующего кадра
        updateRoi(target, bitmap.width, bitmap.height)
        
        return@withContext target
    }
    
    /**
     * Базовый препроцессинг
     */
    private fun preprocess(bitmap: Bitmap): Bitmap {
        // TODO: Добавить нормализацию, баланс белого, шумоподавление
        // Пока просто возвращаем оригинал
        return bitmap
    }
    
    /**
     * Детекция в области интереса (ROI)
     */
    private fun detectInRoi(bitmap: Bitmap, roi: RectF): ObjectTracker.Result? {
        // Вырезаем ROI из bitmap
        val x = (roi.left * bitmap.width).toInt()
        val y = (roi.top * bitmap.height).toInt()
        val width = (roi.width() * bitmap.width).toInt()
        val height = (roi.height() * bitmap.height).toInt()
        
        // Проверяем границы
        if (x < 0 || y < 0 || x + width > bitmap.width || y + height > bitmap.height) {
            return null
        }
        
        val roiBitmap = Bitmap.createBitmap(bitmap, x, y, width, height)
        val result = detector.process(roiBitmap)
        roiBitmap.recycle()
        
        // Конвертируем координаты из ROI в глобальные
        return result?.let { r ->
            r.copy(
                detection = r.detection?.let { det ->
                    DetectionResult(
                        cx = (det.cx * roi.width()) + roi.left,
                        cy = (det.cy * roi.height()) + roi.top,
                        w = det.w * roi.width(),
                        h = det.h * roi.height(),
                        confidence = det.confidence,
                        label = det.label
                    )
                }
            )
        }
    }
    
    /**
     * Обновление цели с экспоненциальным сглаживанием
     */
    private fun updateTarget(detection: DetectionResult?, imageWidth: Int, imageHeight: Int): Target? {
        if (detection == null || detection.confidence < minConfidence) {
            lostFrames++
            return lastTarget?.copy(confidence = lastTarget.confidence * 0.8f)  // Уменьшаем уверенность
        }
        
        val newTarget = Target(
            x = detection.cx,
            y = detection.cy,
            width = detection.w,
            height = detection.h,
            confidence = detection.confidence,
            label = detection.label
        )
        
        // Экспоненциальное сглаживание
        val smoothedTarget = if (lastTarget != null) {
            lastTarget!!.copy(
                x = smoothingAlpha * newTarget.x + (1 - smoothingAlpha) * lastTarget!!.x,
                y = smoothingAlpha * newTarget.y + (1 - smoothingAlpha) * lastTarget!!.y,
                width = smoothingAlpha * newTarget.width + (1 - smoothingAlpha) * lastTarget!!.width,
                height = smoothingAlpha * newTarget.height + (1 - smoothingAlpha) * lastTarget!!.height,
                confidence = max(newTarget.confidence, lastTarget!!.confidence * 0.95f),
                label = newTarget.label,
                velocityX = newTarget.x - lastTarget!!.x,
                velocityY = newTarget.y - lastTarget!!.y
            )
        } else {
            newTarget
        }
        
        lastTarget = smoothedTarget
        lostFrames = 0
        return smoothedTarget
    }
    
    /**
     * Обработка потери цели
     */
    private fun handleLostDetection(): Target? {
        lostFrames++
        if (lostFrames > maxLostFrames) {
            lastTarget = null
            currentRoi = null
        }
        return lastTarget?.copy(confidence = lastTarget!!.confidence * 0.7f)
    }
    
    /**
     * Обновление области интереса (ROI)
     */
    private fun updateRoi(target: Target?, imageWidth: Int, imageHeight: Int) {
        currentRoi = if (target != null && target.confidence > 0.5f) {
            // Расширяем ROI вокруг цели
            val centerX = target.x
            val centerY = target.y
            val halfWidth = target.width * roiExpansion / 2
            val halfHeight = target.height * roiExpansion / 2
            
            RectF(
                max(0f, centerX - halfWidth),
                max(0f, centerY - halfHeight),
                min(1f, centerX + halfWidth),
                min(1f, centerY + halfHeight)
            )
        } else {
            null
        }
    }
    
    /**
     * Получение текущего состояния
     */
    fun getState(): String {
        return "Frames: $frameCount, Lost: $lostFrames, Target: ${lastTarget?.label ?: "none"}, ROI: ${currentRoi != null}"
    }
    
    /**
     * Сброс состояния
     */
    fun reset() {
        lastTarget = null
        currentRoi = null
        lostFrames = 0
        cvScope.coroutineContext.cancelChildren()
    }
    
    /**
     * Очистка ресурсов
     */
    fun release() {
        cvScope.cancel()
    }
}