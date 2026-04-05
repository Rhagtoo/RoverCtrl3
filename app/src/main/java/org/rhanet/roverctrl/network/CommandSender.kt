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
        val laserInt = if (laser) 1 else 0
        val msg = "SPD:$spd;STR:$str;FWD:$fwd;LASER:$laserInt;GEAR:$gear\n"
        scope.launch {
            val addr = roverAddr
            if (addr != null) {
                sendRaw(msg.toByteArray(), addr, roverPort)
            }
        }
    }

    fun sendXiao(pan: Int, tilt: Int) {
        Log.d(TAG, "sendXiao: pan=$pan, tilt=$tilt")
        val panScaled = (pan * 90 / 100).coerceIn(-90, 90)
        val tiltScaled = (tilt * 90 / 100).coerceIn(-90, 90)
        Log.d(TAG, "sendXiao scaled: panScaled=$panScaled, tiltScaled=$tiltScaled")
        val msg = "PAN:$panScaled;TILT:$tiltScaled\n"
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                Log.d(TAG, "Sending to $addr:$xiaoPort: $msg")
                sendRaw(msg.toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** VCAL — direct virtual tilt angle set (calibration) */
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

    /**
     * TSET — set tilt parameters at runtime (not persisted until TSAVE)
     *
     * @param neutral    PWM stop value (70-110, typically 88-92)
     * @param maxSpeed   max PWM offset from neutral (10-90)
     * @param dpsUp      °/s when camera moves UP (angle decreasing, against gravity)
     * @param dpsDn      °/s when camera moves DOWN (angle increasing, with gravity)
     * @param deadband   deadband in degrees (1.0-20.0)
     * @param driftCorr  drift correction speed °/s (0-100)
     * @param tcalSpeed  safe PWM offset for TCAL sweeps (5-60, default 20)
     */
    fun sendTset(
        neutral: Int? = null,
        maxSpeed: Int? = null,
        dpsUp: Float? = null,
        dpsDn: Float? = null,
        deadband: Float? = null,
        driftCorr: Float? = null,
        tcalSpeed: Int? = null
    ) {
        val parts = mutableListOf("TSET:")
        neutral?.let    { parts.add("N:$it") }
        maxSpeed?.let   { parts.add("S:$it") }
        dpsUp?.let      { parts.add("U:${String.format("%.1f", it)}") }
        dpsDn?.let      { parts.add("D:${String.format("%.1f", it)}") }
        deadband?.let   { parts.add("DB:${String.format("%.1f", it)}") }
        driftCorr?.let  { parts.add("DC:${String.format("%.1f", it)}") }
        tcalSpeed?.let  { parts.add("TS:$it") }

        if (parts.size <= 1) return  // nothing to set
        val msg = parts.joinToString(";") + "\n"
        Log.d(TAG, "TSET: $msg")
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw(msg.toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** TSAVE — persist current tilt config to ESP32 NVS */
    fun sendTsave() {
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw("TSAVE\n".toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** TCAL:UP[:ms] — test sweep camera UP for given duration */
    fun sendTcalUp(durationMs: Int = 500) {
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw("TCAL:UP:$durationMs\n".toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** TCAL:DN[:ms] — test sweep camera DOWN for given duration */
    fun sendTcalDn(durationMs: Int = 500) {
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw("TCAL:DN:$durationMs\n".toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** TCAL:STOP — abort test sweep */
    fun sendTcalStop() {
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw("TCAL:STOP\n".toByteArray(), addr, xiaoPort)
            }
        }
    }

    /** CEXIT — exit calibration mode, resume normal PAN/TILT control */
    fun sendCexit() {
        scope.launch {
            val addr = xiaoAddr
            if (addr != null) {
                sendRaw("CEXIT\n".toByteArray(), addr, xiaoPort)
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

    fun getStats(): String = "Sent:$sendCount Err:$errorCount"
}
