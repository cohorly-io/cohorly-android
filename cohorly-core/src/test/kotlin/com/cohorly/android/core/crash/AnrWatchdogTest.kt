package com.cohorly.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrWatchdogTest {
    private val clock = FakeClock(0L)
    private val mainQueue = mutableListOf<Runnable>()
    private var foreground = true
    private val reports = mutableListOf<Long>()
    private val watchdog = AnrWatchdog(
        clock = clock,
        postToMain = { mainQueue.add(it) },
        isForeground = { foreground },
        onBlocked = { reports.add(it) },
    )

    private fun mainRuns() {
        mainQueue.toList().forEach { it.run() }
        mainQueue.clear()
    }

    private fun after(ms: Long) {
        clock.nowMs += ms
        watchdog.check()
    }

    @Test
    fun `a block that starts right after a tick ran is reported within the threshold plus one poll`() {
        // The loop as startThread runs it: sleep for whatever check() returns.
        var sleep = watchdog.check()
        mainRuns() // the first tick runs at once; the main thread blocks from here
        val blockStart = clock.nowMs
        while (reports.isEmpty() && clock.nowMs < 30_000) {
            clock.nowMs += sleep
            sleep = watchdog.check()
        }

        assertEquals(1, reports.size)
        assertTrue(clock.nowMs - blockStart <= watchdog.thresholdMs + 2 * AnrWatchdog.DEFAULT_POLL_MS)
    }

    @Test
    fun `a responsive main thread is never reported`() {
        watchdog.check()
        repeat(5) {
            mainRuns()
            after(5_000)
        }

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a main thread blocked for 5 s in the foreground is reported once per block`() {
        watchdog.check()
        after(4_999)
        assertTrue(reports.isEmpty())

        after(1)
        after(500)
        after(10_000)
        assertEquals(listOf(5_000L), reports)

        mainRuns()
        after(500)
        after(5_000)
        assertEquals(listOf(5_000L, 5_000L), reports)
    }

    @Test
    fun `a block in the background is not reported until the app is in the foreground`() {
        foreground = false
        watchdog.check()
        after(6_000)
        assertTrue(reports.isEmpty())

        foreground = true
        after(500)
        assertEquals(1, reports.size)
    }

    @Test
    fun `a watchdog report is an ANR at level error, sent in the background`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val background = CrashTestSupport.Background()
        val transport = RecordingCrashTransport.status(200)
        val reporter = newReporter(store, dir, transport, background = background)
        reporter.start(null)
        background.runAll()

        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)

        assertEquals(1, pendingFiles(dir).size)
        assertTrue(transport.posts.isEmpty())
        background.runAll()
        val event = transport.events().single()
        val entry = event.firstException()
        assertEquals("ANR", entry.getString("type"))
        assertEquals("Main thread blocked for at least 5000 ms (watchdog)", entry.getString("value"))
        assertEquals("anr", entry.getJSONObject("mechanism").getString("type"))
        assertFalse(entry.getJSONObject("mechanism").getBoolean("handled"))
        assertEquals("error", event.props().getString("\$exception_level"))
        assertTrue(entry.frames().length() > 0)
        assertTrue(pendingFiles(dir).isEmpty())
    }

    @Test
    fun `no watchdog report once reporting is turned off`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val reporter = newReporter(store, dir, RecordingCrashTransport.status(200))
        reporter.start(null)
        reporter.updateConfig(CrashConfig(enabled = false))

        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)

        assertTrue(pendingFiles(dir).isEmpty())
    }
}
