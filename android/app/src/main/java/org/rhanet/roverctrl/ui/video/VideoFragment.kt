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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.TrackingMode
import org.rhanet.roverctrl.tracking.CalibrationData
import org.rhanet.roverctrl.tracking.LaserTracker
import org.rhanet.roverctrl.tracking.ObjectTracker
import org.rhanet.roverctrl.ui.RoverViewModel
import androidx.navigation.fragment.findNavController
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

// ──────────────────────────────────────────────────────────────────────────
// VideoFragment
//
// Камера телефона через CameraX + Camera2 interop.
// Теперь также показывает PiP стрим с камеры турели (XIAO Sense).
// GYRO_TILT режим: камера телефона не обрабатывается (как Manual).
// ──────────────────────────────────────────────────────────────────────────

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class VideoFragment : Fragment() {

    companion object {
        private const val TAG = "VideoFrag"
    }

    private val vm: RoverViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var previewView: PreviewView
    private lateinit var overlay:     TrackingOverlayView
    private lateinit var spinnerMode: Spinner
    private lateinit var tvFps:       TextView
    private lateinit var tvStatus:    TextView

    // PiP
    private lateinit var pipContainer:   FrameLayout
    private lateinit var ivTurretPip:    ImageView
    private lateinit var tvTurretFps:    TextView
    private lateinit var btnPipVideo:    ToggleButton

    private var laserTracker:  LaserTracker?  = null
    private var objectTracker: ObjectTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // FPS counter
    private var frameCount  = 0
    private var lastFpsTime = System.currentTimeMillis()

    // Permission
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_video, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView  = view.findViewById(R.id.preview_view)
        overlay      = view.findViewById(R.id.overlay)
        spinnerMode  = view.findViewById(R.id.spinner_mode)
        tvFps        = view.findViewById(R.id.tv_fps)
        tvStatus     = view.findViewById(R.id.tv_status)
        pipContainer = view.findViewById(R.id.pip_container_video)
        ivTurretPip  = view.findViewById(R.id.iv_turret_pip_video)
        tvTurretFps  = view.findViewById(R.id.tv_turret_fps_video)
        btnPipVideo  = view.findViewById(R.id.btn_pip_video)

        setupModeSpinner()
        setupPip()

        // Calibrate button → navigation to CalibrationFragment
        view.findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            findNavController().navigate(R.id.action_video_to_calibration)
        }

        // Load saved calibration → apply to PID
        CalibrationData.load(requireContext())?.let { calib ->
            vm.pidPan.kp  = calib.optimalPanKp()
            vm.pidTilt.kp = calib.optimalTiltKp()
            Log.i(TAG, "Loaded calibration: panKp=${vm.pidPan.kp} tiltKp=${vm.pidTilt.kp}")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.trackMode.collectLatest { mode ->
                overlay.trackingActive = mode != TrackingMode.MANUAL && mode != TrackingMode.GYRO_TILT
                tvStatus.text = when (mode) {
                    TrackingMode.MANUAL       -> "Manual"
                    TrackingMode.LASER_DOT    -> "Laser Tracking"
                    TrackingMode.OBJECT_TRACK -> "Object Tracking"
                    TrackingMode.GYRO_TILT    -> "Gyro Tilt"
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── PiP ──────────────────────────────────────────────────────────────

    private fun setupPip() {
        btnPipVideo.setOnCheckedChangeListener { _, isChecked ->
            pipContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFrame.collectLatest { bmp ->
                if (bmp != null && pipContainer.visibility == View.VISIBLE) {
                    ivTurretPip.setImageBitmap(bmp)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFps.collectLatest { fps ->
                tvTurretFps.text = if (fps > 0) String.format("%.0f", fps) else "--"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretConnected.collectLatest { connected ->
                if (connected && !btnPipVideo.isChecked) {
                    btnPipVideo.isChecked = true
                }
            }
        }
    }

    // ── CameraX ──────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            provider.unbindAll()

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(30, 120)
            )
            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(480, 360))

            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(30, 120)
            )

            val imageAnalysis = analysisBuilder.build()
            imageAnalysis.setAnalyzer(analysisExecutor, ::processFrame)

            try {
                val camera = provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageAnalysis
                )

                try {
                    val cam2ctrl = Camera2CameraControl.from(camera.cameraControl)
                    cam2ctrl.addCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(
                                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(30, 120)
                            )
                            .build()
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "Camera2CameraControl not available: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed, trying fallback", e)
                provider.unbindAll()
                val fbPreview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val fbAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(480, 360))
                    .build()
                fbAnalysis.setAnalyzer(analysisExecutor, ::processFrame)
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                    fbPreview, fbAnalysis
                )
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── Frame Processing ─────────────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount * 1000f / (now - lastFpsTime)
            handler.post { tvFps.text = String.format("%.0f FPS", fps) }
            frameCount = 0
            lastFpsTime = now
        }

        val mode = vm.trackMode.value

        // В Manual и GYRO_TILT — не конвертируем кадр, экономим CPU
        if (mode == TrackingMode.MANUAL || mode == TrackingMode.GYRO_TILT) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        bitmap ?: return

        when (mode) {
            TrackingMode.LASER_DOT -> {
                val result = laserTracker?.process(bitmap)
                if (result != null && result.found) {
                    vm.setPanTilt(result.panDelta.toInt(), result.tiltDelta.toInt())
                    handler.post { overlay.detection = result.detection }
                } else {
                    handler.post { overlay.detection = null }
                }
            }
            TrackingMode.OBJECT_TRACK -> {
                val result = objectTracker?.process(bitmap)
                if (result != null && result.found) {
                    Log.d(TAG, "YOLO: ${result.detection?.label} " +
                          "${((result.detection?.confidence ?: 0f) * 100).toInt()}% " +
                          "bbox=${result.detection?.w?.let { "%.2f".format(it) }}x${result.detection?.h?.let { "%.2f".format(it) }}")
                    vm.setPanTilt(result.panDelta.toInt(), result.tiltDelta.toInt())
                    handler.post { overlay.detection = result.detection }
                } else {
                    handler.post { overlay.detection = null }
                }
            }
            else -> {}
        }
        bitmap.recycle()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            return try {
                val plane = image.planes[0]
                val buf = plane.buffer; buf.rewind()
                val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                applyRotation(bmp, image.imageInfo.rotationDegrees)
            } catch (_: Throwable) { null }
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val ySize = yBuf.remaining(); val uSize = uBuf.remaining(); val vSize = vBuf.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuf.get(nv21, 0, ySize)
        val vBytes = ByteArray(vSize); vBuf.get(vBytes)
        val uBytes = ByteArray(uSize); uBuf.get(uBytes)
        if (vPlane.pixelStride == 2) {
            System.arraycopy(vBytes, 0, nv21, ySize, vSize)
        } else {
            var offset = ySize
            for (i in 0 until minOf(vBytes.size, uBytes.size)) {
                nv21[offset++] = vBytes[i]; nv21[offset++] = uBytes[i]
            }
        }
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        return applyRotation(bmp, image.imageInfo.rotationDegrees)
    }

    private fun applyRotation(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    // ── Mode Spinner ─────────────────────────────────────────────────────

    private fun setupModeSpinner() {
        val modes = resources.getStringArray(R.array.tracking_modes)
        spinnerMode.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, modes
        )
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val mode = TrackingMode.entries[pos]
                vm.setTrackMode(mode)
                initTracker(mode)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun initTracker(mode: TrackingMode) {
        when (mode) {
            TrackingMode.MANUAL -> {}
            TrackingMode.GYRO_TILT -> {}  // управляется из ControlFragment
            TrackingMode.LASER_DOT -> {
                if (laserTracker == null) laserTracker = LaserTracker()
                else laserTracker?.resetPid()
            }
            TrackingMode.OBJECT_TRACK -> {
                if (objectTracker == null) {
                    try {
                        objectTracker = ObjectTracker(requireContext())
                    } catch (e: Throwable) {
                        Toast.makeText(requireContext(),
                            "YOLOv8 init failed: ${e.message}",
                            Toast.LENGTH_LONG).show()
                        vm.setTrackMode(TrackingMode.MANUAL)
                        spinnerMode.setSelection(0)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
        objectTracker?.close()
        analysisExecutor.shutdown()
    }
}
