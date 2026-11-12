package com.cohorly.android.core.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UncaughtCrashTest {
    private fun checkoutFailure(): Throwable =
        IllegalStateException("Checkout failed for cart 42", IOException("payment gateway unreachable"))

    @Test
    fun `an uncaught exception is written by the handler and sent on the next launch`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        val crashedLaunch = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        assertTrue(crashedLaunch.start(null))

        val error = checkoutFailure()
        slot.current!!.uncaughtException(Thread.currentThread(), error)

        assertEquals(listOf(error), previous.calls)
        assertEquals(1, pendingFiles(dir).size)

        val transport = RecordingCrashTransport.status(200)
        val nextLaunch = newReporter(store, dir, transport, slot = FakeSlot())
        assertTrue(nextLaunch.start(null))

        assertEquals(1, transport.posts.size)
        val post = transport.posts.single()
        assertEquals("$TEST_HOST/track", post.url)
        assertEquals(TEST_TOKEN, post.headers["X-Cohorly-Token"])
        val event = JSONObject(post.body)
        assertEquals("\$exception", event.getString("event"))
        val props = event.props()
        assertEquals("fatal", props.getString("\$exception_level"))
        assertEquals("com.cohorly.spike.crash@0.1.0+7", props.getString("\$exception_release"))
        assertEquals("spike-user-42", props.getString("distinct_id"))
        assertEquals("spike-user-42", props.getString("\$user_id"))
        assertEquals(TEST_DEVICE_ID, props.getString("\$device_id"))
        val list = props.getJSONArray("\$exception_list").objects()
        assertEquals(
            listOf("java.lang.IllegalStateException", "java.io.IOException"),
            list.map { it.getString("type") },
        )
        assertTrue(pendingFiles(dir).isEmpty())
    }

    @Test
    fun `the event has the fixture's shape`() {
        val expected = fixture("android-uncaught.json")
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val other = Thread("pool-4-thread-1")
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(
            store,
            dir,
            RecordingCrashTransport.offline(),
            slot = slot,
            threadDump = { mapOf(Thread.currentThread() to emptyArray(), other to Thread.currentThread().stackTrace) },
        )
        reporter.start(null)
        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        val actual = PendingReportStore(dir).list().single().event
        // The app version is stamped at send time, by the sending launch.
        assertEquals(
            expected.props().keySet() - setOf("\$app_version_string", "\$app_build_number"),
            actual.props().keySet(),
        )
        for (entry in actual.props().getJSONArray("\$exception_list").objects()) {
            assertTrue(expected.firstException().getJSONObject("mechanism").similar(entry.getJSONObject("mechanism")))
            assertEquals(expected.firstException().keySet(), entry.keySet())
            assertEquals("raw", entry.getJSONObject("stacktrace").getString("type"))
        }
        val frame = actual.firstException().frames().objects().last()
        assertEquals(setOf("platform", "module", "function", "filename", "lineno", "in_app"), frame.keySet())
        assertEquals("java", frame.getString("platform"))
        assertEquals(UncaughtCrashTest::class.java.name, frame.getString("module"))
        assertTrue(frame.getBoolean("in_app"))

        // Only the other thread, never the crashed one.
        val threads = actual.props().getJSONArray("\$exception_threads").objects()
        assertEquals(listOf("pool-4-thread-1"), threads.map { it.getString("name") })
        val expectedThread = expected.props().getJSONArray("\$exception_threads").getJSONObject(0)
        assertEquals(expectedThread.keySet(), threads[0].keySet())
    }

    @Test
    fun `a slow send in the handler gives up after 2 s and the previous handler still runs`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val release = CountDownLatch(1)
        val transport = RecordingCrashTransport { release.await(30, TimeUnit.SECONDS) }
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        newReporter(store, dir, transport, slot = slot).start(null)

        val started = System.nanoTime()
        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("handler took $elapsedMs ms", elapsedMs < CrashReporter.HANDLER_SEND_TIMEOUT_MS + 1_000)
        assertEquals(1, previous.calls.size)
        assertEquals(1, transport.posts.size)
        assertEquals(1, pendingFiles(dir).size)
        release.countDown()
    }

    @Test
    fun `the previous handler runs even when writing the report fails`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val notADirectory = java.io.File(tempDir(), "file").apply { writeText("x") }
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        newReporter(store, notADirectory, RecordingCrashTransport.status(200), slot = slot).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        assertEquals(1, previous.calls.size)
    }

    @Test
    fun `without a previous handler the process is terminated`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        var terminated = 0
        val slot = FakeSlot(null)
        newReporter(store, tempDir(), RecordingCrashTransport.offline(), slot = slot, terminate = { terminated++ })
            .start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        assertEquals(1, terminated)
    }

    @Test
    fun `the same crash coming back through a chaining handler is written once`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val original = RecordingHandler()
        val slot = FakeSlot(original)
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)
        val ours = slot.current!!
        // A library installed after us that chains to us...
        val chaining = Thread.UncaughtExceptionHandler { t, e -> ours.uncaughtException(t, e) }
        slot.current = chaining
        // ...and our reinstall on top of it on the first activity resume.
        reporter.reinstallIfReplaced()
        assertSame(ours, slot.current)

        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        assertEquals(1, pendingFiles(dir).size)
        assertEquals(1, original.calls.size)
    }

    @Test
    fun `reinstall chains to the handler that replaced ours`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)
        val ours = slot.current!!
        val replacing = RecordingHandler()
        slot.current = replacing

        reporter.reinstallIfReplaced()
        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        assertSame(ours, slot.current)
        assertEquals(1, replacing.calls.size)
        assertEquals(1, pendingFiles(dir).size)
    }

    @Test
    fun `a live config turned off makes the handler only chain`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)

        reporter.updateConfig(CrashReporter.writeConfig(store, """{"enabled":false}"""))
        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        assertTrue(pendingFiles(dir).isEmpty())
        assertEquals(1, previous.calls.size)
    }

    @Test
    fun `identity is read from the live config at crash time`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig(distinctId = TEST_DEVICE_ID)) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)

        reporter.updateConfig(CrashReporter.writeConfig(store, enabledConfig(distinctId = "user-7").toJson()))
        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        val props = PendingReportStore(dir).list().single().event.props()
        assertEquals("user-7", props.getString("distinct_id"))
        assertEquals("user-7", props.getString("\$user_id"))
        assertEquals(TEST_DEVICE_ID, props.getString("\$device_id"))
    }

    @Test
    fun `an anonymous user has no user id and the time and insert id are from the crash`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig(distinctId = TEST_DEVICE_ID)) }
        val dir = tempDir()
        val clock = FakeClock(1_700_000_000_000L)
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot, clock = clock).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        val report = PendingReportStore(dir).list().single()
        val props = report.event.props()
        assertFalse(props.has("\$user_id"))
        assertEquals(1_700_000_000_000L, props.getLong("time"))
        assertEquals(report.insertId, props.getString("\$insert_id"))
    }

    @Test
    fun `without a release in the config the handler uses the build that crashed`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig(release = null, dist = null)) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), checkoutFailure())

        val props = PendingReportStore(dir).list().single().event.props()
        assertEquals("com.cohorly.spike.crash@0.1.0+7", props.getString("\$exception_release"))
        assertNull(props.opt("\$exception_dist"))
    }
}
