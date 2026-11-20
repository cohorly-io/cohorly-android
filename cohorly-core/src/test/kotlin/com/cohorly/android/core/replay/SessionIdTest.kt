@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.MockTransport
import com.cohorly.android.core.makeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SessionIdTest {
    @Test
    fun `sessionReplay is off by default and each rate is clamped at both ends`() {
        assertNull(CohorlyConfig().sessionReplay)
        assertTrue(SessionReplayConfig().maskAllImages)

        val high = SessionReplayConfig(sampleRate = 1.5, onErrorSampleRate = 7.0)
        assertEquals(1.0, high.sampleRate, 0.0)
        assertEquals(1.0, high.onErrorSampleRate, 0.0)
        val low = SessionReplayConfig(sampleRate = -0.2, onErrorSampleRate = -3.0)
        assertEquals(0.0, low.sampleRate, 0.0)
        assertEquals(0.0, low.onErrorSampleRate, 0.0)
        val nan = SessionReplayConfig(sampleRate = Double.NaN, onErrorSampleRate = Double.NaN)
        assertEquals(0.0, nan.sampleRate, 0.0)
        assertEquals(0.0, nan.onErrorSampleRate, 0.0)
        val inside = SessionReplayConfig(sampleRate = 0.25, onErrorSampleRate = 0.75)
        assertEquals(0.25, inside.sampleRate, 0.0)
        assertEquals(0.75, inside.onErrorSampleRate, 0.0)
    }

    @Test
    fun `every tracked event carries the session id even with both rates at zero`() {
        val transport = MockTransport()
        val core = replayCore(transport = transport, replay = SessionReplayConfig())

        core.track("Opened")
        core.track("Checkout", mapOf("plan" to "pro"))
        core.flush()

        val sessionId = core.replayController.sessionId
        val stamped = transport.trackedEvents().map { it.props().getString("\$session_id") }
        assertEquals(listOf(sessionId, sessionId), stamped)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
    }

    @Test
    fun `a session id passed by the caller wins`() {
        val transport = MockTransport()
        val core = replayCore(transport = transport)

        core.track("Opened", mapOf("\$session_id" to "from-the-app"))
        core.flush()

        assertEquals("from-the-app", transport.trackedEvents().single().props().getString("\$session_id"))
    }

    @Test
    fun `without sessionReplay no event has a session id and the replay stores are never touched`() {
        val transport = MockTransport()
        val storage = RecordingStorage()
        val state = RecordingStorage()
        val store = CountingChunkStore()
        val core = makeCore(
            storage = storage,
            transport = transport,
            replayChunkStore = store,
            replayStateStorage = state,
            replayExecutor = TestReplayExecutor(),
        )

        core.track("Opened")
        core.identify("user-1")
        core.onAppBackgrounded()
        core.onAppForegrounded()
        core.reset()
        core.optOutTracking()
        core.optInTracking()
        core.track("Again")
        core.flush()

        assertNull(core.replay)
        assertTrue(transport.trackedEvents().none { it.props().has("\$session_id") })
        assertEquals(emptyList<String>(), storage.replayKeysWritten())
        assertTrue(state.writtenKeys.isEmpty())
        assertEquals(0, store.calls)
        assertTrue(transport.bytePosts.isEmpty())
    }

    @Test
    fun `the session id is a random UUID v4 in its standard form`() {
        val first = replayCore().replayController.sessionId!!
        val second = replayCore().replayController.sessionId!!

        val parsed = UUID.fromString(first)
        assertEquals(4, parsed.version())
        assertEquals(parsed.toString(), first)
        assertNotEquals(first, second)
    }

    @Test
    fun `track stamps the session id from memory and leaves every disk write to the replay worker`() {
        val storage = RecordingStorage()
        val state = RecordingStorage()
        val store = CountingChunkStore()
        val executor = TestReplayExecutor(immediate = false)
        val core = replayCore(storage = storage, state = state, store = store, executor = executor)
        executor.runAll()
        state.writtenKeys.clear()
        val storeCallsBefore = store.calls

        core.track("Opened")

        assertEquals(emptyList<String>(), state.writtenKeys)
        assertEquals(storeCallsBefore, store.calls)
        executor.runAll()
        assertEquals(listOf(ReplayController.Keys.SESSION), state.writtenKeys)
        // Never in the SDK's own event file.
        assertEquals(emptyList<String>(), storage.replayKeysWritten())
    }

    @Test
    fun `enqueue returns at once and the chunk is written on the replay worker`() {
        val store = CountingChunkStore()
        val executor = TestReplayExecutor(immediate = false)
        val core = replayCore(store = store, executor = executor)
        executor.runAll()
        val storeCallsBefore = store.calls

        assertTrue(core.replayController.enqueue(replayEvents(2)))

        assertEquals(storeCallsBefore, store.calls)
        executor.runAll()
        assertTrue(store.calls > storeCallsBefore)
    }

    @Test
    fun `enqueue refuses an empty list and mode OFF`() {
        val core = replayCore()

        assertFalse(core.replayController.enqueue(emptyList()))
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.OFF))
    }
}
