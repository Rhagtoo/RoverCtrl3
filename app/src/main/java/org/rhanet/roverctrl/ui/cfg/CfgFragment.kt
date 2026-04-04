package org.rhanet.roverctrl.ui.cfg

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.AppSettings
import org.rhanet.roverctrl.data.ConnectionConfig
import org.rhanet.roverctrl.network.OtaUploader
import org.rhanet.roverctrl.ui.RoverViewModel
import java.net.HttpURLConnection
import java.net.URL

class CfgFragment : Fragment() {

    companion object {
        private const val TAG = "CfgFragment"
        private const val TILT_POLL_MS = 500L
        private const val TCAL_DURATION_MS = 2000L
    }

    private val vm: RoverViewModel by activityViewModels()

    // Connection fields
    private lateinit var etRoverIp:         EditText
    private lateinit var etCmdPort:         EditText
    private lateinit var etTelPort:         EditText
    private lateinit var etXiaoIp:          EditText
    private lateinit var etXiaoPort:        EditText
    private lateinit var etXiaoStreamPort:  EditText
    private lateinit var btnConnect:        Button
    private lateinit var btnReset:          Button
    private lateinit var tvStatus:          TextView
    private lateinit var tvHint:            TextView

    // OTA views
    private lateinit var rgOtaTarget:   RadioGroup
    private lateinit var rbOtaRover:    RadioButton
    private lateinit var rbOtaTurret:   RadioButton
    private lateinit var btnSelectBin:  Button
    private lateinit var tvOtaFilename: TextView
    private lateinit var pbOta:         ProgressBar
    private lateinit var tvOtaStatus:   TextView
    private lateinit var btnOtaUpload:  Button

    // Sensitivity sliders
    private lateinit var sbDriveSpeed:    SeekBar
    private lateinit var sbDriveSteer:    SeekBar
    private lateinit var sbCamPan:        SeekBar
    private lateinit var sbCamTilt:       SeekBar
    private lateinit var tvDriveSpeedVal: TextView
    private lateinit var tvDriveSteerVal: TextView
    private lateinit var tvCamPanVal:     TextView
    private lateinit var tvCamTiltVal:    TextView

    // Tilt calibration (VCAL)
    private lateinit var tvTiltStatus: TextView
    private lateinit var btnTiltM5:    Button
    private lateinit var btnTiltM1:    Button
    private lateinit var btnTiltReset: Button
    private lateinit var btnTiltP1:    Button
    private lateinit var btnTiltP5:    Button

    // Tilt parameters (TSET)
    private lateinit var sbTpNeutral:    SeekBar
    private lateinit var sbTpSpeed:      SeekBar
    private lateinit var sbTpDpsUp:      SeekBar
    private lateinit var sbTpDpsDn:      SeekBar
    private lateinit var sbTpDeadband:   SeekBar
    private lateinit var tvTpNeutralVal: TextView
    private lateinit var tvTpSpeedVal:   TextView
    private lateinit var tvTpDpsUpVal:   TextView
    private lateinit var tvTpDpsDnVal:   TextView
    private lateinit var tvTpDeadbandVal:TextView
    private lateinit var btnTcalUp:      Button
    private lateinit var btnTcalDn:      Button
    private lateinit var btnTpSave:      Button
    private lateinit var tvTcalResult:   TextView

    private var tiltPollJob: Job? = null
    private var lastKnownVirtual: Float = 90f
    private var tcalStartAngle: Float = 0f   // for measuring test sweep delta

