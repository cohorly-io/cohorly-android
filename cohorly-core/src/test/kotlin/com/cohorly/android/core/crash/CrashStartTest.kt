package com.cohorly.android.core.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashStartTest {
    private fun assertStartsNothing(storedConfig: String?) {
        val store = MemoryCrashStore()
        storedConfig?.let { store.putString(CrashConfig.KEY_CONFIG, it) }
        val dir = tempDir()
        PendingReportStore(dir).write("old", JSONObject().put("event", "\$exception"))
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        val background = CrashTestSupport.Background()
        val transport = RecordingCrashTransport.status(200)
        var exitInfoRead = false

        val started = newReporter(store, dir, transport, slot = slot, background = background).start {
            exitInfoRead = true
            emptyList()
        }

        assertFalse(started)
        assertEquals(0, slot.sets)
        assertSame(previous, slot.current)
        assertTrue(background.jobs.isEmpty())
        assertFalse(exitInfoRead)
        assertTrue(transport.posts.isEmpty())
        assertEquals(1, pendingFiles(dir).size)
        assertNull(store.getLong(CrashConfig.KEY_EXIT_WATERMARK))
    }

    @Test
    fun `an absent config installs nothing`() = assertStartsNothing(null)

    @Test
    fun `an invalid config installs nothing`() = assertStartsNothing("{not json")

    @Test
    fun `a disabled config installs nothing`() = assertStartsNothing("""{"enabled":false,"deviceId":"d"}""")

    @Test
    fun `only a JSON true enables`() {
        assertStartsNothing("""{"enabled":"true"}""")
        assertStartsNothing("""{"enabled":1}""")
        assertStartsNothing("""{"deviceId":"d"}""")
    }

    @Test
    fun `an enabled config installs the handler at once and does the rest in the background`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        val background = CrashTestSupport.Background()
        var exitInfoRead = false

        val reporter =
            newReporter(store, tempDir(), RecordingCrashTransport.status(200), slot = slot, background = background)
        val started = reporter.start {
            exitInfoRead = true
            emptyList()
        }

        assertTrue(started)
        assertEquals(1, slot.sets)
        assertTrue(slot.current is CrashReporter.Handler)
        assertFalse(exitInfoRead)
        background.runAll()
        assertTrue(exitInfoRead)
    }

    @Test
    fun `the persisted config parses the React Native contract`() {
        val config = CrashConfig.parse(
            """{"enabled":true,"release":"r","dist":"d","distinctId":"u","deviceId":"dev","extra":1}""",
        )

        assertEquals(CrashConfig(enabled = true, release = "r", dist = "d", distinctId = "u", deviceId = "dev"), config)
        assertNull(CrashConfig.parse(null))
        assertNull(CrashConfig.parse("[]"))
    }

    @Test
    fun `writing the JS contract keeps the native-only token and host`() {
        val store = MemoryCrashStore().apply {
            putConfig(enabledConfig().copy(anrWatchdog = true))
        }

        val written = CrashReporter.writeConfig(store, """{"enabled":true,"distinctId":"new-user","deviceId":"dev"}""")

        assertEquals("new-user", written!!.distinctId)
        assertEquals(TEST_TOKEN, written.token)
        assertEquals(TEST_HOST, written.apiHost)
        assertTrue(written.anrWatchdog)
        // Incoming wins for every contract field, absent ones included.
        assertNull(written.release)
        assertEquals(written, CrashConfig.read(store))
    }

    @Test
    fun `writing invalid JSON turns reporting off`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }

        val written = CrashReporter.writeConfig(store, "{oops")

        assertNull(written)
        assertNull(CrashConfig.readEnabled(store))
    }

    @Test
    fun `the default release is applicationId at versionName plus versionCode`() {
        assertEquals("com.cohorly.spike.crash@0.1.0+7", TEST_APP.defaultRelease)
        assertNull(TEST_APP.copy(versionName = null).defaultRelease)
    }

    @Test
    fun `the summary carries the live config, with the default release when none is set`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig(release = null)) }
        val reporter = newReporter(store, tempDir(), RecordingCrashTransport.offline())
        reporter.start(null)

        val decoded = ProcessStateSummary.decode(reporter.processStateSummary())!!

        assertEquals("com.cohorly.spike.crash@0.1.0+7", decoded.release)
        assertEquals("spike-user-42", decoded.distinctId)
        assertEquals(TEST_DEVICE_ID, decoded.deviceId)

        reporter.updateConfig(CrashConfig.parse(enabledConfig(distinctId = "after-identify").toJson()))
        assertEquals("after-identify", ProcessStateSummary.decode(reporter.processStateSummary())!!.distinctId)
    }

    @Test
    fun `a config without a device id gets one stable fallback per install`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig(distinctId = null, deviceId = null)) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot).start(null)
        val fallback = store.getString(CrashConfig.KEY_FALLBACK_DEVICE_ID)!!

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("x"))

        val props = PendingReportStore(dir).list().single().event.props()
        assertEquals(fallback, props.getString("distinct_id"))
        assertEquals(fallback, props.getString("\$device_id"))
        assertEquals(fallback, CrashReporter.fallbackDeviceId(store))
    }

    @Test
    fun `an opt-out while the startup job waits stops every send and the exit info read`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        PendingReportStore(dir).write("old", JSONObject().put("event", "\$exception"))
        val background = CrashTestSupport.Background()
        val transport = RecordingCrashTransport.status(200)
        var exitInfoRead = false
        val reporter = newReporter(store, dir, transport, background = background)
        assertTrue(
            reporter.start {
                exitInfoRead = true
                emptyList()
            },
        )

        reporter.updateConfig(CrashReporter.writeConfig(store, """{"enabled":false}"""))
        background.runAll()

        assertTrue(transport.posts.isEmpty())
        assertFalse(exitInfoRead)
        assertEquals(1, pendingFiles(dir).size)
    }

    @Test
    fun `a release written by an older build is not used by the build running now`() {
        val store = MemoryCrashStore()
        CrashReporter.writeConfig(store, enabledConfig(release = "com.cohorly.spike.crash@0.0.9+6").toJson(), build = 6)
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val props = PendingReportStore(dir).list().single().event.getJSONObject("properties")
        assertEquals(TEST_APP.defaultRelease, props.getString("\$exception_release"))
        assertFalse(props.has("\$exception_dist"))
    }

    @Test
    fun `a release written by the build running now is kept`() {
        val store = MemoryCrashStore()
        CrashReporter.writeConfig(store, enabledConfig(release = "custom@1").toJson(), build = TEST_APP.versionCode)
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val props = PendingReportStore(dir).list().single().event.getJSONObject("properties")
        assertEquals("custom@1", props.getString("\$exception_release"))
    }

    @Test
    fun `an opt-out between two pending sends stops the rest`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val pending = PendingReportStore(dir)
        pending.write("first", JSONObject().put("event", "\$exception").put("properties", JSONObject().put("time", 1L)))
        pending.write(
            "second",
            JSONObject().put("event", "\$exception").put("properties", JSONObject().put("time", 2L)),
        )
        lateinit var reporter: CrashReporter
        val transport = RecordingCrashTransport {
            reporter.updateConfig(CrashReporter.writeConfig(store, """{"enabled":false}"""))
        }
        reporter = newReporter(store, dir, transport, background = CrashTestSupport.Background())
        reporter.start(null)

        reporter.sendPending()

        assertEquals(1, transport.posts.size)
        assertEquals(1, pendingFiles(dir).size)
    }
}
