package org.rhanet.roverctrl.data

/**
 * Результат детекции объекта или лазерной точки.
 * Используется ObjectTracker'ом и LaserTracker'ом.
 * 
 * Координаты нормализованы в [0, 1] относительно изображения:
 * - (0, 0) — левый верхний угол
 * - (1, 1) — правый нижний угол
 */
data class DetectionResult(
    /** X-координата центра (0..1) */
    val cx: Float,
    
    /** Y-координата центра (0..1) */
    val cy: Float,
    
    /** Ширина bounding box (0..1) */
    val w: Float,
    
    /** Высота bounding box (0..1) */
    val h: Float,
    
    /** Уверенность детекции (0..1) */
    val confidence: Float,
    
    /** Метка класса ("person", "cat", "laser", etc.) */
    val label: String
) {
    /** Альтернативный конструктор для лазерной точки (w=h=0) */
    constructor(cx: Float, cy: Float, confidence: Float, label: String) : 
        this(cx, cy, 0f, 0f, confidence, label)
    
    /** Левая граница bounding box */
    val left: Float get() = cx - w / 2
    
    /** Правая граница bounding box */
    val right: Float get() = cx + w / 2
    
    /** Верхняя граница bounding box */
    val top: Float get() = cy - h / 2
    
    /** Нижняя граница bounding box */
    val bottom: Float get() = cy + h / 2
    
    /** Проверка, является ли детекция лазерной точкой (w=h=0) */
    val isLaser: Boolean get() = w == 0f && h == 0f
    
    /** Проверка, является ли детекция bounding box'ом */
    val isBoundingBox: Boolean get() = w > 0f && h > 0f
    
    /** Создаёт копию с изменёнными полями (удобно для сглаживания) */
    fun copy(
        cx: Float = this.cx,
        cy: Float = this.cy,
        w: Float = this.w,
        h: Float = this.h,
        confidence: Float = this.confidence,
        label: String = this.label
    ): DetectionResult = DetectionResult(cx, cy, w, h, confidence, label)
}