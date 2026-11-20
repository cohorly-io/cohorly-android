@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReplayOptOutTest {
    private fun offline() = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }

    /** A launch that queued two chunks (offline), then a stored opt-out its purge never applied. */
    private fun optedOutWithChunks(storage: InMemoryStorage, state: InMemoryStorage, store: ReplayChunkStore) {
        val clock = MockClock()
        val first = replayCore(storage = storage, state = state, store = store, clock = clock, transport = offline())
        first.replayController.enqueue(replayEvents(1, clock.nowMs))
        clock.advance(1)
        first.replayController.enqueue(replayEvents(1, clock.nowMs))
        assertEquals(2, store.names().size)
        storage.set(CohorlyCore.Keys.OPT_OUT, "1")
    }

    @Test
    fun `opting out deletes the queued chunks, ends the session and stops queuing and sending`() {
        val storage = InMemoryStorage()
        val state = InMemoryStorage()
        val store = InMemoryReplayChunkStore()
        val transport = offline()
        val core = replayCore(storage = storage, state = state, store = store, transport = transport)
        val heard = mutableListOf<String?>()
        core.replayController.addSessionListener { heard.add(it) }
        core.replayController.enqueue(replayEvents(1))
        core.replayController.enqueue(replayEvents(1))
        assertEquals(2, store.names().size)

        core.optOutTracking()

        assertTrue(store.names().isEmpty())
        assertNull(core.replayController.sessionId)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        assertNull(state.get(ReplayController.Keys.SESSION))
        assertEquals(listOf<String?>(null), heard)
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION))
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.ERROR))
        transport.bytesResponder = { _, _ -> }
        transport.bytePosts.clear()
        core.replayController.flush()
        assertTrue(transport.bytePosts.isEmpty())

        // A relaunch while opted out has no session either.
        assertNull(replayCore(storage = storage, state = state, store = store).replayController.sessionId)
    }

    @Test
    fun `a relaunch while opted out deletes the chunks left on disk before any upload`() {
        val storage = InMemoryStorage()
        val state = InMemoryStorage()
        val store = InMemoryReplayChunkStore()
        optedOutWithChunks(storage, state, store)

        val transport = MockTransport()
        val relaunch = replayCore(storage = storage, state = state, store = store, transport = transport)
        relaunch.replayController.flush()

        assertTrue(store.names().isEmpty())
        assertTrue(transport.bytePosts.isEmpty())
        assertNull(state.get(ReplayController.Keys.PURGE))
    }

    @Test
    fun `opting in after that never sends the old chunks, even when they cannot be deleted`() {
        val storage = InMemoryStorage()
        val state = InMemoryStorage()
        val store = FaultyChunkStore()
        optedOutWithChunks(storage, state, store)
        store.failDelete = true

        val transport = MockTransport()
        val relaunch = replayCore(storage = storage, state = state, store = store, transport = transport)
        assertEquals(2, store.names().size)
        assertNotNull(state.get(ReplayController.Keys.PURGE))

        relaunch.optInTracking()
        val newSession = relaunch.replayController.sessionId
        assertTrue(relaunch.replayController.enqueue(replayEvents(1)))
        relaunch.replayController.flush()

        // Deletes still fail, so the new chunk can go out again on flush; only the old ones must never go.
        assertEquals(setOf(newSession), transport.replayChunks().map { it.getString("session_id") }.toSet())

        // Once deletes work again the next launch finishes the purge.
        store.failDelete = false
        replayCore(storage = storage, state = state, store = store, transport = MockTransport())
        assertTrue(store.names().isEmpty())
        assertNull(state.get(ReplayController.Keys.PURGE))
    }

    @Test
    fun `an opt-out between reading a chunk and posting it sends nothing`() {
        val store = FaultyChunkStore()
        val transport = MockTransport()
        lateinit var core: CohorlyCore
        var optedOut = false
        store.beforeRead = {
            if (!optedOut) {
                optedOut = true
                core.optOutTracking()
                // Even an opt-in right after cannot let that drain post.
                core.optInTracking()
            }
        }
        core = replayCore(store = store, transport = transport)

        core.replayController.enqueue(replayEvents(1))

        assertTrue(optedOut)
        assertTrue(transport.bytePosts.isEmpty())
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `opting back in starts a new session and queues again`() {
        val transport = MockTransport()
        val core = replayCore(transport = transport)
        val before = core.replayController.sessionId
        core.optOutTracking()

        core.optInTracking()

        val after = core.replayController.sessionId
        assertNotNull(after)
        assertNotEquals(before, after)
        assertTrue(core.replayController.enqueue(replayEvents(1)))
        assertEquals(after, transport.replayChunks().single().getString("session_id"))
    }

    @Test
    fun `session listeners hear every rotation in order and can be removed`() {
        val core = replayCore()
        val heard = mutableListOf<String?>()
        val remove = core.replayController.addSessionListener { heard.add(it) }

        core.reset()
        val afterFirst = core.replayController.sessionId
        core.reset()
        val afterSecond = core.replayController.sessionId
        remove()
        core.reset()

        assertEquals(listOf(afterFirst, afterSecond), heard)
    }

    @Test
    fun `a chunk taken before an opt-out is never written, even when its write runs after an opt-in`() {
        val executor = TestReplayExecutor(immediate = false)
        val store = InMemoryReplayChunkStore()
        val transport = MockTransport()
        val core = replayCore(store = store, transport = transport, executor = executor)
        executor.runAll()

        assertTrue(core.replayController.enqueue(replayEvents(1)))
        val lateWrite = executor.pending.removeLast()
        core.optOutTracking()
        core.optInTracking()
        executor.runAll()
        // The recorder thread resumes only now, after the purge and the opt-in.
        executor.pending.addLast(lateWrite)
        executor.runAll()

        assertTrue(store.names().isEmpty())
        assertTrue(transport.bytePosts.isEmpty())
    }
}
