package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryContractTest {
    private fun failWith(status: Int, retryAfterMs: Long? = null): (String, String) -> Unit =
        { url, _ -> if (url.endsWith("/track")) throw CohorlyHttpException(status, retryAfterMs) }

    @Test
    fun `5xx keeps queue and backs off exponentially with doubling`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(500)

        core.track("evt")
        core.flush()
        assertEquals(1, core.pendingEventCount()) // re-queued
        assertEquals(clock.nowMs + 2_000, core.backoffUntilMs) // base 2s, jitter 0

        // Second consecutive failure doubles the delay.
        core.flush()
        assertEquals(clock.nowMs + 4_000, core.backoffUntilMs)

        // Third: 8s.
        core.flush()
        assertEquals(clock.nowMs + 8_000, core.backoffUntilMs)
    }

    @Test
    fun `backoff is capped at maxRetryDelayMs`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(
            transport = transport,
            clock = clock,
            config = CohorlyConfig(flushIntervalMs = 0, maxRetryDelayMs = 5_000),
        )
        transport.responder = failWith(500)
        core.track("evt")
        repeat(6) { core.flush() } // 2s, 4s, then capped
        assertEquals(clock.nowMs + 5_000, core.backoffUntilMs)
    }

    @Test
    fun `jitter stays within plus or minus 20 percent`() {
        for (jitterValue in listOf(-1.0, 1.0)) {
            val transport = MockTransport()
            val clock = MockClock()
            val core = makeCore(
                transport = transport,
                clock = clock,
                jitter = { jitterValue },
            )
            transport.responder = failWith(500)
            core.track("evt")
            core.flush()
            val delay = core.backoffUntilMs - clock.nowMs
            assertEquals((2_000 + 2_000 * 0.2 * jitterValue).toLong(), delay)
        }
    }

    @Test
    fun `maybeFlush is skipped during backoff and resumes after the deadline`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(500)
        core.track("evt")
        core.maybeFlush()
        assertEquals(1, transport.trackBatches().size)

        // Within backoff: no attempt.
        clock.advance(1_000)
        core.maybeFlush()
        assertEquals(1, transport.trackBatches().size)

        // Past the deadline: retried with the same payload.
        transport.responder = { _, _ -> }
        clock.advance(2_000)
        core.maybeFlush()
        assertEquals(2, transport.trackBatches().size)
        assertEquals("evt", transport.trackBatches()[1].getJSONObject(0).getString("event"))
        assertEquals(0, core.pendingEventCount())
    }

    @Test
    fun `forced flush bypasses backoff`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(500)
        core.track("evt")
        core.flush()
        assertEquals(1, transport.trackBatches().size)

        core.flush() // still within backoff, but forced
        assertEquals(2, transport.trackBatches().size)
    }

    @Test
    fun `429 honors Retry-After capped at maxRetryDelayMs`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(429, retryAfterMs = 7_000)
        core.track("evt")
        core.flush()
        assertEquals(1, core.pendingEventCount())
        assertEquals(clock.nowMs + 7_000, core.backoffUntilMs)

        // Retry-After above the cap (e.g. quota 3600s) is clamped.
        transport.responder = failWith(429, retryAfterMs = 3_600_000)
        core.flush()
        assertEquals(clock.nowMs + 600_000, core.backoffUntilMs)
    }

    @Test
    fun `400 drops the batch permanently without backoff`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(400)
        core.track("evt")
        core.flush()
        assertEquals(0, core.pendingEventCount())
        assertEquals(0L, core.backoffUntilMs)

        // Next events flow normally.
        transport.responder = { _, _ -> }
        core.track("next")
        core.flush()
        assertEquals("next", transport.trackBatches()[1].getJSONObject(0).getString("event"))
    }

    @Test
    fun `413 halves effective batch size down to floor 1`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 20),
        )
        transport.responder = failWith(413)
        repeat(20) { core.track("evt") }

        core.flush()
        assertEquals(20, core.pendingEventCount()) // re-queued
        assertEquals(10, core.effectiveBatchSize)
        assertEquals(0L, core.backoffUntilMs) // no backoff on 413

        core.flush()
        assertEquals(5, core.effectiveBatchSize)
        core.flush()
        assertEquals(2, core.effectiveBatchSize)
        core.flush()
        assertEquals(1, core.effectiveBatchSize)

        // Batches shrink accordingly.
        assertEquals(10, transport.trackBatches()[1].length())
        assertEquals(5, transport.trackBatches()[2].length())
    }

    @Test
    fun `a single event that alone 413s is dropped to avoid livelock`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, batchSize = 1),
        )
        transport.responder = failWith(413)
        core.track("giant")
        core.track("normal")
        core.flush()
        // "giant" dropped, "normal" still queued.
        assertEquals(1, core.pendingEventCount())

        transport.responder = { _, _ -> }
        core.flush()
        assertEquals("normal", transport.trackBatches().last().getJSONObject(0).getString("event"))
    }

    @Test
    fun `401 keeps the queue and backs off at max delay`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(401)
        core.track("evt")
        core.flush()
        assertEquals(1, core.pendingEventCount())
        assertEquals(clock.nowMs + 600_000, core.backoffUntilMs)
    }

    @Test
    fun `network error keeps queue and backs off like 5xx`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = { url, _ -> if (url.endsWith("/track")) networkError() }
        core.track("evt")
        core.flush()
        assertEquals(1, core.pendingEventCount())
        assertEquals(clock.nowMs + 2_000, core.backoffUntilMs)
    }

    @Test
    fun `success resets consecutive failures and backoff`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = makeCore(transport = transport, clock = clock)
        transport.responder = failWith(500)
        core.track("evt")
        core.flush()
        core.flush()
        assertEquals(clock.nowMs + 4_000, core.backoffUntilMs)

        transport.responder = { _, _ -> }
        core.flush()
        assertEquals(0L, core.backoffUntilMs)

        // A new failure starts the ladder from the base again.
        transport.responder = failWith(500)
        core.track("evt2")
        core.flush()
        assertEquals(clock.nowMs + 2_000, core.backoffUntilMs)
    }

    @Test
    fun `failed batch is retried in original order ahead of newer events`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport)
        transport.responder = failWith(500)
        core.track("a")
        core.flush()
        core.track("b")

        transport.responder = { _, _ -> }
        core.flush()
        val batch = transport.trackBatches().last()
        assertEquals("a", batch.getJSONObject(0).getString("event"))
        assertEquals("b", batch.getJSONObject(1).getString("event"))
    }

    @Test
    fun `real jitter stays within bounds`() {
        // Sanity check the default jitter source shape via repeated runs.
        repeat(50) {
            val transport = MockTransport()
            val clock = MockClock()
            val core = CohorlyCore(
                apiHost = "http://localhost:4000",
                token = null,
                storage = InMemoryStorage(),
                transport = transport,
                deviceInfo = StubDeviceInfo(),
                clock = clock,
                config = CohorlyConfig(flushIntervalMs = 0),
            )
            transport.responder = failWith(500)
            core.track("evt")
            core.flush()
            val delay = core.backoffUntilMs - clock.nowMs
            assertTrue("delay $delay out of range", delay in 1_600..2_400)
        }
    }
}
