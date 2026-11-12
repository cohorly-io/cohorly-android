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
}

/**
 * Non-2xx HTTP response. [retryAfterMs] is the parsed `Retry-After` header
 * (delta-seconds form, per the server contract) when present, so the client
 * can honor the server's backoff hint on 429 / quota responses.
 */
class CohorlyHttpException(val status: Int, val retryAfterMs: Long? = null) :
    IOException("Cohorly: request failed with status $status")

/** Default transport using HttpURLConnection (works on Android and plain JVM). */
class HttpUrlConnectionTransport(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 10_000) :
    CohorlyTransport {
    override fun post(url: String, body: String) {
        post(url, body, emptyMap())
    }

    override fun post(url: String, body: String, headers: Map<String, String>) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw CohorlyHttpException(status, retryAfterMs)
            }
            // Drain the body so the connection can be reused.
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Parses a `Retry-After` header (delta-seconds form) into ms. */
        fun parseRetryAfterMs(header: String?): Long? {
            val seconds = header?.trim()?.toDoubleOrNull() ?: return null
            return (seconds * 1000).toLong().coerceAtLeast(0)
        }
    }
}
