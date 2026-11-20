@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import com.cohorly.android.core.makeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ReplayThreadingTest {
    @Test
    fun `a buffer captured in one session is refused once the session is two rotations back`() {
        val transport = MockTransport()
        val core = replayCore(transport = transport)
        val captured = core.replayController.sessionId

        // One rotation still accepts it for a while (PreviousSessionChunkTest); two never do.
        core.reset()
        core.reset()

        assertFalse(core.replayController.enqueue(replayEvents(1), sessionId = captured))
        assertTrue(transport.bytePosts.isEmpty())
        val current = core.replayController.sessionId
        assertTrue(core.replayController.enqueue(replayEvents(1), sessionId = current))
        assertEquals(current, transport.replayChunks().single().getString("session_id"))
    }

    @Test
    fun `a chunk always pairs the session with the identity of the same moment`() {
        val transport = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }
        val clock = MockClock()
        val core = replayCore(transport = transport, clock = clock)
        val firstSession = core.replayController.sessionId
        core.identify("user-1")
        core.replayController.enqueue(replayEvents(1))
        clock.advance(1)
        core.reset()
        val secondSession = core.replayController.sessionId
        val secondId = core.getDistinctId()
        core.replayController.enqueue(replayEvents(1))
        transport.bytesResponder = { _, _ -> }
        transport.bytePosts.clear()

        core.replayController.flush()

        val sent = transport.replayChunks().map { it.getString("session_id") to it.getString("distinct_id") }
        assertEquals(listOf(firstSession to "user-1", secondSession to secondId), sent)
    }

    @Test
    fun `session listeners are called in order on the state thread while an upload is stuck`() {
        val posting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = MockTransport().apply {
            bytesResponder = { _, _ ->
                posting.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, sessionReplay = SessionReplayConfig(sampleRate = 1.0)),
            replayExecutor = ThreadReplayExecutor(ThreadReplayExecutor.STATE_THREAD),
            replayUploadExecutor = ThreadReplayExecutor(ThreadReplayExecutor.UPLOAD_THREAD),
        )
        try {
            core.replayController.enqueue(replayEvents(1))
            assertTrue("the upload never started", posting.await(5, TimeUnit.SECONDS))
            val heard = LinkedBlockingQueue<Pair<String?, String>>()
            core.replayController.addSessionListener { heard.add(it to Thread.currentThread().name) }

            core.reset()
            val afterFirst = core.replayController.sessionId
            core.reset()
            val afterSecond = core.replayController.sessionId

            val first = heard.poll(5, TimeUnit.SECONDS)
            val second = heard.poll(5, TimeUnit.SECONDS)
            assertEquals(afterFirst to ThreadReplayExecutor.STATE_THREAD, first)
            assertEquals(afterSecond to ThreadReplayExecutor.STATE_THREAD, second)
            // Heard while the POST was still blocked.
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
        }
    }
}
