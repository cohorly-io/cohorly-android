package com.cohorly.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePersistenceTest {
    private val queueKey = "cohorly.event_queue"

    @Test
    fun `queued events survive a relaunch`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage).track("persisted")

        val transport = MockTransport()
        val core2 = makeCore(storage = storage, transport = transport)
        assertEquals(1, core2.pendingEventCount())
        core2.flush()
        assertEquals("persisted", transport.trackedEvents()[0].getString("event"))
    }

    @Test
    fun `queue is capped at 1000 dropping the oldest`() {
        val core = makeCore()
        repeat(1_005) { core.track("evt-$it") }
        assertEquals(1_000, core.pendingEventCount())

        val transport = MockTransport()
        val core2 = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 500),
        )
        repeat(1_005) { core2.track("evt-$it") }
        core2.flush()
        // Oldest five dropped: first surviving event is evt-5.
        assertEquals("evt-5", transport.trackBatches()[0].getJSONObject(0).getString("event"))
    }

    @Test
    fun `cap is enforced on load of an oversized persisted queue`() {
        val storage = InMemoryStorage()
        val oversized = JSONArray()
        repeat(1_200) {
            oversized.put(
                JSONObject().put("event", "evt-$it").put("properties", JSONObject()),
            )
        }
        storage.set(queueKey, oversized.toString())

        val core = makeCore(storage = storage)
        assertEquals(1_000, core.pendingEventCount())
    }

    @Test
    fun `corrupt persisted queue is discarded without crashing`() {
        val storage = InMemoryStorage()
        storage.set(queueKey, "{not json]")
        val core = makeCore(storage = storage)
        assertEquals(0, core.pendingEventCount())
    }

    @Test
    fun `custom maxQueueSize is respected`() {
        val core = makeCore(
            config = CohorlyConfig(flushIntervalMs = 0, maxQueueSize = 3),
        )
        repeat(5) { core.track("evt-$it") }
        assertEquals(3, core.pendingEventCount())
    }
}
