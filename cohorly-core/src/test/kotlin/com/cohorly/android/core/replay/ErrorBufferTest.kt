@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.MockClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VEL-2151: the error-mode buffer, with the web SDK's cut rules. */
class ErrorBufferTest {
    private val clock = MockClock(nowMs = 10_000_000L)
    private val session = "session-1"

    private fun meta(at: Long = clock.nowMs, tag: String = "") =
        JSONObject().put("type", 4).put("timestamp", at).put("data", JSONObject().put("href", tag))

    private fun full(at: Long = clock.nowMs, tag: String = "") =
        JSONObject().put("type", 2).put("timestamp", at).put("data", JSONObject().put("tag", tag))

    private fun mutation(at: Long = clock.nowMs, tag: String = "") =
        JSONObject().put("type", 3).put("timestamp", at).put("data", JSONObject().put("source", 0).put("tag", tag))

    /** A Meta + FullSnapshot group tagged [tag], like a capture. */
    private fun ErrorBuffer.snapshot(tag: String) = add(session, listOf(meta(tag = tag), full(tag = tag)))

    private fun ErrorBuffer.activity(tag: String) = add(session, listOf(mutation(tag = tag)))

    /** The cut's events without the closing error marker (its own test checks it). */
    private fun List<JSONObject>.tags(): List<String> = filter { it.getInt("type") != ErrorBuffer.CUSTOM }.map {
        val data = it.getJSONObject("data")
        "${it.getInt("type")}:${data.optString("href").ifEmpty { data.optString("tag") }}"
    }

    @Test
    fun `a cut starts at the newest snapshot at least 30 s old and runs to the error`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("s0")
        clock.advance(5_000)
        buffer.activity("m0")
        clock.advance(25_000)
        buffer.snapshot("s30")
        clock.advance(5_000)
        buffer.activity("m35")
        clock.advance(25_000)
        buffer.snapshot("s60")
        clock.advance(10_000)
        buffer.activity("m70")
        // Error at 70 s: s30 began 40 s ago (the newest >= 30 s old) and is usable.
        val cut = buffer.cut(session)!!

