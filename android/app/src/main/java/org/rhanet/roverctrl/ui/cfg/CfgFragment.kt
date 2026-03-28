package org.rhanet.roverctrl.ui.cfg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.data.ConnectionConfig
import org.rhanet.roverctrl.ui.RoverViewModel

// ──────────────────────────────────────────────────────────────────────────
// CfgFragment — Настройки подключения
//
// Rover IP + порты, Xiao IP + порт (серво) + stream port (камера).
// ──────────────────────────────────────────────────────────────────────────

class CfgFragment : Fragment() {

    private val vm: RoverViewModel by activityViewModels()

    private lateinit var etRoverIp:       EditText
    private lateinit var etCmdPort:       EditText
    private lateinit var etTelPort:       EditText
    private lateinit var etXiaoIp:        EditText
    private lateinit var etXiaoPort:      EditText
    private lateinit var etXiaoStreamPort: EditText
    private lateinit var btnConnect:      Button
    private lateinit var tvStatus:        TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cfg, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etRoverIp       = view.findViewById(R.id.et_rover_ip)
        etCmdPort       = view.findViewById(R.id.et_cmd_port)
        etTelPort       = view.findViewById(R.id.et_tel_port)
        etXiaoIp        = view.findViewById(R.id.et_xiao_ip)
        etXiaoPort      = view.findViewById(R.id.et_xiao_port)
        etXiaoStreamPort = view.findViewById(R.id.et_xiao_stream_port)
        btnConnect      = view.findViewById(R.id.btn_connect)
        tvStatus        = view.findViewById(R.id.tv_connection_status)

        val defaults = ConnectionConfig()
        etRoverIp.setText(defaults.roverIp)
        etCmdPort.setText(defaults.cmdPort.toString())
        etTelPort.setText(defaults.telPort.toString())
        etXiaoIp.setText(defaults.xiaoIp)
        etXiaoPort.setText(defaults.xiaoPort.toString())
        etXiaoStreamPort.setText(defaults.xiaoStreamPort.toString())

        btnConnect.setOnClickListener { toggleConnection() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.connected.collectLatest { connected ->
                btnConnect.text = if (connected) "Disconnect" else "Connect"
                tvStatus.text   = if (connected) "Connected" else "Disconnected"
                tvStatus.setTextColor(
                    if (connected) 0xFF00E676.toInt() else 0xFFFF5252.toInt()
                )
                setFieldsEnabled(!connected)
            }
        }
    }

    private fun toggleConnection() {
        if (vm.connected.value) {
            vm.disconnect()
        } else {
            val cfg = ConnectionConfig(
                roverIp        = etRoverIp.text.toString().trim(),
                cmdPort        = etCmdPort.text.toString().toIntOrNull() ?: 4210,
                telPort        = etTelPort.text.toString().toIntOrNull() ?: 4211,
                xiaoIp         = etXiaoIp.text.toString().trim(),
                xiaoPort       = etXiaoPort.text.toString().toIntOrNull() ?: 4210,
                xiaoStreamPort = etXiaoStreamPort.text.toString().toIntOrNull() ?: 81
            )
            vm.connect(cfg)
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        etRoverIp.isEnabled       = enabled
        etCmdPort.isEnabled       = enabled
        etTelPort.isEnabled       = enabled
        etXiaoIp.isEnabled        = enabled
        etXiaoPort.isEnabled      = enabled
        etXiaoStreamPort.isEnabled = enabled
    }
}
