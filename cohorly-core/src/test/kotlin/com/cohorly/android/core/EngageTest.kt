package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngageTest {
    @Test
    fun `people ops map to engage payloads with distinct_id and token`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)

        core.people.set(mapOf("name" to "Ada"))
        core.people.setOnce(mapOf("signup" to "2026-01-01"))
        core.people.increment(mapOf("logins" to 1))
        core.people.unset(listOf("legacy"))
        core.people.deleteUser()
        core.flush()

        val payloads = transport.engagePayloads()
        assertEquals(5, payloads.size)
        payloads.forEach {
            assertEquals(core.getDistinctId(), it.getString("distinct_id"))
            assertEquals("test-token", it.getString("token"))
        }
        assertEquals("Ada", payloads[0].getJSONObject("\$set").getString("name"))
        assertEquals("2026-01-01", payloads[1].getJSONObject("\$set_once").getString("signup"))
        assertEquals(1, payloads[2].getJSONObject("\$add").getInt("logins"))
        assertEquals("legacy", payloads[3].getJSONArray("\$unset").getString(0))
        assertTrue(payloads[4].getBoolean("\$delete"))
    }

    @Test
    fun `engage failure re-queues payloads for the next flush`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        transport.responder = { url, _ -> if (url.endsWith("/engage")) networkError() }
        core.people.set(mapOf("a" to 1))
        core.flush()
        assertEquals(1, core.pendingEngageCount())

        transport.responder = { _, _ -> }
        core.flush()
        assertEquals(0, core.pendingEngageCount())
        // Two attempts total (the failed one is recorded too), same payload.
        assertEquals(2, transport.postsTo("/engage").size)
        assertEquals(1, transport.engagePayloads().last().getJSONObject("\$set").getInt("a"))
    }

    @Test
    fun `engage queue persists across relaunch`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage).people.set(mapOf("a" to 1))

        val transport = MockTransport()
        val core2 = makeCore(storage = storage, transport = transport)
        assertEquals(1, core2.pendingEngageCount())
        core2.flush()
        assertEquals(1, transport.engagePayloads().size)
    }

    @Test
    fun `engage is skipped while a track flush fails and both survive`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        transport.responder = { url, _ -> if (url.endsWith("/track")) throw CohorlyHttpException(500) }
        core.track("evt")
        core.people.set(mapOf("a" to 1))
        core.flush()

        // /engage was never attempted; both queues intact.
        assertEquals(0, transport.postsTo("/engage").size)
        assertEquals(1, core.pendingEventCount())
        assertEquals(1, core.pendingEngageCount())

        transport.responder = { _, _ -> }
        core.flush()
        // Second flush delivers both (the first, failed /track attempt is
        // also recorded by the mock, hence 2 track posts total).
        assertEquals(2, transport.postsTo("/track").size)
        assertEquals(1, transport.postsTo("/engage").size)
        assertEquals(0, core.pendingEventCount())
        assertEquals(0, core.pendingEngageCount())
    }
}
