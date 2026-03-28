package org.rhanet.roverctrl.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.data.*
import org.rhanet.roverctrl.network.CommandSender
import org.rhanet.roverctrl.network.MjpegDecoder
import org.rhanet.roverctrl.network.TelemetryReceiver
import org.rhanet.roverctrl.tracking.OdometryTracker
import org.rhanet.roverctrl.tracking.PidController

// ──────────────────────────────────────────────────────────────────────────
// RoverViewModel — Shared ViewModel
//
// Управляет сетевыми подключениями, 20Hz тиком команд, одометрией,
// MJPEG стримом с турели (XIAO Sense), режимами трекинга.
// ──────────────────────────────────────────────────────────────────────────

class RoverViewModel : ViewModel() {

    companion object {
        private const val TAG = "RoverVM"
    }

    // ── Сеть ──────────────────────────────────────────────────────────────
    val sender   = CommandSender()
    val telemRx  = TelemetryReceiver()

    // ── Состояния (UI наблюдает через StateFlow) ──────────────────────────
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = _connected

    private val _telem = MutableStateFlow(TelemetryData())
    val telem: StateFlow<TelemetryData> get() = _telem

    private val _trackMode = MutableStateFlow(TrackingMode.MANUAL)
    val trackMode: StateFlow<TrackingMode> get() = _trackMode

    private val _pose = MutableStateFlow(OdometryTracker.Pose(0f, 0f, 0f))
    val pose: StateFlow<OdometryTracker.Pose> get() = _pose

    private val _cameraFps = MutableStateFlow(0f)
    val cameraFps: StateFlow<Float> get() = _cameraFps

    // ── Калибровка ──────────────────────────────────────────────────────
    private val _calibration = MutableStateFlow(CalibrationResult())
    val calibration: StateFlow<CalibrationResult> get() = _calibration

    // ── MJPEG стрим с камеры турели (XIAO Sense) ────────────────────────
    private val _turretFrame = MutableStateFlow<Bitmap?>(null)
    val turretFrame: StateFlow<Bitmap?> get() = _turretFrame

    private val _turretFps = MutableStateFlow(0f)
    val turretFps: StateFlow<Float> get() = _turretFps

    private val _turretConnected = MutableStateFlow(false)
    val turretConnected: StateFlow<Boolean> get() = _turretConnected

    private var mjpegDecoder: MjpegDecoder? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Управление ────────────────────────────────────────────────────────
    @Volatile var laserOn  = false
    @Volatile var panCmd   = 0      // -100..100, выход PID или джойстик или гиро
    @Volatile var tiltCmd  = 0

    // ── Трекинг PID (используется из VideoFragment) ──────────────────────
    val pidPan  = PidController(kp = 60f, ki = 0.1f, kd = 8f, outMax = 100f)
    val pidTilt = PidController(kp = 60f, ki = 0.1f, kd = 8f, outMax = 100f)

    private val odometry = OdometryTracker()

    // ── Текущий контрольный пакет (обновляется из ControlFragment) ────────
    @Volatile private var spd = 0
    @Volatile private var str = 0
    @Volatile private var fwd = 0

    private var telemJob:  Job? = null
    private var cmdJob:    Job? = null

    // ── Текущий конфиг ────────────────────────────────────────────────────
    private var _config: ConnectionConfig? = null
    val config: ConnectionConfig? get() = _config

    // ─────────────────────────────────────────────────────────────────────
    fun connect(cfg: ConnectionConfig) {
        _config = cfg
        sender.configure(cfg)
        _connected.value = true

        // Телеметрия
        telemJob = viewModelScope.launch {
            telemRx.receive(cfg.telPort) { t ->
                _telem.value = t
                odometry.update(t.rpmL, t.rpmR, t.spd, str.toFloat())
                _pose.value = odometry.pose
            }
        }

        // Тик команд 20 Гц
        cmdJob = viewModelScope.launch {
            while (true) {
                sender.sendRover(spd, str, fwd, laserOn)
                sender.sendXiao(panCmd, tiltCmd)
                delay(50)
            }
        }

        // MJPEG стрим с турели
        startTurretStream(cfg.turretStreamUrl)
    }

    fun disconnect() {
        telemJob?.cancel()
        cmdJob?.cancel()
        telemRx.stop()
        sender.clearHosts()
        _connected.value = false
        _telem.value = TelemetryData()
        odometry.reset()
        _config = null

        stopTurretStream()
    }

    // ── MJPEG турель ─────────────────────────────────────────────────────

    private fun startTurretStream(url: String) {
        stopTurretStream()
        Log.i(TAG, "Starting turret MJPEG stream: $url")

        mjpegDecoder = MjpegDecoder(
            url     = url,
            onFrame = { bmp ->
                // Вызывается в фоновом потоке MjpegDecoder
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                mainHandler.post {
                    _turretFrame.value?.recycle()
                    _turretFrame.value = copy
                    _turretConnected.value = true
                }
            },
            onFps = { fps ->
                mainHandler.post { _turretFps.value = fps }
            }
        ).also { it.start() }
    }

    private fun stopTurretStream() {
        mjpegDecoder?.halt()
        mjpegDecoder = null
        _turretFrame.value?.recycle()
        _turretFrame.value = null
        _turretFps.value = 0f
        _turretConnected.value = false
    }

    // ── Управление ───────────────────────────────────────────────────────

    /** Вызывается из ControlFragment каждые 50 мс */
    fun setDriveCmd(spd: Int, str: Int, fwd: Int) {
        this.spd = spd; this.str = str; this.fwd = fwd
    }

    /** Вызывается из VideoFragment при трекинге или из ControlFragment */
    fun setPanTilt(pan: Int, tilt: Int) {
        panCmd = pan; tiltCmd = tilt
    }

    fun setTrackMode(m: TrackingMode) {
        _trackMode.value = m
        if (m == TrackingMode.MANUAL) {
            pidPan.reset(); pidTilt.reset()
        }
    }

    fun updateCameraFps(fps: Float) {
        _cameraFps.value = fps
    }

    fun getOdometry() = odometry

    /** Применить результат калибровки лазера → обновить PID gains */
    fun applyCalibration(result: CalibrationResult) {
        _calibration.value = result
        if (result.isValid) {
            val kpPan  = result.recommendedKp(CalibrationResult.Axis.PAN)
            val kpTilt = result.recommendedKp(CalibrationResult.Axis.TILT)
            pidPan.kp  = kpPan
            pidTilt.kp = kpTilt
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        sender.close()
    }
}
