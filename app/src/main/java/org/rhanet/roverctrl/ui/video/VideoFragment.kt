package org.rhanet.roverctrl.ui.video

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.DetectionResult
import org.rhanet.roverctrl.data.TrackingMode
import org.rhanet.roverctrl.tracking.CalibrationData
import org.rhanet.roverctrl.tracking.LaserTracker
import org.rhanet.roverctrl.tracking.LatencyTracker
import org.rhanet.roverctrl.tracking.ObjectTracker
import org.rhanet.roverctrl.tracking.PidAutoTuner
import org.rhanet.roverctrl.ui.RoverViewModel
import org.rhanet.roverctrl.ui.control.JoystickView
import androidx.navigation.fragment.findNavController
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoFragment v2.8
 *
 * PERF changes:
 *   - Inference gate (AtomicBoolean): prevents executor queue buildup
 *     If inference is running, new XIAO frames are skipped instead of queued
 *   - Dropped frames counted in latency HUD
 *   - Reduced per-frame allocations
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class VideoFragment : Fragment() {
    companion object { private const val TAG = "VideoFrag" }

    private val vm: RoverViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val latency = LatencyTracker(windowSize = 30)

    // v2.8: inference gate — prevents queueing frames when analysis is busy
    private val inferenceRunning = AtomicBoolean(false)
    private var droppedAnalysisFrames = 0L

    private lateinit var previewView:       PreviewView
    private lateinit var overlay:           TrackingOverlayView
    private lateinit var overlayXiao:       TrackingOverlayView
    private lateinit var spinnerMode:       Spinner
    private lateinit var tvFps:             TextView
    private lateinit var tvStatus:          TextView
    private lateinit var tvLatency:         TextView

    private lateinit var pipContainer:      FrameLayout
    private lateinit var ivTurretPip:       ImageView
    private lateinit var tvTurretFps:       TextView
    private lateinit var tvPipSourceLabel:  TextView
    private lateinit var btnPipVideo:       ToggleButton
    private lateinit var btnSwapVideo:      ToggleButton
    private lateinit var ivXiaoMain:        ImageView
    private lateinit var tvMainSourceLabel: TextView

    private lateinit var joystickDrive:     JoystickView
    private lateinit var joystickCam:       JoystickView
    private lateinit var tvDriveLabel:      TextView
    private lateinit var tvCamLabel:        TextView
    private lateinit var btnLaserVideo:     ToggleButton
    private lateinit var btnAutoTune:       Button

    private var swapped = false
    private var laserTracker:  LaserTracker?  = null
    private var objectTracker: ObjectTracker? = null

    private fun trackingOverlay(): TrackingOverlayView = if (swapped) overlayXiao else overlay
    private var cameraProvider: ProcessCameraProvider? = null
    private var xiaoAnalysisJob: Job? = null

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var lastCameraFps = 0f
    private var lastLatencyUpdate = 0L

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(requireContext(), "Camera needed", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_video, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView        = view.findViewById(R.id.preview_view)
        overlay            = view.findViewById(R.id.overlay)
        overlayXiao        = view.findViewById(R.id.overlay_xiao)
        spinnerMode        = view.findViewById(R.id.spinner_mode)
        tvFps              = view.findViewById(R.id.tv_fps)
        tvStatus           = view.findViewById(R.id.tv_status)
        tvLatency          = view.findViewById(R.id.tv_latency)
        ivXiaoMain         = view.findViewById(R.id.iv_xiao_main)
        tvMainSourceLabel  = view.findViewById(R.id.tv_main_source_label)
        pipContainer       = view.findViewById(R.id.pip_container_video)
        ivTurretPip        = view.findViewById(R.id.iv_turret_pip_video)
        tvTurretFps        = view.findViewById(R.id.tv_turret_fps_video)
        tvPipSourceLabel   = view.findViewById(R.id.tv_pip_source_label)
        btnPipVideo        = view.findViewById(R.id.btn_pip_video)
        btnSwapVideo       = view.findViewById(R.id.btn_swap_video)
        joystickDrive      = view.findViewById(R.id.joystick_drive_video)
        joystickCam        = view.findViewById(R.id.joystick_cam_video)
        tvDriveLabel       = view.findViewById(R.id.tv_drive_label_video)
        tvCamLabel         = view.findViewById(R.id.tv_cam_label_video)
        btnLaserVideo      = view.findViewById(R.id.btn_laser_video)
        btnAutoTune        = view.findViewById(R.id.btn_auto_tune)

        val toolbarVideo = view.findViewById<View>(R.id.toolbar_video)
        ViewCompat.setOnApplyWindowInsetsListener(toolbarVideo) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(joystickCam) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, sb.right, v.paddingBottom)
            insets
        }

        tvLatency.text = "-- ms"
        tvLatency.visibility = View.VISIBLE

        setupModeSpinner()
        setupPip()
        setupSwap()
        setupOverlayControls()

        view.findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            findNavController().navigate(R.id.action_video_to_calibration)
        }

        btnAutoTune.setOnClickListener { showAutoTuneDialog() }

        // Load PID gains from settings and apply to ObjectTracker
        val settings = vm.sensitivity.value
        CalibrationData.load(requireContext())?.let {
            vm.pidPan.kp = it.optimalPanKp()
            vm.pidTilt.kp = it.optimalTiltKp()
        }
        // AppSettings PID gains override CalibrationData if non-default
        if (settings.pidPanKp != 100f || settings.pidPanKi != 0.2f) {
            objectTracker?.updatePidGainsFull(
                settings.pidPanKp, settings.pidPanKi, settings.pidPanKd,
                settings.pidTiltKp, settings.pidTiltKi, settings.pidTiltKd
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.trackMode.collectLatest { mode ->
                updateOverlayVisibility(mode)
                tvStatus.text = when (mode) {
                    TrackingMode.MANUAL       -> "Manual"
                    TrackingMode.LASER_DOT    -> "Laser"
                    TrackingMode.OBJECT_TRACK -> "YOLO"
                    TrackingMode.GYRO_TILT    -> "Gyro"
                }
                if (mode == TrackingMode.MANUAL || mode == TrackingMode.GYRO_TILT) {
                    updateLatencyManual()
                }
                updateOverlayControlsVisibility(mode)
                updateXiaoAnalysis()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.sensitivity.collectLatest { settings ->
                if (objectTracker != null && vm.trackMode.value == TrackingMode.OBJECT_TRACK) {
                    objectTracker?.updateSensitivity(settings.camPanSens, settings.camTiltSens)
                    objectTracker?.updateTrackingTuning(
                        settings.trackDeadzone, settings.trackExpo, settings.trackRateLimit)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Latency ──────────────────────────────────────────────────────────

    private fun updateLatencyManual() {
        handler.post {
            tvLatency.text = if (lastCameraFps > 0f) "fps: %.0f".format(lastCameraFps) else "--"
            tvLatency.setTextColor(0xFFAAAAAA.toInt())
        }
    }

    private fun maybeUpdateLatencyHud() {
        val now = System.currentTimeMillis()
        if (now - lastLatencyUpdate < 500) return
        lastLatencyUpdate = now
        val snap = latency.snapshot()
        if (snap.count == 0) return
        handler.post {
            val totalMs = snap.avgTotalMs
            val color = when {
                totalMs < 30f -> 0xFF00E676.toInt()
                totalMs < 80f -> 0xFFFFAB00.toInt()
                else          -> 0xFFFF5252.toInt()
            }
            tvLatency.setTextColor(color)
            // v2.8: show dropped frames count so user knows if pipeline is overloaded
            val dropInfo = if (droppedAnalysisFrames > 0) " d:$droppedAnalysisFrames" else ""
            tvLatency.text = "lat: %.0f ms · fps: %.0f%s".format(totalMs, lastCameraFps, dropInfo)
        }
    }

    // ── PiP ──────────────────────────────────────────────────────────────

    private fun setupPip() {
        btnPipVideo.setOnCheckedChangeListener { _, c ->
            pipContainer.visibility = if (c) View.VISIBLE else View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFrame.collectLatest { bmp ->
                if (bmp != null) {
                    if (swapped) {
                        ivXiaoMain.setImageBitmap(bmp)
                    }
                    else if (pipContainer.visibility == View.VISIBLE) ivTurretPip.setImageBitmap(bmp)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFps.collectLatest { fps ->
                tvTurretFps.text = if (fps > 0) "%.0f".format(fps) else "--"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretConnected.collectLatest { if (it && !btnPipVideo.isChecked) btnPipVideo.isChecked = true }
        }
    }

    // ── Swap ─────────────────────────────────────────────────────────────

    private fun setupSwap() {
        btnSwapVideo.setOnCheckedChangeListener { _, c -> setSwapped(c) }
        pipContainer.setOnClickListener { btnSwapVideo.isChecked = !swapped }
    }

    private fun setSwapped(s: Boolean) {
        Log.d(TAG, "setSwapped: $s")
        swapped = s
        val mode = vm.trackMode.value
        if (swapped) {
            ivXiaoMain.visibility = View.VISIBLE
            previewView.visibility = View.INVISIBLE
            tvMainSourceLabel.text = "XIAO"
            tvMainSourceLabel.visibility = View.VISIBLE
            tvPipSourceLabel.text = "PHONE"
            pipContainer.visibility = View.GONE
            btnPipVideo.isChecked = false
        } else {
            ivXiaoMain.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            tvMainSourceLabel.visibility = View.GONE
            tvPipSourceLabel.text = "TURRET"
        }
        updateOverlayVisibility(mode)
        updateXiaoAnalysis()
    }

    private fun updateOverlayVisibility(mode: TrackingMode) {
        val showTrackingOverlay = mode == TrackingMode.LASER_DOT || mode == TrackingMode.OBJECT_TRACK
        val trackingActive = mode != TrackingMode.MANUAL && mode != TrackingMode.GYRO_TILT
        overlay.trackingActive = trackingActive
        overlayXiao.trackingActive = trackingActive
        if (swapped) {
            overlay.visibility = View.GONE
            overlayXiao.visibility = if (showTrackingOverlay) View.VISIBLE else View.GONE
        } else {
            overlay.visibility = if (showTrackingOverlay) View.VISIBLE else View.GONE
            overlayXiao.visibility = View.GONE
        }
    }

    // ── XIAO Frame Analysis (swapped + tracking) ─────────────────────────

    private fun updateXiaoAnalysis() {
        xiaoAnalysisJob?.cancel()
        val mode = vm.trackMode.value
        if (swapped && (mode == TrackingMode.LASER_DOT || mode == TrackingMode.OBJECT_TRACK)) {
            xiaoAnalysisJob = viewLifecycleOwner.lifecycleScope.launch {
                vm.turretFrame.collectLatest { bmp ->
                    if (bmp != null && swapped) {
                        // v2.8: inference gate — skip frame if previous inference still running
                        if (!inferenceRunning.compareAndSet(false, true)) {
                            droppedAnalysisFrames++
                            return@collectLatest
                        }
                        // Must copy: turretFrame can be recycled when next frame arrives
                        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        if (copy == null) {
                            inferenceRunning.set(false)
                            return@collectLatest
                        }
                        analysisExecutor.execute {
                            try {
                                processXiaoFrame(copy)
                            } finally {
                                inferenceRunning.set(false)
                            }
                        }
                    }
                }
            }
        } else {
            inferenceRunning.set(false)
        }
    }

    private fun processXiaoFrame(bitmap: Bitmap) {
        val ft = latency.beginFrame()
        latency.mark(ft, LatencyTracker.Stage.DECODED)
        latency.mark(ft, LatencyTracker.Stage.INFERENCE_START)
        handleTracking(bitmap, bitmap.width, bitmap.height, ft)
        bitmap.recycle()
        maybeUpdateLatencyHud()
    }

    /**
     * Common tracking handler for both phone camera and XIAO frames.
     * Fix #3: zeroes pan/tilt when object is lost (prevents stale commands).
     * Fix #10: eliminates code duplication between processFrame/processXiaoFrame.
     */
    private fun handleTracking(bitmap: Bitmap, imgW: Int, imgH: Int, ft: LatencyTracker.FrameTimings) {
        val mode = vm.trackMode.value

        when (mode) {
            TrackingMode.LASER_DOT -> {
                val r = laserTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    vm.laserOn = (r.detection?.label ?: "") == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    postOverlay(imgW, imgH, r.detection)
                } else {
                    // Fix #3: zero commands when tracking lost
                    vm.setPanTilt(0, 0)
                    postOverlay(imgW, imgH, null)
                }
            }
            TrackingMode.OBJECT_TRACK -> {
                val r = objectTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    vm.laserOn = (r.detection?.label ?: "") == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    postOverlay(imgW, imgH, r.detection)
                } else {
                    // Fix #3: zero commands when tracking lost
                    vm.setPanTilt(0, 0)
                    postOverlay(imgW, imgH, null)
                }
            }
            else -> {}
        }
    }

    /** Post detection result to overlay on main thread */
    private fun postOverlay(imgW: Int, imgH: Int, detection: DetectionResult?) {
        handler.post {
            val ov = trackingOverlay()
            ov.sourceImageWidth = imgW
            ov.sourceImageHeight = imgH
            ov.detection = detection
        }
    }

    // ── Overlay Controls ─────────────────────────────────────────────────

    private fun setupOverlayControls() {
        joystickDrive.onMove = { x, y ->
            vm.setDriveCmd((y*100).toInt(), (x*100).toInt(), (y*100).toInt())
        }
        joystickCam.onMove = { x, y ->
            if (vm.trackMode.value == TrackingMode.MANUAL)
                vm.setPanTilt((x*100).toInt(), (y*100).toInt())
        }
        btnLaserVideo.setOnCheckedChangeListener { _, c -> vm.laserOn = c }
        updateOverlayControlsVisibility(vm.trackMode.value)
    }

    private fun updateOverlayControlsVisibility(mode: TrackingMode) {
        val showDrive = when (mode) {
            TrackingMode.MANUAL, TrackingMode.LASER_DOT, TrackingMode.OBJECT_TRACK, TrackingMode.GYRO_TILT -> View.VISIBLE
            else -> View.GONE
        }
        val showCam = when (mode) {
            TrackingMode.MANUAL, TrackingMode.GYRO_TILT -> View.VISIBLE
            else -> View.GONE
        }
        joystickDrive.visibility  = showDrive
        joystickCam.visibility    = showCam
        tvDriveLabel.visibility   = showDrive
        tvCamLabel.visibility     = showCam
        btnLaserVideo.visibility  = showDrive
        // Auto-tune only available in OBJECT_TRACK mode
        btnAutoTune.visibility = if (mode == TrackingMode.OBJECT_TRACK) View.VISIBLE else View.GONE
    }

    // ── PID Auto-Tune Dialog ─────────────────────────────────────────────

    private fun showAutoTuneDialog() {
        val tracker = objectTracker ?: run {
            Toast.makeText(requireContext(), "Start YOLO tracking first", Toast.LENGTH_SHORT).show()
            return
        }

        // If already running → offer abort
        if (tracker.isAutoTuning()) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Auto-Tune Running")
                .setMessage("Abort current test?")
                .setPositiveButton("Abort") { _, _ ->
                    tracker.abortAutoTune()
                    btnAutoTune.text = "AT"
                    tvStatus.text = "YOLO"
                }
                .setNegativeButton("Continue", null)
                .show()
            return
        }

        // Method selection dialog
        val methods = PidAutoTuner.TuningMethod.entries
        val labels = methods.map { it.label }.toTypedArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("PID Auto-Tune")
            .setMessage("Aim camera at a visible object.\nRelay test ≈30s — camera will oscillate.")
            .setSingleChoiceItems(labels, 1, null)  // default: Tyreus-Luyben
            .setPositiveButton("Start") { dlg, _ ->
                val selected = (dlg as android.app.AlertDialog).listView.checkedItemPosition
                val method = methods[selected]
                startAutoTune(tracker, method)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAutoTune(tracker: ObjectTracker, method: PidAutoTuner.TuningMethod) {
        handler.post {
            tvStatus.text = "AT:PAN…"
            btnAutoTune.text = "STOP"
        }

        tracker.startAutoTune(
            method = method,
            onProgress = { axis, done, need ->
                handler.post { tvStatus.text = "AT:${axis.name} $done/$need" }
            },
            onAxisDone = { result ->
                handler.post {
                    if (result.valid) {
                        tvStatus.text = "AT:${result.axis}✓ → ${if (result.axis == PidAutoTuner.Axis.PAN) "TILT…" else "done"}"
                    }
                    Log.i(TAG, "AT ${result.axis}: Ku=%.0f Tu=%.3fs → Kp=%.0f Ki=%.2f Kd=%.1f (%d cyc)"
                        .format(result.ku, result.tu, result.kp, result.ki, result.kd, result.cycles))
                }
            },
            onComplete = { result ->
                handler.post {
                    btnAutoTune.text = "AT"
                    tvStatus.text = "YOLO"
                    showAutoTuneResult(tracker, result)
                }
            },
            onFailed = { axis, reason ->
                handler.post {
                    btnAutoTune.text = "AT"
                    tvStatus.text = "YOLO"
                    Toast.makeText(requireContext(),
                        "Auto-Tune failed ($axis): $reason", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showAutoTuneResult(tracker: ObjectTracker, result: PidAutoTuner.FullResult) {
        val msg = buildString {
            append("Method: ${result.method.label}\n\n")
            result.pan?.let { p ->
                append("PAN:  Kp=%.0f  Ki=%.2f  Kd=%.1f".format(p.kp, p.ki, p.kd))
                if (!p.valid) append("  ⚠ ${p.reason}")
                append("\n  Ku=%.0f  Tu=%.3fs  amp=%.3f  (%d cyc)\n\n".format(p.ku, p.tu, p.oscillationAmplitude, p.cycles))
            } ?: append("PAN: skipped\n\n")
            result.tilt?.let { t ->
                append("TILT: Kp=%.0f  Ki=%.2f  Kd=%.1f".format(t.kp, t.ki, t.kd))
                if (!t.valid) append("  ⚠ ${t.reason}")
                append("\n  Ku=%.0f  Tu=%.3fs  amp=%.3f  (%d cyc)\n".format(t.ku, t.tu, t.oscillationAmplitude, t.cycles))
            } ?: append("TILT: skipped\n")
            if (!result.isValid) append("\n⚠ Partial result — only valid axes applied")
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (result.isValid) "Auto-Tune Complete ✓" else "Auto-Tune Partial")
            .setMessage(msg)
            .setPositiveButton("Keep") { _, _ ->
                // Gains already applied by ObjectTracker.startAutoTune callback
                Log.i(TAG, "Auto-tune gains applied")
            }
            .setNegativeButton("Revert") { _, _ ->
                // Revert to defaults (kp=100, ki=0.2, kd=6 for both axes)
                tracker.updatePidGainsFull(100f, 0.2f, 6f, 100f, 0.2f, 6f)
                Log.i(TAG, "Auto-tune gains reverted to defaults")
            }
            .setCancelable(false)
            .show()
    }

    // ── CameraX ──────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val prov = future.get(); cameraProvider = prov; prov.unbindAll()

            val pb = Preview.Builder()
            Camera2Interop.Extender(pb).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val ab = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(320, 240))
            Camera2Interop.Extender(ab).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
            val analysis = ab.build()
            analysis.setAnalyzer(analysisExecutor, ::processFrame)

            try {
                val cam = prov.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                try {
                    Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                        CaptureRequestOptions.Builder().setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(30, 60)).build())
                } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.e(TAG, "Camera fallback", e)
                prov.unbindAll()
                val fp = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val fa = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(320, 240)).build()
                fa.setAnalyzer(analysisExecutor, ::processFrame)
                prov.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, fp, fa)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── CameraX Frame Processing ──────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            lastCameraFps = frameCount * 1000f / (now - lastFpsTime)
            handler.post { tvFps.text = "fps: %.0f".format(lastCameraFps) }
            frameCount = 0
            lastFpsTime = now
            val mode = vm.trackMode.value
            if (mode == TrackingMode.MANUAL || mode == TrackingMode.GYRO_TILT) {
                updateLatencyManual()
            }
        }

        val mode = vm.trackMode.value
        if (swapped || mode == TrackingMode.MANUAL || mode == TrackingMode.GYRO_TILT) {
            imageProxy.close()
            return
        }

        // Fix #6: phone camera tracking controls turret servos but viewpoints differ.
        // This only makes sense when phone and turret are roughly co-located.
        // Prefer swapped mode (XIAO as source) for accurate closed-loop tracking.

        val ft = latency.beginFrame()
        val originalWidth = imageProxy.width
        val originalHeight = imageProxy.height
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        bitmap ?: return

        latency.mark(ft, LatencyTracker.Stage.DECODED)
        latency.mark(ft, LatencyTracker.Stage.INFERENCE_START)

        // Fix #10: use common handler instead of duplicated tracking blocks
        handleTracking(bitmap, originalWidth, originalHeight, ft)
        bitmap.recycle()
        maybeUpdateLatencyHud()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            return try {
                val buf = image.planes[0].buffer; buf.rewind()
                val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                applyRotation(bmp, image.imageInfo.rotationDegrees)
            } catch (_: Throwable) { null }
        }
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yB = y.buffer; val uB = u.buffer; val vB = v.buffer
        val nv21 = ByteArray(yB.remaining() + uB.remaining() + vB.remaining())
        val yS = yB.remaining(); yB.get(nv21, 0, yS)
        val vBytes = ByteArray(vB.remaining()); vB.get(vBytes)
        val uBytes = ByteArray(uB.remaining()); uB.get(uBytes)
        if (v.pixelStride == 2) System.arraycopy(vBytes, 0, nv21, yS, vBytes.size)
        else {
            var o = yS
            for (i in 0 until minOf(vBytes.size, uBytes.size)) {
                nv21[o++] = vBytes[i]; nv21[o++] = uBytes[i]
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null
        return applyRotation(bmp, image.imageInfo.rotationDegrees)
    }

    private fun applyRotation(bmp: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return bmp
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    private fun setupModeSpinner() {
        val modes = resources.getStringArray(R.array.tracking_modes)
        spinnerMode.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val m = TrackingMode.entries[pos]
                vm.setTrackMode(m)
                initTracker(m)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun initTracker(mode: TrackingMode) {
        when (mode) {
            TrackingMode.MANUAL, TrackingMode.GYRO_TILT -> {}
            TrackingMode.LASER_DOT -> {
                if (laserTracker == null) laserTracker = LaserTracker()
                else laserTracker?.resetPid()
            }
            TrackingMode.OBJECT_TRACK -> {
                if (objectTracker == null) {
                    try {
                        val settings = vm.sensitivity.value
                        // Use selected model from settings
                        val modelFile = settings.modelName
                        objectTracker = ObjectTracker(
                            context = requireContext(),
                            modelFile = modelFile,
                            panSensitivity = settings.camPanSens,
                            tiltSensitivity = settings.camTiltSens
                        )
                        objectTracker?.updateTrackingTuning(
                            settings.trackDeadzone, settings.trackExpo, settings.trackRateLimit)
                        Log.i(TAG, "YOLO model: $modelFile")
                    } catch (e: Throwable) {
                        Toast.makeText(requireContext(), "YOLO: ${e.message}", Toast.LENGTH_LONG).show()
                        vm.setTrackMode(TrackingMode.MANUAL)
                        spinnerMode.setSelection(0)
                    }
                } else {
                    val settings = vm.sensitivity.value
                    objectTracker?.updateSensitivity(settings.camPanSens, settings.camTiltSens)
                    objectTracker?.updateTrackingTuning(
                        settings.trackDeadzone, settings.trackExpo, settings.trackRateLimit)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        xiaoAnalysisJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        xiaoAnalysisJob?.cancel()
        cameraProvider?.unbindAll()
        objectTracker?.close()
        analysisExecutor.shutdown()
    }
}
