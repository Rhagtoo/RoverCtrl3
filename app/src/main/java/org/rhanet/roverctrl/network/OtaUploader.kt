package org.rhanet.roverctrl.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP OTA загрузчик прошивки на ESP32.
 *
 * Поддерживает два режима:
 *   - multipart/form-data (rover: Arduino WebServer на порту 80)
 *   - raw binary POST     (turret v2.7: esp_httpd на порту 81)
 */
object OtaUploader {

    private const val BOUNDARY = "RoverOTABoundary"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 120_000  // прошивка может занять до 2 минут
    private const val CHUNK_SIZE         = 4096

    /**
     * Загружает .bin файл по URI на устройство.
     *
     * @param deviceIp   IP-адрес ESP32 (напр. "192.168.4.1")
     * @param port       HTTP-порт OTA-эндпоинта (80 для ровера, 81 для турели)
     * @param rawBinary  true = POST application/octet-stream (turret v2.7+)
     *                   false = POST multipart/form-data (rover, turret v2.6)
     * @param binUri     URI выбранного .bin файла (SAF)
     * @param context    Context для ContentResolver
     * @param onProgress коллбэк прогресса 0..100
     * @return Result.success("OK") или Result.failure(Exception)
     */
    suspend fun upload(
        deviceIp: String,
        port: Int = 80,
        rawBinary: Boolean = false,
        binUri: Uri,
        context: Context,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(binUri)
                ?: return@withContext Result.failure(Exception("Cannot open file"))
            val fileBytes = inputStream.use { it.readBytes() }
            val totalSize = fileBytes.size

            if (totalSize == 0) {
                return@withContext Result.failure(Exception("File is empty"))
            }

            if (rawBinary) {
                uploadRaw(deviceIp, port, fileBytes, onProgress)
            } else {
                uploadMultipart(deviceIp, port, fileBytes, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Raw binary POST (turret v2.7+ on esp_httpd) ──────────────────────

    private fun uploadRaw(
        ip: String, port: Int, fileBytes: ByteArray, onProgress: (Int) -> Unit
    ): Result<String> {
        val url = URL("http://$ip:$port/update")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
            requestMethod  = "POST"
            doOutput       = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(fileBytes.size)
        }
        conn.connect()

        val out = conn.outputStream.buffered()
        var written = 0
        while (written < fileBytes.size) {
            val len = minOf(CHUNK_SIZE, fileBytes.size - written)
            out.write(fileBytes, written, len)
            written += len
            onProgress(written * 100 / fileBytes.size)
        }
        out.flush()
        out.close()

        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText().trim()
        conn.disconnect()

        return if (code == 200) {
            Result.success("OK")
        } else {
            Result.failure(Exception("Server: $code $body"))
        }
    }

    // ── Multipart form-data POST (rover on Arduino WebServer) ────────────

    private fun uploadMultipart(
        ip: String, port: Int, fileBytes: ByteArray, onProgress: (Int) -> Unit
    ): Result<String> {
        val url = URL("http://$ip:$port/update")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
            requestMethod  = "POST"
            doOutput       = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        }

        val prefix = ("--$BOUNDARY\r\nContent-Disposition: form-data; " +
                "name=\"update\"; filename=\"firmware.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = prefix.size + fileBytes.size + suffix.size

        conn.setFixedLengthStreamingMode(contentLength)
        conn.connect()

        val out = DataOutputStream(conn.outputStream.buffered())
        out.write(prefix)

        var written = 0
        while (written < fileBytes.size) {
            val len = minOf(CHUNK_SIZE, fileBytes.size - written)
            out.write(fileBytes, written, len)
            written += len
            onProgress(written * 100 / fileBytes.size)
        }

        out.write(suffix)
        out.flush()
        out.close()

        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText().trim()
        conn.disconnect()

        return if (code == 200 && body == "OK") {
            Result.success("OK")
        } else {
            Result.failure(Exception("Server: $code $body"))
        }
    }
}
