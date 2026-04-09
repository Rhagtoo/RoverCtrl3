package org.rhanet.roverctrl.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
// v3.0 FIXES:
//   - Fix #1: INT8 model support — separate uint8 fill functions (was putFloat on 1-byte buffer!)
//   - Fix #5: Kalman predict-only between detections (was feeding stale measurement)
//   - Fix #7: @Volatile on tuning/sensitivity fields (cross-thread access)
//   - Fix #9: Kalman initialize() with first detection (was transient from 0,0)
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
    @Volatile private var panSensitivity:  Float = 1.0f,
    @Volatile private var tiltSensitivity: Float = 1.0f
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
    // @Volatile: tuning updated from main thread, read from analysis thread
    @Volatile private var DEADZONE = 0.04f
    @Volatile private var EXPO_POWER = 2.0f
    private val EXPO_SCALE = 400f     // fixed: compensates quadratic shrinkage
    @Volatile private var MAX_DELTA_PER_FRAME = 8f
    private var lastPanOutput = 0f
    private var lastTiltOutput = 0f

    // ── Acquisition ramp: soften initial tracking to prevent tilt overshoot ──
    // CR servo tilt is rate-controlled — target angle runs ahead of physical position
    // if commands ramp too fast. On first detection, limit output for RAMP_FRAMES.
    private var acquisitionFrame = 0          // counts up from 0 when tracking acquired
    private val RAMP_FRAMES = 25             // ~0.8s at 30fps: gradual increase
    private val TILT_RATE_FACTOR = 0.4f      // tilt rate limit = pan × this factor

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
    private var cachedScaledBitmap: Bitmap? = null

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

    // ── PID Auto-Tune (relay feedback test) ──
    private var autoTuner: PidAutoTuner? = null

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
                val wasTracking = isTracking
                isTracking = true
                framesSinceDetection = 0
                lastDetectionTime = currentTime
                // Fix #9: initialize Kalman with actual detection (not 0,0)
                kalman.initialize(detected)
                lastDetection = detected
                trackingConfidence = 1.0f
                // Reset acquisition ramp on new tracking start
                if (!wasTracking) {
                    acquisitionFrame = 0
                    lastPanOutput = 0f
                    lastTiltOutput = 0f
                    pidPan.reset()
                    pidTilt.reset()
                }
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
            // Fix #5: predict-only (no stale measurement) — extrapolate using velocity
            val predicted = kalman.predict()
            if (predicted.cx in 0f..1f && predicted.cy in 0f..1f &&
                predicted.w in 0f..1f && predicted.h in 0f..1f) {
                trackingConfidence *= 0.95f  // decay confidence during prediction
                predicted
            } else {
                isTracking = false
                kalman.reset()
                null
            }
        }

        val finalDetection = detection ?: run {
            // No detection this frame
            val tuner = autoTuner
            if (tuner != null && tuner.isRunning()) {
                // Notify tuner — it tracks gap duration and aborts if too long
                tuner.update(0f, false)
                return TrackResult(false, 0f, 0f, null)
            }
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

        // ── Auto-Tune mode: relay feedback bypasses entire anti-jitter pipeline ──
        val tuner = autoTuner
        if (tuner != null && tuner.isRunning()) {
            val axis = tuner.currentAxis
            val rawError = if (axis == PidAutoTuner.Axis.PAN) errX else errY
            val relayCmd = tuner.update(rawError, true)

            val pan: Float
            val tilt: Float
            if (axis == PidAutoTuner.Axis.PAN) {
                pan = relayCmd.coerceIn(-100f, 100f)
                tilt = 0f
            } else {
                pan = 0f
                tilt = relayCmd.coerceIn(-100f, 100f)
            }
            lastPanOutput = pan
            lastTiltOutput = tilt

            detectCount++
            if (detectCount % LOG_EVERY_N == 0L) {
                val (done, need) = tuner.progress()
                Log.d(TAG, "[AT:$axis] err=${"%.3f".format(rawError)} relay=${"%.0f".format(relayCmd)} $done/$need")
            }
            return TrackResult(true, pan, tilt, finalDetection)
        }

        // ── Normal mode: deadzone → expo scaling → PID → rate limit ──
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

        // ── Rate limiter with acquisition ramp + separate tilt limit ──
        // CR servo tilt: target angle runs ahead of physical position if commands
        // ramp too fast → massive overshoot. Limit tilt rate to TILT_RATE_FACTOR × pan rate.
        // On tracking acquisition: gradually ramp up rate limit over RAMP_FRAMES.
        acquisitionFrame++
        val rampFactor = (acquisitionFrame.toFloat() / RAMP_FRAMES).coerceIn(0.1f, 1.0f)
        val panRate  = MAX_DELTA_PER_FRAME * rampFactor
        val tiltRate = MAX_DELTA_PER_FRAME * TILT_RATE_FACTOR * rampFactor

        pan  = pan.coerceIn(lastPanOutput - panRate, lastPanOutput + panRate)
        tilt = tilt.coerceIn(lastTiltOutput - tiltRate, lastTiltOutput + tiltRate)
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

    // ── Float32 model: NHWC layout ──
    private fun fillNhwcFloat(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
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

    // ── Float32 model: NCHW layout ──
    private fun fillNchwFloat(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        val sz = modelInputSize
        buf.rewind()
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(((p shr 8)  and 0xFF) / 255f)
        for (p in pixels) buf.putFloat(( p         and 0xFF) / 255f)
        buf.rewind()
    }

    // ── INT8 (uint8) model: NHWC layout — raw 0..255 bytes, no normalization ──
    private fun fillNhwcInt8(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        val sz = modelInputSize
        buf.rewind()
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8)  and 0xFF).toByte())
            buf.put(( p         and 0xFF).toByte())
        }
        buf.rewind()
    }

    // ── INT8 (uint8) model: NCHW layout — raw 0..255 bytes ──
    private fun fillNchwInt8(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        val sz = modelInputSize
        buf.rewind()
        bmp.getPixels(pixels, 0, sz, 0, 0, sz, sz)
        for (p in pixels) buf.put(((p shr 16) and 0xFF).toByte())
        for (p in pixels) buf.put(((p shr 8)  and 0xFF).toByte())
        for (p in pixels) buf.put(( p         and 0xFF).toByte())
        buf.rewind()
    }

    /** Fill input buffer with correct format based on model type and layout */
    private fun fillInputBuffer(bmp: Bitmap, buf: ByteBuffer, pixels: IntArray) {
        if (modelIsInt8) {
            if (inputNchw) fillNchwInt8(bmp, buf, pixels) else fillNhwcInt8(bmp, buf, pixels)
        } else {
            if (inputNchw) fillNchwFloat(bmp, buf, pixels) else fillNhwcFloat(bmp, buf, pixels)
        }
    }

    private fun detect(frame: Bitmap): DetectionResult? {
        if (closed) return null
        synchronized(this) {
            if (closed) return null
            val sz = modelInputSize

            // Reuse scaled bitmap (eliminates 1.6MB alloc per frame)
            val scaled = cachedScaledBitmap?.takeIf {
                !it.isRecycled && it.width == sz && it.height == sz
            } ?: Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also {
                cachedScaledBitmap = it
            }
            val canvas = Canvas(scaled)
            canvas.drawBitmap(frame,
                Rect(0, 0, frame.width, frame.height),
                Rect(0, 0, sz, sz), null)

            val buf = cachedInputBuffer!!
            val pixels = cachedPixels!!
            fillInputBuffer(scaled, buf, pixels)

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

        // YOLO26 NMS-free: 6 attrs = [x1, y1, x2, y2, conf, classId]
        // YOLOv8: 84 attrs = [cx, cy, w, h, 80×class_scores] or 85 with objectness
        val isYolo26 = (numAttrs == 6)
        val isYoloV8 = (numAttrs == 84 || numAttrs == 85)
        if (!isYoloV8 && !isYolo26) {
            Log.w(TAG, "Unknown output format: ${dim1}x${dim2}, numAttrs=$numAttrs")
            return null
        }

        var maxCoord = 0f
        for (j in 0 until minOf(50, numBoxes)) {
            val cx = if (isTransposed) out[j][0] else out[0][j]
            val cy = if (isTransposed) out[j][1] else out[1][j]
            maxCoord = maxOf(maxCoord, cx, cy)
        }
        val scale = if (maxCoord > 2.0f) modelInputSize.toFloat() else 1.0f

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

    /** Full PID gain update (from auto-tune or manual) */
    fun updatePidGainsFull(
        panKp: Float, panKi: Float, panKd: Float,
        tiltKp: Float, tiltKi: Float, tiltKd: Float
    ) {
        pidPan.kp = panKp; pidPan.ki = panKi; pidPan.kd = panKd
        pidTilt.kp = tiltKp; pidTilt.ki = tiltKi; pidTilt.kd = tiltKd
        pidPan.reset(); pidTilt.reset()
        Log.i(TAG, "PID updated: pan(%.1f/%.2f/%.1f) tilt(%.1f/%.2f/%.1f)"
            .format(panKp, panKi, panKd, tiltKp, tiltKi, tiltKd))
    }

    fun updateSensitivity(panSens: Float, tiltSens: Float) {
        panSensitivity = panSens
        tiltSensitivity = tiltSens
    }

    // ── Auto-Tune ─────────────────────────────────────────────────────────

    /** Start PID auto-tune relay test. Tuner takes over pan/tilt output until complete. */
    fun startAutoTune(
        method: PidAutoTuner.TuningMethod = PidAutoTuner.TuningMethod.TYREUS_LUYBEN,
        onProgress: ((PidAutoTuner.Axis, Int, Int) -> Unit)? = null,
        onAxisDone: ((PidAutoTuner.AxisResult) -> Unit)? = null,
        onComplete: ((PidAutoTuner.FullResult) -> Unit)? = null,
        onFailed: ((PidAutoTuner.Axis, String) -> Unit)? = null
    ) {
        pidPan.reset(); pidTilt.reset()
        lastPanOutput = 0f; lastTiltOutput = 0f

        val tuner = PidAutoTuner(relayAmplitude = 45f, hysteresis = 0.015f, minCycles = 4)
        tuner.method = method
        tuner.onProgress = onProgress
        tuner.onAxisDone = onAxisDone
        tuner.onComplete = { result ->
            autoTuner = null // exit auto-tune mode
            // Apply gains if valid
            if (result.panValid && result.pan != null) {
                pidPan.kp = result.pan.kp
                pidPan.ki = result.pan.ki
                pidPan.kd = result.pan.kd
            }
            if (result.tiltValid && result.tilt != null) {
                pidTilt.kp = result.tilt.kp
                pidTilt.ki = result.tilt.ki
                pidTilt.kd = result.tilt.kd
            }
            pidPan.reset(); pidTilt.reset()
            onComplete?.invoke(result)
        }
        tuner.onFailed = { axis, reason ->
            autoTuner = null
            pidPan.reset(); pidTilt.reset()
            onFailed?.invoke(axis, reason)
        }
        autoTuner = tuner
        tuner.startFull()
        Log.i(TAG, "Auto-tune started (${method.label})")
    }

    fun abortAutoTune() {
        autoTuner?.abort()
        autoTuner = null
        pidPan.reset(); pidTilt.reset()
        lastPanOutput = 0f; lastTiltOutput = 0f
        Log.i(TAG, "Auto-tune aborted")
    }

    fun isAutoTuning(): Boolean = autoTuner?.isRunning() == true

    fun isClosed(): Boolean = closed

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            interp.close()
            cachedScaledBitmap?.recycle()
            cachedScaledBitmap = null
            cachedInputBuffer = null
            cachedPixels = null
        }
    }
}
