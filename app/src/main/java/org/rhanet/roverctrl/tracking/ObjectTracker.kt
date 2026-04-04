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
//   Input:  [1, 640, 640, 3]  float32 — NHWC
//   Output: [1, 84, 8400]  или  [1, 8400, 84]
//           84 = cx,cy,w,h + 80 классов COCO
//
// FIX v2.6: removed tilt Y inversion — was causing camera to track
//           in wrong direction. Fixed cat offset sign.
//
// Physical convention (confirmed):
//   YOLO cy=0 = top of frame
//   errY negative → tilt negative → firmware target→0° → camera UP ✓
//   errY positive → tilt positive → firmware target→180° → camera DOWN ✓
// ──────────────────────────────────────────────────────────────────────────

class ObjectTracker(
    context:          Context,
    modelFile:        String = "yolov8n.tflite",
    private val targetClass: Int = -1,
    private val confThresh:  Float = 0.25f,
    private val iouThresh:   Float = 0.45f,
    useGpu:           Boolean = true,
    private var panSensitivity:  Float = 1.0f,
    private var tiltSensitivity: Float = 1.0f
) {
    private var isTracking = false
    private var framesSinceDetection = 0
    private val maxTrackingTimeMs = 1000L
    private var lastDetectionTime = 0L
    private val kalman = KalmanFilter2D()
    private var lastDetection: DetectionResult? = null
    private var trackingConfidence = 0f

    private val minDetectionConfidence = 0.3f
    private val minTrackingConfidence = 0.1f

    private var cachedOutputBuffer: Array<Array<FloatArray>>? = null

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

    private var outputTransposed: Boolean? = null
    private var inputNchw: Boolean = false
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

        val inShape  = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        Log.i(TAG, "Input shape: ${inShape.contentToString()}")
        Log.i(TAG, "Output shape: ${outShape.contentToString()}")

        if (inShape.size == 4) {
            inputNchw = (inShape[1] == 3 && inShape[2] > 3)
            modelInputSize = if (inputNchw) inShape[2] else inShape[1]
            Log.i(TAG, "Input format: ${if (inputNchw) "NCHW" else "NHWC"}, size=$modelInputSize")
        }

        if (outShape.size == 3) {
            outputTransposed = outShape[1] > outShape[2]
        }
    }

    data class TrackResult(
        val found:     Boolean,
        val panDelta:  Float,
        val tiltDelta: Float,
        val detection: DetectionResult?
    )

    fun process(frame: Bitmap): TrackResult {
        framesSinceDetection++
        val currentTime = System.currentTimeMillis()

        val timeSinceLastDetection = if (lastDetectionTime > 0) currentTime - lastDetectionTime else Long.MAX_VALUE
        val shouldDetect = !isTracking ||
                          timeSinceLastDetection >= maxTrackingTimeMs ||
                          trackingConfidence < minTrackingConfidence

        val detection = if (shouldDetect) {
            val detected = detect(frame)
            if (detected != null && detected.confidence >= minDetectionConfidence) {
                isTracking = true
                framesSinceDetection = 0
                lastDetectionTime = currentTime
                kalman.reset()
                lastDetection = detected
                trackingConfidence = 1.0f
                detected
            } else {
                isTracking = false
                lastDetection = null
                trackingConfidence = 0f
                lastDetectionTime = 0L
                kalman.reset()
                null
            }
        } else {
            lastDetection?.let {
                val tracked = kalman.update(it)
                if (tracked.cx in 0f..1f && tracked.cy in 0f..1f &&
                    tracked.w in 0f..1f && tracked.h in 0f..1f) {
                    tracked
                } else {
                    isTracking = false
                    kalman.reset()
                    null
                }
            } ?: run {
                isTracking = false
                null
            }
        }

        val finalDetection = detection ?: run {
            pidPan.reset(); pidTilt.reset()
            return TrackResult(false, 0f, 0f, null)
        }

        lastDetection = finalDetection
        trackingConfidence = finalDetection.confidence

        // ── Error calculation ────────────────────────────────────────────
        var targetCx = finalDetection.cx
        var targetCy = finalDetection.cy

        // FIX v2.6: NO INVERSION of targetCy
        //
        // YOLO coordinates: cy=0 top, cy=1 bottom
        // errY = cy - 0.5:
        //   object at top (cy≈0): errY negative → tilt negative
        //     → firmware: map(-90..90, 0..180) → target≈0° → camera UP ✓
        //   object at bottom (cy≈1): errY positive → tilt positive
        //     → firmware: target≈180° → camera DOWN ✓
        //
        // The inversion was WRONG — it reversed the tracking direction.

        // For cat: aim at feet (bottom of bbox = cy + h/2)
        // cy increases downward, so adding h/2 shifts target toward feet
        if (finalDetection.label == "cat") {
            targetCy = (targetCy + finalDetection.h / 2f).coerceAtMost(1f)
        }

        val errX = targetCx - 0.5f
        val errY = targetCy - 0.5f
        val panRaw  = pidPan.updateWithDeadband(errX)
        val tiltRaw = pidTilt.updateWithDeadband(errY)
        
        // Apply sensitivity multipliers
        val pan  = (panRaw * panSensitivity).toInt().coerceIn(-100, 100)
        val tilt = (tiltRaw * tiltSensitivity).toInt().coerceIn(-100, 100)

        val mode = if (shouldDetect) "DETECT" else "TRACK"
        Log.d(TAG, "[$mode] ${finalDetection.label} ${(finalDetection.confidence*100).toInt()}% " +
                   "at (${finalDetection.cx}, ${finalDetection.cy}) → err(${"%.2f".format(errX)}, ${"%.2f".format(errY)})")

        return TrackResult(true, pan, tilt, finalDetection)
    }

    // ── NHWC float32 ─────────────────────────────────────────────────────
    private fun bitmapToNhwcBuffer(bmp: Bitmap): ByteBuffer {
        val sz = modelInputSize
        val buf = ByteBuffer.allocateDirect(4 * sz * sz * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)
            buf.putFloat(( p         and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    // ── NCHW float32 ─────────────────────────────────────────────────────
    private fun bitmapToNchwBuffer(bmp: Bitmap): ByteBuffer {
        val sz = modelInputSize
        val buf = ByteBuffer.allocateDirect(4 * 3 * sz * sz).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(((p shr 8)  and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(( p         and 0xFF) / 255f)
        buf.rewind()
        return buf
    }

    private fun detect(frame: Bitmap): DetectionResult? {
        val sz = modelInputSize
        val scaled = Bitmap.createScaledBitmap(frame, sz, sz, true)
        val input  = if (inputNchw) bitmapToNchwBuffer(scaled) else bitmapToNhwcBuffer(scaled)
        scaled.recycle()

        val outShape = interp.getOutputTensor(0).shape()
        val dim1 = outShape[1]
        val dim2 = outShape[2]

        val outputBuf = cachedOutputBuffer?.takeIf {
            it.size == 1 && it[0].size == dim1 && it[0][0].size == dim2
        } ?: Array(1) { Array(dim1) { FloatArray(dim2) } }.also {
            cachedOutputBuffer = it
        }

        interp.run(input, outputBuf)
        return bestBox(outputBuf[0], dim1, dim2)
    }

    private fun bestBox(out: Array<FloatArray>, dim1: Int, dim2: Int): DetectionResult? {
        val isTransposed = outputTransposed ?: (dim1 > dim2)
        val numBoxes  = if (isTransposed) dim1 else dim2
        val numAttrs  = if (isTransposed) dim2 else dim1

        if (numAttrs < 84) { Log.w(TAG, "Unexpected output attrs: $numAttrs"); return null }

        var maxCoord = 0f
        for (j in 0 until minOf(50, numBoxes)) {
            val cx = if (isTransposed) out[j][0] else out[0][j]
            val cy = if (isTransposed) out[j][1] else out[1][j]
            maxCoord = maxOf(maxCoord, cx, cy)
        }
        val scale = if (maxCoord > 2.0f) modelInputSize.toFloat() else 1.0f

        val boxes = mutableListOf<Box>()

        for (j in 0 until numBoxes) {
            val cx: Float; val cy: Float; val w: Float; val h: Float
            if (isTransposed) {
                cx = out[j][0]; cy = out[j][1]; w = out[j][2]; h = out[j][3]
            } else {
                cx = out[0][j]; cy = out[1][j]; w = out[2][j]; h = out[3][j]
            }

            var maxScore = 0f; var maxCls = 0
            for (c in 0 until NUM_CLASSES) {
                val s = if (isTransposed) out[j][4 + c] else out[4 + c][j]
                if (s > maxScore) { maxScore = s; maxCls = c }
            }

            if (targetClass >= 0) {
                val targetScore = if (isTransposed) out[j][4 + targetClass] else out[4 + targetClass][j]
                if (targetScore < confThresh) continue
                maxScore = targetScore
                maxCls = targetClass
            } else {
                if (maxScore < confThresh) continue
            }

            if (maxCls != 0 && maxCls != 15) continue

            boxes += Box(cx / scale, cy / scale, w / scale, h / scale, maxScore, maxCls)
        }

        if (boxes.isEmpty()) return null

        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept   = mutableListOf<Box>()
        while (sorted.isNotEmpty()) {
            val cur = sorted.removeFirst()
            kept += cur
            sorted.removeIf { iou(cur, it) > iouThresh }
        }

        val b = kept.first()
        return DetectionResult(
            cx = b.cx, cy = b.cy, w = b.w, h = b.h,
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

    fun updatePidGains(kpPan: Float, kpTilt: Float) {
        pidPan.kp = kpPan; pidTilt.kp = kpTilt
        pidPan.reset(); pidTilt.reset()
    }

    fun updateSensitivity(panSens: Float, tiltSens: Float) {
        panSensitivity = panSens
        tiltSensitivity = tiltSens
        Log.d(TAG, "Sensitivity updated: pan=$panSens, tilt=$tiltSens")
    }

    fun close() = interp.close()
}
