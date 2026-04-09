package org.rhanet.roverctrl.tracking

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs

/**
 * PID Auto-Tuner — Relay Feedback Test (метод Åström–Hägglund)
 *
 * Принцип:
 *   1. PID заменяется релейным регулятором: output = ±A по знаку ошибки
 *   2. Система входит в устойчивые автоколебания с периодом Tu
 *   3. Из амплитуды реле A и амплитуды колебаний B:
 *      Ku = 4A / (πB) — предельный коэффициент усиления
 *   4. По Ku и Tu вычисляются оптимальные Kp, Ki, Kd
 *
 * Методы настройки:
 *   - Ziegler-Nichols: агрессивный, возможен overshoot
 *   - Tyreus-Luyben: консервативный, лучше для шумных/с задержкой систем (default)
 *   - Some Overshoot / No Overshoot: промежуточные варианты
 *
 * Последовательность:
 *   startFull() → тест PAN (settle → relay → анализ) → тест TILT → onComplete
 *
 * Ограничения:
 *   - Tilt (CR серво) имеет integrator в plant → Z-N менее точен, может потребовать ручной подстройки
 *   - Сетевая задержка (~50ms) добавляет фазовый сдвиг → Tyreus-Luyben предпочтительнее Z-N
 *   - Требует стабильную детекцию объекта на протяжении всего теста
 */
