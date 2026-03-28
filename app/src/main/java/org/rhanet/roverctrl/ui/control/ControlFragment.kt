package org.rhanet.roverctrl.ui.control

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.ToggleButton
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
    private lateinit var tvYaw:         TextView
    private lateinit var tvRssi:        TextView
    private lateinit var tvOdom:        TextView
    private lateinit var tvGyroDebug:   TextView
    private lateinit var tvCamLabel:    TextView

    // PiP
    private lateinit var pipContainer:  FrameLayout
    private lateinit var ivTurretPip:   ImageView
    private lateinit var tvTurretFps:   TextView

    // Карта одометрии
    private var roverViz: RoverVizView? = null

    // Gyro
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
        tvYaw         = view.findViewById(R.id.tv_yaw)
        tvRssi        = view.findViewById(R.id.tv_rssi)
        tvOdom        = view.findViewById(R.id.tv_odom)
        tvGyroDebug   = view.findViewById(R.id.tv_gyro_debug)
        tvCamLabel    = view.findViewById(R.id.tv_cam_label)
        pipContainer  = view.findViewById(R.id.pip_container)
        ivTurretPip   = view.findViewById(R.id.iv_turret_pip)
        tvTurretFps   = view.findViewById(R.id.tv_turret_fps)

        // Карта одометрии
        roverViz = view.findViewById(R.id.rover_viz)
        roverViz?.setOdometry(vm.getOdometry())

        setupDriveJoystick()
        setupCameraJoystick()
        setupLaser()
        setupGyroTilt()
        setupPip()
        setupGear()
        observeTelemetry()
        observeTurretStream()
    }

    // ── Drive ────────────────────────────────────────────────────────────

    private fun setupDriveJoystick() {
        joystickDrive.onMove = { x, y ->
            val fwd = (y * 100).toInt()
            val str = (x * 100).toInt()
            vm.setDriveCmd(fwd, str, fwd)
        }
    }

    // ── Camera joystick (disabled in GYRO_TILT) ──────────────────────────

    private fun setupCameraJoystick() {
        joystickCam.onMove = { x, y ->
            if (vm.trackMode.value == TrackingMode.MANUAL) {
                vm.setPanTilt((x * 100).toInt(), (y * 100).toInt())
            }
        }
    }

    // ── Laser ────────────────────────────────────────────────────────────

    private fun setupLaser() {
        btnLaser.setOnCheckedChangeListener { _, isChecked ->
            vm.laserOn = isChecked
        }
    }

    // ── Gyro Tilt ────────────────────────────────────────────────────────

    private fun setupGyroTilt() {
        btnGyroTilt.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startGyroTilt() else stopGyroTilt()
        }
    }

    private fun startGyroTilt() {
        vm.setTrackMode(TrackingMode.GYRO_TILT)

        if (gyroCtrl == null) {
            gyroCtrl = GyroTiltController(requireContext())
        }
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
        gyroTickJob?.cancel()
        gyroTickJob = null
        gyroCtrl?.stop()

        vm.setTrackMode(TrackingMode.MANUAL)
        vm.setPanTilt(0, 0)

        joystickCam.alpha = 1.0f
        joystickCam.isEnabled = true
        tvCamLabel.text = "CAMERA"
        tvGyroDebug.visibility = View.GONE
    }

    // ── PiP ──────────────────────────────────────────────────────────────

    private fun setupPip() {
        btnPip.setOnCheckedChangeListener { _, isChecked ->
            pipContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Тап по PiP = recenter гиро
        pipContainer.setOnClickListener {
            gyroCtrl?.zero()
        }
    }

    // ── Gear ─────────────────────────────────────────────────────────────

    private fun setupGear() {
        btnGear.setOnCheckedChangeListener { _, isChecked ->
            vm.setGear(if (isChecked) 1 else 2)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.gear.collectLatest { g ->
                btnGear.isChecked = (g == 1)
            }
        }
    }

    // ── Turret stream ────────────────────────────────────────────────────

    private fun observeTurretStream() {
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
                if (connected && !btnPip.isChecked) {
                    btnPip.isChecked = true
                }
            }
        }
    }

    // ── Телеметрия ───────────────────────────────────────────────────────

    private fun observeTelemetry() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.telem.collectLatest { t ->
                tvBat.text = if (t.bat >= 0) "${t.bat}%" else "--"

                // Реальная скорость из RPM + мощность мотора
                val realSpeed = t.speedMs
                tvSpd.text = if (!realSpeed.isNaN()) {
                    String.format("%.2f m/s (%d%%)", kotlin.math.abs(realSpeed), t.powerPct)
                } else {
                    String.format("%d%%", t.powerPct)
                }

                tvYaw.text  = String.format("%.0f°", t.yaw)
                tvRssi.text = "${t.rssi} dBm"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.pose.collectLatest { p ->
                tvOdom.text = String.format("X:%.2f Y:%.2f H:%.0f° D:%.1fm",
                    p.x, p.y,
                    Math.toDegrees(p.headingRad.toDouble()),
                    vm.getOdometry().distanceMeters
                )
                // Обновляем карту
                roverViz?.refresh()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        gyroCtrl?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (btnGyroTilt.isChecked) {
            gyroCtrl?.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gyroTickJob?.cancel()
        gyroCtrl?.stop()
    }
}
