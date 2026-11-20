package com.cohorly.android.replay

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.cohorly.android.Cohorly
import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.CohorlyTransport
import com.cohorly.android.core.replay.SessionReplayConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** An Activity whose screen is set in onCreate, so the first capture already shows it. */
class LabelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Recorded label" })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayInstallerTest {
    private val app = RuntimeEnvironment.getApplication()

    private val instances = mutableListOf<Cohorly>()

    /** Their Curtains listeners would otherwise hear the next test's windows. */
    @After
    fun tearDown() {
        instances.forEach { ReplayInstaller.recorderFor(it)?.uninstall() }
        idleMain()
    }

    private fun cohorly(replay: SessionReplayConfig?, transport: CohorlyTransport = NoNetworkTransport()): Cohorly =
        Cohorly.getInstance(
            app,
            "token-${UUID.randomUUID()}",
            "http://localhost:1",
            CohorlyConfig(flushIntervalMs = 0, loadFeatureFlags = false, maxRetryDelayMs = 50, sessionReplay = replay),
            transport,
        ).also { instances.add(it) }

    @Test
    fun `getInstance with sessionReplay installs a recorder whose capture is queued and sent by the real controller`() {
        val transport = RecordingTransport()
        val cohorly = cohorly(SessionReplayConfig(sampleRate = 1.0), transport)
        idleMain()
        val recorder = requireNotNull(ReplayInstaller.recorderFor(cohorly))

        val screen = Robolectric.buildActivity(LabelActivity::class.java).setup()
        idleMain()
        recorder.capture()
        idleMain()
        // Background ships the buffer to the controller's queue, which uploads it.
        screen.pause().stop()

        awaitTrue("a chunk sent for the session") { transport.chunks().isNotEmpty() }
        val chunk = transport.chunks().first()
        assertEquals(cohorly.replaySessionId, chunk.getString("session_id"))
        assertEquals("session", chunk.getString("mode"))
        assertEquals(listOf(4, 2), chunk.events().take(2).map { it.getInt("type") })
        assertTrue(chunk.toString().contains("Recorded label"))
    }

    @Test
    fun `without sessionReplay the recorder is never looked up`() {
        val cohorly = cohorly(null)
        idleMain()

        assertNull(ReplayInstaller.recorderFor(cohorly))
    }

    @Test
    @Config(sdk = [25])
    fun `below API 26 nothing is recorded and the session id goes on`() {
        val cohorly = cohorly(SessionReplayConfig(sampleRate = 1.0))
        idleMain()

        assertNull(ReplayInstaller.recorderFor(cohorly))
        assertNotNull(cohorly.replaySessionId)
    }

    /** The real controller's session and sampling decision, with the chunks kept in the test. */
    private class Recording(private val delegate: ReplaySessionSource) : ReplaySessionSource by delegate {
        val chunks = mutableListOf<List<JSONObject>>()

        override fun enqueue(events: List<JSONObject>, sessionId: String): Boolean {
            chunks.add(events)
            return delegate.enqueue(events, sessionId)
        }
    }

    private fun recordOneScreen(sampleRate: Double): Recording {
        val cohorly = cohorly(SessionReplayConfig(sampleRate = sampleRate))
        val source = Recording(ControllerSessionSource(requireNotNull(cohorly.replay)))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(TextView(activity).apply { text = "Hello" })
        idleMain()
        val worker = TestExecutor()
        val recorder = ReplayRecorder(
            application = app,
            session = source,
            maskAllImages = true,
            worker = worker,
            rootViewSource = FakeRootViews(activity.window.decorView),
            throttleDelayMs = 0,
        )
        recorder.install()
        idleMain()
        recorder.capture()
        idleMain()
        worker.advance(10_000)
        recorder.uninstall()
        idleMain()
        return source
    }

    @Test
    fun `sampleRate 0 records nothing`() {
        assertEquals(0, recordOneScreen(sampleRate = 0.0).chunks.size)
    }

    @Test
    fun `sampleRate 1 queues a chunk with a full snapshot`() {
        val chunks = recordOneScreen(sampleRate = 1.0).chunks
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].any { it.getInt("type") == 2 })
    }
}