class PidAutoTuner(
    private val relayAmplitude: Float = 45f,     // ±A in command space (-100..100)
    private val hysteresis: Float = 0.015f,       // error dead band to prevent chattering
    private val minCycles: Int = 4,               // min full oscillation cycles
    private val maxDurationMs: Long = 15_000L,    // per-axis timeout
    private val settleMs: Long = 2000L            // settle time before recording
) {
    companion object {
        private const val TAG = "PidAutoTuner"

        fun gainsFromKuTu(ku: Float, tu: Float, method: TuningMethod): Triple<Float, Float, Float> {
            return when (method) {
                TuningMethod.ZIEGLER_NICHOLS -> Triple(
                    0.6f * ku,                  // Kp
                    1.2f * ku / tu,             // Ki
                    0.075f * ku * tu            // Kd
                )
                TuningMethod.TYREUS_LUYBEN -> Triple(
                    0.45f * ku,
                    0.45f * ku / (2.2f * tu),
                    0.45f * ku * tu / 6.3f
                )
                TuningMethod.SOME_OVERSHOOT -> Triple(
                    0.33f * ku,
                    0.66f * ku / tu,
                    0.11f * ku * tu
                )
                TuningMethod.NO_OVERSHOOT -> Triple(
                    0.2f * ku,
                    0.4f * ku / tu,
                    0.067f * ku * tu
                )
            }
        }
    }

    enum class Axis { PAN, TILT }
    enum class State { IDLE, SETTLING, RUNNING, COMPLETE, FAILED }

    enum class TuningMethod(val label: String) {
        ZIEGLER_NICHOLS("Z-N (aggressive)"),
        TYREUS_LUYBEN("Tyreus-Luyben (balanced)"),
        SOME_OVERSHOOT("Some overshoot"),
        NO_OVERSHOOT("No overshoot")
    }

    data class AxisResult(
        val axis: Axis,
        val ku: Float,           // ultimate gain
        val tu: Float,           // ultimate period (seconds)
        val oscillationAmplitude: Float,
        val kp: Float, val ki: Float, val kd: Float,
        val cycles: Int,
        val valid: Boolean,
        val reason: String = ""
    )

    data class FullResult(
        val pan: AxisResult?,
        val tilt: AxisResult?,
        val method: TuningMethod
    ) {
        val isValid get() = pan?.valid == true && tilt?.valid == true
        val panValid get() = pan?.valid == true
        val tiltValid get() = tilt?.valid == true
    }

    // ── Public state ──
    var state = State.IDLE; private set
    var currentAxis = Axis.PAN; private set
    var panResult: AxisResult? = null; private set
    var tiltResult: AxisResult? = null; private set
    var method = TuningMethod.TYREUS_LUYBEN

    // ── Callbacks ──
    var onProgress: ((axis: Axis, cycles: Int, needed: Int) -> Unit)? = null
    var onAxisDone: ((result: AxisResult) -> Unit)? = null
    var onComplete: ((result: FullResult) -> Unit)? = null
    var onFailed: ((axis: Axis, reason: String) -> Unit)? = null

    // ── Internal relay state ──
    private var relaySign = 1f
    private var startMs = 0L
    private var settleEndMs = 0L

    // Error recording
    private val zeroCrossings = mutableListOf<Long>()
    private var lastErrorSign = 0

    // Peak tracking for amplitude
    private var currentPeakMax = -Float.MAX_VALUE
    private var currentPeakMin = Float.MAX_VALUE
    private val halfCyclePeaks = mutableListOf<Float>() // abs peak per half-cycle
    private var trackingMax = true // true = looking for max, false = looking for min

    // Detection loss handling
    private var lastDetectionMs = 0L
    private val maxDetectionGapMs = 2000L

    // ── Lifecycle ──

    fun startFull() {
        panResult = null
        tiltResult = null
        startAxis(Axis.PAN)
    }

    fun startSingle(axis: Axis) {
        if (axis == Axis.PAN) panResult = null else tiltResult = null
        startAxis(axis)
    }

    fun isRunning() = state == State.SETTLING || state == State.RUNNING

    fun abort() {
        if (isRunning()) {
            state = State.IDLE
            Log.i(TAG, "Aborted during $currentAxis")
        }
    }

    /**
     * Feed raw error for current axis. Returns relay command output.
     *
     * @param error Raw error: (detectedCx - 0.5) for PAN, (detectedCy - 0.5) for TILT
     *              Range roughly -0.5..+0.5, positive = object right/below center
     * @param detected Whether YOLO detection is valid this frame
     * @return Command in -100..100 (relay output), or 0 if paused/done
     */
    fun update(error: Float, detected: Boolean): Float {
        if (!isRunning()) return 0f

        val now = System.currentTimeMillis()

        // Detection loss check
        if (detected) {
            lastDetectionMs = now
        } else {
            if (lastDetectionMs > 0 && (now - lastDetectionMs) > maxDetectionGapMs) {
                fail("Detection lost for ${maxDetectionGapMs}ms")
                return 0f
            }
            return 0f // hold still while detection lost
        }

        // Timeout
        if (now - startMs > maxDurationMs + settleMs) {
            val cycles = zeroCrossings.size / 2
            if (cycles >= 2) {
                finishAxis()
            } else {
                fail("Timeout: $cycles cycles in ${maxDurationMs / 1000}s (need $minCycles)")
            }
            return 0f
        }

        // Settling phase — output relay but don't record
        if (state == State.SETTLING) {
            if (now >= settleEndMs) {
                state = State.RUNNING
                Log.d(TAG, "Settle done, recording for $currentAxis")
            }
            return computeRelay(error)
        }

        // ── Recording phase ──

        val currentSign = when {
            error > hysteresis -> 1
            error < -hysteresis -> -1
            else -> lastErrorSign
        }

        // Zero-crossing
        if (lastErrorSign != 0 && currentSign != 0 && currentSign != lastErrorSign) {
            zeroCrossings.add(now)

            // Record peak from completed half-cycle
            val peak = if (trackingMax) currentPeakMax else currentPeakMin
            if (peak != -Float.MAX_VALUE && peak != Float.MAX_VALUE) {
                halfCyclePeaks.add(abs(peak))
            }
            // Reset peak tracker for next half-cycle
            currentPeakMax = -Float.MAX_VALUE
            currentPeakMin = Float.MAX_VALUE
            trackingMax = !trackingMax

            val cycles = zeroCrossings.size / 2
            onProgress?.invoke(currentAxis, cycles, minCycles)

            if (cycles >= minCycles) {
                finishAxis()
                return 0f
            }
        }
        if (currentSign != 0) lastErrorSign = currentSign

        // Track peaks
        if (error > currentPeakMax) currentPeakMax = error
        if (error < currentPeakMin) currentPeakMin = error

        return computeRelay(error)
    }

    /** Current axis progress: cycles completed / needed */
    fun progress(): Pair<Int, Int> = (zeroCrossings.size / 2) to minCycles

    // ── Internal ──

    private fun startAxis(axis: Axis) {
        currentAxis = axis
        state = State.SETTLING
        val now = System.currentTimeMillis()
        startMs = now
        settleEndMs = now + settleMs
        lastDetectionMs = now
        relaySign = 1f
        zeroCrossings.clear()
        halfCyclePeaks.clear()
        lastErrorSign = 0
        currentPeakMax = -Float.MAX_VALUE
        currentPeakMin = Float.MAX_VALUE
        trackingMax = true
        Log.i(TAG, "Start $axis: relay=±$relayAmplitude hyst=$hysteresis settle=${settleMs}ms")
    }

    private fun computeRelay(error: Float): Float {
        // Standard relay with hysteresis, same sign convention as PID:
        // positive error (object right of center) → positive output (camera follows right)
        if (error > hysteresis) relaySign = 1f
        else if (error < -hysteresis) relaySign = -1f
        return relaySign * relayAmplitude
    }

    private fun finishAxis() {
        val cycles = zeroCrossings.size / 2
        if (cycles < 2 || halfCyclePeaks.size < 2) {
            fail("Insufficient data: $cycles cycles, ${halfCyclePeaks.size} peaks")
            return
        }

        // ── Compute Tu (ultimate period) ──
        // Skip first 1-2 crossings (transient), use remaining for average
        val skip = if (zeroCrossings.size > 6) 2 else 0
        val usable = zeroCrossings.subList(skip, zeroCrossings.size)
        if (usable.size < 4) {
            fail("Not enough zero-crossings after skip: ${usable.size}")
            return
        }

        // Full cycle = 2 crossings apart
        val cycleDurations = mutableListOf<Float>()
        for (i in 2 until usable.size step 2) {
            val dt = (usable[i] - usable[i - 2]) / 1000f
            if (dt in 0.05f..10f) cycleDurations.add(dt) // reject outliers
        }
        if (cycleDurations.isEmpty()) {
            fail("No valid cycle durations")
            return
        }
        val tu = cycleDurations.average().toFloat()

        // ── Compute B (oscillation amplitude) ──
        // Skip first peak (transient), average the rest
        val skipPeaks = if (halfCyclePeaks.size > 3) 1 else 0
        val usablePeaks = halfCyclePeaks.subList(skipPeaks, halfCyclePeaks.size)
        val amplitude = usablePeaks.average().toFloat()

        if (amplitude < 0.005f) {
            fail("Oscillation amplitude too small: ${"%.4f".format(amplitude)} — increase relay amplitude")
            return
        }

        // ── Ku = 4A / (πB) ──
        val ku = (4f * relayAmplitude) / (PI.toFloat() * amplitude)

        // ── Compute gains ──
        val (kp, ki, kd) = gainsFromKuTu(ku, tu, method)

        // Sanity check
        val maxKp = 500f
        val clampedKp = kp.coerceIn(1f, maxKp)
        val clampedKi = ki.coerceIn(0f, 10f)
        val clampedKd = kd.coerceIn(0f, 50f)

        val result = AxisResult(
            axis = currentAxis,
            ku = ku, tu = tu, oscillationAmplitude = amplitude,
            kp = clampedKp, ki = clampedKi, kd = clampedKd,
            cycles = cycles, valid = true
        )

        Log.i(TAG, "$currentAxis DONE: Ku=%.1f Tu=%.3fs B=%.4f → Kp=%.1f Ki=%.2f Kd=%.1f [%s] (%d cycles)"
            .format(ku, tu, amplitude, clampedKp, clampedKi, clampedKd, method.label, cycles))

        when (currentAxis) {
            Axis.PAN -> {
                panResult = result
                onAxisDone?.invoke(result)
                // Auto-continue to tilt
                startAxis(Axis.TILT)
            }
            Axis.TILT -> {
                tiltResult = result
                onAxisDone?.invoke(result)
                state = State.COMPLETE
                onComplete?.invoke(FullResult(panResult, tiltResult, method))
            }
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "FAILED ($currentAxis): $reason")
        val failedResult = AxisResult(
            currentAxis, 0f, 0f, 0f, 0f, 0f, 0f, 0, false, reason
        )
        when (currentAxis) {
            Axis.PAN -> panResult = failedResult
            Axis.TILT -> tiltResult = failedResult
        }
        state = State.FAILED
        onFailed?.invoke(currentAxis, reason)
    }

}