        assertEquals(listOf("4:s30", "2:s30", "3:m35", "4:s60", "2:s60", "3:m70"), cut.events.tags())
        assertEquals(session, cut.sessionId)
    }

    @Test
    fun `a cut ends with an error marker at the moment of the error`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("s0")
        clock.advance(1_000)
        buffer.activity("m1")
        clock.advance(2_500)
        val errorAt = clock.nowMs

        val last = buffer.cut(session)!!.events.last()

        assertEquals(ErrorBuffer.CUSTOM, last.getInt("type"))
        assertEquals(errorAt, last.getLong("timestamp"))
        assertEquals(ErrorBuffer.ERROR_TAG, last.getJSONObject("data").getString("tag"))
    }

    @Test
    fun `an idle snapshot older than 60 s starts the cut, stamped at the cutoff`() {
        val buffer = ErrorBuffer(clock)
        val snapshotAt = clock.nowMs
        buffer.snapshot("idle")
        clock.advance(90_000)
        buffer.activity("tap")
        val errorAt = clock.nowMs

        val cut = buffer.cut(session)!!

        assertEquals(listOf("4:idle", "2:idle", "3:tap"), cut.events.tags())
        val cutoff = errorAt - 60_000
        assertTrue(snapshotAt < cutoff)
        assertEquals(listOf(cutoff, cutoff, errorAt, errorAt), cut.events.map { it.getLong("timestamp") })
    }

    @Test
    fun `activity stamped before the cutoff that arrives late is left out of an idle start`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("idle")
        clock.advance(90_000)
        val errorAt = clock.nowMs
        buffer.activity("tap")
        // Delivered after the tap but stamped 65 s before the error.
        buffer.add(session, listOf(mutation(at = errorAt - 65_000, tag = "late")))

        val cut = buffer.cut(session)!!

        assertEquals(listOf("4:idle", "2:idle", "3:tap"), cut.events.tags())
    }

    @Test
    fun `no activity older than 60 s is ever sent`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("old")
        clock.advance(1_000)
        buffer.activity("too-old") // 89 s before the error
        clock.advance(50_000)
        buffer.snapshot("mid") // 39 s before the error
        clock.advance(39_000)

        val cut = buffer.cut(session)!!
        val errorAt = clock.nowMs

        assertEquals(listOf("4:mid", "2:mid"), cut.events.tags())
        assertTrue(cut.events.all { it.getLong("timestamp") >= errorAt - 60_000 })
    }

    @Test
    fun `an unusable old segment falls back to the oldest newer than 30 s`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("old")
        clock.advance(1_000)
        buffer.activity("busy") // 79 s before the error: "old" is not usable
        clock.advance(60_000)
        buffer.snapshot("recent") // 19 s before the error
        clock.advance(19_000)

        val cut = buffer.cut(session)!!

        assertEquals(listOf("4:recent", "2:recent"), cut.events.tags())
    }

    @Test
    fun `no usable segment means no cut, and a checkout is asked for`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("old")
        clock.advance(1_000)
        buffer.activity("busy")
        clock.advance(80_000) // the only segment has activity 80 s old

        assertNull(buffer.cut(session))
        assertTrue(buffer.checkoutDue())
        // Not cleared: a fresh snapshot then makes a cut possible.
        buffer.snapshot("fresh")
        assertEquals(listOf("4:fresh", "2:fresh"), buffer.cut(session)!!.events.tags())
    }

    @Test
    fun `a cut covers at least 30 s when the recording is that old`() {
        val buffer = ErrorBuffer(clock)
        val start = clock.nowMs
        buffer.snapshot("a")
        repeat(9) {
            clock.advance(5_000)
            buffer.activity("m$it")
        }
        clock.advance(5_000) // 50 s of recording, one segment
        val cut = buffer.cut(session)!!

        val first = cut.events.first()
        assertEquals(4, first.getInt("type"))
        assertTrue(clock.nowMs - first.getLong("timestamp") >= ErrorBuffer.MIN_WINDOW_MS)
        assertEquals(start, first.getLong("timestamp"))
    }

    @Test
    fun `events before the first snapshot are dropped and ask for one`() {
        val buffer = ErrorBuffer(clock)
        buffer.activity("orphan")

        assertEquals(0L, buffer.size)
        assertTrue(buffer.checkoutDue())
        buffer.snapshot("a")
        assertFalse(buffer.checkoutDue())
    }

    @Test
    fun `a checkout is due every 30 s only when there was activity`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        clock.advance(60_000)
        assertFalse("idle: no checkout", buffer.checkoutDue())

        buffer.activity("tap")
        assertTrue(buffer.checkoutDue())

        buffer.snapshot("b")
        assertFalse("a new segment resets it", buffer.checkoutDue())
        buffer.activity("tap2")
        clock.advance(29_999)
        assertFalse(buffer.checkoutDue())
        clock.advance(1)
        assertTrue(buffer.checkoutDue())
    }

    @Test
    fun `a second cut within 5 s waits for the rest of the interval`() {
        val buffer = ErrorBuffer(clock)
        assertEquals(0L, buffer.nextSendDelayMs())
        buffer.snapshot("a")
        buffer.cut(session)!!

        clock.advance(2_000)
        assertEquals(3_000L, buffer.nextSendDelayMs())
        clock.advance(3_000)
        assertEquals(0L, buffer.nextSendDelayMs())
    }

    @Test
    fun `nothing new since the last cut means no cut`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")
        assertTrue(buffer.cut(session) != null)

        // The recorder's fresh snapshot of the same screen after a send is state, not news.
        buffer.snapshot("a")
        clock.advance(10_000)
        assertNull(buffer.cut(session))

        buffer.activity("m2")
        assertEquals(listOf("4:a", "2:a", "3:m2"), buffer.cut(session)!!.events.tags())
    }

    @Test
    fun `a full snapshot of a changed screen after a cut is news`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")
        assertTrue(buffer.cut(session) != null)
        buffer.snapshot("a")
        clock.advance(1_000)

        // Another Activity, with no Mutation or touch in between.
        buffer.snapshot("b")

        assertEquals(listOf("4:a", "2:a", "4:b", "2:b"), buffer.cut(session)!!.events.tags())
    }

    @Test
    fun `a cut empties the buffer, so the next one starts at a new snapshot`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")
        buffer.cut(session)!!

        buffer.activity("after") // no segment open: dropped
        assertEquals(0L, buffer.size)
        assertNull(buffer.cut(session))
    }

    @Test
    fun `a cut for another session than the buffered one is refused`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")

        assertNull(buffer.cut("session-2"))
        assertTrue(buffer.cut(session) != null)
    }

    @Test
    fun `events of another session replace the buffer`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")
        buffer.add("session-2", listOf(meta(tag = "b"), full(tag = "b")))

        assertNull(buffer.cut(session))
        assertEquals(listOf("4:b", "2:b"), buffer.cut("session-2")!!.events.tags())
    }

    @Test
    fun `over the bound the oldest segments go whole, never the current one`() {
        val padding = "x".repeat(1_000)
        val bigMutation = { tag: String ->
            JSONObject().put(
                "type",
                3,
            ).put("timestamp", clock.nowMs).put("data", JSONObject().put("tag", tag).put("p", padding))
        }
        val oneSegment = listOf(meta(tag = "s"), full(tag = "s"), bigMutation("m")).sumOf {
            it.toString().toByteArray(Charsets.UTF_8).size.toLong()
        }
        // Room for two segments, not three.
        val buffer = ErrorBuffer(clock, maxBytes = oneSegment * 2 + oneSegment / 2)
        for (tag in listOf("s1", "s2", "s3")) {
            buffer.add(session, listOf(meta(tag = tag), full(tag = tag)))
            buffer.add(session, listOf(bigMutation(tag)))
            clock.advance(1_000)
        }

        assertTrue(buffer.size <= oneSegment * 2 + oneSegment / 2)
        assertFalse(buffer.checkoutDue())
        assertEquals(listOf("4:s2", "2:s2", "3:s2", "4:s3", "2:s3", "3:s3"), buffer.cut(session)!!.events.tags())
    }

    @Test
    fun `a single segment over the bound asks for a checkout and goes once the next one starts`() {
        val padding = "x".repeat(2_000)
        val buffer = ErrorBuffer(clock, maxBytes = 1_500)
        buffer.snapshot("s1")
        buffer.add(
            session,
            listOf(
                JSONObject().put("type", 3).put("timestamp", clock.nowMs).put("data", JSONObject().put("p", padding)),
            ),
        )

        assertTrue(buffer.checkoutDue())
        buffer.snapshot("s2")
        assertFalse(buffer.checkoutDue())
        assertEquals(listOf("4:s2", "2:s2"), buffer.cut(session)!!.events.tags())
    }

    @Test
    fun `the default bound is 10 MiB`() {
        assertEquals(10L * 1024 * 1024, ErrorBuffer.MAX_BYTES)
    }

    @Test
    fun `clear drops everything and the next cut needs a snapshot`() {
        val buffer = ErrorBuffer(clock)
        buffer.snapshot("a")
        buffer.activity("m")
        buffer.clear()

        assertNull(buffer.currentSessionId)
        assertNull(buffer.cut(session))
    }
}
