package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptOutTest {
    @Test
    fun `optOut clears queues and drops subsequent tracking`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.track("before")
        core.people.set(mapOf("a" to 1))

        core.optOutTracking()
        assertTrue(core.hasOptedOutTracking())
        assertEquals(0, core.pendingEventCount())
        assertEquals(0, core.pendingEngageCount())

        core.track("after")
        core.people.set(mapOf("b" to 2))
        core.identify("user-1")
        core.flush()
        assertEquals(0, transport.posts.size)
    }

    @Test
    fun `optOut state persists across relaunch`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage).optOutTracking()
        val core2 = makeCore(storage = storage)
        assertTrue(core2.hasOptedOutTracking())
    }

    @Test
    fun `optIn resumes tracking`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.optOutTracking()
        core.optInTracking()
        assertFalse(core.hasOptedOutTracking())
        core.track("evt")
        core.flush()
        assertEquals(1, transport.trackedEvents().size)
    }
}
