package com.cohorly.android.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network transport abstraction. Implementations POST a JSON body and throw
 * [CohorlyHttpException] on a non-2xx response, or any other exception
 * (e.g. [IOException]) on network failure.
 */
interface CohorlyTransport {
    fun post(url: String, body: String)

    /**
     * Same as [post], with extra request headers. The crash reporter sends its
     * reports with `X-Cohorly-Token`, since a pending report is a bare event.
     * The default drops [headers], so a custom transport that needs them must
     * override this.
     */
    fun post(url: String, body: String, headers: Map<String, String>) {
        post(url, body)
    }

    /**
     * POSTs raw bytes with [headers] (session replay sends gzipped chunks this
     * way, with `Content-Encoding: gzip`). Same failure contract as [post].
     * The default throws [UnsupportedOperationException]: a custom transport
     * that does not implement it turns session replay off for the launch.
     */
    fun postBytes(url: String, body: ByteArray, headers: Map<String, String>): Unit =
        throw UnsupportedOperationException("postBytes is not implemented by this transport")
}

/**
 * Non-2xx HTTP response. [retryAfterMs] is the parsed `Retry-After` header
 * (delta-seconds form, per the server contract) when present, so the client
 * can honor the server's backoff hint on 429 / quota responses. [body] is the
 * start of the response body, read by [CohorlyTransport.postBytes] only (it
 * tells a replay quota refusal from a rate limit, both 429); null otherwise.
 */
class CohorlyHttpException(val status: Int, val retryAfterMs: Long? = null, val body: String? = null) :
    IOException("Cohorly: request failed with status $status")

/** Default transport using HttpURLConnection (works on Android and plain JVM). */
class HttpUrlConnectionTransport(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 10_000) :
    CohorlyTransport {
    override fun post(url: String, body: String) {
        post(url, body, emptyMap())
    }

    override fun post(url: String, body: String, headers: Map<String, String>) {
        send(url, body.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json") + headers, false)
    }

    /** The only call that reads the error body: it tells a replay quota refusal from a rate limit. */
    override fun postBytes(url: String, body: ByteArray, headers: Map<String, String>) {
        send(url, body, headers, true)
    }

    private fun send(url: String, body: ByteArray, headers: Map<String, String>, readErrorBody: Boolean) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                val errorBody = if (readErrorBody) readErrorBody(connection) else null
                throw CohorlyHttpException(status, retryAfterMs, errorBody)
            }
            // Drain the body so the connection can be reused.
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** At most [MAX_ERROR_BODY_BYTES] of the error body, or null when unreadable. */
    private fun readErrorBody(connection: HttpURLConnection): String? = try {
        connection.errorStream?.use { stream ->
            val buffer = ByteArray(MAX_ERROR_BODY_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_ERROR_BODY_BYTES = 4_096

        /** Parses a `Retry-After` header (delta-seconds form) into ms. */
        fun parseRetryAfterMs(header: String?): Long? {
            val seconds = header?.trim()?.toDoubleOrNull() ?: return null
            return (seconds * 1000).toLong().coerceAtLeast(0)
        }
    }
}
