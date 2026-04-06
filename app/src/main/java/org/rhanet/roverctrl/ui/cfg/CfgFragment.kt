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
import android.widget.LinearLayout
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
    }

    // Adjustable test duration (ms). Start with 500ms for safety.
    // Firmware TCAL runs for 2s max, with auto-stop at 60° estimated travel
    private var tcalDurationMs = 500L

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

    // Tracking tuning
    private lateinit var sbTrkDeadzone:  SeekBar
    private lateinit var sbTrkExpo:      SeekBar
    private lateinit var sbTrkRate:      SeekBar
    private lateinit var tvTrkDzVal:     TextView
    private lateinit var tvTrkExpoVal:   TextView
    private lateinit var tvTrkRateVal:   TextView

    // Tilt calibration (VCAL)
    private lateinit var tvTiltStatus: TextView
    private lateinit var etVcalAngle: EditText
    private lateinit var btnVcalSet:  Button

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
    private lateinit var llTcalInput:    LinearLayout
    private lateinit var etTcalActual:   EditText
    private lateinit var btnTcalApply:   Button
    private lateinit var btnTcalCancel:  Button
    private lateinit var rgTcalDuration: RadioGroup

    private var tiltPollJob: Job? = null
    private var lastKnownVirtual: Float = 90f
    private var tcalStartAngle: Float = 0f
    private var lastTcalDirection: String = ""  // "UP" or "DN"

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

        // Tracking tuning
        sbTrkDeadzone   = view.findViewById(R.id.sb_trk_deadzone)
        sbTrkExpo       = view.findViewById(R.id.sb_trk_expo)
        sbTrkRate       = view.findViewById(R.id.sb_trk_rate)
        tvTrkDzVal      = view.findViewById(R.id.tv_trk_dz_val)
        tvTrkExpoVal    = view.findViewById(R.id.tv_trk_expo_val)
        tvTrkRateVal    = view.findViewById(R.id.tv_trk_rate_val)

        // Tilt calibration (VCAL)
        tvTiltStatus = view.findViewById(R.id.tv_tilt_status)
        etVcalAngle  = view.findViewById(R.id.et_vcal_angle)
        btnVcalSet   = view.findViewById(R.id.btn_vcal_set)

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
        llTcalInput     = view.findViewById(R.id.ll_tcal_input)
        etTcalActual    = view.findViewById(R.id.et_tcal_actual)
        btnTcalApply    = view.findViewById(R.id.btn_tcal_apply)
        btnTcalCancel   = view.findViewById(R.id.btn_tcal_cancel)
        rgTcalDuration  = view.findViewById(R.id.rg_tcal_duration)

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
        // Set Virtual angle — tells ESP "this position = X°", camera does NOT move
        btnVcalSet.setOnClickListener {
            val angle = etVcalAngle.text.toString().toFloatOrNull() ?: 90f
            sendVcal(angle)
        }

        // TCAL Apply — user entered actual degrees moved
        btnTcalApply.setOnClickListener { applyTcalResult() }
        btnTcalCancel.setOnClickListener {
            llTcalInput.visibility = View.GONE
            tvTcalResult.text = "Cancelled"
            tvTcalResult.setTextColor(0xFF888888.toInt())
        }
    }

    private fun sendVcal(angle: Float) {
        val clamped = angle.coerceIn(0f, 180f)
        lastKnownVirtual = clamped
        vm.sender.sendVcal(clamped)
        tvTiltStatus.text = "VCAL → ${String.format("%.1f", clamped)}°"
        tvTiltStatus.setTextColor(0xFFFFAB00.toInt())
        Log.d(TAG, "VCAL sent: $clamped° (camera stays put)")
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

        // Duration selector
        rgTcalDuration.setOnCheckedChangeListener { _, id ->
            tcalDurationMs = when (id) {
                R.id.rb_tcal_05s -> 500L
                R.id.rb_tcal_1s  -> 1000L
                R.id.rb_tcal_2s  -> 2000L
                else -> 500L
            }
        }

        // Test sweep buttons — motor runs tcalDurationMs, NO virtual angle update on ESP
        btnTcalUp.setOnClickListener { startTcal("UP") }
        btnTcalDn.setOnClickListener { startTcal("DN") }

        // Save to ESP NVS
        btnTpSave.setOnClickListener {
            vm.sender.sendTsave()
            Toast.makeText(requireContext(), "Saved to ESP NVS", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Start calibration test sweep.
     * Motor runs at tcalSpeed (NOT maxSpeed) for tcalDurationMs.
     * Auto-stops at 60° estimated. Virtual angle NOT updated on ESP.
     */
    private fun startTcal(direction: String) {
        tcalStartAngle = lastKnownVirtual
        lastTcalDirection = direction
        llTcalInput.visibility = View.GONE
        etTcalActual.text.clear()
        val durSec = tcalDurationMs / 1000f
        tvTcalResult.text = "Testing $direction... ${durSec}s (safe speed, auto-stop 60°)"
        tvTcalResult.setTextColor(0xFFFFAB00.toInt())

        btnTcalUp.isEnabled = false
        btnTcalDn.isEnabled = false

        val durMs = tcalDurationMs.toInt()
        if (direction == "UP") vm.sender.sendTcalUp(durMs)
        else vm.sender.sendTcalDn(durMs)

        viewLifecycleOwner.lifecycleScope.launch {
            delay(tcalDurationMs + 500)  // wait for sweep + settle
            btnTcalUp.isEnabled = true
            btnTcalDn.isEnabled = true

            val dir = if (direction == "UP") "↑" else "↓"
            tvTcalResult.text = "Test $dir done. How many degrees did the camera actually move?"
            tvTcalResult.setTextColor(0xFF80CBC4.toInt())
            llTcalInput.visibility = View.VISIBLE
            etTcalActual.requestFocus()
        }
    }

    /**
     * Apply calibration result.
     * dps = actualDegrees / (tcalDurationMs / 1000)
     * Sync virtual angle to new position via VCAL.
     */
    private fun applyTcalResult() {
        val actualDegrees = etTcalActual.text.toString().toFloatOrNull()
        if (actualDegrees == null || actualDegrees <= 0f) {
            Toast.makeText(requireContext(), "Enter positive degrees", Toast.LENGTH_SHORT).show()
            return
        }
        if (actualDegrees > 170f) {
            Toast.makeText(requireContext(),
                "⚠ ${actualDegrees}° is very large — are you sure? Consider shorter test duration.",
                Toast.LENGTH_LONG).show()
        }

        val elapsedSec = tcalDurationMs / 1000f
        val newDps = actualDegrees / elapsedSec
        val isUp = lastTcalDirection == "UP"

        // New virtual angle after test
        val newVirtual = if (isUp)
            (tcalStartAngle - actualDegrees).coerceIn(0f, 180f)
        else
            (tcalStartAngle + actualDegrees).coerceIn(0f, 180f)

        // Apply dps
        if (isUp) {
            vm.sender.sendTset(dpsUp = newDps)
            sbTpDpsUp.progress = newDps.toInt()
            tvTpDpsUpVal.text = newDps.toInt().toString()
        } else {
            vm.sender.sendTset(dpsDn = newDps)
            sbTpDpsDn.progress = newDps.toInt()
            tvTpDpsDnVal.text = newDps.toInt().toString()
        }

        // Sync virtual angle — servo stays put (VCAL forces neutral)
        sendVcal(newVirtual)
        etVcalAngle.setText(String.format("%.0f", newVirtual))

        val dir = if (isUp) "↑ UP" else "↓ DN"
        tvTcalResult.text = String.format(
            "%s: %.0f° / %.1fs = %.0f°/s → Virtual=%.0f°",
            dir, actualDegrees, elapsedSec, newDps, newVirtual)
        tvTcalResult.setTextColor(0xFF00E676.toInt())
        llTcalInput.visibility = View.GONE

        Log.d(TAG, "TCAL applied: $dir actual=${actualDegrees}° dps=${newDps} virtual=$newVirtual")
    }

    // ── Tilt Status Polling ───────────────────────────────────────────────

    private fun startTiltPolling() {
        stopTiltPolling()
        val ip = etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
        // v2.7: /status only on stream port (81, async esp_httpd)
        val port = etXiaoStreamPort.text.toString().toIntOrNull() ?: 81
        val statusUrl = "http://$ip:$port/status"

        tiltPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val json = withContext(Dispatchers.IO) { fetchStatus(statusUrl) }

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

                        updateSlidersFromStatus(json)
                    } else {
                        tvTiltStatus.text = String.format(
                            "V:%.1f° (assumed)  — /status not available",
                            lastKnownVirtual)
                        tvTiltStatus.setTextColor(0xFFFFAB00.toInt())
                    }
                } catch (e: Exception) {
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
            val isRover = rbOtaRover.isChecked
            val ip = if (isRover)
                etRoverIp.text.toString().trim().ifEmpty { "192.168.4.1" }
            else
                etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
            // Rover: multipart on port 80 (Arduino WebServer)
            // Turret v2.7+: raw binary on stream port (esp_httpd)
            val port = if (isRover) 80
                       else etXiaoStreamPort.text.toString().toIntOrNull() ?: 81
            val rawBinary = !isRover
            startOtaUpload(ip, port, rawBinary, uri)
        }
    }

    private fun startOtaUpload(ip: String, port: Int, rawBinary: Boolean, uri: Uri) {
        btnOtaUpload.isEnabled = false
        btnSelectBin.isEnabled = false
        pbOta.visibility = View.VISIBLE
        pbOta.progress = 0
        tvOtaStatus.text = "Uploading..."
        tvOtaStatus.setTextColor(0xFFFFAB00.toInt())

        viewLifecycleOwner.lifecycleScope.launch {
            val result = OtaUploader.upload(
                deviceIp   = ip,
                port       = port,
                rawBinary  = rawBinary,
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
        // Tracking tuning
        sbTrkDeadzone.progress = (s.trackDeadzone * 100).toInt()  // 0.04 → 4
        sbTrkExpo.progress     = (s.trackExpo * 10).toInt()       // 2.0 → 20
        sbTrkRate.progress     = s.trackRateLimit.toInt()          // 8 → 8
        tvTrkDzVal.text  = "${sbTrkDeadzone.progress}%"
        tvTrkExpoVal.text = String.format("%.1f", s.trackExpo)
        tvTrkRateVal.text = sbTrkRate.progress.toString()
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

        // Tracking tuning sliders
        sbTrkDeadzone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvTrkDzVal.text = "$p%"
                if (fromUser) saveSensitivity()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        sbTrkExpo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvTrkExpoVal.text = String.format("%.1f", p / 10f)
                if (fromUser) saveSensitivity()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        sbTrkRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvTrkRateVal.text = p.toString()
                if (fromUser) saveSensitivity()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun saveSensitivity() {
        val s = AppSettings(
            driveSpeedSens = AppSettings.progressToSens(sbDriveSpeed.progress),
            driveSteerSens = AppSettings.progressToSens(sbDriveSteer.progress),
            camPanSens     = AppSettings.progressToSens(sbCamPan.progress),
            camTiltSens    = AppSettings.progressToSens(sbCamTilt.progress),
            trackDeadzone  = sbTrkDeadzone.progress / 100f,   // 4 → 0.04
            trackExpo      = sbTrkExpo.progress / 10f,        // 20 → 2.0
            trackRateLimit = sbTrkRate.progress.toFloat()      // 8 → 8.0
        )
        vm.updateSensitivity(requireContext(), s)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: stopping tilt polling")
        stopTiltPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: stopping tilt polling")
        stopTiltPolling()
    }
}
