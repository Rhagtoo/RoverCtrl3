package org.rhanet.roverctrl.tracking

import android.graphics.Bitmap
import android.graphics.Color
import org.rhanet.roverctrl.data.DetectionResult

/**
 * LaserTracker — детекция лазерной точки в кадре камеры
 *
 * ИСПРАВЛЕНИЯ:
 * 1. isBrightSpot() теперь использует локальный контраст вместо абсолютного порога
 * 2. Добавлена проверка на минимальный размер "яркого пятна" для фильтрации шума
 * 3. Увеличен порог яркости для белых пикселей (200 вместо 180)
 * 4. Добавлена проверка на окружение пикселя (лазер имеет характерное свечение)
 *
 * Алгоритм:
 *   1. Скалирует кадр до procW×procH
 *   2. HSV-маска красного + fallback на сверхъяркие пиксели
 *   3. Взвешенный центроид по яркости
 *   4. PID → pan/tilt delta (-100..100)
 */
class LaserTracker(
    // HSV диапазоны красного (Android Color.colorToHSV: hue 0..360)
    private val hueLo1: Float = 0f, private val hueHi1: Float = 35f,
    private val hueLo2: Float = 315f, private val hueHi2: Float = 360f,
    private val satMin: Float = 0.20f,
    private val valMin: Float = 0.50f,
    // Рабочий размер
    private val procW: Int = 320,
    private val procH: Int = 240,
    // PID
    pidKp: Float = 120f, pidKi: Float = 0.5f, pidKd: Float = 8f,
    // ИСПРАВЛЕНИЕ: параметры для улучшенной детекции
    private val brightSpotThreshold: Int = 200,  // повышен с 180
    private val minBrightPixelRatio: Float = 0.001f  // минимум 0.1% ярких пикселей
) {
    private val pidPan = PidController(kp = pidKp, ki = pidKi, kd = pidKd, outMax = 100f)
    private val pidTilt = PidController(kp = pidKp, ki = pidKi, kd = pidKd, outMax = 100f)

    // Буфер для подсчёта яркости окружения
    private var lastBrightCount = 0

    data class TrackResult(
        val found: Boolean,
        val panDelta: Float,
        val tiltDelta: Float,
        val detection: DetectionResult?
    )

    fun process(frame: Bitmap): TrackResult {
        val small = Bitmap.createScaledBitmap(frame, procW, procH, false)

        var sumX = 0.0
        var sumY = 0.0
        var sumW = 0.0
        val hsv = FloatArray(3)

        // Первый проход: подсчёт средней яркости для адаптивного порога
        var totalBrightness = 0.0
        val totalPixels = procW * procH
        
        // Получаем все пиксели за один JNI вызов
        val pixels = IntArray(totalPixels)
        small.getPixels(pixels, 0, procW, 0, 0, procW, procH)
        
        // Массив для хранения яркости пикселей (для второго прохода)
        val brightnessMap = FloatArray(totalPixels)
        var brightPixelCount = 0

        // Первый проход: анализ яркости
        for (idx in 0 until totalPixels) {
            val px = pixels[idx]
            Color.colorToHSV(px, hsv)
            brightnessMap[idx] = hsv[2]
            totalBrightness += hsv[2]

            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            if (r > brightSpotThreshold && g > brightSpotThreshold && b > brightSpotThreshold) {
                brightPixelCount++
            }
        }

        val avgBrightness = (totalBrightness / totalPixels).toFloat()
        lastBrightCount = brightPixelCount

        // ИСПРАВЛЕНИЕ: Если слишком много ярких пикселей — это не лазер, а яркая сцена
        val brightRatio = brightPixelCount.toFloat() / totalPixels
        if (brightRatio > 0.1f) {
            // >10% пикселей ярко-белые — скорее всего солнечный свет или лампа
            small.recycle()
            pidPan.reset()
            pidTilt.reset()
            return TrackResult(false, 0f, 0f, null)
        }

        // Второй проход: детекция лазера с адаптивным порогом
        for (idx in 0 until totalPixels) {
            val px = pixels[idx]
            val x = idx % procW
            val y = idx / procW
            
            Color.colorToHSV(px, hsv)

            val isLaser = isRedHsv(hsv) ||
                          isBrightRedRgb(px, hsv[2]) ||
                          isBrightSpotImproved(px, x, y, brightnessMap, avgBrightness)

            if (isLaser) {
                val w = hsv[2].toDouble()
                sumX += x * w
                sumY += y * w
                sumW += w
            }
        }
        small.recycle()

        // ИСПРАВЛЕНИЕ: требуем минимальное количество ярких пикселей
        val detectedRatio = sumW / totalPixels
        if (sumW < 1.0 || detectedRatio < minBrightPixelRatio) {
            pidPan.reset()
            pidTilt.reset()
            return TrackResult(false, 0f, 0f, null)
        }

        val cx = (sumX / sumW / procW).toFloat()
        val cy = (sumY / sumW / procH).toFloat()

        val errX = cx - 0.5f
        val errY = cy - 0.5f

        val panOut = pidPan.updateWithDeadband(errX)
        val tiltOut = pidTilt.updateWithDeadband(errY)

        return TrackResult(
            found = true,
            panDelta = panOut,
            tiltDelta = tiltOut,
            detection = DetectionResult(cx = cx, cy = cy, confidence = 1f, label = "laser")
        )
    }

    /**
     * Классический HSV-маска красного
     */
    private fun isRedHsv(hsv: FloatArray): Boolean {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        if (s < satMin || v < valMin) return false
        return (h in hueLo1..hueHi1) || (h in hueLo2..hueHi2)
    }

    /**
     * Fallback: красный канал доминирует (для далёкой лазерной точки)
     */
    private fun isBrightRedRgb(pixel: Int, value: Float): Boolean {
        if (value < 0.25f) return false
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r > 50 && (g < 5 || r.toFloat() / g > 1.6f) && (b < 5 || r.toFloat() / b > 1.6f)
    }

    /**
     * ИСПРАВЛЕНИЕ: Улучшенная детекция "яркого пятна"
     *
     * Вместо простого порога RGB > 180, проверяем:
     * 1. Пиксель должен быть значительно ярче среднего (локальный контраст)
     * 2. Повышен абсолютный порог до 200
     * 3. Пиксель должен выделяться на фоне окружения
     */
    private fun isBrightSpotImproved(
        pixel: Int,
        x: Int,
        y: Int,
        brightnessMap: FloatArray,
        avgBrightness: Float
    ): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        // 1. Абсолютный порог (повышен)
        if (r <= brightSpotThreshold || g <= brightSpotThreshold || b <= brightSpotThreshold) {
            return false
        }

        // 2. Проверка локального контраста
        // Пиксель должен быть значительно ярче среднего кадра
        val pixelBrightness = (r + g + b) / 3f / 255f
        if (pixelBrightness < avgBrightness + 0.3f) {
            return false
        }

        // 3. Проверка окружения: лазер создаёт "ореол"
        // Соседние пиксели тоже должны быть ярче среднего
        val neighborBrightness = getNeighborAvgBrightness(x, y, brightnessMap)
        if (neighborBrightness < avgBrightness + 0.15f) {
            // Одиночный яркий пиксель без ореола — вероятно шум или отражение
            return false
        }

        return true
    }

    /**
     * Средняя яркость соседних пикселей (3x3 окрестность)
     */
    private fun getNeighborAvgBrightness(x: Int, y: Int, brightnessMap: FloatArray): Float {
        var sum = 0f
        var count = 0

        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until procW && ny in 0 until procH) {
                    sum += brightnessMap[ny * procW + nx]
                    count++
                }
            }
        }

        return if (count > 0) sum / count else 0f
    }

    fun resetPid() {
        pidPan.reset()
        pidTilt.reset()
    }

    /**
     * Обновить kp после калибровки
     */
    fun updatePidGains(kpPan: Float, kpTilt: Float) {
        pidPan.kp = kpPan
        pidTilt.kp = kpTilt
        pidPan.reset()
        pidTilt.reset()
    }

    /**
     * Диагностика: количество ярких пикселей в последнем кадре
     */
    fun getLastBrightCount(): Int = lastBrightCount
}
