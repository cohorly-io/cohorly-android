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
 * VEL-2139: the recorder hears of a rotation after it happened, so its last
 * buffer of the old session arrives once the new one is current. The
 * controller still takes it, for 60 s, under the old session's identity.
 */
class PreviousSessionChunkTest {
    private fun offline() = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }

    private fun MockTransport.sendQueued(core: com.cohorly.android.core.CohorlyCore): List<JSONObject> {
        bytesResponder = { _, _ -> }
        bytePosts.clear()
        core.replayController.flush()
        return replayChunks()
    }

    @Test
    fun `a chunk of the session reset just ended is queued with that session's identity and next seq`() {
        val transport = offline()
        val clock = MockClock()
        val core = replayCore(transport = transport, clock = clock)
        core.identify("user-1")
        val old = core.replayController.sessionId!!
        core.replayController.enqueue(replayEvents(1, clock.nowMs), sessionId = old)
        clock.advance(1)

        core.reset()
        val new = core.replayController.sessionId!!
        val newDistinctId = core.getDistinctId()
        clock.advance(1)
        assertTrue(core.replayController.enqueue(replayEvents(2, clock.nowMs), ReplayMode.SESSION, old))
        clock.advance(1)
        assertTrue(core.replayController.enqueue(replayEvents(1, clock.nowMs), ReplayMode.SESSION, new))

        val sent = transport.sendQueued(core).map {
            Triple(it.getString("session_id"), it.getString("distinct_id"), it.getLong("seq"))
        }
        assertEquals(
            listOf(Triple(old, "user-1", 0L), Triple(old, "user-1", 1L), Triple(new, newDistinctId, 0L)),
            sent,
        )
        assertNotEquals("user-1", newDistinctId)
    }

    @Test
    fun `the old session's seq is in the state before its chunk is written`() {
        val state = RecordingStorage()
        val store = CountingChunkStore()
        val clock = MockClock()
        val core = replayCore(state = state, store = store, transport = offline(), clock = clock)
        val old = core.replayController.sessionId!!
        core.replayController.enqueue(replayEvents(1, clock.nowMs), sessionId = old)
        clock.advance(1)
        core.reset()
        state.writtenKeys.clear()

        assertTrue(core.replayController.enqueue(replayEvents(1, clock.nowMs), ReplayMode.SESSION, old))

        assertEquals(ReplayController.Keys.SESSION, state.writtenKeys.first())
        val persisted = ReplaySession.parse(state.get(ReplayController.Keys.SESSION))!!
        assertEquals(2L, persisted.retiredSeqs[old])
    }

    @Test
    fun `the old session is accepted for 60 s after the rotation, not after`() {
        val clock = MockClock()
        val core = replayCore(transport = offline(), clock = clock)
        val old = core.replayController.sessionId!!
        core.reset()

        clock.advance(ReplayController.PREVIOUS_SESSION_GRACE_MS)
        assertTrue(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, old))
        clock.advance(1)
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, old))
    }

    @Test
    fun `only the session the last rotation ended is accepted`() {
        val core = replayCore(transport = offline())
        val first = core.replayController.sessionId!!
        core.reset()
        val second = core.replayController.sessionId!!
        core.reset()

        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, first))
        assertTrue(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, second))
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, "never-a-session"))
    }

    @Test
    fun `an opt-out forgets the old session, also after opting in again`() {
        val store = InMemoryReplayChunkStore()
        val core = replayCore(transport = offline(), store = store)
        val old = core.replayController.sessionId!!
        core.reset()
        val ended = core.replayController.sessionId!!

        core.optOutTracking()
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, ended))
        core.optInTracking()

        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, old))
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, ended))
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `an idle rotation hands the old session over too`() {
        val clock = MockClock()
        val core = replayCore(transport = offline(), clock = clock, state = InMemoryStorage())
        val old = core.replayController.sessionId!!

        clock.advance(30 * 60_000L)
        core.replayController.recordActivity()

        assertNotEquals(old, core.replayController.sessionId)
        assertTrue(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, old))
    }
}
