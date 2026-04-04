package org.rhanet.roverctrl.ui.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.rhanet.roverctrl.data.DetectionResult

// ──────────────────────────────────────────────────────────────────────────
// TrackingOverlayView v2.5
//
// Рисует:
// - Красную точку по центру кадра (всегда)
// - Если w/h > 0: bounding box (прямоугольник) + label ← YOLO
// - Если w/h == 0: crosshair + circle + label ← Laser
//
// FIX v2.5: fitCenter letterbox компенсация.
// Когда sourceImageWidth/Height заданы, координаты маппятся на
// реальную область изображения внутри view (как ImageView fitCenter),
// а не на всю view. Это исправляет bbox на полный экран при XIAO swap.
// Для камеры телефона (sourceImage = 0/0) — весь view как раньше.
// ──────────────────────────────────────────────────────────────────────────

class TrackingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var detection: DetectionResult? = null
        set(value) { field = value; postInvalidate() }

    var trackingActive = false
        set(value) { field = value; postInvalidate() }

    // ── Источник изображения (для fitCenter компенсации) ─────────────
    // Размер исходного кадра (например XIAO MJPEG 480×320).
    // Overlay вычислит fitCenter rect и маппит нормализованные координаты
    // только на него, а не на весь view (включая чёрные полосы).
    // Если 0/0 — используется вся область view (камера телефона).
    var sourceImageWidth: Int = 0
        set(value) { field = value; imageRectDirty = true; postInvalidate() }
    var sourceImageHeight: Int = 0
        set(value) { field = value; imageRectDirty = true; postInvalidate() }

    private val imageRect = RectF() // fitCenter rect внутри view
    private var imageRectDirty = true

    private val bboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 30f
        style = Paint.Style.FILL
    }
    private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }

    private val tmpRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        imageRectDirty = true
    }

    /**
     * Вычислить fitCenter rect — область внутри view, куда ImageView
     * реально отрисовывает изображение (с сохранением aspect ratio).
     * Чёрные полосы = зазор между imageRect и границами view.
     */
    private fun updateImageRect() {
        if (!imageRectDirty) return
        imageRectDirty = false

        val vw = width.toFloat()
        val vh = height.toFloat()

        if (sourceImageWidth <= 0 || sourceImageHeight <= 0 || vw <= 0f || vh <= 0f) {
            // Нет данных об источнике → весь view (камера телефона / PreviewView)
            imageRect.set(0f, 0f, vw, vh)
            return
        }

        val imgAspect = sourceImageWidth.toFloat() / sourceImageHeight
        val viewAspect = vw / vh

        if (imgAspect > viewAspect) {
            // Изображение шире view → чёрные полосы сверху/снизу
            val drawW = vw
            val drawH = vw / imgAspect
            val offsetY = (vh - drawH) / 2f
            imageRect.set(0f, offsetY, drawW, offsetY + drawH)
        } else {
            // Изображение уже view → чёрные полосы слева/справа
            val drawH = vh
            val drawW = vh * imgAspect
            val offsetX = (vw - drawW) / 2f
            imageRect.set(offsetX, 0f, offsetX + drawW, drawH)
        }
    }

    /** Map normalized X (0..1) → pixel X внутри imageRect */
    private fun mapX(normX: Float): Float = imageRect.left + normX * imageRect.width()
    /** Map normalized Y (0..1) → pixel Y внутри imageRect */
    private fun mapY(normY: Float): Float = imageRect.top + normY * imageRect.height()
    /** Map normalized W (0..1) → pixel width внутри imageRect */
    private fun mapW(normW: Float): Float = normW * imageRect.width()
    /** Map normalized H (0..1) → pixel height внутри imageRect */
    private fun mapH(normH: Float): Float = normH * imageRect.height()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateImageRect()

        // Центр изображения — всегда виден (в центре imageRect, не view!)
        val centerX = imageRect.centerX()
        val centerY = imageRect.centerY()
        canvas.drawCircle(centerX, centerY, 4f, centerDot)

        val det = detection ?: return
        if (!trackingActive) return

        val px = mapX(det.cx)
        val py = mapY(det.cy)

        val hasBbox = det.w > 0.01f && det.h > 0.01f

        if (hasBbox) {
            // ── YOLO bounding box ──────────────────────────────────────
            val bw = mapW(det.w)
            val bh = mapH(det.h)
            tmpRect.set(px - bw / 2f, py - bh / 2f, px + bw / 2f, py + bh / 2f)
            canvas.drawRect(tmpRect, bboxPaint)

            // Маленький крест по центру bbox
            val arm = 10f
            canvas.drawLine(px - arm, py, px + arm, py, crossPaint)
            canvas.drawLine(px, py - arm, px, py + arm, crossPaint)

            // Label над bbox
            val label = "${det.label} ${(det.confidence * 100).toInt()}%"
            val textW = textPaint.measureText(label)
            val labelX = tmpRect.left
            val labelY = tmpRect.top - 6f

            // Фон под текст
            canvas.drawRect(labelX - 2f, labelY - 28f, labelX + textW + 6f, labelY + 4f, textBgPaint)
            canvas.drawText(label, labelX + 2f, labelY, textPaint)

        } else {
            // ── Laser crosshair ────────────────────────────────────────
            val arm = 30f
            canvas.drawLine(px - arm, py, px + arm, py, crossPaint)
            canvas.drawLine(px, py - arm, px, py + arm, crossPaint)
            canvas.drawCircle(px, py, arm * 1.5f, circlePaint)

            val label = if (det.label.isNotEmpty())
                "${det.label} ${(det.confidence * 100).toInt()}%"
            else
                "${(det.confidence * 100).toInt()}%"
            canvas.drawText(label, px + arm + 8, py - 8, textPaint)
        }
    }
}