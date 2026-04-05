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
import org.rhanet.roverctrl.data.TrackingMode
import org.rhanet.roverctrl.tracking.CalibrationData
import org.rhanet.roverctrl.tracking.LaserTracker
import org.rhanet.roverctrl.tracking.LatencyTracker
import org.rhanet.roverctrl.tracking.ObjectTracker
import org.rhanet.roverctrl.ui.RoverViewModel
import org.rhanet.roverctrl.ui.control.JoystickView
import androidx.navigation.fragment.findNavController
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * VideoFragment v2.4
 * - Латенси пайплайна: всегда видна (tv_latency всегда VISIBLE)
 *   Manual: показывает только FPS камеры
 *   Tracking: decode→infer→cmd задержка в мс
 * - tv_status / tv_main_source_label / tv_latency объединены в video_hud_left
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class VideoFragment : Fragment() {
    companion object { private const val TAG = "VideoFrag" }

    private val vm: RoverViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val latency = LatencyTracker(windowSize = 30)

    private lateinit var previewView:       PreviewView
    private lateinit var overlay:           TrackingOverlayView
    private lateinit var overlayXiao:       TrackingOverlayView
    private lateinit var spinnerMode:       Spinner
    private lateinit var tvFps:             TextView
    private lateinit var tvStatus:          TextView
    private lateinit var tvLatency:         TextView   // всегда видна

    // PiP + Swap
    private lateinit var pipContainer:      FrameLayout
    private lateinit var ivTurretPip:       ImageView
    private lateinit var tvTurretFps:       TextView
    private lateinit var tvPipSourceLabel:  TextView
    private lateinit var btnPipVideo:       ToggleButton
    private lateinit var btnSwapVideo:      ToggleButton
    private lateinit var ivXiaoMain:        ImageView
    private lateinit var tvMainSourceLabel: TextView

    // Overlay controls
    private lateinit var joystickDrive:     JoystickView
    private lateinit var joystickCam:       JoystickView
    private lateinit var tvDriveLabel:      TextView
    private lateinit var tvCamLabel:        TextView
    private lateinit var btnLaserVideo:     ToggleButton

    private var swapped = false
    private var laserTracker:  LaserTracker?  = null
    private var objectTracker: ObjectTracker? = null

    private fun trackingOverlay(): TrackingOverlayView = if (swapped) overlayXiao else overlay
    private var cameraProvider: ProcessCameraProvider? = null
    private var xiaoAnalysisJob: Job? = null

    // FPS счётчик для камеры
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var lastCameraFps = 0f

    // Латенси: обновляем не чаще раза в 500мс
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

        // edge-to-edge: insets для верхней панели и правого джойстика
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

        // Латенси всегда видна — начальное состояние
        tvLatency.text = "-- ms"
        tvLatency.visibility = View.VISIBLE

        setupModeSpinner()
        setupPip()
        setupSwap()
        setupOverlayControls()

        view.findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            findNavController().navigate(R.id.action_video_to_calibration)
        }

        CalibrationData.load(requireContext())?.let {
            vm.pidPan.kp = it.optimalPanKp()
            vm.pidTilt.kp = it.optimalTiltKp()
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
                // В Manual латенси показывает только FPS камеры
                if (mode == TrackingMode.MANUAL || mode == TrackingMode.GYRO_TILT) {
                    updateLatencyManual()
                }
                updateOverlayControlsVisibility(mode)
                updateXiaoAnalysis()
            }
        }
        
        // Observe sensitivity changes
        viewLifecycleOwner.lifecycleScope.launch {
            vm.sensitivity.collectLatest { settings ->
                // Update ObjectTracker sensitivity if it exists and YOLO mode is active
                if (objectTracker != null && vm.trackMode.value == TrackingMode.OBJECT_TRACK) {
                    objectTracker?.updateSensitivity(settings.camPanSens, settings.camTiltSens)
                    Log.d(TAG, "YOLO sensitivity updated: pan=${settings.camPanSens}, tilt=${settings.camTiltSens}")
                }
                // TODO: Add sensitivity support for LaserTracker
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Латенси ──────────────────────────────────────────────────────────

    /**
     * Manual / Gyro: нет inference, показываем только FPS камеры.
     */
    private fun updateLatencyManual() {
        handler.post {
            tvLatency.text = if (lastCameraFps > 0f)
                "fps: %.0f".format(lastCameraFps)
            else "--"
            tvLatency.setTextColor(0xFFAAAAAA.toInt())
        }
    }

    /**
     * Tracking: decode + inference + cmd задержка.
     * Формат: "12 ms  · cam 60 fps"
     * Вызывается из analysis-потока не чаще раза в 500мс.
     */
    private fun maybeUpdateLatencyHud() {
        val now = System.currentTimeMillis()
        if (now - lastLatencyUpdate < 500) return
        lastLatencyUpdate = now
        val snap = latency.snapshot()
        if (snap.count == 0) return
        handler.post {
            val inferMs = snap.stageAvgMs["inference"] ?: 0f
            val totalMs = snap.avgTotalMs
            // Цвет по задержке: <30ms зелёный, <80ms жёлтый, >80ms красный
            val color = when {
                totalMs < 30f -> 0xFF00E676.toInt()
                totalMs < 80f -> 0xFFFFAB00.toInt()
                else          -> 0xFFFF5252.toInt()
            }
            tvLatency.setTextColor(color)
            tvLatency.text = "lat: %.0f ms · fps: %.0f".format(totalMs, lastCameraFps)
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
                        Log.d(TAG, "Setting XIAO bitmap: ${bmp.width}x${bmp.height}")
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
            // Скрываем PiP, потому что он не может показывать камеру телефона
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
        Log.d(TAG, "updateXiaoAnalysis: swapped=$swapped mode=${vm.trackMode.value}")
        xiaoAnalysisJob?.cancel()
        val mode = vm.trackMode.value
        if (swapped && (mode == TrackingMode.LASER_DOT || mode == TrackingMode.OBJECT_TRACK)) {
            xiaoAnalysisJob = viewLifecycleOwner.lifecycleScope.launch {
                vm.turretFrame.collectLatest { bmp ->
                    if (bmp != null && swapped) {
                        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return@collectLatest
                        analysisExecutor.execute { processXiaoFrame(copy) }
                    }
                }
            }
        }
    }

    private fun processXiaoFrame(bitmap: Bitmap) {
        val ft = latency.beginFrame()
        latency.mark(ft, LatencyTracker.Stage.DECODED)
        latency.mark(ft, LatencyTracker.Stage.INFERENCE_START)
        
        // Сохраняем размеры ДО recycle()
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        
        when (vm.trackMode.value) {
            TrackingMode.LASER_DOT -> {
                val r = laserTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    val label = r.detection?.label ?: ""
                    vm.laserOn = label == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    handler.post {
                        val ov = trackingOverlay()
                        ov.sourceImageWidth = bitmapWidth
                        ov.sourceImageHeight = bitmapHeight
                        ov.detection = r.detection
                    }
                } else handler.post {
                    val ov = trackingOverlay()
                    ov.sourceImageWidth = bitmapWidth
                    ov.sourceImageHeight = bitmapHeight
                    ov.detection = null
                }
            }
            TrackingMode.OBJECT_TRACK -> {
                val r = objectTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    val label = r.detection?.label ?: ""
                    vm.laserOn = label == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    handler.post {
                        val ov = trackingOverlay()
                        ov.sourceImageWidth = bitmapWidth
                        ov.sourceImageHeight = bitmapHeight
                        ov.detection = r.detection
                    }
                } else handler.post {
                    val ov = trackingOverlay()
                    ov.sourceImageWidth = bitmapWidth
                    ov.sourceImageHeight = bitmapHeight
                    ov.detection = null
                }
            }
            else -> {}
        }
        bitmap.recycle()
        maybeUpdateLatencyHud()
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
        btnLaserVideo.visibility  = showDrive  // кнопка лазера видна когда виден джойстик движения
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
        // FPS камеры (считаем все кадры независимо от режима)
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            lastCameraFps = frameCount * 1000f / (now - lastFpsTime)
            handler.post { tvFps.text = "fps: %.0f".format(lastCameraFps) }
            frameCount = 0
            lastFpsTime = now
            // В Manual обновляем латенси просто FPS-ом
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

        val ft = latency.beginFrame()
        val originalWidth = imageProxy.width
        val originalHeight = imageProxy.height
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        bitmap ?: return

        latency.mark(ft, LatencyTracker.Stage.DECODED)
        latency.mark(ft, LatencyTracker.Stage.INFERENCE_START)

        when (mode) {
            TrackingMode.LASER_DOT -> {
                val r = laserTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    val label = r.detection?.label ?: ""
                    vm.laserOn = label == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    handler.post {
                        val ov = trackingOverlay()
                        ov.sourceImageWidth = originalWidth
                        ov.sourceImageHeight = originalHeight
                        ov.detection = r.detection
                    }
                } else handler.post {
                    val ov = trackingOverlay()
                    ov.sourceImageWidth = originalWidth
                    ov.sourceImageHeight = originalHeight
                    ov.detection = null
                }
            }
            TrackingMode.OBJECT_TRACK -> {
                val r = objectTracker?.process(bitmap)
                latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
                if (r != null && r.found) {
                    vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
                    val label = r.detection?.label ?: ""
                    vm.laserOn = label == "cat"
                    latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
                    handler.post {
                        val ov = trackingOverlay()
                        ov.sourceImageWidth = originalWidth
                        ov.sourceImageHeight = originalHeight
                        ov.detection = r.detection
                    }
                } else handler.post {
                    val ov = trackingOverlay()
                    ov.sourceImageWidth = originalWidth
                    ov.sourceImageHeight = originalHeight
                    ov.detection = null
                }
            }
            else -> {}
        }
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
                        objectTracker = ObjectTracker(
                            context = requireContext(),
                            panSensitivity = settings.camPanSens,
                            tiltSensitivity = settings.camTiltSens
                        )
                    } catch (e: Throwable) {
                        Toast.makeText(requireContext(), "YOLO: ${e.message}", Toast.LENGTH_LONG).show()
                        vm.setTrackMode(TrackingMode.MANUAL)
                        spinnerMode.setSelection(0)
                    }
                } else {
                    // Update sensitivity if tracker already exists
                    val settings = vm.sensitivity.value
                    objectTracker?.updateSensitivity(settings.camPanSens, settings.camTiltSens)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: fragment going to background")
        xiaoAnalysisJob?.cancel()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: fragment paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: cancelling jobs and releasing resources")
        xiaoAnalysisJob?.cancel()
        cameraProvider?.unbindAll()
        objectTracker?.close()
        analysisExecutor.shutdown()
    }
}
