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
 * Отправляет .bin файл как multipart/form-data POST на http://<ip>/update
 */
object OtaUploader {

    private const val BOUNDARY = "RoverOTABoundary"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 120_000  // прошивка может занять до 2 минут
    private const val CHUNK_SIZE         = 4096

    /**
     * Загружает .bin файл по URI на устройство по адресу [deviceIp].
     *
     * @param deviceIp  IP-адрес ESP32 (напр. "192.168.4.1")
     * @param binUri    URI выбранного .bin файла (SAF)
     * @param context   Context для ContentResolver
     * @param onProgress коллбэк прогресса 0..100
     * @return Result.success("OK") или Result.failure(Exception)
     */
    suspend fun upload(
        deviceIp: String,
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

            val url = URL("http://$deviceIp/update")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout    = READ_TIMEOUT_MS
            connection.requestMethod  = "POST"
            connection.doOutput       = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")

            // Составляем multipart тело
            val prefix = "--$BOUNDARY\r\nContent-Disposition: form-data; " +
                         "name=\"update\"; filename=\"firmware.bin\"\r\n" +
                         "Content-Type: application/octet-stream\r\n\r\n"
            val suffix = "\r\n--$BOUNDARY--\r\n"
            val prefixBytes  = prefix.toByteArray(Charsets.UTF_8)
            val suffixBytes  = suffix.toByteArray(Charsets.UTF_8)
            val contentLength = prefixBytes.size + totalSize + suffixBytes.size

            connection.setFixedLengthStreamingMode(contentLength)
            connection.connect()

            val out = DataOutputStream(connection.outputStream.buffered())
            out.write(prefixBytes)

            var written = 0
            var offset  = 0
            while (offset < totalSize) {
                val len = minOf(CHUNK_SIZE, totalSize - offset)
                out.write(fileBytes, offset, len)
                offset  += len
                written += len
                onProgress(written * 100 / totalSize)
            }

            out.write(suffixBytes)
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            if (responseCode == 200 && body.trim() == "OK") {
                Result.success("OK")
            } else {
                Result.failure(Exception("Server: $responseCode $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
