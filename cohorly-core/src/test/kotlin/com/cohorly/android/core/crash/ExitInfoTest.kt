package com.cohorly.android.core.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitInfoTest {
    private val anrFixture = fixture("android-anr.json")
    private val nativeFixture = fixture("android-native.json")
    private val lowMemoryFixture = fixture("android-low-memory.json")

    private val crashTimeSummary = ProcessStateSummary.encode(
        ProcessStateSummary.Values(
            release = "android@0.1.0+7",
            distinctId = "spike-user-42",
            deviceId = TEST_DEVICE_ID,
            dist = "5f3c2a10-8e4b-4c7d-9a61-0b2e7d4f9c13",
        ),
    )

    private fun anr(timestamp: Long = 2_000L, summary: ByteArray? = crashTimeSummary) = ExitRecord(
        pid = 13814,
        timestamp = timestamp,
        reason = ExitRecord.REASON_ANR,
        importance = ExitRecord.IMPORTANCE_FOREGROUND,
        description = anrFixture.firstException().getString("value"),
        processName = TEST_PACKAGE,
        rssKb = 149468,
        pssKb = 0,
        processStateSummary = summary,
        readTrace = { resourceBytes("anr-trace.txt") },
    )

    private fun nativeCrash(timestamp: Long = 3_000L) = ExitRecord(
        pid = 15232,
        timestamp = timestamp,
        reason = ExitRecord.REASON_CRASH_NATIVE,
        importance = ExitRecord.IMPORTANCE_FOREGROUND,
        description = null,
        processName = TEST_PACKAGE,
        rssKb = 0,
        pssKb = 0,
        processStateSummary = crashTimeSummary,
        readTrace = { resourceBytes("tombstone-sigsegv.pb") },
    )

    private fun lowMemory(timestamp: Long = 4_000L, importance: Int = ExitRecord.IMPORTANCE_FOREGROUND) = ExitRecord(
        pid = 16000,
        timestamp = timestamp,
        reason = ExitRecord.REASON_LOW_MEMORY,
        importance = importance,
        description = null,
        processName = TEST_PACKAGE,
        rssKb = 1726472,
        pssKb = 0,
        processStateSummary = crashTimeSummary,
    )

    private fun javaCrash(timestamp: Long = 5_000L) = ExitRecord(
        pid = 17000,
        timestamp = timestamp,
        reason = ExitRecord.REASON_CRASH,
        importance = ExitRecord.IMPORTANCE_FOREGROUND,
        description = "crash",
        processName = TEST_PACKAGE,
        rssKb = 0,
        pssKb = 0,
        processStateSummary = crashTimeSummary,
    )

    /** A store whose first run already happened, at watermark [watermark]. */
    private fun storeAfterFirstRun(watermark: Long = 1_000L) = MemoryCrashStore().apply {
        putConfig(enabledConfig())
        putLong(CrashConfig.KEY_EXIT_WATERMARK, watermark)
    }

    private fun launch(store: MemoryCrashStore, dir: java.io.File, records: List<ExitRecord>): List<JSONObject> {
        val transport = RecordingCrashTransport.status(200)
        assertTrue(newReporter(store, dir, transport).start { records })
        return transport.events()
    }

    @Test
    fun `ANR, native crash and low memory become three events, sent once`() {
        val store = storeAfterFirstRun()
        val dir = tempDir()
        val records = listOf(anr(), nativeCrash(), lowMemory(), javaCrash())

        val first = launch(store, dir, records)

        assertEquals(3, first.size)
        assertEquals(listOf("ANR", "SIGSEGV", "LowMemoryKill"), first.map { it.firstException().getString("type") })
        assertEquals(
            listOf("anr", "exit_info", "exit_info"),
            first.map { it.firstException().getJSONObject("mechanism").getString("type") },
        )
        assertEquals(3, first.map { it.props().getString("\$insert_id") }.toSet().size)
        assertEquals(listOf(2_000L, 3_000L, 4_000L), first.map { it.props().getLong("time") })
        assertTrue(pendingFiles(dir).isEmpty())

        val second = launch(store, dir, records)

        assertTrue(second.isEmpty())
    }

    @Test
    fun `a record newer than the watermark makes exactly one event`() {
        val store = storeAfterFirstRun()
        val dir = tempDir()
        launch(store, dir, listOf(anr()))

        val next = launch(store, dir, listOf(anr(), lowMemory(timestamp = 9_000L)))

        assertEquals(1, next.size)
        assertEquals("LowMemoryKill", next.single().firstException().getString("type"))
    }

    @Test
    fun `the first run with reporting on only sets the watermark`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        var traceReads = 0
        val old = ExitRecord(
            pid = 1,
            timestamp = 7_000L,
            reason = ExitRecord.REASON_ANR,
            importance = ExitRecord.IMPORTANCE_FOREGROUND,
            description = null,
            processName = TEST_PACKAGE,
            rssKb = 0,
            pssKb = 0,
            processStateSummary = null,
            readTrace = {
                traceReads++
                null
            },
        )

        val sent = launch(store, dir, listOf(old, lowMemory(timestamp = 6_000L)))

        assertTrue(sent.isEmpty())
        assertEquals(7_000L, store.getLong(CrashConfig.KEY_EXIT_WATERMARK))
        assertEquals(0, traceReads)
    }

    @Test
    fun `a low memory kill in the background is not reported`() {
        val store = storeAfterFirstRun()

        val sent = launch(store, tempDir(), listOf(lowMemory(importance = 400)))

        assertTrue(sent.isEmpty())
        assertEquals(4_000L, store.getLong(CrashConfig.KEY_EXIT_WATERMARK))
    }

    @Test
    fun `excessive resource use is a warning at any importance`() {
        val record = ExitRecord(
            pid = 1,
            timestamp = 8_000L,
            reason = ExitRecord.REASON_EXCESSIVE_RESOURCE_USAGE,
            importance = 400,
            description = null,
            processName = TEST_PACKAGE,
            rssKb = 1,
            pssKb = 2,
            processStateSummary = crashTimeSummary,
        )

        val sent = launch(storeAfterFirstRun(), tempDir(), listOf(record)).single()

        assertEquals("ExcessiveResourceUsage", sent.firstException().getString("type"))
        assertEquals("exit_info", sent.firstException().getJSONObject("mechanism").getString("type"))
        assertEquals("warning", sent.props().getString("\$exception_level"))
    }

    @Test
    fun `the ANR event matches the fixture`() {
        val event = launch(storeAfterFirstRun(), tempDir(), listOf(anr())).single()
        val expected = anrFixture.props()
        val actual = event.props()

        assertEquals(expected.keySet() + "\$user_id", actual.keySet())
        assertEquals("fatal", actual.getString("\$exception_level"))
        val entry = event.firstException()
        assertEquals(anrFixture.firstException().getString("value"), entry.getString("value"))
        assertTrue(anrFixture.firstException().getJSONObject("mechanism").similar(entry.getJSONObject("mechanism")))
        assertTrue(anrFixture.firstException().frames().similar(entry.frames()))

        val threads = actual.getJSONArray("\$exception_threads").objects()
        assertFalse(threads.any { it.getString("name") == "main" })
        val expectedThreads = expected.getJSONArray("\$exception_threads").objects()
        assertEquals(expectedThreads.take(4).map { it.getString("name") }, threads.take(4).map { it.getString("name") })
        assertTrue(expectedThreads[0].similar(threads[0]))
        // Threads not attached to the runtime keep their own frames (sysTid as id).
        val tracingMuxer = threads.single { it.getString("name") == "TracingMuxer" }
        assertEquals(13837L, tracingMuxer.getLong("id"))
        assertEquals(7, tracingMuxer.frames().length())
        val binder = threads.single { it.getString("name") == "binder:13814_5" }
        assertTrue(binder.frames().length() < 25)
        // A native-only thread: native frames, offset dropped from the symbol.
        val signalCatcher = threads.single { it.getString("name") == "Signal Catcher" }.frames().objects()
        assertEquals("native", signalCatcher.first().getString("platform"))
        assertEquals("__start_thread", signalCatcher.first().getString("function"))
        assertEquals("__pthread_start", signalCatcher[1].getString("function"))
    }

    @Test
    fun `the native crash event matches the fixture`() {
        val event = launch(storeAfterFirstRun(), tempDir(), listOf(nativeCrash())).single()
        val expected = nativeFixture.firstException()
        val entry = event.firstException()

        assertEquals("SIGSEGV", entry.getString("type"))
        assertEquals("SEGV_MAPERR at 0x0", entry.getString("value"))
        assertTrue(expected.getJSONObject("mechanism").similar(entry.getJSONObject("mechanism")))
        // The fixture comes from another run of the same build: the deterministic
        // ends of the stack (libc at the bottom, the app's own frames on top) match.
        val expectedFrames = expected.frames().objects()
        val frames = entry.frames().objects()
        assertTrue(expectedFrames.first().similar(frames.first()))
        for (i in 1..3) assertTrue(expectedFrames[expectedFrames.size - i].similar(frames[frames.size - i]))
        val wireKeys = setOf("platform", "module", "function", "instruction_addr", "image_addr", "debug_id", "in_app")
        assertTrue(frames.all { wireKeys.containsAll(it.keySet()) })
        val crashing = entry.frames().objects().last()
        assertTrue(crashing.getBoolean("in_app"))
        assertFalse(crashing.has("function"))
        assertEquals("0x0000000000000000", crashing.getString("image_addr"))
        assertEquals("f2300c0536deda922add95b25c0f05caaa421e2f", crashing.getString("debug_id"))

        val threads = event.props().getJSONArray("\$exception_threads").objects()
        assertEquals(CrashLimits.MAX_THREADS, threads.size)
        val expectedFrame = nativeFixture.props().getJSONArray("\$exception_threads").getJSONObject(0)
            .frames().getJSONObject(0)
        assertEquals(expectedFrame.keySet(), threads[0].frames().getJSONObject(0).keySet())
        assertEquals("fatal", event.props().getString("\$exception_level"))
    }

    @Test
    fun `the low memory event matches the fixture`() {
        val event = launch(storeAfterFirstRun(), tempDir(), listOf(lowMemory())).single()

        assertEquals(lowMemoryFixture.props().keySet() + "\$user_id", event.props().keySet())
        assertTrue(lowMemoryFixture.firstException().similar(event.firstException()))
        assertEquals("warning", event.props().getString("\$exception_level"))
    }

    @Test
    fun `the insert id is a name-based uuid of pid, timestamp and reason`() {
        val event = launch(storeAfterFirstRun(), tempDir(), listOf(anr())).single()

        val expected = java.util.UUID.nameUUIDFromBytes("13814:2000:6".toByteArray()).toString()
        assertEquals(expected, event.props().getString("\$insert_id"))
    }

    @Test
    fun `crash-time values come from the record's summary`() {
        val props = launch(storeAfterFirstRun(), tempDir(), listOf(anr())).single().props()

        assertEquals("android@0.1.0+7", props.getString("\$exception_release"))
        assertEquals("5f3c2a10-8e4b-4c7d-9a61-0b2e7d4f9c13", props.getString("\$exception_dist"))
        assertEquals("spike-user-42", props.getString("distinct_id"))
        assertEquals("spike-user-42", props.getString("\$user_id"))
        assertEquals(TEST_DEVICE_ID, props.getString("\$device_id"))
        // The sending launch's build, like every event.
        assertEquals("0.1.0", props.getString("\$app_version_string"))
        assertEquals("7", props.getString("\$app_build_number"))
    }

    @Test
    fun `without a summary the release is unknown, never the current one`() {
        val store = storeAfterFirstRun().apply {
            putConfig(enabledConfig(distinctId = "current-user", deviceId = "install-device", release = "current@1+1"))
        }

        val props = launch(store, tempDir(), listOf(anr(summary = null))).single().props()

        assertEquals(CrashTimeValues.UNKNOWN_RELEASE, props.getString("\$exception_release"))
        assertNotEquals(TEST_APP.defaultRelease, props.getString("\$exception_release"))
        assertFalse(props.has("\$exception_dist"))
        assertEquals("install-device", props.getString("distinct_id"))
        assertEquals("install-device", props.getString("\$device_id"))
        assertFalse(props.has("\$user_id"))
    }

    @Test
    fun `an undecodable summary is treated as absent`() {
        val props = launch(storeAfterFirstRun(), tempDir(), listOf(anr(summary = "garbage".toByteArray())))
            .single().props()

        assertEquals(CrashTimeValues.UNKNOWN_RELEASE, props.getString("\$exception_release"))
        assertEquals(TEST_DEVICE_ID, props.getString("distinct_id"))
        assertFalse(props.has("\$user_id"))
    }

    @Test
    fun `pending reports are sent before exit records are read`() {
        val store = storeAfterFirstRun()
        val dir = tempDir()
        val order = mutableListOf<String>()
        PendingReportStore(dir).write("older", JSONObject().put("event", "\$exception").put("properties", JSONObject()))
        val transport = RecordingCrashTransport { order.add("send") }
        newReporter(store, dir, transport).start {
            order.add("read exit info")
            emptyList()
        }

        assertEquals(listOf("send", "read exit info"), order)
    }

    @Test
    fun `the watermark is not advanced when a report cannot be written`() {
        val store = storeAfterFirstRun()
        val notADirectory = java.io.File(tempDir(), "file").apply { writeText("x") }

        newReporter(store, notADirectory, RecordingCrashTransport.status(200)).start { listOf(lowMemory()) }

        assertEquals(1_000L, store.getLong(CrashConfig.KEY_EXIT_WATERMARK))
    }
}
