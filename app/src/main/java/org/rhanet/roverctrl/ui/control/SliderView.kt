package org.rhanet.roverctrl.ui.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────────
// SliderView — одномерный слайдер (горизонтальный или вертикальный)
//
// Возвращает нормализованное значение [-1..1].
//   - HORIZONTAL:  -1 = влево,  +1 = вправо,  0 = центр
//   - VERTICAL:    -1 = вниз,   +1 = вверх,    0 = центр
//
// Callback: onMove(value: Float) — нормализованное значение
// ──────────────────────────────────────────────────────────────────────────

class SliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Orientation { HORIZONTAL, VERTICAL }

    var orientation = Orientation.HORIZONTAL
    var onMove: ((value: Float) -> Unit)? = null

    var value = 0f; private set

    // ── Paints ──────────────────────────────────────────────────────────
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E1E")
        style = Paint.Style.FILL
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    // Активная зона (заполнение от центра до текущего значения)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        style = Paint.Style.FILL
    }
    // Thumb (бегунок)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#69F0AE")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    // Центральная насечка
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    // ── Геометрия ──────────────────────────────────────────────────────
    private var trackRect = RectF()
    private var thumbX = 0f
    private var thumbY = 0f
    private var thumbRadius = 0f
    private var touching = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 16f
        if (orientation == Orientation.HORIZONTAL) {
            val trackH = h * 0.35f
            trackRect.set(pad, (h - trackH) / 2f, w - pad, (h + trackH) / 2f)
            thumbRadius = trackH * 0.55f
            thumbX = w / 2f
            thumbY = h / 2f
        } else {
            val trackW = w * 0.35f
            trackRect.set((w - trackW) / 2f, pad, (w + trackW) / 2f, h - pad)
            thumbRadius = trackW * 0.55f
            thumbX = w / 2f
            thumbY = h / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Track background
        val cornerR = if (orientation == Orientation.HORIZONTAL)
            trackRect.height() / 2f else trackRect.width() / 2f
        canvas.drawRoundRect(trackRect, cornerR, cornerR, trackBgPaint)
        canvas.drawRoundRect(trackRect, cornerR, cornerR, trackBorderPaint)

        // Active fill (from center to thumb)
        val cx = trackRect.centerX()
        val cy = trackRect.centerY()
        if (abs(value) > 0.01f) {
            activePaint.alpha = if (touching) 200 else 130
            if (orientation == Orientation.HORIZONTAL) {
                val left = if (value > 0) cx else thumbX
                val right = if (value > 0) thumbX else cx
                canvas.drawRoundRect(
                    RectF(left, trackRect.top, right, trackRect.bottom),
                    cornerR, cornerR, activePaint
                )
            } else {
                // VERTICAL: активная зона от центра до thumb (вверх или вниз)
                val top = if (value < 0) cy else thumbY  // value<0 = вниз
                val bottom = if (value < 0) thumbY else cy
                canvas.drawRoundRect(
                    RectF(trackRect.left, top, trackRect.right, bottom),
                    cornerR, cornerR, activePaint
                )
            }
        }

        // Center mark
        if (orientation == Orientation.HORIZONTAL) {
            canvas.drawLine(cx, trackRect.top + 8f, cx, trackRect.bottom - 8f, centerPaint)
        } else {
            canvas.drawLine(trackRect.left + 8f, cy, trackRect.right - 8f, cy, centerPaint)
        }

        // Thumb
        thumbPaint.alpha = if (touching) 240 else 180
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbBorderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                updateThumb(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                value = 0f
                onMove?.invoke(0f)
                // Анимируем возврат в центр
                thumbX = trackRect.centerX()
                thumbY = trackRect.centerY()
                invalidate()
            }
        }
        return true
    }

    private fun updateThumb(tx: Float, ty: Float) {
        if (orientation == Orientation.HORIZONTAL) {
            val margin = thumbRadius + 2f
            val clamped = tx.coerceIn(trackRect.left + margin, trackRect.right - margin)
            thumbX = clamped
            thumbY = trackRect.centerY()
            val half = trackRect.width() / 2f - margin
            value = ((clamped - trackRect.centerX()) / half).coerceIn(-1f, 1f)
        } else {
            val margin = thumbRadius + 2f
            val clamped = ty.coerceIn(trackRect.top + margin, trackRect.bottom - margin)
            thumbY = clamped
            thumbX = trackRect.centerX()
            val half = trackRect.height() / 2f - margin
            value = -((clamped - trackRect.centerY()) / half).coerceIn(-1f, 1f)  // invert: up=+
        }
        onMove?.invoke(value)
        invalidate()
    }
}
