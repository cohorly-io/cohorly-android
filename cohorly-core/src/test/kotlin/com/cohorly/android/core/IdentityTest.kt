package com.cohorly.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    @Test
    fun `starts with a persistent anonymous uuid`() {
        val storage = InMemoryStorage()
        val core1 = makeCore(storage = storage)
        val id = core1.getDistinctId()
        assertTrue(core1.isAnonymous())
        assertTrue(id.isNotEmpty())

        val core2 = makeCore(storage = storage)
        assertEquals(id, core2.getDistinctId())
        assertTrue(core2.isAnonymous())
    }

    @Test
    fun `identify while anonymous sends alias linking the anonymous id`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        val anonId = core.getDistinctId()

        core.identify("user-42")

        val aliases = transport.postsTo("/alias")
        assertEquals(1, aliases.size)
        val body = JSONObject(aliases[0].body)
        assertEquals(anonId, body.getString("alias"))
        assertEquals("user-42", body.getString("distinct_id"))
        assertEquals("test-token", body.getString("token"))

        assertEquals("user-42", core.getDistinctId())
        assertFalse(core.isAnonymous())

        // Subsequent events carry the new id.
        core.track("evt")
        core.flush()
        assertEquals(
            "user-42",
            transport.trackedEvents()[0].getJSONObject("properties").getString("distinct_id"),
        )
    }

    @Test
    fun `identify when already identified does not alias`() {
        val transport = MockTransport()
        val storage = InMemoryStorage()
        val core = makeCore(storage = storage, transport = transport)
        core.identify("user-1")
        core.identify("user-2")
        assertEquals(1, transport.postsTo("/alias").size)
        assertEquals("user-2", core.getDistinctId())

        // Identified state persists across restarts.
        val core2 = makeCore(storage = storage, transport = transport)
        assertFalse(core2.isAnonymous())
        core2.identify("user-3")
        assertEquals(1, transport.postsTo("/alias").size)
    }

    @Test
    fun `identify with same id is a no-op`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.identify(core.getDistinctId())
        assertEquals(0, transport.posts.size)
        assertTrue(core.isAnonymous())
    }

    @Test
    fun `alias failure still switches identity`() {
        val transport = MockTransport()
        transport.responder = { _, _ -> networkError() }
        val core = makeCore(transport = transport)
        core.identify("user-9")
        assertEquals("user-9", core.getDistinctId())
        assertFalse(core.isAnonymous())
    }

    @Test
    fun `reset assigns fresh anonymous id and clears state`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.registerSuperProperties(mapOf("plan" to "pro"))
        core.identify("user-42")
        core.track("queued-before-reset")

        val before = core.getDistinctId()
        core.reset()

        assertNotEquals(before, core.getDistinctId())
        assertTrue(core.isAnonymous())
        assertEquals(0, core.getSuperProperties().length())
        assertEquals(0, core.pendingEventCount())

        core.flush()
        assertEquals(0, transport.trackedEvents().size)
    }
}
