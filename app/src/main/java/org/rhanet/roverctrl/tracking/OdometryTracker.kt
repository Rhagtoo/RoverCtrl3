package org.rhanet.roverctrl.tracking

import kotlin.math.*

/**
 * Одометрия по счислению пути (dead reckoning).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * МОДЕЛЬ: Ackermann (bicycle model)
 *
 * Ровер имеет:
 *   - Один мотор → оба колеса с одинаковым PWM
 *   - Рулевое серво: STR -100..+100 → servo map(40,140) → угол поворота
 *
 * Это НЕ дифференциальный привод! Кинематика:
 *   steerAngle = strCmd / 100 * maxSteerAngle
 *   headingRate = velocity * tan(steerAngle) / wheelBase
 *   dx = v * cos(heading) * dt
 *   dy = v * sin(heading) * dt
 *
 * Источники данных (приоритет):
 *   1. rpmL/rpmR → средняя скорость + strCmd → угол поворота
 *   2. spdPct + strPct → fallback если RPM недоступен
 * ═══════════════════════════════════════════════════════════════════════
 */
class OdometryTracker(
    private val wheelDiameter: Float = 0.068f,     // диаметр колеса с резиной 6.8 см = 0.068 м
    private val wheelBase: Float = 0.180f,          // м — расстояние между осями (18 см)
    private val maxSteerAngleDeg: Float = 18f,      // макс угол поворота колёс (градусы) после изменения сервопривода 60-120
) {
    data class Pose(val x: Float, val y: Float, val headingRad: Float)

    var pose = Pose(0f, 0f, 0f)
        private set

    /** История трека для визуализации карты */
    val track = mutableListOf<Pose>()

    private var lastUpdateMs = 0L
    private var totalDistM = 0f

    /** Пройденная дистанция (м) */
    val distanceMeters: Float get() = totalDistM

    /** Максимальная скорость за сессию (м/с) */
    var maxSpeedMs = 0f
        private set

    /** Текущая линейная скорость (м/с) */
    var currentSpeedMs = 0f
        private set

    private val maxSteerAngleRad = Math.toRadians(maxSteerAngleDeg.toDouble()).toFloat()

    /**
     * Обновить одометрию.
     *
     * @param rpmL    RPM левого колеса (NaN → fallback)
     * @param rpmR    RPM правого колеса (NaN → fallback)
     * @param spdPct  мощность мотора % из телеметрии (0..100, fallback)
     * @param strPct  команда руления -100..+100 (из телеметрии или команды)
     */
    fun update(
        rpmL: Float, rpmR: Float,
        spdPct: Float, strPct: Float,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (lastUpdateMs == 0L) {
            lastUpdateMs = nowMs
            return
        }
        val dt = (nowMs - lastUpdateMs) / 1000f
        lastUpdateMs = nowMs

        // Защита от аномальных dt (переключение вкладки, пауза)
        if (dt <= 0f || dt > 2f) return

        // ── Линейная скорость ────────────────────────────────────────
        val velocity: Float
        if (!rpmL.isNaN() && !rpmR.isNaN()) {
            // Точный метод: средний RPM → линейная скорость
            val avgRpm = (rpmL + rpmR) / 2f
            val circumference = Math.PI.toFloat() * wheelDiameter
            velocity = avgRpm / 60f * circumference
        } else {
            // Fallback: оценка из % мощности (грубо)
            val vMax = 2.0f  // предполагаемая макс скорость м/с (для wheelDiameter=0.068м)
            velocity = spdPct / 100f * vMax
        }

        currentSpeedMs = abs(velocity)
        if (currentSpeedMs > maxSpeedMs) maxSpeedMs = currentSpeedMs

        // ── Угол поворота (Ackermann / bicycle model) ────────────────
        // strPct: -100..+100 → steerAngle: -maxSteerAngleRad..+maxSteerAngleRad
        val steerFraction = (strPct / 100f).coerceIn(-1f, 1f)
        val steerAngleRad = steerFraction * maxSteerAngleRad

        // ── Кинематика bicycle model ─────────────────────────────────
        // headingRate = v * tan(steerAngle) / wheelBase
        val headingRate = if (abs(steerAngleRad) > 0.001f) {
            velocity * tan(steerAngleRad.toDouble()).toFloat() / wheelBase
        } else {
            0f  // едем прямо
        }

        val dHeading = headingRate * dt
        // Интегрируем по mid-point heading для лучшей точности
        val midHeading = pose.headingRad + dHeading / 2f

        val dx = velocity * cos(midHeading.toDouble()).toFloat() * dt
        val dy = velocity * sin(midHeading.toDouble()).toFloat() * dt
        val newHeading = pose.headingRad + dHeading

        totalDistM += abs(velocity * dt)
        pose = Pose(pose.x + dx, pose.y + dy, newHeading)

        // Добавляем в трек только если сдвинулись (экономия памяти)
        if (abs(dx) > 0.001f || abs(dy) > 0.001f || abs(dHeading) > 0.001f) {
            track.add(pose)
        }

        // Ограничиваем историю трека
        if (track.size > 5000) {
            // Прореживаем: убираем каждую вторую точку из первой половины
            val half = track.size / 2
            for (i in half - 1 downTo 0 step 2) {
                track.removeAt(i)
            }
        }
    }

    /** Радиус поворота для текущего угла руля (м). Infinity = прямо */
    fun turningRadius(strPct: Float): Float {
        val steerRad = (strPct / 100f) * maxSteerAngleRad
        return if (abs(steerRad) > 0.001f) {
            abs(wheelBase / tan(steerRad.toDouble()).toFloat())
        } else {
            Float.POSITIVE_INFINITY
        }
    }

    fun reset() {
        pose = Pose(0f, 0f, 0f)
        lastUpdateMs = 0L
        totalDistM = 0f
        currentSpeedMs = 0f
        maxSpeedMs = 0f
        track.clear()
    }
}
