package com.cohorly.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VEL-2151: the session replay recorder hears of each report the crash reporter writes. */
class CrashListenerTest {
    @Test
    fun `an uncaught exception calls the listener with true after the report is written and before it is sent`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val transport = RecordingCrashTransport.status(200)
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, transport, slot = slot)
        reporter.start(null)
        val seen = mutableListOf<Triple<Boolean, Int, Int>>()
        reporter.addListener { fatal -> seen.add(Triple(fatal, pendingFiles(dir).size, transport.posts.size)) }

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(listOf(Triple(true, 1, 0)), seen)
        // The bounded send from the handler still happened, after the listener.
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `the listener reads the session id the report was stamped with`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.status(200), slot = slot)
        reporter.start(null)
        var current = "session-a"
        reporter.sessionIdProvider = { current }
        val seen = mutableListOf<String?>()
        reporter.addListener {
            seen.add(reporter.lastReportSessionId)
            // A rotation after the report never changes what the report named.
            current = "session-b"
        }

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(listOf<String?>("session-a"), seen)
        assertEquals("session-a", reporter.lastReportSessionId)
    }

    @Test
    fun `a watchdog ANR calls the listener with false after the report is written`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val background = CrashTestSupport.Background()
        val transport = RecordingCrashTransport.status(200)
        val reporter = newReporter(store, dir, transport, background = background)
        reporter.start(null)
        background.runAll()
        val seen = mutableListOf<Pair<Boolean, Int>>()
        reporter.addListener { fatal -> seen.add(fatal to pendingFiles(dir).size) }

        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)

        assertEquals(listOf(false to 1), seen)
        background.runAll()
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `a listener that throws stops neither the report, its send, the other listeners nor the chain`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val transport = RecordingCrashTransport.status(200)
        val previous = RecordingHandler()
        val slot = FakeSlot(previous)
        val reporter = newReporter(store, dir, transport, slot = slot)
        reporter.start(null)
        var after = 0
        reporter.addListener { throw OutOfMemoryError("listener") }
        reporter.addListener { after++ }
        val error = IllegalStateException("boom")

        slot.current!!.uncaughtException(Thread.currentThread(), error)

        assertEquals(1, after)
        assertEquals(1, transport.posts.size)
        assertEquals(listOf<Throwable>(error), previous.calls)
    }

    @Test
    fun `no report written, no call`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)
        var calls = 0
        reporter.addListener { calls++ }
        reporter.updateConfig(CrashConfig(enabled = false))

        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)
        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(0, calls)
        assertTrue(pendingFiles(dir).isEmpty())
        assertTrue(!reporter.isRecording)
    }

    @Test
    fun `a removed listener is not called`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline())
        reporter.start(null)
        var kept = 0
        var removed = 0
        reporter.addListener { kept++ }
        val remove = reporter.addListener { removed++ }

        remove()
        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)

        assertEquals(1, kept)
        assertEquals(0, removed)
    }
}
