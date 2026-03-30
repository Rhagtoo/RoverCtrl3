package org.rhanet.roverctrl.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.rhanet.roverctrl.data.ConnectionConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CommandSender {

    companion object {
        private const val TAG = "CommandSender"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private val socketLock = ReentrantLock()
    private var roverAddr: InetAddress? = null
    private var roverPort: Int = 4210
    private var xiaoAddr: InetAddress? = null
    private var xiaoPort: Int = 4210
    private var sendCount: Long = 0
    private var errorCount: Long = 0

    init {
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Socket init failed: ${e.message}")
        }
    }

    fun configure(cfg: ConnectionConfig) {
        try {
            roverAddr = InetAddress.getByName(cfg.roverIp)
            roverPort = cfg.cmdPort
            xiaoAddr = InetAddress.getByName(cfg.xiaoIp)
            xiaoPort = cfg.xiaoPort
        } catch (e: Exception) {
            Log.e(TAG, "Configure failed: ${e.message}")
        }
    }

    fun clearHosts() {
        roverAddr = null
        xiaoAddr = null
    }

    fun send(spd: Int, str: Int, fwd: Int, laser: Boolean, pan: Int, tilt: Int, gear: Int = 2) {
        sendRover(spd, str, fwd, laser, gear)
        sendXiao(pan, tilt)
    }

    fun sendRover(spd: Int, str: Int, fwd: Int, laser: Boolean, gear: Int = 2) {
        var laserInt = 0
        if (laser) {
            laserInt = 1
        }
        val msg = "SPD:$spd;STR:$str;FWD:$fwd;LASER:$laserInt;GEAR:$gear\n"
        scope.launch {
            val addr = roverAddr
            if (addr != null) {
                sendRaw(msg.toByteArray(), addr, roverPort)
            }
        }
    }

    fun sendXiao(pan: Int, tilt: Int) {
        val panScaled = (pan * 90 / 100).coerceIn(-90, 90)
        val tiltScaled = (tilt * 90 / 100).coerceIn(-90, 90)
        val msg = "PAN:$panScaled;TILT:$tiltScaled\n"
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw(msg.toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** Отправить VCAL команду — прямая установка виртуального угла тилта (калибровка) */
    fun sendVcal(angle: Float) {
        val clamped = angle.coerceIn(0f, 180f)
        val msg = "VCAL:${String.format("%.1f", clamped)}\n"
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw(msg.toByteArray(), addr, xiaoPort)
            }
        }
    }

    fun sendEmergencyStop() {
        scope.launch {
            val addr = roverAddr
            if (addr != null) {
                sendRaw("SPD:0;STR:0;FWD:0;LASER:0\n".toByteArray(), addr, roverPort)
            }
        }
    }

    private fun sendRaw(data: ByteArray, addr: InetAddress, port: Int) {
        socketLock.withLock {
            try {
                val sock = socket
                if (sock != null) {
                    val pkt = DatagramPacket(data, data.size, addr, port)
                    sock.send(pkt)
                    sendCount++
                }
            } catch (e: Exception) {
                errorCount++
                if (errorCount % 100 == 1L) {
                    Log.w(TAG, "Send error ($errorCount): ${e.message}")
                }
            }
        }
    }

    fun close() {
        scope.cancel()
        socketLock.withLock {
            socket?.close()
            socket = null
        }
    }

    fun getStats(): String {
        return "Sent:$sendCount Err:$errorCount"
    }
}
