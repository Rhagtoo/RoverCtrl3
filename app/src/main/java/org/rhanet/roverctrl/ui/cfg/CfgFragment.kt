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

    // Tilt calibration
    private lateinit var tvTiltStatus: TextView
    private lateinit var btnTiltM5:    Button
    private lateinit var btnTiltM1:    Button
    private lateinit var btnTiltReset: Button
    private lateinit var btnTiltP1:    Button
    private lateinit var btnTiltP5:    Button
    private var tiltPollJob: Job? = null
    private var lastKnownVirtual: Float = 90f

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

        // Tilt calibration
        tvTiltStatus = view.findViewById(R.id.tv_tilt_status)
        btnTiltM5    = view.findViewById(R.id.btn_tilt_m5)
        btnTiltM1    = view.findViewById(R.id.btn_tilt_m1)
        btnTiltReset = view.findViewById(R.id.btn_tilt_reset)
        btnTiltP1    = view.findViewById(R.id.btn_tilt_p1)
        btnTiltP5    = view.findViewById(R.id.btn_tilt_p5)

        loadSavedConfig()
        loadSensitivity()
        setupOta()
        setupSensitivitySliders()
        setupTiltCalibration()

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

    // ── Tilt Calibration ──────────────────────────────────────────────────

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
    }

    private fun startTiltPolling() {
        stopTiltPolling()
        val ip = etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
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
                    } else {
                        tvTiltStatus.text = "turret offline"
                        tvTiltStatus.setTextColor(0xFFFF5252.toInt())
                    }
                } catch (e: Exception) {
                    tvTiltStatus.text = "poll error"
                    tvTiltStatus.setTextColor(0xFFFF5252.toInt())
                }
                delay(TILT_POLL_MS)
            }
        }
    }

    private fun stopTiltPolling() {
        tiltPollJob?.cancel()
        tiltPollJob = null
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
        btnSelectBin.setOnClickListener {
            filePicker.launch("*/*")
        }

        btnOtaUpload.setOnClickListener {
            val uri = selectedBinUri ?: return@setOnClickListener
            val ip = if (rbOtaRover.isChecked) {
                etRoverIp.text.toString().trim().ifEmpty { "192.168.4.1" }
            } else {
                etXiaoIp.text.toString().trim().ifEmpty { "192.168.4.2" }
            }
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
