package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    @Test
    fun `stamps device defaults, reserved props and token`() {
        val transport = MockTransport()
        val clock = MockClock(1_700_000_000_000)
        val core = makeCore(transport = transport, clock = clock)

        core.track("Signed Up", mapOf("plan" to "pro"))
        core.flush()

        val events = transport.trackedEvents()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("Signed Up", event.getString("event"))
        val props = event.getJSONObject("properties")

        // Device defaults from the provider.
        assertEquals("Android", props.getString("\$os"))
        assertEquals("14", props.getString("\$os_version"))
        assertEquals("Google", props.getString("\$manufacturer"))
        assertEquals("google", props.getString("\$brand"))
        assertEquals("Pixel 8", props.getString("\$model"))
        assertEquals(420, props.getInt("\$screen_dpi"))
        assertEquals(2400, props.getInt("\$screen_height"))
        assertEquals(1080, props.getInt("\$screen_width"))
        assertEquals("0.1.0", props.getString("\$lib_version"))

        // Reserved props.
        assertEquals(core.getDistinctId(), props.getString("distinct_id"))
        assertEquals(1_700_000_000_000, props.getLong("time"))
        assertTrue(props.getString("\$insert_id").isNotEmpty())
        assertEquals("android", props.getString("\$lib"))
        assertEquals("test-token", props.getString("token"))

        // Custom prop.
        assertEquals("pro", props.getString("plan"))
    }

    @Test
    fun `unique insert_id per event`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.track("a")
        core.track("a")
        core.flush()
        val events = transport.trackedEvents()
        assertNotEquals(
            events[0].getJSONObject("properties").getString("\$insert_id"),
            events[1].getJSONObject("properties").getString("\$insert_id"),
        )
    }

    @Test
    fun `explicit props override super props which override defaults`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.registerSuperProperties(mapOf("\$os" to "SuperOS", "source" to "super"))
        core.track("evt", mapOf("source" to "explicit"))
        core.flush()

        val props = transport.trackedEvents()[0].getJSONObject("properties")
        assertEquals("SuperOS", props.getString("\$os")) // super beats default
        assertEquals("explicit", props.getString("source")) // explicit beats super
    }

    @Test
    fun `reserved props cannot be overridden by caller`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.track(
            "evt",
            mapOf("distinct_id" to "spoof", "\$lib" to "spoof", "token" to "spoof", "time" to 1),
        )
        core.flush()

        val props = transport.trackedEvents()[0].getJSONObject("properties")
        assertEquals(core.getDistinctId(), props.getString("distinct_id"))
        assertEquals("android", props.getString("\$lib"))
        assertEquals("test-token", props.getString("token"))
        assertNotEquals(1L, props.getLong("time"))
    }

    @Test
    fun `no token stamped when token is null`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, token = null)
        core.track("evt")
        core.flush()
        assertFalse(transport.trackedEvents()[0].getJSONObject("properties").has("token"))
    }

    @Test
    fun `JSONObject overload merges like map overload`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        core.track("evt", org.json.JSONObject().put("plan", "pro"))
        core.flush()
        assertEquals(
            "pro",
            transport.trackedEvents()[0].getJSONObject("properties").getString("plan"),
        )
    }
}
