package org.rhanet.roverctrl.ui.control

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.rhanet.roverctrl.tracking.OdometryTracker
import kotlin.math.cos
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────────────────
// RoverVizView — 2D визуализация одометрии ровера
//
// Рисует трек (history) и текущую позицию/направление ровера.
// Масштабирование автоматическое (fit track in view).
// ──────────────────────────────────────────────────────────────────────────

class RoverVizView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var odometry: OdometryTracker? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 24f
    }

    fun setOdometry(odom: OdometryTracker) {
        this.odometry = odom
    }

    fun refresh() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val odom = odometry ?: return
        val track = odom.track
        val pose = odom.pose

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Определяем масштаб по экстремумам трека
        var maxDist = 1f  // минимум 1 метр
        for (p in track) {
            maxDist = maxOf(maxDist,
                kotlin.math.abs(p.x),
                kotlin.math.abs(p.y))
        }
        maxDist = maxOf(maxDist,
            kotlin.math.abs(pose.x),
            kotlin.math.abs(pose.y))

        val scale = (minOf(w, h) / 2f - 40f) / (maxDist * 1.1f)

        // Сетка (каждые 0.5 метра)
        val gridStep = 0.5f
        val gridPixels = gridStep * scale
        if (gridPixels > 20f) {
            var g = gridPixels
            while (g < maxOf(w, h)) {
                canvas.drawLine(cx + g, 0f, cx + g, h, gridPaint)
                canvas.drawLine(cx - g, 0f, cx - g, h, gridPaint)
                canvas.drawLine(0f, cy + g, w, cy + g, gridPaint)
                canvas.drawLine(0f, cy - g, w, cy - g, gridPaint)
                g += gridPixels
            }
        }

        // Оси
        canvas.drawLine(0f, cy, w, cy, gridPaint)
        canvas.drawLine(cx, 0f, cx, h, gridPaint)

        // Трек
        if (track.size >= 2) {
            val path = Path()
            val first = track[0]
            path.moveTo(cx + first.x * scale, cy - first.y * scale)
            for (i in 1 until track.size) {
                val p = track[i]
                path.lineTo(cx + p.x * scale, cy - p.y * scale)
            }
            canvas.drawPath(path, trackPaint)
        }

        // Начало координат
        canvas.drawCircle(cx, cy, 5f, originPaint)

        // Текущая позиция ровера
        val rx = cx + pose.x * scale
        val ry = cy - pose.y * scale
        canvas.drawCircle(rx, ry, 8f, roverPaint)

        // Направление (heading)
        val hLen = 20f
        val hx = rx + hLen * cos(pose.headingRad).toFloat()
        val hy = ry - hLen * sin(pose.headingRad).toFloat()
        canvas.drawLine(rx, ry, hx, hy, headingPaint)

        // Инфо-текст
        val info = String.format("%.2fm  H:%.0f°",
            odom.distanceMeters,
            Math.toDegrees(pose.headingRad.toDouble()))
        canvas.drawText(info, 10f, h - 10f, textPaint)
    }
}
