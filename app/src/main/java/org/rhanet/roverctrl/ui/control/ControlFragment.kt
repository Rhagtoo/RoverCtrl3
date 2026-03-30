package org.rhanet.roverctrl.ui.control

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.TrackingMode
import org.rhanet.roverctrl.tracking.GyroTiltController
import org.rhanet.roverctrl.ui.RoverViewModel

class ControlFragment : Fragment() {

    private val vm: RoverViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var joystickDrive: JoystickView
    private lateinit var joystickCam:   JoystickView
    private lateinit var btnLaser:      ToggleButton
    private lateinit var btnGyroTilt:   ToggleButton
    private lateinit var btnPip:        ToggleButton
    private lateinit var btnGear:       ToggleButton
    private lateinit var tvBat:         TextView
    private lateinit var tvSpd:         TextView
    private lateinit var tvSpdSource:   TextView
    private lateinit var tvRpmL:        TextView
    private lateinit var tvRpmR:        TextView
    private lateinit var tvYaw:         TextView
    private lateinit var tvRssi:        TextView
    private lateinit var tvOdom:        TextView
    private lateinit var tvGyroDebug:   TextView
    private lateinit var tvCamLabel:    TextView

    private lateinit var pipContainer: FrameLayout
    private lateinit var ivTurretPip:  ImageView
    private lateinit var tvTurretFps:  TextView

    private var roverViz: RoverVizView? = null

    private var gyroCtrl: GyroTiltController? = null
    private var gyroTickJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_control, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        joystickDrive = view.findViewById(R.id.joystick_drive)
        joystickCam   = view.findViewById(R.id.joystick_cam)
        btnLaser      = view.findViewById(R.id.btn_laser)
        btnGyroTilt   = view.findViewById(R.id.btn_gyro_tilt)
        btnPip        = view.findViewById(R.id.btn_pip)
        btnGear       = view.findViewById(R.id.btn_gear)
        tvBat         = view.findViewById(R.id.tv_bat)
        tvSpd         = view.findViewById(R.id.tv_spd)
        tvSpdSource   = view.findViewById(R.id.tv_spd_source)
        tvRpmL        = view.findViewById(R.id.tv_rpm_l)
        tvRpmR        = view.findViewById(R.id.tv_rpm_r)
        tvYaw         = view.findViewById(R.id.tv_yaw)
        tvRssi        = view.findViewById(R.id.tv_rssi)
        tvOdom        = view.findViewById(R.id.tv_odom)
        tvGyroDebug   = view.findViewById(R.id.tv_gyro_debug)
        tvCamLabel    = view.findViewById(R.id.tv_cam_label)
        pipContainer  = view.findViewById(R.id.pip_container)
        ivTurretPip   = view.findViewById(R.id.iv_turret_pip)
        tvTurretFps   = view.findViewById(R.id.tv_turret_fps)

        roverViz = view.findViewById(R.id.rover_viz)
        roverViz?.setOdometry(vm.getOdometry())

