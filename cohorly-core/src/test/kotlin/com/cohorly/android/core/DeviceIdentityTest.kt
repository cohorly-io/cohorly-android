package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    private fun deviceIdOf(transport: MockTransport, index: Int = 0): String =
        transport.trackedEvents()[index].getJSONObject("properties").getString("\$device_id")

    @Test
    fun `device id equals the anonymous distinct id on a fresh install`() {
        val core = makeCore()
        assertEquals(core.getDistinctId(), core.getDeviceId())
    }

    @Test
    fun `stamps device id on every event and it survives identify`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        val deviceId = core.getDeviceId()

        core.track("before")
        core.identify("user-42")
        core.track("after")
        core.flush()

        val events = transport.trackedEvents()
        assertEquals(2, events.size)
        assertEquals(deviceId, deviceIdOf(transport, 0))
        assertEquals(deviceId, deviceIdOf(transport, 1))
        // identify() does not change the device id.
        assertEquals(deviceId, core.getDeviceId())
    }

    @Test
    fun `user id is stamped only once identified`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)

        core.track("anon")
        core.identify("user-7")
        core.track("known")
        core.flush()

        val anon = transport.trackedEvents()[0].getJSONObject("properties")
        assertFalse(anon.has("\$user_id"))

        val known = transport.trackedEvents()[1].getJSONObject("properties")
        assertEquals("user-7", known.getString("\$user_id"))
    }

    @Test
    fun `device id persists across restarts`() {
        val storage = InMemoryStorage()
        val core1 = makeCore(storage = storage)
        val deviceId = core1.getDeviceId()

        val core2 = makeCore(storage = storage)
        assertEquals(deviceId, core2.getDeviceId())
    }

    @Test
    fun `migrating an already-identified install mints a fresh device id`() {
        val storage = InMemoryStorage()
        // Simulate an older install with an identity but no persisted device id.
        storage.set("cohorly.distinct_id", "user-legacy")
        storage.set("cohorly.anonymous", "0")

        val core = makeCore(storage = storage)
        assertFalse(core.isAnonymous())
        assertNotEquals(core.getDistinctId(), core.getDeviceId())
        assertTrue(core.getDeviceId().isNotEmpty())
    }

    @Test
    fun `caller-supplied device id wins over the stamped one`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)

        core.track("evt", mapOf("\$device_id" to "custom-device", "\$user_id" to "custom-user"))
        core.flush()

        val props = transport.trackedEvents()[0].getJSONObject("properties")
        assertEquals("custom-device", props.getString("\$device_id"))
        assertEquals("custom-user", props.getString("\$user_id"))
    }

    @Test
    fun `reset mints a new device id equal to the fresh anon id and persists it`() {
        val storage = InMemoryStorage()
        val core = makeCore(storage = storage)
        val before = core.getDeviceId()

        core.identify("user-42")
        core.reset()

        val after = core.getDeviceId()
        assertNotEquals(before, after)
        assertEquals(core.getDistinctId(), after)
        assertEquals(after, storage.get("cohorly.device_id"))
    }

    @Test
    fun `reset clears pending timed events`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("Checkout")
        core.reset()
        clock.advance(5_000)
        core.track("Checkout")
        core.flush()

        val props = transport.trackedEvents()[0].getJSONObject("properties")
        assertFalse(props.has("\$duration"))
    }
}
