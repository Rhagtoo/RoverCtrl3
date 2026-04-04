package org.rhanet.roverctrl.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.wifi.WifiManager
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
import org.rhanet.roverctrl.data.AppSettings
import org.rhanet.roverctrl.tracking.CalibrationResult
import org.rhanet.roverctrl.tracking.OdometryTracker
import org.rhanet.roverctrl.tracking.PidController

class RoverViewModel : ViewModel() {

    companion object {
        private const val TAG = "RoverVM"
        private const val CMD_TICK_MS = 50L
        private const val RSSI_POLL_MS = 2000L

        // Угол поворота кадра с камеры XIAO (градусы, по часовой стрелке).
        // Меняй если турель смонтирована иначе: 0 / 90 / 180 / 270
        private const val TURRET_ROTATION_DEG = 90f  // XIAO камера в portrait, поворачиваем на 90° для landscape
    }

    val sender   = CommandSender()
    val telemRx  = TelemetryReceiver()

    private val _connected       = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = _connected

    private val _telem           = MutableStateFlow(TelemetryData())
    val telem: StateFlow<TelemetryData> get() = _telem

    private val _trackMode       = MutableStateFlow(TrackingMode.MANUAL)
    val trackMode: StateFlow<TrackingMode> get() = _trackMode

    private val _pose            = MutableStateFlow(OdometryTracker.Pose(0f, 0f, 0f))
    val pose: StateFlow<OdometryTracker.Pose> get() = _pose

    private val _cameraFps       = MutableStateFlow(0f)
    val cameraFps: StateFlow<Float> get() = _cameraFps

    private val _calibration     = MutableStateFlow(CalibrationResult())
    val calibration: StateFlow<CalibrationResult> get() = _calibration

    private val _turretFrame     = MutableStateFlow<Bitmap?>(null)
    val turretFrame: StateFlow<Bitmap?> get() = _turretFrame

    private val _turretFps       = MutableStateFlow(0f)
    val turretFps: StateFlow<Float> get() = _turretFps

    private val _turretConnected = MutableStateFlow(false)
    val turretConnected: StateFlow<Boolean> get() = _turretConnected

    // RSSI телефона к роверу (dBm).
    // Ровер в AP-режиме — WiFi.RSSI() на ESP32 всегда 0 (нет базовой станции).
    // Читаем RSSI на стороне Android через WifiManager.connectionInfo.rssi
    private val _wifiRssi        = MutableStateFlow(0)
    val wifiRssi: StateFlow<Int> get() = _wifiRssi

    private val _gear            = MutableStateFlow(2)
    val gear: StateFlow<Int> get() = _gear
    fun setGear(g: Int) { _gear.value = g }

    private val _sensitivity     = MutableStateFlow(AppSettings())
    val sensitivity: StateFlow<AppSettings> get() = _sensitivity

    fun loadSettings(context: Context) {
        _sensitivity.value = AppSettings.load(context)
    }

    fun updateSensitivity(context: Context, s: AppSettings) {
        AppSettings.save(context, s)
        _sensitivity.value = s
    }

    @Volatile var panCmd  = 0
    @Volatile var tiltCmd = 0
    @Volatile var laserOn = false

    private val odometry = OdometryTracker()
    val pidPan  = PidController(kp = 120f, ki = 0.5f, kd = 8f, outMax = 100f)
    val pidTilt = PidController(kp = 120f, ki = 0.5f, kd = 8f, outMax = 100f)

    private var mjpegDecoder:  MjpegDecoder? = null
    private val mainHandler    = Handler(Looper.getMainLooper())
    private var cmdTickJob:    Job? = null
    private var telemJob:      Job? = null
    private var rssiJob:       Job? = null
    private var currentConfig: ConnectionConfig? = null

    @Volatile private var spd = 0
    @Volatile private var str = 0
    @Volatile private var fwd = 0

    // Матрица поворота — пересоздавать не нужно, используем один экземпляр
    private val turretRotMatrix = Matrix().apply { postRotate(TURRET_ROTATION_DEG) }

    // ── Connect ───────────────────────────────────────────────────────────

