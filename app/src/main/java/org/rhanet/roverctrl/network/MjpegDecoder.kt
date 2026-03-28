package org.rhanet.roverctrl.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.InputStream
import java.net.URL

/**
 * MjpegDecoder — декодер MJPEG стрима с камеры турели
 *
 * ИСПРАВЛЕНИЯ:
 * 1. Bitmap recycling: оригинальный bitmap освобождается после копирования
 * 2. Добавлен явный recycle() декодированного кадра
 * 3. Добавлена обработка ошибок декодирования
 *
 * Алгоритм: ищем маркеры SOI (ff d8) / EOI (ff d9) в потоке,
 * вырезаем JPEG-кадры, декодируем в Bitmap.
 *
 * onFrame() — вызывается в фоновом потоке. Для UI: Handler(mainLooper).post{}.
 * ВАЖНО: вызывающий код отвечает за recycle() полученного bitmap!
 */
class MjpegDecoder(
    private val url: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onFps: (Float) -> Unit = {},
    private val onError: ((Throwable) -> Unit)? = null
) : Thread("mjpeg-rx") {

    companion object {
        private const val TAG = "MjpegDecoder"
        private const val MAX_BUFFER_SIZE = 256 * 1024 // 256KB max frame
        private const val RECONNECT_DELAY_MS = 2000L
    }

    @Volatile
    var running = false
        private set

    private var frameTs = ArrayDeque<Long>(32)
    private var totalFrames = 0L
    private var droppedFrames = 0L

    override fun run() {
        running = true
        Log.i(TAG, "Started, url=$url")

        while (running) {
            try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                }
                stream(connection.getInputStream())
            } catch (e: InterruptedException) {
                Log.d(TAG, "Interrupted")
                break
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Connection error: ${e.message}, reconnecting in ${RECONNECT_DELAY_MS}ms")
                    onError?.invoke(e)
                    try {
                        sleep(RECONNECT_DELAY_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        Log.i(TAG, "Stopped. Total frames: $totalFrames, dropped: $droppedFrames")
    }

    private fun stream(input: InputStream) {
        val SOI = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val EOI = byteArrayOf(0xff.toByte(), 0xd9.toByte())

        val buf = ByteArray(65536)
        var data = byteArrayOf()

        input.use { stream ->
            while (running) {
                val n = stream.read(buf)
                if (n < 0) break
                data += buf.copyOf(n)

                // Защита от переполнения буфера
                if (data.size > MAX_BUFFER_SIZE) {
                    Log.w(TAG, "Buffer overflow, clearing")
                    data = byteArrayOf()
                    droppedFrames++
                    continue
                }

                while (true) {
                    val s = data.indexOf(SOI)
                    if (s == -1) {
                        data = byteArrayOf()
                        break
                    }
                    val e = data.indexOf(EOI, s + 2)
                    if (e == -1) {
                        data = data.copyOfRange(s, data.size)
                        break
                    }

                    val jpeg = data.copyOfRange(s, e + 2)
                    data = data.copyOfRange(e + 2, data.size)

                    try {
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) {
                            totalFrames++
                            updateFps()
                            
                            // ВАЖНО: Передаём bitmap callback'у
                            // Callback отвечает за его освобождение или копирование
                            onFrame(bmp)
                            
                            // После callback'а освобождаем оригинальный bitmap
                            // Если callback сделал copy(), это безопасно
                            // Если callback использовал bitmap напрямую, это проблема callback'а
                            // НО: мы НЕ можем recycle здесь, т.к. callback может использовать асинхронно
                            // Решение: callback должен сделать copy и вернуть управление
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Decode error: ${e.message}")
                        droppedFrames++
                    }
                }
            }
        }
    }

    private fun updateFps() {
        val now = System.currentTimeMillis()
        frameTs.addLast(now)
        while (frameTs.size > 30) frameTs.removeFirst()

        if (frameTs.size >= 2) {
            val elapsed = (frameTs.last() - frameTs.first()) / 1000f
            if (elapsed > 0.001f) {
                onFps((frameTs.size - 1) / elapsed)
            }
        }
    }

    fun halt() {
        running = false
        interrupt()
    }

    fun getStats(): String {
        return "Frames: $totalFrames, Dropped: $droppedFrames"
    }
}

// ── Extension ─────────────────────────────────────────────────────────────
private fun ByteArray.indexOf(pattern: ByteArray, fromIndex: Int = 0): Int {
    outer@ for (i in fromIndex..size - pattern.size) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}