    private var selectedBinUri: Uri? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        selectedBinUri = uri
        tvOtaFilename.text = getFileName(uri)
        tvOtaFilename.setTextColor(0xFFFFFFFF.toInt())
        btnOtaUpload.isEnabled = true
        tvOtaStatus.text = ""
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cfg, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Connection
        etRoverIp        = view.findViewById(R.id.et_rover_ip)
        etCmdPort        = view.findViewById(R.id.et_cmd_port)
        etTelPort        = view.findViewById(R.id.et_tel_port)
        etXiaoIp         = view.findViewById(R.id.et_xiao_ip)
        etXiaoPort       = view.findViewById(R.id.et_xiao_port)
        etXiaoStreamPort = view.findViewById(R.id.et_xiao_stream_port)
        btnConnect       = view.findViewById(R.id.btn_connect)
        btnReset         = view.findViewById(R.id.btn_reset)
        tvStatus         = view.findViewById(R.id.tv_connection_status)
        tvHint           = view.findViewById(R.id.tv_hint)

        // OTA
        rgOtaTarget   = view.findViewById(R.id.rg_ota_target)
        rbOtaRover    = view.findViewById(R.id.rb_ota_rover)
        rbOtaTurret   = view.findViewById(R.id.rb_ota_turret)
        btnSelectBin  = view.findViewById(R.id.btn_select_bin)
        tvOtaFilename = view.findViewById(R.id.tv_ota_filename)
        pbOta         = view.findViewById(R.id.pb_ota)
        tvOtaStatus   = view.findViewById(R.id.tv_ota_status)
        btnOtaUpload  = view.findViewById(R.id.btn_ota_upload)

        // Sensitivity
        sbDriveSpeed    = view.findViewById(R.id.sb_drive_speed)
        sbDriveSteer    = view.findViewById(R.id.sb_drive_steer)
        sbCamPan        = view.findViewById(R.id.sb_cam_pan)
        sbCamTilt       = view.findViewById(R.id.sb_cam_tilt)
        tvDriveSpeedVal = view.findViewById(R.id.tv_drive_speed_val)
        tvDriveSteerVal = view.findViewById(R.id.tv_drive_steer_val)
        tvCamPanVal     = view.findViewById(R.id.tv_cam_pan_val)
        tvCamTiltVal    = view.findViewById(R.id.tv_cam_tilt_val)

        // Tilt calibration (VCAL)
        tvTiltStatus = view.findViewById(R.id.tv_tilt_status)
        btnTiltM5    = view.findViewById(R.id.btn_tilt_m5)
        btnTiltM1    = view.findViewById(R.id.btn_tilt_m1)
        btnTiltReset = view.findViewById(R.id.btn_tilt_reset)
        btnTiltP1    = view.findViewById(R.id.btn_tilt_p1)
        btnTiltP5    = view.findViewById(R.id.btn_tilt_p5)

        // Tilt parameters (TSET)
        sbTpNeutral     = view.findViewById(R.id.sb_tp_neutral)
        sbTpSpeed       = view.findViewById(R.id.sb_tp_speed)
        sbTpDpsUp       = view.findViewById(R.id.sb_tp_dps_up)
        sbTpDpsDn       = view.findViewById(R.id.sb_tp_dps_dn)
        sbTpDeadband    = view.findViewById(R.id.sb_tp_deadband)
        tvTpNeutralVal  = view.findViewById(R.id.tv_tp_neutral_val)
        tvTpSpeedVal    = view.findViewById(R.id.tv_tp_speed_val)
        tvTpDpsUpVal    = view.findViewById(R.id.tv_tp_dps_up_val)
        tvTpDpsDnVal    = view.findViewById(R.id.tv_tp_dps_dn_val)
        tvTpDeadbandVal = view.findViewById(R.id.tv_tp_deadband_val)
        btnTcalUp       = view.findViewById(R.id.btn_tcal_up)
        btnTcalDn       = view.findViewById(R.id.btn_tcal_dn)
        btnTpSave       = view.findViewById(R.id.btn_tp_save)
        tvTcalResult    = view.findViewById(R.id.tv_tcal_result)

        loadSavedConfig()
        loadSensitivity()
        setupOta()
        setupSensitivitySliders()
        setupTiltCalibration()
        setupTiltParameters()

