package com.cohorly.android.core.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingReportsTest {
    private val endpoint = CrashEndpoint(TEST_TOKEN, TEST_HOST)

    private fun report(time: Long, id: String = "id-$time"): JSONObject = JSONObject()
        .put("event", "\$exception")
        .put("properties", JSONObject().put("time", time).put("\$insert_id", id))

    private fun storeWith(vararg times: Long): Pair<File, PendingReportStore> {
        val dir = tempDir()
        val store = PendingReportStore(dir)
        times.forEach { store.write("id-$it", report(it)) }
        return dir to store
    }

    private fun outcomeFor(transport: RecordingCrashTransport): Boolean {
        val (dir, store) = storeWith(1L)
        PendingReportSender(store, transport, TEST_APP).sendAll(endpoint)
        return pendingFiles(dir).isEmpty()
    }

    @Test
    fun `2xx, 400 and 413 delete the report`() {
        for (status in listOf(200, 202, 400, 413)) {
            assertTrue("status $status", outcomeFor(RecordingCrashTransport.status(status)))
        }
    }

    @Test
    fun `401, 429, 5xx and network errors keep the report`() {
        for (status in listOf(401, 429, 500, 503)) {
            assertEquals("status $status", false, outcomeFor(RecordingCrashTransport.status(status)))
        }
        assertEquals(false, outcomeFor(RecordingCrashTransport.offline()))
    }

    @Test
    fun `one event per request, oldest first, with the token header`() {
        val (_, store) = storeWith(30L, 10L, 20L)
        val transport = RecordingCrashTransport.status(200)

        val sent = PendingReportSender(store, transport, TEST_APP).sendAll(endpoint)

        assertEquals(3, sent)
        assertEquals(listOf(10L, 20L, 30L), transport.events().map { it.props().getLong("time") })
        assertTrue(transport.posts.all { it.url == "$TEST_HOST/track" && it.headers["X-Cohorly-Token"] == TEST_TOKEN })
        assertTrue(transport.events().all { it.getString("event") == "\$exception" })
    }

    @Test
    fun `the app version sent is the sending launch's, not the one stored in the report`() {
        val dir = tempDir()
        val store = PendingReportStore(dir)
        store.write(
            "old",
            report(1L, "old").apply {
                getJSONObject("properties").put("\$app_version_string", "0.0.9").put("\$app_build_number", "3")
            },
        )
        val transport = RecordingCrashTransport.status(200)

        PendingReportSender(store, transport, TEST_APP.copy(versionName = "2.0.0", versionCode = 40)).sendAll(endpoint)

        val props = transport.events().single().props()
        assertEquals("2.0.0", props.getString("\$app_version_string"))
        assertEquals("40", props.getString("\$app_build_number"))
    }

    @Test
    fun `a temp file left by a dead process is removed, a fresh one is left alone`() {
        val dir = tempDir()
        val store = PendingReportStore(dir)
        val now = 10_000_000L
        val stale = File(dir, "dead.json.tmp").apply {
            writeText("{")
            setLastModified(now - PendingReportStore.STALE_TMP_MS - 1)
        }
        val fresh = File(dir, "live.json.tmp").apply {
            writeText("{")
            setLastModified(now - 1_000)
        }

        assertTrue(store.list(now).isEmpty())

        assertEquals(false, stale.exists())
        assertEquals(true, fresh.exists())
    }

    @Test
    fun `a kept report stops the run so the rest waits for the next launch`() {
        val (dir, store) = storeWith(1L, 2L, 3L)
        val transport = RecordingCrashTransport.status(503)

        PendingReportSender(store, transport, TEST_APP).sendAll(endpoint)

        assertEquals(1, transport.posts.size)
        assertEquals(3, pendingFiles(dir).size)
    }

    @Test
    fun `at most 50 reports stay on disk, the oldest are dropped`() {
        val dir = tempDir()
        val store = PendingReportStore(dir)
        for (t in 1L..55L) store.write("id-$t", report(t))

        val times = store.list().map { it.time }

        assertEquals(PendingReportStore.DEFAULT_MAX_REPORTS, times.size)
        assertEquals((6L..55L).toList(), times)
    }

    @Test
    fun `an unreadable report is deleted`() {
        val (dir, store) = storeWith(1L)
        File(dir, "broken.json").writeText("{not json")
        val transport = RecordingCrashTransport.status(200)

        PendingReportSender(store, transport, TEST_APP).sendAll(endpoint)

        assertEquals(1, transport.posts.size)
        assertTrue(pendingFiles(dir).isEmpty())
    }

    @Test
    fun `a report is written whole, never as a temp file`() {
        val (dir, _) = storeWith(1L)

        assertEquals(listOf("id-1.json"), dir.list()!!.toList())
    }

    @Test
    fun `manifest meta-data wins over the persisted token and host`() {
        val config = enabledConfig()

        assertEquals(CrashEndpoint(TEST_TOKEN, TEST_HOST), CrashEndpoint.resolve(null, null, config))
        assertEquals(
            CrashEndpoint("meta-token", "https://meta.example.com"),
            CrashEndpoint.resolve("meta-token", "https://meta.example.com/", config),
        )
        assertEquals(CrashEndpoint("meta-token", TEST_HOST), CrashEndpoint.resolve("meta-token", null, config))
    }

    @Test
    fun `without a token or a host nothing is sent and reports stay pending`() {
        assertEquals(null, CrashEndpoint.resolve(null, null, enabledConfig().copy(token = null)))
        assertEquals(null, CrashEndpoint.resolve("t", null, enabledConfig().copy(apiHost = null)))

        val store = MemoryCrashStore().apply { putConfig(enabledConfig().copy(token = null, apiHost = null)) }
        val dir = tempDir()
        PendingReportStore(dir).write("old", report(1L))
        val transport = RecordingCrashTransport.status(200)

        newReporter(store, dir, transport).start(null)

        assertTrue(transport.posts.isEmpty())
        assertEquals(1, pendingFiles(dir).size)
    }

    @Test
    fun `meta-data alone is enough to send`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig().copy(token = null, apiHost = null)) }
        val dir = tempDir()
        PendingReportStore(dir).write("old", report(1L))
        val transport = RecordingCrashTransport.status(200)

        newReporter(store, dir, transport, metaToken = "meta", metaApiHost = "https://meta.example.com").start(null)

        assertEquals("https://meta.example.com/track", transport.posts.single().url)
        assertEquals("meta", transport.posts.single().headers["X-Cohorly-Token"])
    }
}
