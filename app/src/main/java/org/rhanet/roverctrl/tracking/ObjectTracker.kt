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
    modelFile:        String = "yolov8n.tflite",      // float32 fallback
    private val int8ModelFile: String = "yolov8n_int8.tflite",  // preferred INT8
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
    // Configurable via AppSettings sliders
    private var DEADZONE = 0.04f
    private var EXPO_POWER = 2.0f
    private val EXPO_SCALE = 400f     // fixed: compensates quadratic shrinkage
    private var MAX_DELTA_PER_FRAME = 8f
    private var lastPanOutput = 0f
    private var lastTiltOutput = 0f

    /** Update tracking tuning from AppSettings (called from VideoFragment on settings change) */
    fun updateTrackingTuning(deadzone: Float, expo: Float, rateLimit: Float) {
        DEADZONE = deadzone.coerceIn(0.01f, 0.15f)
        EXPO_POWER = expo.coerceIn(1.0f, 3.0f)
        MAX_DELTA_PER_FRAME = rateLimit.coerceIn(2f, 30f)
        Log.d(TAG, "Tuning: dz=$DEADZONE expo=$EXPO_POWER rate=$MAX_DELTA_PER_FRAME")
    }

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

    // Whether model expects uint8 input (INT8 quantized) vs float32
    private var modelIsInt8 = false

    init {
        val opts = Interpreter.Options().apply {
            numThreads = 4
            // YOLO26 models are incompatible with NNAPI (ANEURALNETWORKS_BAD_DATA)
            val skipDelegates = modelFile.contains("yolo26", ignoreCase = true)
            if (useGpu && !skipDelegates) {
                var delegateSet = false
                try {
                    val nnapiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate()
                    addDelegate(nnapiDelegate)
                    delegateSet = true
                    Log.i(TAG, "Using NNAPI delegate")
                } catch (_: Throwable) {}
                if (!delegateSet) {
                    try {
                        val gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                        addDelegate(gpuDelegate)
                        Log.i(TAG, "Using GPU delegate")
                    } catch (_: Throwable) {
                        Log.i(TAG, "Using CPU (4 threads)")
                    }
                }
            } else if (skipDelegates) {
                Log.i(TAG, "YOLO26 model detected, skipping NNAPI/GPU delegates (CPU only)")
            } else {
                Log.i(TAG, "Using CPU (4 threads)")
            }
        }

        // Try INT8 model first (2-3× faster on NNAPI), fall back to float32
        val actualModelFile = try {
            context.assets.openFd(int8ModelFile).close()
            Log.i(TAG, "Found INT8 model: $int8ModelFile")
            int8ModelFile
        } catch (_: Throwable) {
            Log.i(TAG, "INT8 model not found, using float32: $modelFile")
            modelFile
        }

        val fd = context.assets.openFd(actualModelFile)
        val model = FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(model, opts)

        val inShape  = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        val inType   = interp.getInputTensor(0).dataType()
        modelIsInt8  = (inType.name == "UINT8")
        Log.i(TAG, "Model: $actualModelFile, Input: ${inShape.contentToString()} $inType, Output: ${outShape.contentToString()}")
        Log.i(TAG, "INT8 mode: $modelIsInt8")

        if (inShape.size == 4) {
            inputNchw = (inShape[1] == 3 && inShape[2] > 3)
            modelInputSize = if (inputNchw) inShape[2] else inShape[1]
        }

        if (outShape.size == 3) {
            outputTransposed = outShape[1] > outShape[2]
        }

        // Pre-allocate reusable buffers
        val sz = modelInputSize
        val bytesPerPixel = if (modelIsInt8) 1 else 4  // uint8=1B, float32=4B
        cachedInputBuffer = ByteBuffer.allocateDirect(bytesPerPixel * sz * sz * 3).order(ByteOrder.nativeOrder())
        cachedPixels = IntArray(sz * sz)

        Log.i(TAG, "Format: ${if (inputNchw) "NCHW" else "NHWC"}, size=$modelInputSize, " +
                   "buf=${bytesPerPixel * sz * sz * 3 / 1024}KB")
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

        Log.d(TAG, "bestBox shape: $dim1 x $dim2, numAttrs=$numAttrs, numBoxes=$numBoxes")

        // Determine model type based on numAttrs
        val isYolo26 = numAttrs == 6 || numAttrs == 85  // 6: x1,y1,x2,y2,conf,class; 85: cx,cy,w,h,conf + 80 classes
        val isYoloV8 = numAttrs == 84 || numAttrs == 85 // 84: cx,cy,w,h + 80 classes
        if (!isYoloV8 && !isYolo26) {
            Log.w(TAG, "Unexpected output attrs: $numAttrs, shape: $dim1 x $dim2")
            // Fallback: try to treat as YOLO26 with 6 attrs
        }

        var maxCoord = 0f
        for (j in 0 until minOf(50, numBoxes)) {
            val cx = if (isTransposed) out[j][0] else out[0][j]
            val cy = if (isTransposed) out[j][1] else out[1][j]
            maxCoord = maxOf(maxCoord, cx, cy)
        }
        val scale = if (maxCoord > 2.0f) modelInputSize.toFloat() else 1.0f
        Log.d(TAG, "maxCoord=$maxCoord, scale=$scale")

        // Collect detections
        val boxes = mutableListOf<Box>()

        for (j in 0 until numBoxes) {
            // Extract coordinates and class scores based on model type
            val cx: Float; val cy: Float; val w: Float; val h: Float
            var maxScore = 0f; var maxCls = 0

            if (isYoloV8) {
                // YOLOv8 format: [cx, cy, w, h, class0, class1, ...] (84 attrs) or [..., conf] (85 attrs)
                if (isTransposed) {
                    cx = out[j][0]; cy = out[j][1]; w = out[j][2]; h = out[j][3]
                } else {
                    cx = out[0][j]; cy = out[1][j]; w = out[2][j]; h = out[3][j]
                }
                for (c in 0 until NUM_CLASSES) {
                    val s = if (isTransposed) out[j][4 + c] else out[4 + c][j]
                    if (s > maxScore) { maxScore = s; maxCls = c }
                }
            } else {
                // Assume YOLO26 format with 6 or 85 attrs
                // If numAttrs == 6: [x1, y1, x2, y2, conf, class]
                // If numAttrs == 85: [cx, cy, w, h, conf, class0, class1, ...] (unlikely)
                // We assume the first 4 values are bounding box coordinates.
                // For simplicity, treat as x1,y1,x2,y2 and convert to cx,cy,w,h.
                val x1: Float; val y1: Float; val x2: Float; val y2: Float
                if (isTransposed) {
                    x1 = out[j][0]; y1 = out[j][1]; x2 = out[j][2]; y2 = out[j][3]
                    maxScore = out[j][4]
                    maxCls   = out[j][5].toInt()
                } else {
                    x1 = out[0][j]; y1 = out[1][j]; x2 = out[2][j]; y2 = out[3][j]
                    maxScore = out[4][j]
                    maxCls   = out[5][j].toInt()
                }
                cx = (x1 + x2) / 2f
                cy = (y1 + y2) / 2f
                w  = x2 - x1
                h  = y2 - y1
            }

            // Apply target class filter (if any)
            if (targetClass >= 0) {
                // For YOLO26 we already have class id; check if matches targetClass
                if (maxCls != targetClass) continue
                // Confidence already in maxScore
            } else {
                if (maxScore < confThresh) continue
            }

            // Filter classes: only person (0) and cat (15) allowed
            if (maxCls != 0 && maxCls != 15) continue

            boxes += Box(cx / scale, cy / scale, w / scale, h / scale, maxScore, maxCls)
        }

        if (boxes.isEmpty()) return null

        // For YOLO26, NMS is not needed (model is NMS-free). Just pick highest confidence.
        // For YOLOv8, perform NMS as before.
        if (isYoloV8) {
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
        } else {
            // YOLO26: pick highest confidence detection
            val b = boxes.maxByOrNull { it.score } ?: return null
            return DetectionResult(
                cx = b.cx, cy = b.cy, w = b.w, h = b.h,
                confidence = b.score,
                label = COCO_LABELS.getOrElse(b.classId) { "cls${b.classId}" }
            )
        }
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