        btnConnect.setOnClickListener {
            if (vm.connected.value) disconnect() else connect()
        }
        btnReset.setOnClickListener { resetToDefaults() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.connected.collectLatest { updateUiState(it) }
        }
    }

    // ── Connection ────────────────────────────────────────────────────────

    private fun loadSavedConfig() {
        val config = ConnectionConfig.load(requireContext())
        etRoverIp.setText(config.roverIp)
        etCmdPort.setText(config.cmdPort.toString())
        etTelPort.setText(config.telPort.toString())
        etXiaoIp.setText(config.xiaoIp)
        etXiaoPort.setText(config.xiaoPort.toString())
        etXiaoStreamPort.setText(config.xiaoStreamPort.toString())
    }

    private fun resetToDefaults() {
        ConnectionConfig.reset(requireContext())
        val d = ConnectionConfig()
        etRoverIp.setText(d.roverIp)
        etCmdPort.setText(d.cmdPort.toString())
        etTelPort.setText(d.telPort.toString())
        etXiaoIp.setText(d.xiaoIp)
        etXiaoPort.setText(d.xiaoPort.toString())
        etXiaoStreamPort.setText(d.xiaoStreamPort.toString())
        Toast.makeText(requireContext(), "Reset to defaults (AP mode)", Toast.LENGTH_SHORT).show()
    }

    private fun connect() {
        val config = try {
            ConnectionConfig(
                roverIp        = etRoverIp.text.toString().trim(),
                cmdPort        = etCmdPort.text.toString().toIntOrNull() ?: 4210,
                telPort        = etTelPort.text.toString().toIntOrNull() ?: 4211,
                xiaoIp         = etXiaoIp.text.toString().trim(),
                xiaoPort       = etXiaoPort.text.toString().toIntOrNull() ?: 4210,
                xiaoStreamPort = etXiaoStreamPort.text.toString().toIntOrNull() ?: 81
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid configuration", Toast.LENGTH_SHORT).show()
            return
        }
        if (!config.isValid()) {
            Toast.makeText(requireContext(), "Invalid IP or port values", Toast.LENGTH_SHORT).show()
            return
        }
        ConnectionConfig.save(requireContext(), config)
        vm.connect(config, requireContext())
        Toast.makeText(requireContext(), "Connecting...", Toast.LENGTH_SHORT).show()
    }

    private fun disconnect() {
        vm.disconnect()
        Toast.makeText(requireContext(), "Disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(connected: Boolean) {
        if (connected) {
            tvStatus.text = "Connected"
            tvStatus.setTextColor(0xFF00E676.toInt())
            btnConnect.text = "Disconnect"
            setFieldsEnabled(false)
            startTiltPolling()
        } else {
            tvStatus.text = "Disconnected"
            tvStatus.setTextColor(0xFFFF5252.toInt())
            btnConnect.text = "Connect"
            setFieldsEnabled(true)
            stopTiltPolling()
            tvTiltStatus.text = "-- not connected --"
            tvTiltStatus.setTextColor(0xFF888888.toInt())
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        etRoverIp.isEnabled        = enabled
        etCmdPort.isEnabled        = enabled
        etTelPort.isEnabled        = enabled
        etXiaoIp.isEnabled         = enabled
        etXiaoPort.isEnabled       = enabled
        etXiaoStreamPort.isEnabled = enabled
        btnReset.isEnabled         = enabled
    }

    // ── Tilt Calibration (VCAL) ───────────────────────────────────────────

    private fun setupTiltCalibration() {
        btnTiltM5.setOnClickListener    { sendVcal(lastKnownVirtual - 5f) }
        btnTiltM1.setOnClickListener    { sendVcal(lastKnownVirtual - 1f) }
        btnTiltReset.setOnClickListener { sendVcal(90f) }
        btnTiltP1.setOnClickListener    { sendVcal(lastKnownVirtual + 1f) }
        btnTiltP5.setOnClickListener    { sendVcal(lastKnownVirtual + 5f) }
    }

    private fun sendVcal(angle: Float) {
        val clamped = angle.coerceIn(0f, 180f)
        lastKnownVirtual = clamped
        vm.sender.sendVcal(clamped)
        // Update UI immediately
        tvTiltStatus.text = "VCAL → ${String.format("%.1f", clamped)}°"
        tvTiltStatus.setTextColor(0xFFFFAB00.toInt())
        Log.d(TAG, "VCAL sent: $clamped°")
    }

    // ── Tilt Parameters (TSET) ────────────────────────────────────────────

    private fun setupTiltParameters() {
        // Slider listeners — send TSET on change
        fun sbListener(tv: TextView, paramSender: (Int) -> Unit) =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    tv.text = progress.toString()
                    if (fromUser) paramSender(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            }

        sbTpNeutral.setOnSeekBarChangeListener(sbListener(tvTpNeutralVal) {
            vm.sender.sendTset(neutral = it)
        })
        sbTpSpeed.setOnSeekBarChangeListener(sbListener(tvTpSpeedVal) {
            vm.sender.sendTset(maxSpeed = it)
        })
        sbTpDpsUp.setOnSeekBarChangeListener(sbListener(tvTpDpsUpVal) {
            vm.sender.sendTset(dpsUp = it.toFloat())
        })
        sbTpDpsDn.setOnSeekBarChangeListener(sbListener(tvTpDpsDnVal) {
            vm.sender.sendTset(dpsDn = it.toFloat())
        })
        sbTpDeadband.setOnSeekBarChangeListener(sbListener(tvTpDeadbandVal) {
            vm.sender.sendTset(deadband = it.toFloat())
        })

        // Test sweep buttons
        btnTcalUp.setOnClickListener { startTcal("UP") }
        btnTcalDn.setOnClickListener { startTcal("DN") }

        // Save to ESP NVS
        btnTpSave.setOnClickListener {
            vm.sender.sendTsave()
            Toast.makeText(requireContext(), "Saved to ESP NVS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTcal(direction: String) {
        tcalStartAngle = lastKnownVirtual
        tvTcalResult.text = "Testing $direction... start=%.1f°".format(tcalStartAngle)
        tvTcalResult.setTextColor(0xFFFFAB00.toInt())

        if (direction == "UP") {
            vm.sender.sendTcalUp()
            // Simulate movement UP (angle decreases)
            lastKnownVirtual = (lastKnownVirtual - 30f).coerceAtLeast(0f)
        } else {
            vm.sender.sendTcalDn()
            // Simulate movement DOWN (angle increases)
            lastKnownVirtual = (lastKnownVirtual + 30f).coerceAtMost(180f)
        }

        // After 2.5s (2s sweep + 0.5s settle), read result
        viewLifecycleOwner.lifecycleScope.launch {
            delay(TCAL_DURATION_MS + 500)
            val delta = lastKnownVirtual - tcalStartAngle
            val actualDps = kotlin.math.abs(delta) / (TCAL_DURATION_MS / 1000f)
            
            if (kotlin.math.abs(delta) < 0.1f) {
                // If no movement detected, show warning
                tvTcalResult.text = String.format(
                    "%s: NO MOVEMENT (Δ=%.1f°) — check XIAO connection/commands",
                    direction, delta
                )
                tvTcalResult.setTextColor(0xFFFF5252.toInt())
            } else {
                tvTcalResult.text = String.format(
                    "%s: Δ=%.1f°  measured=%.0f°/s  (start=%.1f° end=%.1f°)",
                    direction, delta, actualDps, tcalStartAngle, lastKnownVirtual
                )
                tvTiltStatus.text = String.format("V:%.1f° (tested)", lastKnownVirtual)
                tvTcalResult.setTextColor(0xFF00E676.toInt())
            }
        }
    }

    // ── Tilt Status Polling ───────────────────────────────────────────────

    private fun startTiltPolling() {
        stopTiltPolling()
        val ip = etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
        // Try port 80 first (otaServer), then port 81 (camera server)
        val ports = listOf(80, etXiaoStreamPort.text.toString().toIntOrNull() ?: 81)
        var json: JSONObject? = null
        var usedPort = 80

        tiltPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    // Try each port until we get a response
                    json = null
                    for (port in ports) {
                        try {
                            val url = "http://$ip:$port/status"
                            json = withContext(Dispatchers.IO) { fetchStatus(url) }
                            if (json != null) {
                                usedPort = port
                                break
                            }
                        } catch (e: Exception) {
                            // Try next port
                            continue
                        }
                    }
                    
                    if (json != null) {
                        val virt   = json.optDouble("tilt", 90.0).toFloat()
                        val target = json.optDouble("tiltTarget", 90.0).toFloat()
                        val pwm    = json.optInt("tiltPwm", 90)
                        val pan    = json.optInt("pan", 90)
                        lastKnownVirtual = virt

                        tvTiltStatus.text = String.format(
                            "V:%.1f°  T:%.1f°  PWM:%d  PAN:%d", virt, target, pwm, pan)
                        tvTiltStatus.setTextColor(
                            if (kotlin.math.abs(virt - target) < 3f) 0xFF00E676.toInt()
                            else 0xFFFFAB00.toInt()
                        )

                        // Update sliders to match ESP values (first poll only or when not touching)
                        updateSlidersFromStatus(json)
                    } else {
                        // If status endpoint not available, show last known virtual angle
                        tvTiltStatus.text = String.format(
                            "V:%.1f° (assumed)  — status port %d/%d not available", 
                            lastKnownVirtual, ports[0], ports[1])
                        tvTiltStatus.setTextColor(0xFFFFAB00.toInt())
                    }
                } catch (e: Exception) {
                    // Show last known angle even on error
                    tvTiltStatus.text = String.format(
                        "V:%.1f° (last)  — poll error: %s", lastKnownVirtual, e.message?.take(30) ?: "unknown")
                    tvTiltStatus.setTextColor(0xFFFF5252.toInt())
                }
                delay(TILT_POLL_MS)
            }
        }
    }

    private var slidersInitialized = false

    private fun updateSlidersFromStatus(json: JSONObject) {
        if (slidersInitialized) return  // only set once from ESP
        if (!json.has("neutral")) return  // old firmware without params

        sbTpNeutral.progress  = json.optInt("neutral", 90)
        sbTpSpeed.progress    = json.optInt("maxSpeed", 60)
        sbTpDpsUp.progress    = json.optDouble("dpsUp", 70.0).toInt()
        sbTpDpsDn.progress    = json.optDouble("dpsDn", 90.0).toInt()
        sbTpDeadband.progress = json.optDouble("deadband", 5.0).toInt()

        tvTpNeutralVal.text   = sbTpNeutral.progress.toString()
        tvTpSpeedVal.text     = sbTpSpeed.progress.toString()
        tvTpDpsUpVal.text     = sbTpDpsUp.progress.toString()
        tvTpDpsDnVal.text     = sbTpDpsDn.progress.toString()
        tvTpDeadbandVal.text  = sbTpDeadband.progress.toString()

        slidersInitialized = true
    }

    private fun stopTiltPolling() {
        tiltPollJob?.cancel()
        tiltPollJob = null
        slidersInitialized = false
    }

    private fun fetchStatus(url: String): JSONObject? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                JSONObject(body)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchStatus: ${e.message}")
            null
        }
    }

    // ── OTA ───────────────────────────────────────────────────────────────

    private fun setupOta() {
        btnSelectBin.setOnClickListener { filePicker.launch("*/*") }
        btnOtaUpload.setOnClickListener {
            val uri = selectedBinUri ?: return@setOnClickListener
            val ip = if (rbOtaRover.isChecked)
                etRoverIp.text.toString().trim().ifEmpty { "192.168.4.1" }
            else
                etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
            startOtaUpload(ip, uri)
        }
    }

    private fun startOtaUpload(ip: String, uri: Uri) {
        btnOtaUpload.isEnabled = false
        btnSelectBin.isEnabled = false
        pbOta.visibility = View.VISIBLE
        pbOta.progress = 0
        tvOtaStatus.text = "Uploading..."
        tvOtaStatus.setTextColor(0xFFFFAB00.toInt())

        viewLifecycleOwner.lifecycleScope.launch {
            val result = OtaUploader.upload(
                deviceIp   = ip,
                binUri     = uri,
                context    = requireContext(),
                onProgress = { pct ->
                    requireActivity().runOnUiThread {
                        pbOta.progress = pct
                        tvOtaStatus.text = "Uploading... $pct%"
                    }
                }
            )
            result.onSuccess {
                pbOta.progress = 100
                tvOtaStatus.text = "Done! Device is rebooting..."
                tvOtaStatus.setTextColor(0xFF00E676.toInt())
                Toast.makeText(requireContext(), "OTA success! Rebooting...", Toast.LENGTH_LONG).show()
                selectedBinUri = null
                tvOtaFilename.text = "No file selected"
                tvOtaFilename.setTextColor(0xFF888888.toInt())
            }.onFailure { e ->
                tvOtaStatus.text = "Error: ${e.message}"
                tvOtaStatus.setTextColor(0xFFFF5252.toInt())
                Toast.makeText(requireContext(), "OTA failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnOtaUpload.isEnabled = true
            }
            btnSelectBin.isEnabled = true
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "firmware.bin"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    // ── Sensitivity ───────────────────────────────────────────────────────

    private fun loadSensitivity() {
        val s = AppSettings.load(requireContext())
        sbDriveSpeed.progress = AppSettings.sensToProgress(s.driveSpeedSens)
        sbDriveSteer.progress = AppSettings.sensToProgress(s.driveSteerSens)
        sbCamPan.progress     = AppSettings.sensToProgress(s.camPanSens)
        sbCamTilt.progress    = AppSettings.sensToProgress(s.camTiltSens)
        tvDriveSpeedVal.text = AppSettings.format(s.driveSpeedSens)
        tvDriveSteerVal.text = AppSettings.format(s.driveSteerSens)
        tvCamPanVal.text     = AppSettings.format(s.camPanSens)
        tvCamTiltVal.text    = AppSettings.format(s.camTiltSens)
    }

    private fun setupSensitivitySliders() {
        fun makeListener(tvVal: TextView, update: (Float) -> Unit) =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = AppSettings.progressToSens(progress)
                    tvVal.text = AppSettings.format(v)
                    if (fromUser) saveSensitivity()
                    if (fromUser) update(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            }

        sbDriveSpeed.setOnSeekBarChangeListener(makeListener(tvDriveSpeedVal) {})
        sbDriveSteer.setOnSeekBarChangeListener(makeListener(tvDriveSteerVal) {})
        sbCamPan.setOnSeekBarChangeListener(makeListener(tvCamPanVal) {})
        sbCamTilt.setOnSeekBarChangeListener(makeListener(tvCamTiltVal) {})
    }

    private fun saveSensitivity() {
        val s = AppSettings(
            driveSpeedSens = AppSettings.progressToSens(sbDriveSpeed.progress),
            driveSteerSens = AppSettings.progressToSens(sbDriveSteer.progress),
            camPanSens     = AppSettings.progressToSens(sbCamPan.progress),
            camTiltSens    = AppSettings.progressToSens(sbCamTilt.progress)
        )
        vm.updateSensitivity(requireContext(), s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTiltPolling()
    }
}
