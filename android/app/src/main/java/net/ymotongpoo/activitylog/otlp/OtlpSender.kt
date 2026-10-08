package net.ymotongpoo.activitylog.otlp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Outcome of a single OTLP export request. */
sealed interface SendResult {
    data object Success : SendResult

    /** Network error, 429 or 5xx: keep the payload and try again later. */
    data class Retryable(val reason: String) : SendResult

    /** Any other 4xx: the payload will never be accepted and must be dropped. */
    data class Rejected(val status: Int, val body: String) : SendResult
}

/** Posts gzip-compressed OTLP/HTTP JSON payloads with HTTP Basic authentication. */
class OtlpSender(
    endpoint: String,
    instanceId: String,
    token: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val base = endpoint.trim().trimEnd('/')
    private val authorization = basicAuth(instanceId.trim(), token.trim())

    fun send(signal: Signal, gzBody: ByteArray): SendResult {
        val conn = try {
            URL("$base/${signal.path}").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return SendResult.Retryable("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return SendResult.Rejected(0, "invalid endpoint: ${e.message}")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setFixedLengthStreamingMode(gzBody.size)
            conn.setRequestProperty("Authorization", authorization)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(gzBody) }
            val status = conn.responseCode
            val body = readBody(conn, status)
            classify(status, body)
        } catch (e: IOException) {
            SendResult.Retryable("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, status: Int): String {
        val stream = try {
            if (status >= 400) conn.errorStream else conn.inputStream
        } catch (_: IOException) {
            null
        } ?: return ""
        return stream.use { s ->
            val bytes = s.readBytes()
            bytes.copyOf(minOf(bytes.size, MAX_BODY_BYTES)).toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 1024
        const val USER_AGENT = "activitylog-android"

        fun basicAuth(instanceId: String, token: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$instanceId:$token".toByteArray(Charsets.UTF_8))

        fun classify(status: Int, body: String): SendResult = when {
            status in 200..299 -> SendResult.Success
            status == 408 || status == 429 || status >= 500 -> SendResult.Retryable("HTTP $status ${body.take(200)}")
            else -> SendResult.Rejected(status, body)
        }
    }
}
