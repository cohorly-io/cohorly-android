@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * VEL-2151: the fatal crash handler writes the error buffer with
 * `enqueueNow` on the crashing thread; the process then dies before any
 * replay thread runs, and the next launch sends the chunk.
 */
class FatalErrorChunkTest {
    private val errorOnly = SessionReplayConfig(sampleRate = 0.0, onErrorSampleRate = 1.0)

    private fun errorEvents(at: Long): List<JSONObject> = listOf(
        JSONObject().put("type", 4).put("timestamp", at).put("data", JSONObject().put("href", "Checkout")),
        JSONObject().put(
            "type",
            2,
        ).put("timestamp", at).put("data", JSONObject().put("wireframes", org.json.JSONArray())),
        JSONObject().put("type", 3).put("timestamp", at + 1).put("data", JSONObject().put("source", 2)),
    )

    private class Launch(val storage: InMemoryStorage, val state: InMemoryStorage, val store: ReplayChunkStore)

    /** A launch whose replay threads never run (the process dies right after the handler). */
    private fun crashingLaunch(launch: Launch, clock: MockClock) = replayCore(
        storage = launch.storage,
        transport = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("never reached") } },
        clock = clock,
        store = launch.store,
        state = launch.state,
        executor = TestReplayExecutor(immediate = false),
        replay = errorOnly,
    )

    @Test
    fun `the chunk is written before enqueueNow returns, and nothing is sent in the crashing process`() {
        val launch = Launch(InMemoryStorage(), InMemoryStorage(), InMemoryReplayChunkStore())
        val clock = MockClock()
        val core = crashingLaunch(launch, clock)
        val controller = core.replayController
        assertEquals(ReplayMode.ERROR, controller.mode)

        assertTrue(controller.enqueueNow(errorEvents(clock.nowMs), ReplayMode.ERROR, controller.sessionId))

        assertEquals(1, launch.store.names().size)
        // The seq it took is in the state, so the next launch never reuses it.
        assertEquals(1L, ReplaySession.parse(launch.state.get(ReplayController.Keys.SESSION))!!.nextSeq)
    }

    @Test
    fun `the next launch sends the chunk in error mode under the crashed session, even after that session expired`() {
        val launch = Launch(InMemoryStorage(), InMemoryStorage(), InMemoryReplayChunkStore())
        val clock = MockClock()
        val crashed = crashingLaunch(launch, clock).replayController
        val crashedSession = crashed.sessionId!!
        assertTrue(crashed.enqueueNow(errorEvents(clock.nowMs), ReplayMode.ERROR, crashedSession))

        // Long enough for the relaunch to start a new session.
        clock.advance(ReplaySession.RELAUNCH_IDLE_TIMEOUT_MS * 2)
        val transport = MockTransport()
        val next = replayCore(
            storage = launch.storage,
            transport = transport,
            clock = clock,
            store = launch.store,
            state = launch.state,
            replay = errorOnly,
        )

        assertNotEquals(crashedSession, next.replayController.sessionId)
        val chunk = transport.replayChunks().single()
        assertEquals(crashedSession, chunk.getString("session_id"))
        assertEquals("error", chunk.getString("mode"))
        assertEquals(0L, chunk.getLong("seq"))
        val events = chunk.getJSONArray("events")
        val types = (0 until events.length()).map { events.getJSONObject(it).getInt("type") }
        assertEquals(listOf(4, 2, 3), types)
        assertTrue(launch.store.names().isEmpty())
    }

    @Test
    fun `a relaunch within the session continues its seq after the crashed chunk`() {
        val launch = Launch(InMemoryStorage(), InMemoryStorage(), InMemoryReplayChunkStore())
        val clock = MockClock()
        val crashed = crashingLaunch(launch, clock).replayController
        val session = crashed.sessionId!!
        assertTrue(crashed.enqueueNow(errorEvents(clock.nowMs), ReplayMode.ERROR, session))

        clock.advance(1_000)
        val transport = MockTransport()
        val next = replayCore(
            storage = launch.storage,
            transport = transport,
            clock = clock,
            store = launch.store,
            state = launch.state,
            replay = errorOnly,
        ).replayController
        assertEquals(session, next.sessionId)
        assertTrue(next.enqueue(errorEvents(clock.nowMs), ReplayMode.ERROR, session))

        val sent = transport.replayChunks().map { it.getString("session_id") to it.getLong("seq") }
        assertEquals(listOf(session to 0L, session to 1L), sent)
    }

    @Test
    fun `an opt-out while the chunk is being written leaves no file behind`() {
        val inner = InMemoryReplayChunkStore()
        var optOut: () -> Unit = {}
        val store = object : ReplayChunkStore by inner {
            override fun write(name: String, bytes: ByteArray): Boolean {
                val written = inner.write(name, bytes)
                // The opt-out (and its purge) happen after the generation check, before the file is checked again.
                optOut()
                return written
            }
        }
        val launch = Launch(InMemoryStorage(), InMemoryStorage(), store)
        val clock = MockClock()
        val core = crashingLaunch(launch, clock)
        val session = core.replayController.sessionId
        optOut = { core.optOutTracking() }

        assertFalse(core.replayController.enqueueNow(errorEvents(clock.nowMs), ReplayMode.ERROR, session))
        assertTrue(inner.names().none { ReplayChunkQueue.parseName(it) != null })
    }

    @Test
    fun `after opt-out nothing is written`() {
        val launch = Launch(InMemoryStorage(), InMemoryStorage(), InMemoryReplayChunkStore())
        val clock = MockClock()
        val core = crashingLaunch(launch, clock)
        val session = core.replayController.sessionId
        core.optOutTracking()

        assertFalse(core.replayController.enqueueNow(errorEvents(clock.nowMs), ReplayMode.ERROR, session))
        assertTrue(launch.store.names().isEmpty())
    }
}
