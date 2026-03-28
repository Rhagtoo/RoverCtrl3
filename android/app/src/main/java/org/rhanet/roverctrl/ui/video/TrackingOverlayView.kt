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
// TrackingOverlayView
//
// Рисует:
//   - Красную точку по центру кадра (всегда)
//   - Если w/h > 0: bounding box (прямоугольник) + label  ← YOLO
//   - Если w/h == 0: crosshair + circle + label            ← Laser
// ──────────────────────────────────────────────────────────────────────────

class TrackingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var detection: DetectionResult? = null
        set(value) { field = value; postInvalidate() }

    var trackingActive = false
        set(value) { field = value; postInvalidate() }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Центр кадра — всегда виден
        canvas.drawCircle(width / 2f, height / 2f, 4f, centerDot)

        val det = detection ?: return
        if (!trackingActive) return

        val px = det.cx * width
        val py = det.cy * height

        val hasBbox = det.w > 0.01f && det.h > 0.01f

        if (hasBbox) {
            // ── YOLO bounding box ──────────────────────────────────────
            val bw = det.w * width
            val bh = det.h * height
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
            else "${(det.confidence * 100).toInt()}%"
            canvas.drawText(label, px + arm + 8, py - 8, textPaint)
        }
    }
}
