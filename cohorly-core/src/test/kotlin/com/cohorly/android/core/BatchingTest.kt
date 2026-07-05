package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchingTest {
    @Test
    fun `flush sends at most batchSize events per request`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 20),
        )
        repeat(45) { core.track("evt-$it") }

        core.flush()
        assertEquals(1, transport.trackBatches().size)
        assertEquals(20, transport.trackBatches()[0].length())

        core.flush()
        core.flush()
        assertEquals(3, transport.trackBatches().size)
        assertEquals(20, transport.trackBatches()[1].length())
        assertEquals(5, transport.trackBatches()[2].length())
        assertEquals(0, core.pendingEventCount())
    }

    @Test
    fun `batch size is capped at the server max of 500`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 9_999, maxQueueSize = 1_000),
        )
        repeat(600) { core.track("evt") }
        core.flush()
        assertEquals(500, transport.trackBatches()[0].length())
    }

    @Test
    fun `oldest events are sent first`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 2),
        )
        core.track("first")
        core.track("second")
        core.track("third")
        core.flush()
        val batch = transport.trackBatches()[0]
        assertEquals("first", batch.getJSONObject(0).getString("event"))
        assertEquals("second", batch.getJSONObject(1).getString("event"))
    }

    @Test
    fun `flush with empty queue does not post`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.flush()
        assertEquals(0, transport.posts.size)
    }
}
