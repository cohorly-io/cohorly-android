package com.cohorly.android.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class TransportTest {
    private data class Received(val method: String, val headers: Map<String, String?>, val body: ByteArray)

    /** What the server answers; fixed before it starts and never changed while it runs. */
    private data class Response(val status: Int, val body: String, val retryAfter: String? = null)

    private val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null

    /** Starts a server that answers every request with [response]; returns the URL of [path]. */
    private fun serve(response: Response, path: String = "/replay"): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(path) { exchange ->
                received.add(
                    Received(
                        exchange.requestMethod,
                        listOf("X-Cohorly-Token", "Content-Encoding", "Content-Type")
                            .associateWith { exchange.requestHeaders.getFirst(it) },
                        exchange.requestBody.readBytes(),
                    ),
                )
                response.retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
                val bytes = response.body.toByteArray()
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}$path"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `postBytes sends the bytes as they are with the given headers`() {
        val url = serve(Response(200, """{"status":1}"""))
        val body = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 42)
        val headers =
            mapOf("X-Cohorly-Token" to "tok", "Content-Encoding" to "gzip", "Content-Type" to "application/json")

        HttpUrlConnectionTransport().postBytes(url, body, headers)

        val request = received.single()
        assertEquals("POST", request.method)
        assertArrayEquals(body, request.body)
        assertEquals(headers, request.headers)
    }

    @Test
    fun `a non-2xx answer to postBytes throws with the status, Retry-After and the error body`() {
        val url = serve(Response(429, """{"status":0,"error":"replay quota exceeded"}""", retryAfter = "30"))

        try {
            HttpUrlConnectionTransport().postBytes(url, byteArrayOf(1), emptyMap())
            fail("expected a CohorlyHttpException")
        } catch (e: CohorlyHttpException) {
            assertEquals(429, e.status)
            assertEquals(30_000L, e.retryAfterMs)
            assertTrue(e.body!!.contains("replay quota exceeded"))
        }
    }

    @Test
    fun `post for track never reads the error body`() {
        val url = serve(Response(429, """{"status":0,"error":"rate limited"}""", retryAfter = "5"), path = "/track")

        try {
            HttpUrlConnectionTransport().post(url, "[]", mapOf("X-Cohorly-Token" to "tok"))
            fail("expected a CohorlyHttpException")
        } catch (e: CohorlyHttpException) {
            assertEquals(429, e.status)
            assertEquals(5_000L, e.retryAfterMs)
            assertNull(e.body)
        }
        assertEquals("application/json", received.single().headers["Content-Type"])
    }

    @Test
    fun `a transport that does not implement postBytes throws UnsupportedOperationException`() {
        val plain = object : CohorlyTransport {
            override fun post(url: String, body: String) {}
        }
        try {
            plain.postBytes("http://127.0.0.1:1/replay", byteArrayOf(1), emptyMap())
            fail("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
        }
        assertTrue(received.isEmpty())
    }
}
