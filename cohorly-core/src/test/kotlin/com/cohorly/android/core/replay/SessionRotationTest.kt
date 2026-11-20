@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionRotationTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `a foreground after 30 min in background starts a new session, after 29m59s it does not`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val first = core.replayController.sessionId

        core.onAppBackgrounded()
        clock.advance(30 * minute - 1_000)
        core.onAppForegrounded()
        assertEquals(first, core.replayController.sessionId)

        core.onAppBackgrounded()
        clock.advance(30 * minute)
        core.onAppForegrounded()
        assertNotEquals(first, core.replayController.sessionId)
    }

    @Test
    fun `the first track once the session reached 24 h starts a new session, at 23h59m it does not`() {
        val clock = MockClock()
        val transport = MockTransport()
        val core = replayCore(clock = clock, transport = transport)
        val first = core.replayController.sessionId
        // In background, so the 30 min foreground idle rule (VEL-2139) stays out of the way.
        core.onAppBackgrounded()

        clock.advance(24 * hour - minute)
        core.track("Still the same")
        clock.advance(minute)
        core.track("New session")
        core.flush()

        val ids = transport.trackedEvents().map { it.props().getString("\$session_id") }
        assertEquals(first, ids[0])
        assertNotEquals(first, ids[1])
        assertEquals(core.replayController.sessionId, ids[1])
    }

    @Test
    fun `reset starts a new session at once`() {
        val core = replayCore()
        val first = core.replayController.sessionId

        core.reset()

        assertNotEquals(first, core.replayController.sessionId)
    }

    @Test
    fun `a relaunch less than 30 min after the last activity continues the session, 31 min later it rotates`() {
        val state = InMemoryStorage()
        val clock = MockClock()
        val firstLaunch = replayCore(state = state, clock = clock)
        firstLaunch.track("Opened")
        val first = firstLaunch.replayController.sessionId

        clock.advance(30 * minute - 1_000)
        val secondLaunch = replayCore(state = state, clock = clock)
        assertEquals(first, secondLaunch.replayController.sessionId)
        secondLaunch.track("Opened again")

        clock.advance(31 * minute)
        val thirdLaunch = replayCore(state = state, clock = clock)
        assertNotEquals(first, thirdLaunch.replayController.sessionId)
    }

    @Test
    fun `a relaunch never rotates on activity the throttle kept off the disk`() {
        val state = InMemoryStorage()
        val clock = MockClock()
        val noon = clock.nowMs
        val firstLaunch = replayCore(state = state, clock = clock)
        val first = firstLaunch.replayController.sessionId
        firstLaunch.track("Persisted at 12:00")
        clock.advance(59_000)
        firstLaunch.track("Throttled at 12:00:59")
        assertEquals(noon, ReplaySession.parse(state.get(ReplayController.Keys.SESSION))!!.lastActiveAt)

        // 29 min 1 s after the real last activity: the session goes on.
        clock.nowMs = noon + 30 * minute
        assertEquals(first, replayCore(state = state, clock = clock).replayController.sessionId)

        // 30 min 1 s after it: a new session.
        clock.nowMs = noon + 31 * minute
        assertNotEquals(first, replayCore(state = state, clock = clock).replayController.sessionId)
        assertEquals(31 * minute, ReplaySession.RELAUNCH_IDLE_TIMEOUT_MS)
    }

    @Test
    fun `a relaunch 31 min after going to background rotates`() {
        val state = InMemoryStorage()
        val clock = MockClock()
        val firstLaunch = replayCore(state = state, clock = clock)
        val first = firstLaunch.replayController.sessionId
        clock.advance(5 * minute)
        firstLaunch.onAppBackgrounded()

        clock.advance(30 * minute)
        assertEquals(first, replayCore(state = state, clock = clock).replayController.sessionId)
        clock.advance(minute)
        assertNotEquals(first, replayCore(state = state, clock = clock).replayController.sessionId)
    }

    @Test
    fun `a relaunch continues the seq above the highest one queued for the session`() {
        val state = InMemoryStorage()
        val store = InMemoryReplayChunkStore()
        val clock = MockClock()
        val offline = MockTransport().apply { bytesResponder = { _, _ -> throw java.io.IOException("offline") } }
        val firstLaunch = replayCore(state = state, store = store, clock = clock, transport = offline)
        val sessionId = firstLaunch.replayController.sessionId!!
        ReplayChunkQueue(store, "0.3.0").add(
            ReplayChunk(sessionId, "user", 5, ReplayMode.SESSION, clock.nowMs, replayEvents(1)),
        )
        val persisted = ReplaySession.parse(state.get(ReplayController.Keys.SESSION))!!
        state.set(ReplayController.Keys.SESSION, persisted.copy(nextSeq = 3).toJson())

        clock.advance(1)
        val relaunch = replayCore(state = state, store = store, clock = clock, transport = offline)
        assertEquals(sessionId, relaunch.replayController.sessionId)
        relaunch.replayController.enqueue(replayEvents(1, clock.nowMs))

        val seqs = store.names().mapNotNull { ReplayChunkQueue.parseName(it)?.seq }.sorted()
        assertEquals(listOf(5L, 6L), seqs)
    }

    @Test
    fun `the next seq reaches the state file before the chunk file is written`() {
        val state = InMemoryStorage()
        val store = FaultyChunkStore()
        val nextSeqAtWrite = mutableListOf<Long>()
        store.failWrite = {
            nextSeqAtWrite.add(ReplaySession.parse(state.get(ReplayController.Keys.SESSION))!!.nextSeq)
            false
        }
        val offline = MockTransport().apply { bytesResponder = { _, _ -> throw java.io.IOException("offline") } }
        val core = replayCore(state = state, store = store, transport = offline)

        core.replayController.enqueue(replayEvents(1))
        core.replayController.enqueue(replayEvents(1))

        // Chunks 0 and 1: the persisted counter was already past each.
        assertEquals(listOf(1L, 2L), nextSeqAtWrite)
    }

    @Test
    fun `a new session restarts seq at 0 while queued chunks keep the old session`() {
        val transport = MockTransport().apply { bytesResponder = { _, _ -> throw java.io.IOException("offline") } }
        val clock = MockClock()
        val core = replayCore(transport = transport, clock = clock)
        val first = core.replayController.sessionId
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        clock.advance(1)

        core.reset()
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        transport.bytesResponder = { _, _ -> }
        transport.bytePosts.clear()
        core.replayController.flush()

        val sent = transport.replayChunks().map { it.getString("session_id") to it.getLong("seq") }
        val second = core.replayController.sessionId!!
        assertEquals(listOf(first to 0L, first to 1L, second to 0L), sent)
    }

    @Test
    fun `a new session draws again`() {
        val draws = ArrayDeque(listOf(0.1, 0.9, 0.9))
        val core = replayCore(
            replay = SessionReplayConfig(sampleRate = 0.5, onErrorSampleRate = 0.5),
            random = { draws.removeFirst() },
        )
        assertEquals(ReplayMode.SESSION, core.replayController.mode)

        core.reset()

        assertEquals(ReplayMode.OFF, core.replayController.mode)
    }
}
