@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SamplingTest {
    private fun draw(sampleRate: Double, onErrorSampleRate: Double, vararg values: Double): ReplayMode {
        val queue = ArrayDeque(values.toList())
        return ReplaySession.draw(SessionReplayConfig(sampleRate, onErrorSampleRate)) { queue.removeFirst() }
    }

    @Test
    fun `under sampleRate records the session, else under onErrorSampleRate records on error, else nothing`() {
        assertEquals(ReplayMode.SESSION, draw(0.5, 0.5, 0.49))
        assertEquals(ReplayMode.ERROR, draw(0.5, 0.5, 0.5, 0.49))
        assertEquals(ReplayMode.OFF, draw(0.5, 0.5, 0.5, 0.5))
        assertEquals(ReplayMode.SESSION, draw(1.0, 0.0, 0.999))
        assertEquals(ReplayMode.OFF, draw(0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun `the draw and the next seq are persisted, and a relaunch in the same session never draws again`() {
        val state = InMemoryStorage()
        val store = InMemoryReplayChunkStore()
        val clock = MockClock()
        var draws = 0
        val values = ArrayDeque(listOf(0.9, 0.1))
        val random = {
            draws++
            values.removeFirst()
        }
        val config = SessionReplayConfig(sampleRate = 0.5, onErrorSampleRate = 0.5)
        val offline = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }
        fun launch(transport: MockTransport) = replayCore(
            state = state,
            clock = clock,
            replay = config,
            random = random,
            transport = transport,
            store = store,
        )
        val firstLaunch = launch(offline)
        assertEquals(ReplayMode.ERROR, firstLaunch.replayController.mode)
        assertEquals(2, draws)
        firstLaunch.replayController.enqueue(replayEvents(1))
        firstLaunch.replayController.enqueue(replayEvents(1))

        clock.advance(60_000)
        val transport = MockTransport()
        val nextLaunch = launch(transport)

        assertEquals(2, draws)
        assertEquals(firstLaunch.replayController.sessionId, nextLaunch.replayController.sessionId)
        assertEquals(ReplayMode.ERROR, nextLaunch.replayController.mode)
        nextLaunch.replayController.enqueue(replayEvents(1))
        // The two queued by the first launch go out at startup, then the new one continues the seq.
        assertEquals(listOf(0L, 1L, 2L), transport.replayChunks().map { it.getLong("seq") })
        assertTrue(transport.replayChunks().all { it.getString("mode") == "error" })
    }
}
