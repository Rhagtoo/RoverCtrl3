package org.rhanet.roverctrl.ui.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// ──────────────────────────────────────────────────────────────────────────
// JoystickView — Custom View (круговой тач-джойстик)
//
// Круговой джойстик, возвращает нормализованные координаты [-1..1].
// Два режима:
//   - FULL (по умолч.) — свободное перемещение по X и Y
//   - VERTICAL_ONLY    — блокирует X, только вертикальная ось
//
// Callback: onMove(x: Float, y: Float)
// ──────────────────────────────────────────────────────────────────────────

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { FULL, VERTICAL_ONLY }

    var mode = Mode.FULL
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    // Нормализованные значения [-1..1]
    var xValue = 0f; private set
    var yValue = 0f; private set

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        style = Paint.Style.FILL
    }
    private val stickBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var centerX = 0f
    private var centerY = 0f
    private var radius  = 0f
    private var stickR  = 0f
    private var stickX  = 0f
    private var stickY  = 0f
    private var touching = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius  = min(w, h) / 2f * 0.85f
        stickR  = radius * 0.3f
        stickX  = centerX
        stickY  = centerY
    }

    override fun onDraw(canvas: Canvas) {
        // Background circle
        canvas.drawCircle(centerX, centerY, radius, bgPaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        // Crosshair
        canvas.drawLine(centerX - radius * 0.7f, centerY,
                         centerX + radius * 0.7f, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - radius * 0.7f,
                         centerX, centerY + radius * 0.7f, crossPaint)

        // Stick
        val stickAlpha = if (touching) 200 else 150
        stickPaint.alpha = stickAlpha
        canvas.drawCircle(stickX, stickY, stickR, stickPaint)
        canvas.drawCircle(stickX, stickY, stickR, stickBorderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                updateStick(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                stickX = centerX
                stickY = centerY
                xValue = 0f; yValue = 0f
                onMove?.invoke(0f, 0f)
                invalidate()
            }
        }
        return true
    }

    private fun updateStick(tx: Float, ty: Float) {
        var dx = tx - centerX
        var dy = ty - centerY

        if (mode == Mode.VERTICAL_ONLY) dx = 0f

        val dist = hypot(dx, dy)
        val maxDist = radius - stickR

        if (dist > maxDist) {
            val angle = atan2(dy, dx)
            dx = kotlin.math.cos(angle) * maxDist
            dy = kotlin.math.sin(angle) * maxDist
        }

        stickX = centerX + dx
        stickY = centerY + dy

        xValue = (dx / maxDist).coerceIn(-1f, 1f)
        yValue = -(dy / maxDist).coerceIn(-1f, 1f)  // инвертируем Y (вверх = +)

        onMove?.invoke(xValue, yValue)
        invalidate()
    }
}
