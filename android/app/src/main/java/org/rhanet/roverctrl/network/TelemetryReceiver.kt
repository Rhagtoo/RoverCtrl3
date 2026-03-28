package org.rhanet.roverctrl.network

import kotlinx.coroutines.*
import org.json.JSONObject
import org.rhanet.roverctrl.data.TelemetryData
import java.net.DatagramPacket
import java.net.DatagramSocket

// ──────────────────────────────────────────────────────────────────────────
// TelemetryReceiver
//
// Слушает UDP :port.
// Ровер (192.168.88.24) шлёт каждые 500мс:
//   {"bat":N,"yaw":F,"spd":N,"pit":F,"rol":F,"rssi":N,"rpmL":F,"rpmR":F}
//
// Два режима использования:
//   1. receive(port) { data -> ... }  — suspend-функция в корутине
//   2. startThread(port) { data -> ... } + stop()  — Thread-based
// ──────────────────────────────────────────────────────────────────────────

class TelemetryReceiver {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    /**
     * Suspend-функция: слушает UDP в текущей корутине.
     * Отменяется через cancel() корутины.
     */
    suspend fun receive(port: Int, onData: (TelemetryData) -> Unit) {
        withContext(Dispatchers.IO) {
            running = true
            val sock = DatagramSocket(port)
            socket = sock
            sock.soTimeout = 1000
            val buf = ByteArray(512)

            try {
                while (running && isActive) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val json = JSONObject(String(buf, 0, pkt.length))
                        onData(parse(json))
                    } catch (_: java.net.SocketTimeoutException) {
                        // Нормально — проверяем running и ждём следующий пакет
                    } catch (_: Exception) {}
                }
            } finally {
                sock.close()
            }
        }
    }

    private fun parse(json: JSONObject): TelemetryData = TelemetryData(
        bat   = json.optInt("bat", -1),
        yaw   = json.optDouble("yaw", 0.0).toFloat(),
        spd   = json.optDouble("spd", 0.0).toFloat(),
        pitch = json.optDouble("pit", 0.0).toFloat(),
        roll  = json.optDouble("rol", 0.0).toFloat(),
        rssi  = json.optInt("rssi", 0),
        rpmL  = if (json.has("rpmL")) json.optDouble("rpmL").toFloat() else Float.NaN,
        rpmR  = if (json.has("rpmR")) json.optDouble("rpmR").toFloat() else Float.NaN
    )

    fun stop() {
        running = false
        socket?.close()
    }
}
