package org.rhanet.roverctrl.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.net.URL

// ──────────────────────────────────────────────────────────────────────────
// MjpegDecoder
//
// Тот же алгоритм что и в Python: ищем маркеры SOI (ff d8) / EOI (ff d9)
// в потоке байт, вырезаем JPEG-кадры, декодируем в Bitmap.
//
// onFrame() — вызывается в фоновом потоке. Для UI: Handler(mainLooper).post{}.
// ──────────────────────────────────────────────────────────────────────────

class MjpegDecoder(
    private val url:     String,
    private val onFrame: (Bitmap) -> Unit,
    private val onFps:   (Float)  -> Unit = {}
) : Thread("mjpeg-rx") {

    @Volatile var running = false
    private var frameTs   = ArrayDeque<Long>(32)

    override fun run() {
        running = true
        while (running) {
            try {
                stream(URL(url).openStream())
            } catch (e: Exception) {
                if (running) sleep(2000)
            }
        }
    }

    private fun stream(input: InputStream) {
        val SOI = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val EOI = byteArrayOf(0xff.toByte(), 0xd9.toByte())

        val buf = ByteArray(65536)
        var data = byteArrayOf()

        while (running) {
            val n = input.read(buf)
            if (n < 0) break
            data += buf.copyOf(n)

            while (true) {
                val s = data.indexOf(SOI)
                if (s == -1) { data = byteArrayOf(); break }
                val e = data.indexOf(EOI, s + 2)
                if (e == -1) { data = data.copyOfRange(s, data.size); break }

                val jpeg = data.copyOfRange(s, e + 2)
                data = data.copyOfRange(e + 2, data.size)

                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { bmp ->
                    val now = System.currentTimeMillis()
                    frameTs.addLast(now)
                    while (frameTs.size > 30) frameTs.removeFirst()
                    if (frameTs.size >= 2) {
                        val elapsed = (frameTs.last() - frameTs.first()) / 1000f
                        onFps((frameTs.size - 1) / elapsed.coerceAtLeast(0.001f))
                    }
                    onFrame(bmp)
                }
            }
        }
    }

    fun halt() { running = false; interrupt() }
}

// ── Extension ─────────────────────────────────────────────────────────────
private fun ByteArray.indexOf(pattern: ByteArray, fromIndex: Int = 0): Int {
    outer@ for (i in fromIndex..size - pattern.size) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}