        // edge-to-edge: top inset для HUD
        val hudPanel = view.findViewById<LinearLayout>(R.id.hud_panel)
        ViewCompat.setOnApplyWindowInsetsListener(hudPanel) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, v.paddingBottom)
            insets
        }

        vm.loadSettings(requireContext())
        setupDriveJoystick()
        setupCameraJoystick()
        setupLaser()
        setupGyroTilt()
        setupPip()
        setupGear()
        observeTelemetry()
        observeRssi()
        observeTurretStream()
    }

    private fun setupDriveJoystick() {
        joystickDrive.onMove = { x, y ->
            val s = vm.sensitivity.value
            val fwd = (y * 100 * s.driveSpeedSens).toInt().coerceIn(-100, 100)
            val str = (x * 100 * s.driveSteerSens).toInt().coerceIn(-100, 100)
            vm.setDriveCmd(fwd, str, fwd)
        }
    }

    private fun setupCameraJoystick() {
        joystickCam.onMove = { x, y ->
            if (vm.trackMode.value == TrackingMode.MANUAL) {
                val s = vm.sensitivity.value
                val pan  = (x * 100 * s.camPanSens).toInt().coerceIn(-100, 100)
                val tilt = (y * 100 * s.camTiltSens).toInt().coerceIn(-100, 100)
                vm.setPanTilt(pan, tilt)
            }
        }
    }

    private fun setupLaser() {
        btnLaser.setOnCheckedChangeListener { _, isChecked -> vm.laserOn = isChecked }
    }

    private fun setupGyroTilt() {
        btnGyroTilt.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startGyroTilt() else stopGyroTilt()
        }
    }

    private fun startGyroTilt() {
        vm.setTrackMode(TrackingMode.GYRO_TILT)
        if (gyroCtrl == null) gyroCtrl = GyroTiltController(requireContext())
        gyroCtrl!!.zero()
        gyroCtrl!!.start()
        joystickCam.alpha = 0.3f
        joystickCam.isEnabled = false
        tvCamLabel.text = "GYRO"
        tvGyroDebug.visibility = View.VISIBLE
        gyroTickJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                gyroCtrl?.let { g ->
                    val out = g.output
                    vm.setPanTilt(out.pan, out.tilt)
                    tvGyroDebug.text = String.format(
                        "Y:%+.0f° P:%+.0f° → %+d/%+d",
                        g.rawDeltaYaw, g.rawDeltaPitch, out.pan, out.tilt
                    )
                }
                delay(50)
            }
        }
    }

    private fun stopGyroTilt() {
        gyroTickJob?.cancel(); gyroTickJob = null
        gyroCtrl?.stop()
        vm.setTrackMode(TrackingMode.MANUAL)
        vm.setPanTilt(0, 0)
        joystickCam.alpha = 1.0f
        joystickCam.isEnabled = true
        tvCamLabel.text = "CAMERA"
        tvGyroDebug.visibility = View.GONE
    }

    private fun setupPip() {
        btnPip.setOnCheckedChangeListener { _, isChecked ->
            pipContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        pipContainer.setOnClickListener { gyroCtrl?.zero() }
    }

    private fun setupGear() {
        btnGear.setOnCheckedChangeListener { _, isChecked ->
            vm.setGear(if (isChecked) 1 else 2)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.gear.collectLatest { g -> btnGear.isChecked = (g == 1) }
        }
    }

    private fun observeTurretStream() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFrame.collectLatest { bmp ->
                if (bmp != null && pipContainer.visibility == View.VISIBLE)
                    ivTurretPip.setImageBitmap(bmp)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretFps.collectLatest { fps ->
                tvTurretFps.text = if (fps > 0) String.format("%.0f", fps) else "--"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.turretConnected.collectLatest { if (it && !btnPip.isChecked) btnPip.isChecked = true }
        }
    }

    // FIX: RSSI читается через WifiManager (vm.wifiRssi),
    // а не из телеметрии (telem.rssi всегда 0 — ESP32 в AP-режиме).
    private fun observeRssi() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.wifiRssi.collectLatest { rssi ->
                tvRssi.text = if (rssi != 0) "$rssi dBm" else "--"
                tvRssi.setTextColor(when {
                    rssi >= -60 -> 0xFF00E676.toInt()  // отличный
                    rssi >= -70 -> 0xFFFFAB00.toInt()  // средний
                    rssi >= -80 -> 0xFFFF8F00.toInt()  // слабый
                    rssi != 0   -> 0xFFFF5252.toInt()  // плохой
                    else        -> 0xFF888888.toInt()  // нет данных
                })
            }
        }
    }

    private fun observeTelemetry() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.telem.collectLatest { t ->
                tvBat.text = if (t.bat >= 0) "${t.bat}%" else "--"

                val realSpeed = t.speedMs
                if (!realSpeed.isNaN()) {
                    tvSpd.text = String.format("%.2f m/s  %d%%",
                        kotlin.math.abs(realSpeed), t.powerPct)
                    tvSpdSource.text = "⊙ RPM"
                    tvSpdSource.setTextColor(0xFF00E676.toInt())
                } else {
                    tvSpd.text = String.format("%d%%", t.powerPct)
                    tvSpdSource.text = "~ PWM"
                    tvSpdSource.setTextColor(0xFFFF8F00.toInt())
                }

                tvRpmL.text = if (t.rpmL.isNaN()) "--" else String.format("%.0f", t.rpmL)
                tvRpmR.text = if (t.rpmR.isNaN()) "--" else String.format("%.0f", t.rpmR)

                if (!t.rpmL.isNaN() && !t.rpmR.isNaN()) {
                    val diff = kotlin.math.abs(t.rpmL - t.rpmR)
                    val avg  = (kotlin.math.abs(t.rpmL) + kotlin.math.abs(t.rpmR)) / 2f
                    val warn = avg > 5f && diff / avg.coerceAtLeast(1f) > 0.20f
                    val c = if (warn) 0xFFFFAB00.toInt() else 0xFF80CBC4.toInt()
                    tvRpmL.setTextColor(c); tvRpmR.setTextColor(c)
                }

                tvYaw.text = String.format("%.0f°", t.yaw)
                // tvRssi — через observeRssi()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pose.collectLatest { p ->
                tvOdom.text = String.format("X:%.2f Y:%.2f H:%.0f° D:%.1fm",
                    p.x, p.y,
                    Math.toDegrees(p.headingRad.toDouble()),
                    vm.getOdometry().distanceMeters
                )
                roverViz?.refresh()
            }
        }
    }

    override fun onPause() { super.onPause(); gyroCtrl?.stop() }

    override fun onResume() {
        super.onResume()
        if (btnGyroTilt.isChecked) gyroCtrl?.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gyroTickJob?.cancel()
        gyroCtrl?.stop()
    }
}
