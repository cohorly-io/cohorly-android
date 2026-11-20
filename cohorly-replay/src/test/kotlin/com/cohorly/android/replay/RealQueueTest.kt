package com.cohorly.android.replay

import com.cohorly.android.Cohorly
import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.replay.SessionReplayConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The recorder installed by `Cohorly.getInstance` against the real replay
 * controller, its threads, its chunk files and its uploader: what reaches the
 * queue is read back from the files on disk and the `POST /replay` bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealQueueTest {
    private val app = RuntimeEnvironment.getApplication()
    private val token = "token-${UUID.randomUUID()}"
    private val transport = RecordingTransport()

    private val cohorly: Cohorly = Cohorly.getInstance(
        app,
        token,
        "http://localhost:1",
        CohorlyConfig(
            flushIntervalMs = 0,
            loadFeatureFlags = false,
            maxRetryDelayMs = 50,
            sessionReplay = SessionReplayConfig(sampleRate = 1.0),
        ),
        transport,
    )

    private fun recorder(): ReplayRecorder = requireNotNull(ReplayInstaller.recorderFor(cohorly))

    /** The label screen in foreground, captured once (its buffer holds Meta + FullSnapshot). */
    private fun showScreen(): ActivityController<LabelActivity> {
        idleMain()
        val screen = Robolectric.buildActivity(LabelActivity::class.java).setup()
        idleMain()
        recorder().capture()
        idleMain()
        return screen
    }

    /** Its Curtains listener would otherwise hear the next test's windows. */
    @After
    fun tearDown() {
        recorder().uninstall()
        idleMain()
    }

    private fun chunkFiles() = queuedChunkFiles(app.noBackupFilesDir, token)

    @Test
    fun `the old session's buffer reaches the queue under the old id and identity after reset`() {
        cohorly.identify("user-1")
        val screen = showScreen()
        val old = requireNotNull(cohorly.replaySessionId)

        // The rotation happens on the SDK's threads; the recorder hears of it afterwards.
        cohorly.reset()

        awaitTrue("the old session's chunk") { transport.chunks().any { it.getString("session_id") == old } }
        val oldChunk = transport.chunks().first { it.getString("session_id") == old }
        assertEquals("user-1", oldChunk.getString("distinct_id"))
        assertEquals(0L, oldChunk.getLong("seq"))
        assertEquals(listOf(4, 2), oldChunk.events().take(2).map { it.getInt("type") })
        assertTrue(oldChunk.toString().contains("Recorded label"))

        // The new session starts whole, with the new identity.
        awaitTrue("the new session") { cohorly.replaySessionId != old }
        val new = requireNotNull(cohorly.replaySessionId)
        idleMain()
        recorder().capture()
        idleMain()
        screen.pause().stop()
        awaitTrue("the new session's chunk") { transport.chunks().any { it.getString("session_id") == new } }
        val newChunk = transport.chunks().first { it.getString("session_id") == new }
        assertNotEquals("user-1", newChunk.getString("distinct_id"))
        assertEquals(listOf(4, 2), newChunk.events().take(2).map { it.getInt("type") })
    }

    @Test
    fun `opt-out drops queued chunks and the buffer, opt-in records a new session from a full snapshot`() {
        transport.offline = true
        val screen = showScreen()
        val old = requireNotNull(cohorly.replaySessionId)
        // A chunk queued on disk (the upload fails) ...
        screen.pause().stop()
        awaitTrue("a chunk queued on disk") { chunkFiles().isNotEmpty() }
        // ... and a buffer in the recorder's memory.
        screen.restart().resume()
        idleMain()
        recorder().capture()
        idleMain()

        cohorly.optOutTracking()

        awaitTrue("the queue purged") {
            cohorly.replaySessionId == null &&
                chunkFiles().isEmpty() &&
                !recorder().isStarted
        }

        transport.offline = false
        cohorly.optInTracking()
        awaitTrue("recording a new session") { cohorly.replaySessionId != null && recorder().isStarted }
        val new = requireNotNull(cohorly.replaySessionId)
        assertNotEquals(old, new)
        idleMain()
        recorder().capture()
        idleMain()
        screen.pause().stop()

        awaitTrue("the new session's chunk") { transport.chunks().any { it.getString("session_id") == new } }
        val sent = transport.chunks()
        assertNotNull(sent.firstOrNull())
        // Nothing of the session before the opt-out was sent, queued or buffered.
        assertEquals(listOf(new), sent.map { it.getString("session_id") }.distinct())
        assertEquals(listOf(4, 2), sent.first().events().take(2).map { it.getInt("type") })
    }
}