    fun connect(cfg: ConnectionConfig, context: Context? = null) {
        if (_connected.value) return
        currentConfig = cfg
        sender.configure(cfg)

        telemJob = viewModelScope.launch {
            telemRx.receive(
                port = cfg.telPort,
                onData = { data ->
                    _telem.value = data
                    updateOdometry(data)
                },
                onError = { e -> Log.w(TAG, "Telemetry error: ${e.message}") },
                onConnectionLost = { Log.w(TAG, "Telemetry lost") }
            )
        }

        cmdTickJob = viewModelScope.launch {
            while (true) {
                sender.send(spd, str, fwd, laserOn, panCmd, tiltCmd, _gear.value)
                delay(CMD_TICK_MS)
            }
        }

        if (context != null) startRssiPolling(context)

        startTurretStream(cfg.turretStreamUrl)
        _connected.value = true
        Log.i(TAG, "Connected rover=${cfg.roverIp} xiao=${cfg.xiaoIp}")
    }

    private fun startRssiPolling(context: Context) {
        rssiJob?.cancel()
        rssiJob = viewModelScope.launch {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            while (true) {
                _wifiRssi.value = wm?.connectionInfo?.rssi ?: 0
                delay(RSSI_POLL_MS)
            }
        }
    }

    fun disconnect() {
        cmdTickJob?.cancel(); telemJob?.cancel(); rssiJob?.cancel()
        telemRx.stop(); sender.clearHosts(); stopTurretStream()
        spd = 0; str = 0; fwd = 0; panCmd = 0; tiltCmd = 0; laserOn = false
        _telem.value = TelemetryData()
        _wifiRssi.value = 0
        _connected.value = false
    }

    // ── Odometry ──────────────────────────────────────────────────────────

    private fun updateOdometry(data: TelemetryData) {
        odometry.update(
            rpmL   = data.rpmL,
            rpmR   = data.rpmR,
            spdPct = data.spd,
            strPct = data.str.toFloat()
        )
        _pose.value = odometry.pose
    }

    // ── Turret stream ─────────────────────────────────────────────────────

    private fun startTurretStream(url: String) {
        stopTurretStream()
        mjpegDecoder = MjpegDecoder(
            url = url,
            onFrame = { bmp ->
                // Всегда создаём копию bitmap для thread safety
                // XIAO камера в portrait, поворачиваем на 90° для landscape ориентации
                val frameToUse = if (TURRET_ROTATION_DEG == 0f) {
                    // Создаём копию без поворота
                    bmp.copy(bmp.config, true) ?: run {
                        Log.w(TAG, "Failed to copy bitmap, using original")
                        bmp
                    }
                } else {
                    // Создаём копию с поворотом
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, turretRotMatrix, true)
                }
                
                // Освобождаем оригинальный bitmap после создания копии
                // Но только если создали новую bitmap (не используем оригинал как fallback)
                if (frameToUse !== bmp) {
                    bmp.recycle()
                }

                mainHandler.post {
                    _turretFrame.value?.recycle()
                    _turretFrame.value = frameToUse
                    _turretConnected.value = true
                }
            },
            onFps   = { fps -> mainHandler.post { _turretFps.value = fps } },
            onError = { e ->
                Log.w(TAG, "MJPEG: ${e.message}")
                mainHandler.post { _turretConnected.value = false }
            }
        ).also { it.start() }
    }

    private fun stopTurretStream() {
        mjpegDecoder?.halt(); mjpegDecoder = null
        _turretFrame.value?.recycle(); _turretFrame.value = null
        _turretFps.value = 0f; _turretConnected.value = false
    }

    // ── Commands ──────────────────────────────────────────────────────────

    fun setDriveCmd(spd: Int, str: Int, fwd: Int) {
        val maxSpd = GearConfig.MAX_SPEED[_gear.value] ?: 100
        this.spd = spd; this.str = str; this.fwd = fwd.coerceIn(-maxSpd, maxSpd)
    }

    fun setPanTilt(pan: Int, tilt: Int) { panCmd = pan; tiltCmd = tilt }

    fun setTrackMode(m: TrackingMode) {
        _trackMode.value = m
        if (m == TrackingMode.MANUAL) { pidPan.reset(); pidTilt.reset() }
    }

    fun updateCameraFps(fps: Float) { _cameraFps.value = fps }
    fun getOdometry() = odometry
    fun resetOdometry() { odometry.reset(); _pose.value = odometry.pose }

    fun applyCalibration(result: CalibrationResult) {
        _calibration.value = result
        if (result.isValid) {
            pidPan.kp  = result.recommendedKp(CalibrationResult.Axis.PAN)
            pidTilt.kp = result.recommendedKp(CalibrationResult.Axis.TILT)
        }
    }

    override fun onCleared() { super.onCleared(); disconnect(); sender.close() }
}
