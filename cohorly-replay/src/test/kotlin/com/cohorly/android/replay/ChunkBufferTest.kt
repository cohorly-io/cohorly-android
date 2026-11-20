package com.cohorly.android.replay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for the platform's org.json.
@RunWith(RobolectricTestRunner::class)
class ChunkBufferTest {
    private val executor = TestExecutor()
    private val delivered = mutableListOf<Pair<String, List<JSONObject>>>()
    private val buffer = ChunkBuffer(executor, { events, id -> delivered.add(id to events) })

    private fun event(i: Int, padding: Int = 0) =
        JSONObject().put("type", 3).put("i", i).put("pad", "x".repeat(padding))

    @Test
    fun `a chunk is delivered 10 s after its first event, even with no event after it`() {
        buffer.add("s1", event(0))
        executor.advance(5_000)
        buffer.add("s1", event(1))
        executor.advance(4_999)
        assertEquals(0, delivered.size)

        executor.advance(1)
        assertEquals(listOf("s1"), delivered.map { it.first })
        assertEquals(listOf(0, 1), delivered[0].second.map { it.getInt("i") })

        // The next chunk gets its own 10 s.
        buffer.add("s1", event(2))
        executor.advance(9_999)
        assertEquals(1, delivered.size)
        executor.advance(1)
        assertEquals(2, delivered.size)
    }

    @Test
    fun `an event that would take the chunk past 512 KiB closes it first`() {
        val big = 100 * 1024
        repeat(5) { buffer.add("s1", event(it, big)) }
        assertEquals(0, delivered.size)

        buffer.add("s1", event(5, big))
        assertEquals(1, delivered.size)
        assertEquals(listOf(0, 1, 2, 3, 4), delivered[0].second.map { it.getInt("i") })
        assertTrue(delivered[0].second.sumOf { it.toString().length } <= ChunkBuffer.MAX_BYTES)

        // The sixth event opened the next chunk, with its own 10 s.
        executor.advance(9_999)
        assertEquals(1, delivered.size)
        executor.advance(1)
        assertEquals(listOf(5), delivered[1].second.map { it.getInt("i") })
    }

    @Test
    fun `an event bigger than 512 KiB goes alone`() {
        buffer.add("s1", event(0))
        buffer.add("s1", event(1, ChunkBuffer.MAX_BYTES))

        assertEquals(listOf(listOf(0), listOf(1)), delivered.map { chunk -> chunk.second.map { it.getInt("i") } })
        buffer.add("s1", event(2))
        executor.advance(10_000)
        assertEquals(listOf(2), delivered[2].second.map { it.getInt("i") })
    }

    @Test
    fun `a Meta and its FullSnapshot are never split across chunks`() {
        buffer.add("s1", event(0, 300 * 1024))
        val meta = JSONObject().put("type", 4).put("i", 1)
        val full = JSONObject().put("type", 2).put("i", 2).put("pad", "x".repeat(300 * 1024))

        buffer.addGroup("s1", listOf(meta, full))

        assertEquals(listOf(0), delivered[0].second.map { it.getInt("i") })
        executor.advance(10_000)
        assertEquals(listOf(1, 2), delivered[1].second.map { it.getInt("i") })
    }

    @Test
    fun `a chunk never mixes sessions`() {
        buffer.add("s1", event(0))
        buffer.add("s2", event(1))

        assertEquals(listOf("s1"), delivered.map { it.first })
        executor.advance(10_000)
        assertEquals(listOf("s1", "s2"), delivered.map { it.first })
        assertEquals(listOf(1), delivered[1].second.map { it.getInt("i") })
    }

    @Test
    fun `discard drops the buffer and its timer`() {
        buffer.add("s1", event(0))
        buffer.discard()
        executor.advance(60_000)
        buffer.flush()

        assertEquals(0, delivered.size)
    }
}
