@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** VEL-2139: 30 min in foreground without activity (a tracked event or a recorded touch) ends the session. */
class ForegroundIdleRotationTest {
    private val minute = 60_000L

    @Test
    fun `the first event after 30 min in foreground without activity starts a new session`() {
        val clock = MockClock()
        val transport = MockTransport()
        val core = replayCore(clock = clock, transport = transport)
        val first = core.replayController.sessionId
        core.track("Opened")

        clock.advance(30 * minute)
        core.track("Back after 30 min")
        core.flush()

        val ids = transport.trackedEvents().map { it.props().getString("\$session_id") }
        assertEquals(first, ids[0])
        assertNotEquals(first, ids[1])
        assertEquals(core.replayController.sessionId, ids[1])
    }

    @Test
    fun `a recorded touch after 30 min in foreground without activity starts a new session too`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val first = core.replayController.sessionId
        val notified = mutableListOf<String?>()
        core.replayController.addSessionListener { notified.add(it) }

        clock.advance(30 * minute)
        core.replayController.recordActivity()

        val second = core.replayController.sessionId
        assertNotEquals(first, second)
        assertEquals(listOf(second), notified)
    }

    @Test
    fun `activity 1 ms short of 30 min never rotates`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val first = core.replayController.sessionId
        core.track("Opened")

        clock.advance(30 * minute - 1)
        core.replayController.recordActivity()
        clock.advance(30 * minute - 1)
        core.track("Still here")

        assertEquals(first, core.replayController.sessionId)
    }

    @Test
    fun `each activity pushes the deadline back`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val first = core.replayController.sessionId

        // Activity every 20 min for 2 hours: never 30 min idle.
        repeat(6) {
            clock.advance(20 * minute)
            core.replayController.recordActivity()
        }
        assertEquals(first, core.replayController.sessionId)

        clock.advance(30 * minute)
        core.replayController.recordActivity()
        assertNotEquals(first, core.replayController.sessionId)
    }

    @Test
    fun `an event tracked in background never rotates on idle, the foreground rule decides`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val first = core.replayController.sessionId

        core.onAppBackgrounded()
        clock.advance(20 * minute)
        core.track("Background sync")
        clock.advance(20 * minute)
        core.track("Background sync again")
        assertEquals(first, core.replayController.sessionId)
    }

    @Test
    fun `a session restored at launch never rotates on activity the persistence throttle kept off the disk`() {
        val state = InMemoryStorage()
        val clock = MockClock()
        val noon = clock.nowMs
        val firstLaunch = replayCore(state = state, clock = clock)
        val first = firstLaunch.replayController.sessionId
        firstLaunch.track("Persisted at 12:00")
        clock.advance(59_000)
        firstLaunch.track("Throttled at 12:00:59")

        // 30 min 30 s after the persisted activity, 29 min 31 s after the real one.
        clock.nowMs = noon + 30 * minute + 30_000
        val relaunch = replayCore(state = state, clock = clock)
        assertEquals(first, relaunch.replayController.sessionId)
        relaunch.track("First event of the new process")
        assertEquals(first, relaunch.replayController.sessionId)
    }

    @Test
    fun `activity handled out of time order never moves the last activity back`() {
        val clock = MockClock()
        val core = replayCore(clock = clock)
        val start = clock.nowMs
        val first = core.replayController.sessionId

        // A later activity is handled first, an earlier one after it.
        assertEquals(first, core.replayController.sessionIdForEvent(start + 29 * minute))
        assertEquals(first, core.replayController.sessionIdForEvent(start + minute))

        // 29 min after the later activity: still inside the 30 min.
        clock.nowMs = start + 58 * minute
        core.replayController.recordActivity()
        assertEquals(first, core.replayController.sessionId)
    }
}
