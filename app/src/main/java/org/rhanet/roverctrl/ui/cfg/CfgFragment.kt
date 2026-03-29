package org.rhanet.roverctrl.ui.cfg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.ConnectionConfig
import org.rhanet.roverctrl.ui.RoverViewModel

class CfgFragment : Fragment() {

    private val vm: RoverViewModel by activityViewModels()

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cfg, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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

        loadSavedConfig()

        btnConnect.setOnClickListener {
            if (vm.connected.value) disconnect() else connect()
        }
        btnReset.setOnClickListener { resetToDefaults() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.connected.collectLatest { updateUiState(it) }
        }
    }

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

        // FIX: передаём context чтобы ViewModel мог стартовать RSSI polling через WifiManager
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
        } else {
            tvStatus.text = "Disconnected"
            tvStatus.setTextColor(0xFFFF5252.toInt())
            btnConnect.text = "Connect"
            setFieldsEnabled(true)
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
}
