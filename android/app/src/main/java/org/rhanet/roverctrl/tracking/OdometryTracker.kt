package org.rhanet.roverctrl.tracking

import kotlin.math.cos
import kotlin.math.sin

/**
 * Одометрия по счислению пути (dead reckoning).
 *
 * Источник данных (приоритет):
 *   1. rpmL / rpmR из расширенной телеметрии  (после обновления прошивки)
 *   2. spd + str из базовой телеметрии + управляющего пакета (fallback)
 *
 * Для включения rpmL/rpmR добавь в rover_esp32s3.ino:
 *   snprintf(buf, ..., "...,\"rpmL\":%.1f,\"rpmR\":%.1f}", rpmL, rpmR);
 *
 * Координаты в метрах, heading в радианах (0 = вперёд, CCW+).
 */
class OdometryTracker(
    private val wheelDiameter: Float = 0.065f,  // м
    private val wheelBase:     Float = 0.150f,  // м, расстояние между колёсами
) {
    data class Pose(val x: Float, val y: Float, val headingRad: Float)

    var pose = Pose(0f, 0f, 0f)
        private set

    /** История трека для визуализации */
    val track = mutableListOf<Pose>()

    private var lastUpdateMs = 0L
    private var totalDistM   = 0f

    val distanceMeters: Float get() = totalDistM

    /** Вызывай каждый раз когда приходит телеметрия */
    fun update(
        rpmL: Float, rpmR: Float,       // NaN → использовать fallback
        spdPct: Float, strPct: Float,   // из телеметрии/пакета команд (-100..100)
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (lastUpdateMs == 0L) { lastUpdateMs = nowMs; return }
        val dt = (nowMs - lastUpdateMs) / 1000f
        lastUpdateMs = nowMs

        val vL: Float
        val vR: Float

        if (!rpmL.isNaN() && !rpmR.isNaN()) {
            // Точный метод: линейная скорость из RPM
            val circ = Math.PI.toFloat() * wheelDiameter
            vL = rpmL / 60f * circ
            vR = rpmR / 60f * circ
        } else {
            // Fallback: оцениваем из процентов скорости
            val vMax = 0.5f
            val v    = spdPct / 100f * vMax
            val dv   = strPct / 100f * vMax * 0.4f
            vL = v - dv
            vR = v + dv
        }

        // Кинематика дифференциального привода
        val vLinear  = (vL + vR) / 2f
        val vAngular = (vR - vL) / wheelBase

        val dHeading = vAngular * dt
        val newHeading = pose.headingRad + dHeading

        val dx = vLinear * cos(pose.headingRad.toDouble()).toFloat() * dt
        val dy = vLinear * sin(pose.headingRad.toDouble()).toFloat() * dt

        totalDistM += kotlin.math.abs(vLinear * dt)
        pose = Pose(pose.x + dx, pose.y + dy, newHeading)
        track.add(pose)

        // Ограничиваем историю трека
        if (track.size > 2000) track.removeAt(0)
    }

    fun reset() {
        pose = Pose(0f, 0f, 0f)
        lastUpdateMs = 0L
        totalDistM = 0f
        track.clear()
    }
}
