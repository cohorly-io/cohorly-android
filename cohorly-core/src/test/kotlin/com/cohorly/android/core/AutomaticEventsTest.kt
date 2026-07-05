package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticEventsTest {
    private fun aeConfig() = CohorlyConfig(flushIntervalMs = 0, trackAutomaticEvents = true)

    @Test
    fun `first open fires once ever and sets first open date profile prop`() {
        val storage = InMemoryStorage()
        val transport = MockTransport()
        val core = makeCore(storage = storage, transport = transport, config = aeConfig())
        core.flush()

        val events = transport.trackedEvents()
        assertEquals(1, events.count { it.getString("event") == "\$ae_first_open" })
        val engage = transport.engagePayloads()
        assertEquals(1, engage.size)
        assertTrue(engage[0].getJSONObject("\$set_once").has("\$ae_first_app_open_date"))

        // Relaunch: no second $ae_first_open.
        val transport2 = MockTransport()
        val core2 = makeCore(storage = storage, transport = transport2, config = aeConfig())
        core2.flush()
        assertEquals(
            0,
            transport2.trackedEvents().count { it.getString("event") == "\$ae_first_open" },
        )
    }

    @Test
    fun `updated fires on app version change with the new version`() {
        val storage = InMemoryStorage()
        makeCore(
            storage = storage,
            config = aeConfig(),
            deviceInfo = StubDeviceInfo(version = "1.0.0"),
        )

        val transport = MockTransport()
        val core2 = makeCore(
            storage = storage,
            transport = transport,
            config = aeConfig(),
            deviceInfo = StubDeviceInfo(version = "2.0.0"),
        )
        core2.flush()
        val updated = transport.trackedEvents().filter { it.getString("event") == "\$ae_updated" }
        assertEquals(1, updated.size)
        assertEquals(
            "2.0.0",
            updated[0].getJSONObject("properties").getString("\$ae_updated_version"),
        )

        // Same version relaunch: no $ae_updated.
        val transport3 = MockTransport()
        val core3 = makeCore(
            storage = storage,
            transport = transport3,
            config = aeConfig(),
            deviceInfo = StubDeviceInfo(version = "2.0.0"),
        )
        core3.flush()
        assertEquals(
            0,
            transport3.trackedEvents().count { it.getString("event") == "\$ae_updated" },
        )
    }

    @Test
    fun `fresh install does not fire updated`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = aeConfig(),
            deviceInfo = StubDeviceInfo(version = "1.0.0"),
        )
        core.flush()
        assertEquals(
            0,
            transport.trackedEvents().count { it.getString("event") == "\$ae_updated" },
        )
    }

    @Test
    fun `session shorter than 10 seconds does not fire ae_session`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock, config = aeConfig())

        core.onAppForegrounded()
        clock.advance(9_999)
        core.onAppBackgrounded()
        core.flush()
        assertEquals(
            0,
            transport.trackedEvents().count { it.getString("event") == "\$ae_session" },
        )
    }

    @Test
    fun `session of 10s or more fires ae_session with length and profile increment`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock, config = aeConfig())

        core.onAppForegrounded()
        clock.advance(72_500)
        core.onAppBackgrounded()
        core.flush()

        val sessions = transport.trackedEvents().filter { it.getString("event") == "\$ae_session" }
        assertEquals(1, sessions.size)
        assertEquals(
            72,
            sessions[0].getJSONObject("properties").getInt("\$ae_session_length"),
        )
        val increments = transport.engagePayloads().filter { it.has("\$add") }
        assertEquals(1, increments.size)
        assertEquals(1, increments[0].getJSONObject("\$add").getInt("\$ae_total_app_sessions"))
    }

    @Test
    fun `initial session starts at init without an explicit foreground`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock, config = aeConfig())
        clock.advance(30_000)
        core.onAppBackgrounded()
        core.flush()
        val sessions = transport.trackedEvents().filter { it.getString("event") == "\$ae_session" }
        assertEquals(1, sessions.size)
        assertEquals(30, sessions[0].getJSONObject("properties").getInt("\$ae_session_length"))
    }

    @Test
    fun `double background does not fire twice`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock, config = aeConfig())
        clock.advance(20_000)
        core.onAppBackgrounded()
        core.onAppBackgrounded()
        core.flush()
        assertEquals(
            1,
            transport.trackedEvents().count { it.getString("event") == "\$ae_session" },
        )
    }

    @Test
    fun `foreground resets the session start`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock, config = aeConfig())
        clock.advance(50_000)
        core.onAppBackgrounded() // 50s session
        core.onAppForegrounded()
        clock.advance(12_000)
        core.onAppBackgrounded() // 12s session
        core.flush()
        val lengths = transport.trackedEvents()
            .filter { it.getString("event") == "\$ae_session" }
            .map { it.getJSONObject("properties").getInt("\$ae_session_length") }
        assertEquals(listOf(50, 12), lengths)
    }

    @Test
    fun `automatic events disabled by default`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        clock.advance(60_000)
        core.onAppBackgrounded()
        core.flush()
        assertEquals(0, transport.trackedEvents().size)
    }
}
