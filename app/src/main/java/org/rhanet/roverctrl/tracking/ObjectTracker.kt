package org.rhanet.roverctrl.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.rhanet.roverctrl.data.DetectionResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// ──────────────────────────────────────────────────────────────────────────
// ObjectTracker — YOLOv8n TFLite
//
// Модель: yolov8n.tflite (assets/)
//   Экспорт: yolo export model=yolov8n.pt format=tflite imgsz=640
//
//   Input:  [1, 640, 640, 3]  float32 — NHWC (стандарт TFLite)
//   Output: [1, 84, 8400]  или  [1, 8400, 84]  — зависит от версии ultralytics
//           84 = cx,cy,w,h + 80 классов COCO
//
// targetClass = -1 → детектить любой класс (по умолчанию)
// targetClass = 0  → только person
// targetClass = 15 → только cat
// ──────────────────────────────────────────────────────────────────────────

class ObjectTracker(
    context:          Context,
    modelFile:        String = "yolov8n.tflite",
    private val targetClass: Int = -1,   // -1 = любой класс
    private val confThresh:  Float = 0.25f,
    private val iouThresh:   Float = 0.45f,
    useGpu:           Boolean = true
) {
    companion object {
        private const val TAG = "ObjectTracker"
        const val INPUT_SIZE  = 640
        const val NUM_CLASSES = 80

        val COCO_LABELS = arrayOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck",
            "boat","traffic light","fire hydrant","stop sign","parking meter","bench",
            "bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe",
            "backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard",
            "sports ball","kite","baseball bat","baseball glove","skateboard","surfboard",
            "tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl",
            "banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza",
            "donut","cake","chair","couch","potted plant","bed","dining table","toilet",
            "tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven",
            "toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear",
            "hair drier","toothbrush"
        )
    }

    private data class Box(
        val cx: Float, val cy: Float,
        val w: Float, val h: Float,
        val score: Float, val classId: Int
    )

    private val interp: Interpreter
    private val pidPan  = PidController(kp = 100f, ki = 0.2f, kd = 6f, outMax = 100f)
    private val pidTilt = PidController(kp = 100f, ki = 0.2f, kd = 6f, outMax = 100f)

    // Определяется из model tensor shapes
    private var outputTransposed: Boolean? = null
    private var inputNchw: Boolean = false   // true=[1,3,H,W], false=[1,H,W,3]
    private var modelInputSize: Int = INPUT_SIZE

    init {
        val opts = Interpreter.Options().apply {
            numThreads = 4
            if (useGpu) {
                try {
                    val gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                    addDelegate(gpuDelegate)
                } catch (_: Throwable) { /* fallback CPU */ }
            }
        }
        val fd = context.assets.openFd(modelFile)
        val model = FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(model, opts)

        // Логируем shape для отладки
        val inShape  = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        Log.i(TAG, "Input shape: ${inShape.contentToString()}")
        Log.i(TAG, "Output shape: ${outShape.contentToString()}")

        // Определяем формат input
        // [1, 3, 640, 640] → NCHW (channels-first)
        // [1, 640, 640, 3] → NHWC (channels-last, стандарт TFLite)
        if (inShape.size == 4) {
            inputNchw = (inShape[1] == 3 && inShape[2] > 3)    // [1,3,H,W]
            modelInputSize = if (inputNchw) inShape[2] else inShape[1]
            Log.i(TAG, "Input format: ${if (inputNchw) "NCHW" else "NHWC"}, size=$modelInputSize")
        }

        // Определяем формат output
        if (outShape.size == 3) {
            outputTransposed = outShape[1] > outShape[2]  // 8400 > 84
        }
    }

    data class TrackResult(
        val found:     Boolean,
        val panDelta:  Float,
        val tiltDelta: Float,
        val detection: DetectionResult?
    )

    fun process(frame: Bitmap): TrackResult {
        val sz = modelInputSize
        val scaled = Bitmap.createScaledBitmap(frame, sz, sz, true)
        val input  = if (inputNchw) bitmapToNchwBuffer(scaled) else bitmapToNhwcBuffer(scaled)
        scaled.recycle()

        // Allocate output based on detected shape
        val outShape = interp.getOutputTensor(0).shape()
        val dim1 = outShape[1]
        val dim2 = outShape[2]
        val outputBuf = Array(1) { Array(dim1) { FloatArray(dim2) } }
        interp.run(input, outputBuf)

        val best = bestBox(outputBuf[0], dim1, dim2) ?: run {
            pidPan.reset(); pidTilt.reset()
            return TrackResult(false, 0f, 0f, null)
        }

        var targetCx = best.cx
        var targetCy = best.cy
        // Для cat смещаем цель к нижней части рамки (под ноги)
        if (best.label == "cat") {
            targetCy = (best.cy + best.h / 2).coerceAtMost(1.0f)
        }

        val errX = targetCx - 0.5f
        val errY = targetCy - 0.5f
        val pan  = pidPan.updateWithDeadband(errX)
        val tilt = pidTilt.updateWithDeadband(errY)

        return TrackResult(true, pan, tilt, best)
    }

    // ── NHWC float32: [R,G,B, R,G,B, ...] — стандарт TFLite ─────────────
    private fun bitmapToNhwcBuffer(bmp: Bitmap): ByteBuffer {
        val sz = modelInputSize
        val buf = ByteBuffer.allocateDirect(4 * sz * sz * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)

        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)  // R
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)  // G
            buf.putFloat(( p         and 0xFF) / 255f)  // B
        }
        buf.rewind()
        return buf
    }

    // ── NCHW float32: [R-plane][G-plane][B-plane] — Ultralytics export ───
    private fun bitmapToNchwBuffer(bmp: Bitmap): ByteBuffer {
        val sz = modelInputSize
        val buf = ByteBuffer.allocateDirect(4 * 3 * sz * sz)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)

        // R plane
        for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255f)
        // G plane
        for (p in pixels) buf.putFloat(((p shr 8) and 0xFF) / 255f)
        // B plane
        for (p in pixels) buf.putFloat((p and 0xFF) / 255f)

        buf.rewind()
        return buf
    }

    // ── Найти лучший бокс после NMS ─────────────────────────────────────
    private fun bestBox(out: Array<FloatArray>, dim1: Int, dim2: Int): DetectionResult? {
        val isTransposed = outputTransposed ?: (dim1 > dim2)  // 8400 > 84 → transposed
        val numBoxes  = if (isTransposed) dim1 else dim2
        val numAttrs  = if (isTransposed) dim2 else dim1

        if (numAttrs < 84) {
            Log.w(TAG, "Unexpected output attrs: $numAttrs")
            return null
        }

        // ── Auto-detect: pixel-space (0..640) vs normalized (0..1) ───
        // Сэмплируем первые 50 боксов, смотрим максимум cx/cy
        var maxCoord = 0f
        for (j in 0 until minOf(50, numBoxes)) {
            val cx = if (isTransposed) out[j][0] else out[0][j]
            val cy = if (isTransposed) out[j][1] else out[1][j]
            maxCoord = maxOf(maxCoord, cx, cy)
        }
        // Если максимум > 2.0 → координаты в пикселях, нужно делить
        val scale = if (maxCoord > 2.0f) modelInputSize.toFloat() else 1.0f
        Log.d(TAG, "Output coords: maxSample=$maxCoord, scale=$scale " +
                   "(${if (scale > 1f) "pixel-space" else "normalized"})")

        val boxes = mutableListOf<Box>()

        for (j in 0 until numBoxes) {
            val cx: Float; val cy: Float; val w: Float; val h: Float

            if (isTransposed) {
                cx = out[j][0]; cy = out[j][1]; w = out[j][2]; h = out[j][3]
            } else {
                cx = out[0][j]; cy = out[1][j]; w = out[2][j]; h = out[3][j]
            }

            // Найти лучший класс
            var maxScore = 0f; var maxCls = 0
            for (c in 0 until NUM_CLASSES) {
                val s = if (isTransposed) out[j][4 + c] else out[4 + c][j]
                if (s > maxScore) { maxScore = s; maxCls = c }
            }

            // Фильтр по targetClass
            if (targetClass >= 0) {
                val targetScore = if (isTransposed) out[j][4 + targetClass]
                                  else out[4 + targetClass][j]
                if (targetScore < confThresh) continue
                maxScore = targetScore
                maxCls = targetClass
            } else {
                if (maxScore < confThresh) continue
            }

            // Фильтр: только cat (15) и person (0)
            if (maxCls != 0 && maxCls != 15) continue

            boxes += Box(
                cx = cx / scale,
                cy = cy / scale,
                w  = w  / scale,
                h  = h  / scale,
                score = maxScore,
                classId = maxCls
            )
        }

        if (boxes.isEmpty()) return null

        // Жадный NMS
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept   = mutableListOf<Box>()
        while (sorted.isNotEmpty()) {
            val cur = sorted.removeFirst()
            kept += cur
            sorted.removeIf { iou(cur, it) > iouThresh }
        }

        val b = kept.first()
        Log.d(TAG, "Detection: ${COCO_LABELS.getOrElse(b.classId){"?"}} " +
                    "${(b.score*100).toInt()}% at (${b.cx}, ${b.cy})")

        return DetectionResult(
            cx = b.cx, cy = b.cy,
            w = b.w, h = b.h,
            confidence = b.score,
            label = COCO_LABELS.getOrElse(b.classId) { "cls${b.classId}" }
        )
    }

    private fun iou(a: Box, b: Box): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2
        val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2
        val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val ix  = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
        val iy  = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0f) inter / union else 0f
    }

    /** Обновить kp после калибровки */
    fun updatePidGains(kpPan: Float, kpTilt: Float) {
        pidPan.kp = kpPan
        pidTilt.kp = kpTilt
        pidPan.reset(); pidTilt.reset()
    }

    fun close() = interp.close()
}
