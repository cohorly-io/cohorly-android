package com.cohorly.android.replay

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.cohorly.android.core.replay.ErrorBuffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * VEL-2151: a session in error mode records into memory only, and a crash
 * report sends the buffer (an ANR now, a fatal crash written synchronously).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ErrorModeRecorderTest {
    /** [FakeSession] plus the crash reporter side the recorder sees in error mode. */
    private class ErrorSession(val base: FakeSession) : ReplaySessionSource by base {
        var reporterRunning = true
        val listeners = mutableListOf<(Boolean) -> Unit>()
        val queued = mutableListOf<Pair<String, List<JSONObject>>>()
        val written = mutableListOf<Pair<String, List<JSONObject>>>()

        override val errorReportsOn: Boolean get() = reporterRunning

        /** The `$session_id` the next report carries; null: the current session. */
        var reportedSession: String? = null

        override val reportSessionId: String? get() = reportedSession ?: base.sessionId

        override fun addCrashListener(listener: (fatal: Boolean) -> Unit): () -> Unit {
            listeners.add(listener)
            return { listeners.remove(listener) }
        }

        override fun enqueueError(events: List<JSONObject>, sessionId: String): Boolean {
            queued.add(sessionId to events)
            return true
        }

        override fun enqueueErrorNow(events: List<JSONObject>, sessionId: String): Boolean {
            written.add(sessionId to events)
            return true
        }

        /** The SDK's error buffer, as [ControllerSessionSource] gives it. */
        override fun createErrorBuffer(clock: () -> Long): RecorderErrorBuffer = CoreErrorBuffer(clock)

        fun report(fatal: Boolean) = listeners.toList().forEach { it(fatal) }
    }

    private val app = RuntimeEnvironment.getApplication()
    private val worker = TestExecutor()
    private val base = FakeSession(mode = RecorderMode.ERROR)
    private val session = ErrorSession(base)
    private val roots = FakeRootViews()
    private var now = 1_700_000_000_000L
    private lateinit var activity: Activity
    private lateinit var label: TextView
    private var recorder: ReplayRecorder? = null

    private fun startScreen() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(content)
        label = TextView(activity).apply { text = "Total 10" }
        content.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
        idleMain()
        roots.add(activity.root)
    }

    private fun install() {
        recorder = ReplayRecorder(
            application = app,
            session = session,
            maskAllImages = true,
            worker = worker,
            clock = { now },
            uptimeMillis = { 0L },
            rootViewSource = roots,
            throttleDelayMs = 0,
        ).also { it.install() }
        idleMain()
    }

    /** Moves both clocks: the recorder's wall clock and the worker's timers. */
    private fun advance(ms: Long) {
        now += ms
        worker.advance(ms)
        idleMain()
    }

    private fun changeLabel(text: String) {
        label.text = text
        idleMain()
        now += 1
        recorder!!.capture()
        idleMain()
    }

    /** Event types, without the error marker that closes every cut. */
    private fun types(events: List<JSONObject>) = events.map { it.getInt("type") }.filter { it != ErrorBuffer.CUSTOM }

    @After
    fun tearDown() {
        recorder?.uninstall()
        idleMain()
    }

    @Test
    fun `error mode never queues anything without an error`() {
        startScreen()
        install()
        changeLabel("Total 20")
        advance(ChunkBuffer.MAX_AGE_MS * 10)

        assertTrue(base.attempts.isEmpty())
        assertTrue(session.queued.isEmpty())
        assertTrue(session.written.isEmpty())
    }

    @Test
    fun `without a running crash reporter error mode records nothing`() {
        session.reporterRunning = false
        startScreen()
        install()
        changeLabel("Total 20")
        // A report could not come without a reporter; even if one did, nothing was buffered.
        session.report(false)

        assertTrue(session.queued.isEmpty())
        assertTrue(base.attempts.isEmpty())
    }

    @Test
    fun `an ANR queues the buffer from a full snapshot to the change before it`() {
        startScreen()
        install()
        changeLabel("Total 20")

        session.report(false)

        val (id, events) = session.queued.single()
        assertEquals("session-1", id)
        assertEquals(listOf(4, 2, 3), types(events))
        assertTrue(events.dropLast(1).last().toString().contains("Total 20"))
    }

    @Test
    fun `a second ANR within 5 s waits for the rest of the interval, and errors meanwhile join it`() {
        startScreen()
        install()
        changeLabel("Total 20")
        session.report(false)
        assertEquals(1, session.queued.size)

        advance(1_000)
        changeLabel("Total 30")
        session.report(false)
        session.report(false)
        assertEquals("waits for the interval", 1, session.queued.size)

        advance(ErrorBuffer.SEND_GAP_MS)

        assertEquals(2, session.queued.size)
        val second = session.queued[1].second
        // Started again from the full snapshot taken after the first send.
        assertEquals(listOf(4, 2), types(second).take(2))
        assertTrue(second.toString().contains("Total 30"))
    }

    @Test
    fun `a fatal crash writes the buffer synchronously, ignoring the 5 s interval`() {
        startScreen()
        install()
        changeLabel("Total 20")
        session.report(false)
        // The full snapshot the send asked for, then a change after it.
        idleMain()
        changeLabel("Total 30")

        session.report(true)

        val (id, events) = session.written.single()
        assertEquals(base.sessionId, id)
        assertEquals(listOf(4, 2), types(events).take(2))
        assertTrue(events.toString().contains("Total 30"))
    }

    @Test
    fun `a checkout is taken 30 s after a change`() {
        startScreen()
        install()
        changeLabel("Total 20")

        advance(ErrorBuffer.CHECKOUT_EVERY_MS)
        session.report(false)

        val events = session.queued.single().second
        assertEquals(listOf(4, 2, 3, 4, 2), types(events))
    }

    @Test
    fun `no checkout while the screen is idle`() {
        startScreen()
        install()

        advance(ErrorBuffer.CHECKOUT_EVERY_MS * 3)
        session.report(false)

        assertEquals(listOf(4, 2), types(session.queued.single().second))
    }

    @Test
    fun `switching to session mode drops the error buffer and starts whole`() {
        startScreen()
        install()
        changeLabel("Total 20")

        base.mode = RecorderMode.SESSION
        changeLabel("Total 30")
        session.report(false)
        advance(ChunkBuffer.MAX_AGE_MS)

        assertTrue(session.queued.isEmpty())
        val events = base.events()
        assertEquals(listOf(4, 2), types(events).take(2))
        assertFalse(base.json().contains("Total 20"))
    }

    @Test
    fun `a rotation drops the error buffer`() {
        startScreen()
        install()
        changeLabel("Total 20")

        val rotatedAt = now
        base.rotate("session-2")
        idleMain()
        changeLabel("Total 30")
        session.report(true)

        val (id, events) = session.written.single()
        assertEquals("session-2", id)
        assertEquals(listOf(4, 2), types(events).take(2))
        // Nothing recorded under session-1 (its change to "Total 20") is carried over.
        assertTrue(events.all { it.getLong("timestamp") >= rotatedAt })
    }

    @Test
    fun `a fatal report stamped with another session writes nothing`() {
        startScreen()
        install()
        changeLabel("Total 20")
        session.reportedSession = "session-0"

        session.report(true)

        assertTrue(session.written.isEmpty())
    }

    @Test
    fun `an ANR waiting for the interval is dropped when its session rotates`() {
        startScreen()
        install()
        changeLabel("Total 20")
        session.report(false)
        advance(1_000)
        changeLabel("Total 30")
        session.report(false)

        base.rotate("session-2")
        idleMain()
        changeLabel("Total 40")
        advance(ErrorBuffer.SEND_GAP_MS)

        assertEquals(listOf("session-1"), session.queued.map { it.first })
    }
}
