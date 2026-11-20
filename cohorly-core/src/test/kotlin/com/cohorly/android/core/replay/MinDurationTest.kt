@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinDurationTest {
    private fun rig(config: String = """{"sampleRate":1,"minDurationMs":5000}""") =
        RemoteRig(copy = storedCopy(T - 1000, config))

    @Test
    fun `a recording shorter than the minimum duration never reaches the queue nor takes a seq`() {
        val rig = rig()
        val core = rig.launch()
        val replay = core.replayController
        val id = replay.sessionId!!
        assertTrue(replay.enqueue(replayEvents(1, timestamp = 1_000), ReplayMode.SESSION, id))
        assertTrue(replay.enqueue(replayEvents(1, timestamp = 3_000), ReplayMode.SESSION, id))
        assertTrue(rig.store.names().isEmpty())
        assertEquals(0L, rig.session().nextSeq)

        core.reset()
        // The recorder's last buffer of the session, still short, is held with it and never sent.
        assertTrue(replay.enqueue(replayEvents(1, timestamp = 4_000), ReplayMode.SESSION, id))
        assertEquals(0L, rig.session().retiredSeqs[id])
        assertTrue(rig.transport.replayChunks().isEmpty())
        assertTrue(rig.store.names().isEmpty())
    }

    @Test
    fun `the last buffer after a rotation that makes the recording long enough sends it whole`() {
        val rig = rig()
        val core = rig.launch()
        val replay = core.replayController
        val id = replay.sessionId!!
        replay.enqueue(replayEvents(1, timestamp = 1_000), ReplayMode.SESSION, id)
        core.reset()
        assertTrue(rig.transport.replayChunks().isEmpty())

        replay.enqueue(replayEvents(1, timestamp = 6_000), ReplayMode.SESSION, id)
        val chunks = rig.transport.replayChunks().filter { it.getString("session_id") == id }
        assertEquals(listOf(0L, 1L), chunks.map { it.getLong("seq") })
    }

    @Test
    fun `a recording that reaches the minimum duration arrives whole, from seq 0, in order`() {
        val rig = rig()
        val replay = rig.launch().replayController
        val id = replay.sessionId!!
        for (at in listOf(1_000L, 3_000L)) replay.enqueue(replayEvents(1, timestamp = at), ReplayMode.SESSION, id)
        assertTrue(rig.transport.replayChunks().isEmpty())

        replay.enqueue(replayEvents(1, timestamp = 6_000), ReplayMode.SESSION, id)
        replay.enqueue(replayEvents(1, timestamp = 7_000), ReplayMode.SESSION, id)
        val chunks = rig.transport.replayChunks()
        assertEquals(listOf(0L, 1L, 2L, 3L), chunks.map { it.getLong("seq") })
        assertEquals(
            listOf(1_000L, 3_000L, 6_000L, 7_000L),
            chunks.map { it.getJSONArray("events").getJSONObject(0).getLong("timestamp") },
        )
        assertTrue(chunks.all { it.getString("session_id") == id && it.getString("mode") == "session" })
    }

    @Test
    fun `over 5 MiB held, the chunks are sent even though the recording is short`() {
        val rig = rig()
        val replay = rig.launch().replayController
        val id = replay.sessionId!!
        // About 0.9 MB each, all at the same instant.
        repeat(5) { replay.enqueue(replayEvents(1, timestamp = 1_000, padding = 900_000), ReplayMode.SESSION, id) }
        assertTrue(rig.transport.replayChunks().isEmpty())

        replay.enqueue(replayEvents(1, timestamp = 1_000, padding = 900_000), ReplayMode.SESSION, id)
        assertEquals((0L..5L).toList(), rig.transport.replayChunks().map { it.getLong("seq") })
    }

    @Test
    fun `error mode never holds`() {
        val rig = rig("""{"sampleRate":0,"onErrorSampleRate":1,"minDurationMs":5000}""")
        val replay = rig.launch().replayController
        assertEquals(ReplayMode.ERROR, replay.mode)
        assertTrue(replay.enqueue(replayEvents(1, timestamp = 1_000), ReplayMode.ERROR, replay.sessionId))
        val chunks = rig.transport.replayChunks()
        assertEquals(listOf(0L), chunks.map { it.getLong("seq") })
        assertEquals("error", chunks.single().getString("mode"))
    }

    @Test
    fun `without a minimum duration every chunk is queued at once`() {
        val rig = rig("""{"sampleRate":1}""")
        val replay = rig.launch().replayController
        replay.enqueue(replayEvents(1, timestamp = 1_000), ReplayMode.SESSION, replay.sessionId)
        assertEquals(listOf(0L), rig.transport.replayChunks().map { it.getLong("seq") })
    }
}
