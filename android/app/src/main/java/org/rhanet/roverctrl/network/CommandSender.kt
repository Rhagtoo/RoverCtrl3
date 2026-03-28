package org.rhanet.roverctrl.network

import kotlinx.coroutines.*
import org.rhanet.roverctrl.data.ConnectionConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ──────────────────────────────────────────────────────────────────────────
// CommandSender
//
// Протокол:
//   Ровер  (192.168.88.24) → "SPD:{};STR:{};FWD:{};LASER:{}\n"
//     Прошивка: FWD: abs()→мощность, sign()→направление. SPD игнорируется.
//     STR: -100..100 → map(-100,100, 145,35) → серво руля
//
//   Xiao   (192.168.88.23) → "PAN:{};TILT:{}\n"
//     Прошивка ожидает: PAN: -90..+90,  TILT: -90..+90
//     Маппинг в прошивке:
//       PAN:  map(-90,90, 180,0)  → серво 180°..0° (ИНВЕРТИРОВАН!)
//       TILT: map(-90,90, 0,180)  → серво 0°..180°
//
// Android внутренне использует -100..+100 для всех осей.
// Масштабирование -100..100 → -90..90 происходит здесь, при отправке.
// ──────────────────────────────────────────────────────────────────────────

class CommandSender {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val socket = DatagramSocket()

    private var roverAddr:  InetAddress? = null
    private var roverPort:  Int = 4210
    private var xiaoAddr:   InetAddress? = null
    private var xiaoPort:   Int = 4210

    fun configure(cfg: ConnectionConfig) {
        roverAddr = InetAddress.getByName(cfg.roverIp)
        roverPort = cfg.cmdPort
        xiaoAddr  = InetAddress.getByName(cfg.xiaoIp)
        xiaoPort  = cfg.xiaoPort
    }

    fun clearHosts() {
        roverAddr = null
        xiaoAddr  = null
    }

    /** Единый метод отправки всех команд */
    fun send(spd: Int, str: Int, fwd: Int, laser: Boolean, pan: Int, tilt: Int) {
        sendRover(spd, str, fwd, laser)
        sendXiao(pan, tilt)
    }

    /** Отправка только команд ровера */
    fun sendRover(spd: Int, str: Int, fwd: Int, laser: Boolean) {
        val laserInt = if (laser) 1 else 0
        val msg = "SPD:$spd;STR:$str;FWD:$fwd;LASER:$laserInt\n"
        scope.launch {
            roverAddr?.let { sendRaw(msg.toByteArray(), it, roverPort) }
        }
    }

    /**
     * Отправка команд pan/tilt серво на Xiao.
     *
     * @param pan  -100..+100 (внутренний диапазон Android)
     * @param tilt -100..+100
     *
     * Прошивка XIAO принимает -90..+90 и делает:
     *   PAN:  constrain(-90,90) → map(-90,90, 180,0)   (инвертирован)
     *   TILT: constrain(-90,90) → map(-90,90, 0,180)
     */
    fun sendXiao(pan: Int, tilt: Int) {
        // Масштабируем -100..100 → -90..90
        val panScaled  = (pan  * 90 / 100).coerceIn(-90, 90)
        val tiltScaled = (tilt * 90 / 100).coerceIn(-90, 90)
        val msg = "PAN:$panScaled;TILT:$tiltScaled\n"
        scope.launch {
            xiaoAddr?.let { sendRaw(msg.toByteArray(), it, xiaoPort) }
        }
    }

    private fun sendRaw(data: ByteArray, addr: InetAddress, port: Int) {
        try {
            socket.send(DatagramPacket(data, data.size, addr, port))
        } catch (_: Exception) {}
    }

    fun close() {
        scope.cancel()
        socket.close()
    }
}
