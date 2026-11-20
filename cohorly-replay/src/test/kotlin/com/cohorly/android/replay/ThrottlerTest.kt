package com.cohorly.android.replay

import com.cohorly.android.replay.internal.Throttler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottlerTest {
    /** A fake main handler on a fake uptime clock: posted tasks run when [advance] reaches their time. */
    private class FakeTime {
        var nowMs = 0L
        private val posted = mutableListOf<Pair<Long, Runnable>>()

        fun post(task: Runnable, delayMs: Long) {
            posted.add(nowMs + delayMs to task)
        }

        fun uptimeMillis(): Long = nowMs

        fun advance(ms: Long) {
            val end = nowMs + ms
            while (true) {
                val next = posted.filter { it.first <= end }.minByOrNull { it.first } ?: break
                posted.remove(next)
                nowMs = next.first
                next.second.run()
            }
            nowMs = end
        }
    }

    private val time = FakeTime()

    /** What the screen shows: each draw changes it, each capture reads it. */
    private var screenState = 0
    private val runs = mutableListOf<Pair<Long, Int>>()
    private val throttler = Throttler(time::post, time::uptimeMillis, 1_000)
    private val capture = Runnable { runs.add(time.nowMs to screenState) }

    private fun draw() {
        screenState++
        throttler.throttle(capture)
    }

    @Test
    fun `the first draw captures at once`() {
        draw()
        assertEquals(listOf(0L to 1), runs)
    }

    @Test
    fun `a burst ending at 2,9 s captures every second and its final state at 3 s, then nothing`() {
        // A draw every 100 ms from 0 to 2.9 s.
        repeat(30) {
            draw()
            time.advance(100)
        }
        time.advance(10_000)

        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), runs.map { it.first })
        assertTrue(runs.zipWithNext().all { (a, b) -> b.first - a.first >= 1_000 })
        // The capture at 3 s saw the last draw (the 30th, at 2.9 s).
        assertEquals(30, runs.last().second)
    }

    @Test
    fun `a draw 999 ms after a capture waits for the full second`() {
        draw()
        time.advance(999)
        draw()
        assertEquals(1, runs.size)

        time.advance(0)
        assertEquals(1, runs.size)
        time.advance(1)
        assertEquals(listOf(0L, 1_000L), runs.map { it.first })
    }

    @Test
    fun `a handler that fires early is put back to the deadline`() {
        val early = mutableListOf<Runnable>()
        val throttler = Throttler({ task, _ -> early.add(task) }, time::uptimeMillis, 1_000)
        throttler.throttle(capture)
        time.nowMs = 300
        throttler.throttle(capture)

        // Fired 500 ms before its time: it runs nothing and posts itself again.
        time.nowMs = 500
        early.removeAt(0).run()
        assertEquals(1, runs.size)
        time.nowMs = 1_000
        early.removeAt(0).run()
        assertEquals(listOf(0L, 1_000L), runs.map { it.first })
    }

    @Test
    fun `no draw, no capture`() {
        draw()
        time.advance(60_000)
        assertEquals(1, runs.size)
    }
}
