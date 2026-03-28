package org.rhanet.roverctrl.ui.control

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.rhanet.roverctrl.tracking.OdometryTracker
import kotlin.math.*

/**
 * RoverVizView — 2D карта одометрии ровера
 *
 * Функции:
 *   - Сетка с масштабом (автоподбор шага: 0.1м, 0.25м, 0.5м, 1м, 2м, 5м...)
 *   - Трек (цветовой градиент от старых точек к новым)
 *   - Текущая позиция + стрелка направления
 *   - Начальная точка (красная)
 *   - Статистика: дистанция, скорость, heading
 *   - Автомасштаб (fit track) + ручной pinch-to-zoom
 *   - Drag для перемещения
 */
class RoverVizView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var odometry: OdometryTracker? = null

    // ── Трансформация ────────────────────────────────────────────────────
    private var userScale = 1f            // ручной зум (pinch)
    private var autoScale = 100f          // пикселей на метр (авто)
    private var offsetX = 0f              // смещение карты (drag)
    private var offsetY = 0f
    private var autoCenter = true         // следить за ровером

    // ── Жесты ────────────────────────────────────────────────────────────
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                userScale = (userScale * det.scaleFactor).coerceIn(0.2f, 10f)
                autoCenter = false
                invalidate()
                return true
            }
        })

    // ── Paints ───────────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        textSize = 20f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
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
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val statsBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC111111")
        style = Paint.Style.FILL
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.SQUARE
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    // Цвета градиента трека
    private val trackColorOld = Color.parseColor("#33448AFF")
    private val trackColorNew = Color.parseColor("#FF00E676")

    fun setOdometry(odom: OdometryTracker) {
        this.odometry = odom
    }

    fun refresh() {
        invalidate()
    }

    /** Вернуть авто-центрирование (двойной тап) */
    fun recenter() {
        autoCenter = true
        userScale = 1f
        invalidate()
    }

    // ── Touch ────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || hypot(dx, dy) > 10f) {
                        isDragging = true
                        autoCenter = false
                        offsetX += dx
                        offsetY += dy
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // Двойной тап определяем отдельно если нужно
            }
        }
        return true
    }

    // ── Рисование ────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#111111"))

        val odom = odometry ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // ── Масштаб ──────────────────────────────────────────────────
        val track = odom.track
        if (autoCenter && track.isNotEmpty()) {
            autoScale = computeAutoScale(track, w, h)
        }
        val scale = autoScale * userScale  // пикселей на метр

        // ── Центр карты ──────────────────────────────────────────────
        val mapCx: Float
        val mapCy: Float
        if (autoCenter) {
            mapCx = cx - odom.pose.x * scale
            mapCy = cy + odom.pose.y * scale  // Y инвертирован (вверх = +Y в мире)
        } else {
            mapCx = cx + offsetX
            mapCy = cy + offsetY
        }

        // ── Сетка ────────────────────────────────────────────────────
        drawGrid(canvas, mapCx, mapCy, scale, w, h)

        // ── Оси ──────────────────────────────────────────────────────
        canvas.drawLine(0f, mapCy, w, mapCy, axisPaint)
        canvas.drawLine(mapCx, 0f, mapCx, h, axisPaint)

        // ── Начальная точка ──────────────────────────────────────────
        val ox = mapCx
        val oy = mapCy
        canvas.drawCircle(ox, oy, 6f, originPaint)

        // ── Трек (градиент) ──────────────────────────────────────────
        if (track.size >= 2) {
            val path = Path()
            val firstPx = worldToScreenX(track[0].x, mapCx, scale)
            val firstPy = worldToScreenY(track[0].y, mapCy, scale)
            path.moveTo(firstPx, firstPy)

            // Рисуем сегментами с градиентом цвета
            val total = track.size
            for (i in 1 until total) {
                val px = worldToScreenX(track[i].x, mapCx, scale)
                val py = worldToScreenY(track[i].y, mapCy, scale)
                val fraction = i.toFloat() / total
                trackPaint.color = lerpColor(trackColorOld, trackColorNew, fraction)
                canvas.drawLine(
                    worldToScreenX(track[i - 1].x, mapCx, scale),
                    worldToScreenY(track[i - 1].y, mapCy, scale),
                    px, py, trackPaint
                )
            }
        }

        // ── Ровер (позиция + heading) ────────────────────────────────
        val rx = worldToScreenX(odom.pose.x, mapCx, scale)
        val ry = worldToScreenY(odom.pose.y, mapCy, scale)
        val heading = odom.pose.headingRad

        // Треугольник ровера
        val roverSize = 12f
        val path = Path()
        path.moveTo(
            rx + roverSize * 1.5f * cos(heading.toDouble()).toFloat(),
            ry - roverSize * 1.5f * sin(heading.toDouble()).toFloat()
        )
        path.lineTo(
            rx + roverSize * cos((heading + 2.5).toDouble()).toFloat(),
            ry - roverSize * sin((heading + 2.5).toDouble()).toFloat()
        )
        path.lineTo(
            rx + roverSize * cos((heading - 2.5).toDouble()).toFloat(),
            ry - roverSize * sin((heading - 2.5).toDouble()).toFloat()
        )
        path.close()
        canvas.drawPath(path, roverPaint)

        // Линия направления
        val headLen = 25f
        canvas.drawLine(
            rx, ry,
            rx + headLen * cos(heading.toDouble()).toFloat(),
            ry - headLen * sin(heading.toDouble()).toFloat(),
            headingPaint
        )

        // ── Scale bar (линейка внизу) ────────────────────────────────
        drawScaleBar(canvas, scale, w, h)

        // ── Статистика ───────────────────────────────────────────────
        drawStats(canvas, odom, w)
    }

    // ── Хелперы ──────────────────────────────────────────────────────────

    private fun worldToScreenX(worldX: Float, mapCx: Float, scale: Float): Float =
        mapCx + worldX * scale

    private fun worldToScreenY(worldY: Float, mapCy: Float, scale: Float): Float =
        mapCy - worldY * scale  // Y инвертирован

    private fun computeAutoScale(track: List<OdometryTracker.Pose>, w: Float, h: Float): Float {
        if (track.isEmpty()) return 100f
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in track) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        // Включаем начало координат
        minX = min(minX, 0f); maxX = max(maxX, 0f)
        minY = min(minY, 0f); maxY = max(maxY, 0f)

        val rangeX = max(maxX - minX, 0.5f)  // минимум 0.5м
        val rangeY = max(maxY - minY, 0.5f)
        val margin = 0.7f  // 70% вьюпорта
        val scaleX = w * margin / rangeX
        val scaleY = h * margin / rangeY
        return min(scaleX, scaleY).coerceIn(10f, 1000f)
    }

    private fun drawGrid(canvas: Canvas, mapCx: Float, mapCy: Float, scale: Float, w: Float, h: Float) {
        // Подбираем шаг сетки чтобы было 4-10 линий на экране
        val gridSteps = floatArrayOf(0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 25f, 50f)
        var gridM = 1f
        for (step in gridSteps) {
            val linesH = w / (step * scale)
            if (linesH in 3f..12f) { gridM = step; break }
        }
        val gridPx = gridM * scale

        // Вертикальные линии
        val startX = mapCx % gridPx
        var x = startX
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            // Метка
            val worldX = (x - mapCx) / scale
            if (abs(worldX) > gridM * 0.1f) {
                canvas.drawText(formatDist(worldX), x + 3, h - 4, gridTextPaint)
            }
            x += gridPx
        }

        // Горизонтальные линии
        val startY = mapCy % gridPx
        var y = startY
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            val worldY = -(y - mapCy) / scale  // инвертированный Y
            if (abs(worldY) > gridM * 0.1f) {
                canvas.drawText(formatDist(worldY), 3f, y - 3, gridTextPaint)
            }
            y += gridPx
        }
    }

    private fun drawScaleBar(canvas: Canvas, scale: Float, w: Float, h: Float) {
        // Линейка ~100px
        val targetPx = 100f
        val targetM = targetPx / scale
        // Округляем до красивого числа
        val niceValues = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 20f)
        var barM = 1f
        for (v in niceValues) {
            if (v >= targetM * 0.5f) { barM = v; break }
        }
        val barPx = barM * scale

        val barX = w - barPx - 20f
        val barY = h - 30f
        canvas.drawLine(barX, barY, barX + barPx, barY, scalePaint)
        canvas.drawLine(barX, barY - 5, barX, barY + 5, scalePaint)
        canvas.drawLine(barX + barPx, barY - 5, barX + barPx, barY + 5, scalePaint)
        canvas.drawText(formatDist(barM), barX + barPx / 2, barY - 8, scaleTextPaint)
    }

    private fun drawStats(canvas: Canvas, odom: OdometryTracker, w: Float) {
        val lines = mutableListOf<String>()
        lines.add(String.format("X:%.2fm Y:%.2fm", odom.pose.x, odom.pose.y))
        lines.add(String.format("H:%.0f° D:%.2fm", Math.toDegrees(odom.pose.headingRad.toDouble()), odom.distanceMeters))
        lines.add(String.format("V:%.2fm/s Max:%.2f", odom.currentSpeedMs, odom.maxSpeedMs))

        val textH = statsPaint.textSize + 4f
        val boxH = lines.size * textH + 12f
        val boxW = 240f
        val boxX = w - boxW - 8f
        val boxY = 8f

        canvas.drawRoundRect(boxX, boxY, boxX + boxW, boxY + boxH, 6f, 6f, statsBoxPaint)
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, boxX + 8f, boxY + 20f + i * textH, statsPaint)
        }
    }

    private fun formatDist(m: Float): String {
        val abs = abs(m)
        return when {
            abs < 0.01f -> ""
            abs < 1f -> String.format("%.0fcm", m * 100)
            else -> String.format("%.1fm", m)
        }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val a = ((Color.alpha(c1) * (1 - t) + Color.alpha(c2) * t)).toInt()
        val r = ((Color.red(c1) * (1 - t) + Color.red(c2) * t)).toInt()
        val g = ((Color.green(c1) * (1 - t) + Color.green(c2) * t)).toInt()
        val b = ((Color.blue(c1) * (1 - t) + Color.blue(c2) * t)).toInt()
        return Color.argb(a, r, g, b)
    }
}
