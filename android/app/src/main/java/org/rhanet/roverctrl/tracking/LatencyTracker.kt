package org.rhanet.roverctrl.tracking

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

// ──────────────────────────────────────────────────────────────────────────
// LatencyTracker
//
// Измеряет задержку каждого этапа обработки кадра.
//
// Использование:
//   val frame = latency.beginFrame()          // на входе кадра
//   latency.mark(frame, Stage.DECODED)        // после декодирования
//   latency.mark(frame, Stage.INFERENCE)      // после YOLO
//   latency.mark(frame, Stage.CMD_SENT)       // после отправки команды
//   val snap = latency.snapshot()             // текущая статистика
//
// Потокобезопасен. beginFrame() → mark() можно вызывать из разных потоков.
// Статистика считается по скользящему окну (последние N кадров).
// ──────────────────────────────────────────────────────────────────────────

class LatencyTracker(private val windowSize: Int = 60) {

    enum class Stage {
        FRAME_RECEIVED,   // кадр получен с XIAO (MJPEG decoded) или с CameraX
        DECODED,          // JPEG → Bitmap
        RESIZE,           // resize к размеру модели
        INFERENCE_START,  // перед YOLO/LaserTracker
        INFERENCE_END,    // после inference
        PID_DONE,         // PID рассчитан
        CMD_SENT          // UDP команда отправлена
    }

    data class FrameTimings(
        val frameId: Long,
        val timestamps: LongArray = LongArray(Stage.entries.size) { 0L }
    ) {
        fun mark(stage: Stage) {
            timestamps[stage.ordinal] = System.nanoTime()
        }

        /** Задержка между двумя стадиями в миллисекундах */
        fun deltaMs(from: Stage, to: Stage): Float {
            val t0 = timestamps[from.ordinal]
            val t1 = timestamps[to.ordinal]
            return if (t0 > 0 && t1 > 0) (t1 - t0) / 1_000_000f else Float.NaN
        }

        /** Полная задержка от первой до последней заполненной стадии */
        fun totalMs(): Float {
            val first = timestamps.firstOrNull { it > 0 } ?: return Float.NaN
            val last  = timestamps.lastOrNull  { it > 0 } ?: return Float.NaN
            return if (last > first) (last - first) / 1_000_000f else Float.NaN
        }
    }

    /** Статистика по скользящему окну */
    data class Snapshot(
        val count:       Int,
        val avgTotalMs:  Float,
        val minTotalMs:  Float,
        val maxTotalMs:  Float,
        val stageAvgMs:  Map<String, Float>,  // "DECODED→INFERENCE_END" → avg ms
        val fps:         Float                // эффективный FPS обработки
    ) {
        fun format(): String = buildString {
            append("Latency: avg=%.1fms min=%.1f max=%.1f fps=%.1f\n".format(
                avgTotalMs, minTotalMs, maxTotalMs, fps))
            stageAvgMs.forEach { (k, v) ->
                append("  $k: %.1fms\n".format(v))
            }
        }

        /** Краткая строка для HUD */
        fun hud(): String = "%.0fms (%.0f fps)".format(avgTotalMs, fps)
    }

    private val frameSeq = AtomicLong(0)
    private val ring = arrayOfNulls<FrameTimings>(windowSize)
    private var writeIdx = 0
    @Volatile private var totalFrames = 0L

    // ── API ──────────────────────────────────────────────────────────────

    /** Начать новый кадр. Возвращает FrameTimings для mark(). */
    fun beginFrame(): FrameTimings {
        val ft = FrameTimings(frameId = frameSeq.incrementAndGet())
        ft.mark(Stage.FRAME_RECEIVED)
        synchronized(this) {
            ring[writeIdx] = ft
            writeIdx = (writeIdx + 1) % windowSize
            totalFrames++
        }
        return ft
    }

    /** Отметить стадию. Вызывается из любого потока. */
    fun mark(frame: FrameTimings, stage: Stage) {
        frame.mark(stage)
    }

    /** Быстрый вызов: beginFrame + mark + вернуть timing object */
    fun begin(stage: Stage = Stage.FRAME_RECEIVED): FrameTimings {
        val ft = beginFrame()
        if (stage != Stage.FRAME_RECEIVED) ft.mark(stage)
        return ft
    }

    /** Текущая статистика */
    fun snapshot(): Snapshot {
        val frames: List<FrameTimings>
        synchronized(this) {
            frames = ring.filterNotNull().toList()
        }
        if (frames.isEmpty()) return Snapshot(0, 0f, 0f, 0f, emptyMap(), 0f)

        // Totals
        val totals = frames.mapNotNull { ft ->
            val t = ft.totalMs()
            if (t.isNaN() || t <= 0) null else t
        }
        val avgTotal = if (totals.isNotEmpty()) totals.average().toFloat() else 0f
        val minTotal = totals.minOrNull() ?: 0f
        val maxTotal = totals.maxOrNull() ?: 0f

        // Per-stage deltas
        val stageNames = mutableMapOf<String, MutableList<Float>>()
        val stagePairs = listOf(
            Stage.FRAME_RECEIVED to Stage.DECODED     to "receive→decode",
            Stage.DECODED        to Stage.RESIZE      to "decode→resize",
            Stage.RESIZE         to Stage.INFERENCE_START to "resize→infer",
            Stage.INFERENCE_START to Stage.INFERENCE_END to "inference",
            Stage.INFERENCE_END  to Stage.PID_DONE    to "PID",
            Stage.PID_DONE       to Stage.CMD_SENT    to "PID→send",
            Stage.FRAME_RECEIVED to Stage.CMD_SENT    to "TOTAL",
        )
        for ((pair, name) in stagePairs) {
            val (from, to) = pair
            val deltas = frames.mapNotNull { ft ->
                val d = ft.deltaMs(from, to)
                if (d.isNaN() || d <= 0) null else d
            }
            if (deltas.isNotEmpty()) {
                stageNames[name] = deltas.toMutableList()
            }
        }
        val stageAvg = stageNames.mapValues { (_, v) -> v.average().toFloat() }

        // FPS: из timestamps первого и последнего кадра в окне
        val fps = if (frames.size >= 2) {
            val t0 = frames.first().timestamps[Stage.FRAME_RECEIVED.ordinal]
            val tN = frames.last().timestamps[Stage.FRAME_RECEIVED.ordinal]
            val elapsed = (tN - t0) / 1_000_000_000f
            if (elapsed > 0) (frames.size - 1) / elapsed else 0f
        } else 0f

        return Snapshot(
            count      = frames.size,
            avgTotalMs = avgTotal,
            minTotalMs = minTotal,
            maxTotalMs = maxTotal,
            stageAvgMs = stageAvg,
            fps        = fps
        )
    }

    /** Логирует текущую статистику (вызывать раз в секунду) */
    fun logStats(tag: String = "Latency") {
        val s = snapshot()
        if (s.count > 0) {
            Log.i(tag, s.format())
        }
    }
}
