package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimedEventsTest {
    @Test
    fun `timeEvent attaches duration seconds to the next matching track and clears`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("Checkout")
        clock.advance(2_500)
        core.track("Checkout")
        core.flush()

        val events = transport.trackedEvents().filter { it.getString("event") == "Checkout" }
        assertEquals(1, events.size)
        assertEquals(2.5, events[0].getJSONObject("properties").getDouble("\$duration"), 0.0)

        // Timer cleared: a second track of the same event has no $duration.
        val transport2 = MockTransport()
        val core2 = makeCore(transport = transport2, clock = clock, token = null)
        core2.timeEvent("Checkout")
        clock.advance(1_000)
        core2.track("Checkout")
        core2.track("Checkout")
        core2.flush()
        val checkouts = transport2.trackedEvents().filter { it.getString("event") == "Checkout" }
        assertEquals(2, checkouts.size)
        assertEquals(1.0, checkouts[0].getJSONObject("properties").getDouble("\$duration"), 0.0)
        assertFalse(checkouts[1].getJSONObject("properties").has("\$duration"))
    }

    @Test
    fun `duration rounds to three decimals`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("Load")
        clock.advance(1_234) // 1.234s
        core.track("Load")
        core.flush()

        val props = transport.trackedEvents().first { it.getString("event") == "Load" }
            .getJSONObject("properties")
        assertEquals(1.234, props.getDouble("\$duration"), 0.0)
    }

    @Test
    fun `clearTimedEvent cancels a pending timer`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("Flow")
        clock.advance(5_000)
        core.clearTimedEvent("Flow")
        core.track("Flow")
        core.flush()

        val props = transport.trackedEvents().first { it.getString("event") == "Flow" }
            .getJSONObject("properties")
        assertFalse(props.has("\$duration"))
    }

    @Test
    fun `clearTimedEvents cancels all pending timers`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("A")
        core.timeEvent("B")
        clock.advance(3_000)
        core.clearTimedEvents()
        core.track("A")
        core.track("B")
        core.flush()

        transport.trackedEvents().forEach {
            assertFalse(it.getJSONObject("properties").has("\$duration"))
        }
    }

    @Test
    fun `timer only applies to the matching event name`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)

        core.timeEvent("Target")
        clock.advance(4_000)
        core.track("Other")
        core.track("Target")
        core.flush()

        val other = transport.trackedEvents().first { it.getString("event") == "Other" }
        assertFalse(other.getJSONObject("properties").has("\$duration"))
        val target = transport.trackedEvents().first { it.getString("event") == "Target" }
        assertEquals(4.0, target.getJSONObject("properties").getDouble("\$duration"), 0.0)
    }
}
