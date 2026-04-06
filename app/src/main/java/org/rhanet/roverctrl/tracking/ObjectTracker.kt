package org.rhanet.roverctrl.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.jvm.Volatile
import org.rhanet.roverctrl.data.DetectionResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// ──────────────────────────────────────────────────────────────────────────
// ObjectTracker — YOLOv8n TFLite
//
// v2.8 PERF:
//   - Reused ByteBuffer for input (was allocating 4.9MB per frame!)
//   - Reused IntArray for pixel extraction (was allocating 1.6MB per frame!)
//   - NNAPI delegate attempted before GPU (Hexagon DSP often faster for small models)
//   - Reduced per-frame logging (was 15 Log.d writes/sec)
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

    // ── Anti-jitter: deadzone + exponential scaling + rate limiting ──
    // Deadzone: object within center ±4% of frame → no servo command
    private val DEADZONE = 0.04f
    // Exponential scaling: small errors → tiny commands, big errors → proportional
    // output = sign(err) * |err|^EXPO_POWER * EXPO_SCALE
    private val EXPO_POWER = 2.0f     // quadratic: 5% error → 0.25%, 20% error → 4%
    private val EXPO_SCALE = 400f     // compensate for quadratic shrinkage at large errors
    // Rate limiter: max change per frame (prevents ramp-up jerks)
    private val MAX_DELTA_PER_FRAME = 8f
    private var lastPanOutput = 0f
    private var lastTiltOutput = 0f

    private var cachedOutputBuffer: Array<Array<FloatArray>>? = null
    @Volatile private var closed = false

    // v2.8: pre-allocated buffers — reused every frame instead of allocating
    private var cachedInputBuffer: ByteBuffer? = null
    private var cachedPixels: IntArray? = null

    // v2.8: throttle logging — only log every Nth detection
    private var detectCount = 0L

    companion object {
        private const val TAG = "ObjectTracker"
        const val INPUT_SIZE  = 640
        const val NUM_CLASSES = 80
        private const val LOG_EVERY_N = 30  // log 1 in 30 detections

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
                // v2.8: try NNAPI first (Hexagon DSP on Snapdragon), then GPU, then CPU
                var delegateSet = false
                try {
                    val nnapiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate()
                    addDelegate(nnapiDelegate)
                    delegateSet = true
                    Log.i(TAG, "Using NNAPI delegate")
                } catch (_: Throwable) { /* NNAPI not available */ }
                if (!delegateSet) {
                    try {
                        val gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                        addDelegate(gpuDelegate)
                        Log.i(TAG, "Using GPU delegate")
                    } catch (_: Throwable) {
                        Log.i(TAG, "Using CPU (4 threads)")
                    }
                }
            }
        }
        val fd = context.assets.openFd(modelFile)
        val model = FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(model, opts)

        val inShape  = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        Log.i(TAG, "Input: ${inShape.contentToString()}, Output: ${outShape.contentToString()}")

        if (inShape.size == 4) {
            inputNchw = (inShape[1] == 3 && inShape[2] > 3)
            modelInputSize = if (inputNchw) inShape[2] else inShape[1]
        }

        if (outShape.size == 3) {
            outputTransposed = outShape[1] > outShape[2]
        }

        // Pre-allocate reusable buffers
        val sz = modelInputSize
        cachedInputBuffer = ByteBuffer.allocateDirect(4 * sz * sz * 3).order(ByteOrder.nativeOrder())
        cachedPixels = IntArray(sz * sz)

        Log.i(TAG, "Model: ${if (inputNchw) "NCHW" else "NHWC"}, size=$modelInputSize")
    }

    data class TrackResult(
        val found: Boolean,
        val panDelta: Float,
        val tiltDelta: Float,
        val detection: DetectionResult?
    )

    fun process(frame: Bitmap): TrackResult {
        if (closed) return TrackResult(false, 0f, 0f, null)
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
                val tracked = kalman.update(it, 0f)
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
            lastPanOutput = 0f; lastTiltOutput = 0f
            return TrackResult(false, 0f, 0f, null)
        }

        lastDetection = finalDetection
        trackingConfidence = finalDetection.confidence

        var targetCx = finalDetection.cx
        var targetCy = finalDetection.cy

        if (finalDetection.label == "cat") {
            targetCy -= 0.03f
        }

        val errX = targetCx - 0.5f
        val errY = targetCy - 0.5f

        // ── Anti-jitter pipeline: deadzone → expo scaling → PID → rate limit ──
        val absErrX = kotlin.math.abs(errX)
        val absErrY = kotlin.math.abs(errY)

        var pan  = 0f
        var tilt = 0f

        if (absErrX > DEADZONE || absErrY > DEADZONE) {
            // Exponential scaling: small error → tiny command, big error → proportional
            val scaledErrX = if (absErrX > DEADZONE) {
                val r = absErrX - DEADZONE
                kotlin.math.sign(errX) * Math.pow(r.toDouble(), EXPO_POWER.toDouble()).toFloat() * EXPO_SCALE
            } else 0f
            val scaledErrY = if (absErrY > DEADZONE) {
                val r = absErrY - DEADZONE
                kotlin.math.sign(errY) * Math.pow(r.toDouble(), EXPO_POWER.toDouble()).toFloat() * EXPO_SCALE
            } else 0f

            val panRaw  = pidPan.update(scaledErrX)
            val tiltRaw = pidTilt.update(scaledErrY)
            pan  = (panRaw * panSensitivity).coerceIn(-100f, 100f)
            tilt = (tiltRaw * tiltSensitivity).coerceIn(-100f, 100f)
        } else {
            // Inside deadzone — hold still, decay PID integral
            pidPan.reset()
            pidTilt.reset()
        }

        // Rate limiter: max ±MAX_DELTA_PER_FRAME change between frames
        pan  = (pan.coerceIn(lastPanOutput - MAX_DELTA_PER_FRAME, lastPanOutput + MAX_DELTA_PER_FRAME))
        tilt = (tilt.coerceIn(lastTiltOutput - MAX_DELTA_PER_FRAME, lastTiltOutput + MAX_DELTA_PER_FRAME))
        lastPanOutput = pan
        lastTiltOutput = tilt

        // v2.8: throttled logging — only every Nth detection
        detectCount++
        if (detectCount % LOG_EVERY_N == 0L) {
            val mode = if (shouldDetect) "DET" else "TRK"
            Log.d(TAG, "[$mode] ${finalDetection.label} ${(finalDetection.confidence*100).toInt()}% " +
                       "err(${"%.2f".format(errX)}, ${"%.2f".format(errY)}) #$detectCount")
        }

        return TrackResult(true, pan, tilt, finalDetection)
    }

    // ── Input buffer conversion (reuses pre-allocated buffers) ────────────

    private fun fillNhwcBuffer(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        val sz = modelInputSize
        buf.rewind()
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)
            buf.putFloat(( p         and 0xFF) / 255f)
        }
        buf.rewind()
    }

    private fun fillNchwBuffer(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        val sz = modelInputSize
        buf.rewind()
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(((p shr 8)  and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(( p         and 0xFF) / 255f)
        buf.rewind()
    }

    private fun detect(frame: Bitmap): DetectionResult? {
        if (closed) return null
        synchronized(this) {
            if (closed) return null
            val sz = modelInputSize
            val scaled = Bitmap.createScaledBitmap(frame, sz, sz, true)

            // v2.8: reuse pre-allocated buffers
            val buf = cachedInputBuffer!!
            val pixels = cachedPixels!!
            if (inputNchw) fillNchwBuffer(scaled, buf, pixels)
            else fillNhwcBuffer(scaled, buf, pixels)
            scaled.recycle()

            val outShape = interp.getOutputTensor(0).shape()
            val dim1 = outShape[1]
            val dim2 = outShape[2]

            val outputBuf = cachedOutputBuffer?.takeIf {
                it.size == 1 && it[0].size == dim1 && it[0][0].size == dim2
            } ?: Array(1) { Array(dim1) { FloatArray(dim2) } }.also {
                cachedOutputBuffer = it
            }

            interp.run(buf, outputBuf)
            return bestBox(outputBuf[0], dim1, dim2)
        }
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
    }

    fun isClosed(): Boolean = closed

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            interp.close()
            cachedInputBuffer = null
            cachedPixels = null
        }
    }
}
