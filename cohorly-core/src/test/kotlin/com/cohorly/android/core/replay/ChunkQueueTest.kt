@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.random.Random

class ChunkQueueTest {
    private fun tempDir(): File = Files.createTempDirectory("cohorly-replay").toFile().apply { deleteOnExit() }

    private fun offline() = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }

    private fun chunk(createdAt: Long, seq: Long, session: String, events: Int = 1) =
        ReplayChunk(session, "user", seq, ReplayMode.SESSION, createdAt, replayEvents(events))

    @Test
    fun `chunks are gzip files inside the given directory only, and survive a relaunch`() {
        val root = tempDir()
        val dir = File(root, "cohorly_replay_tok")
        val state = InMemoryStorage()
        val clock = MockClock()
        val firstLaunch =
            replayCore(state = state, clock = clock, store = FileReplayChunkStore(dir), transport = offline())
        firstLaunch.replayController.enqueue(replayEvents(2, clock.nowMs))
        clock.advance(1)
        firstLaunch.replayController.enqueue(replayEvents(1, clock.nowMs))

        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(2, files.size)
        assertTrue(files.all { it.parentFile == dir && it.name.endsWith(".json.gz") })

        val transport = MockTransport()
        val nextLaunch =
            replayCore(state = state, clock = clock, store = FileReplayChunkStore(dir), transport = transport)
        nextLaunch.replayController.flush()

        assertEquals(listOf(0L, 1L), transport.replayChunks().map { it.getLong("seq") })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a leftover chunk temp file is never sent and the state file next to the chunks is left alone`() {
        val dir = tempDir()
        File(dir, "1_0_abc.json.gz.tmp").writeText("half a chunk")
        File(dir, "state.json.tmp").writeText("{}")
        val store = FileReplayChunkStore(dir)

        assertTrue(store.write("2_0_abc.json.gz", byteArrayOf(1, 2, 3)))

        assertEquals(listOf("2_0_abc.json.gz"), store.names())
        assertFalse(File(dir, "1_0_abc.json.gz.tmp").exists())
        assertFalse(File(dir, "2_0_abc.json.gz.tmp").exists())
        assertTrue(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun `a write whose rename fails leaves neither the chunk nor a temp file`() {
        val dir = tempDir()
        val store = FileReplayChunkStore(dir) { _, _ -> false }

        assertFalse(store.write("2_0_abc.json.gz", ByteArray(4_096) { 7 }))

        assertFalse(File(dir, "2_0_abc.json.gz").exists())
        assertFalse(File(dir, "2_0_abc.json.gz.tmp").exists())
        assertTrue(store.names().isEmpty())
        assertNull(store.read("2_0_abc.json.gz"))
    }

    @Test
    fun `without a chunk the store never creates its directory`() {
        val dir = File(tempDir(), "cohorly_replay_tok")
        replayCore(store = FileReplayChunkStore(dir)).track("Opened")

        assertFalse(dir.exists())
    }

    @Test
    fun `chunks are sent by createdAt then seq`() {
        val queue = ReplayChunkQueue(InMemoryReplayChunkStore(), "0.3.0")
        queue.add(chunk(20, 0, "b"))
        queue.add(chunk(10, 3, "a"))
        queue.add(chunk(10, 1, "a"))

        assertEquals(listOf(10L to 1L, 10L to 3L, 20L to 0L), queue.entries().map { it.createdAt to it.seq })
    }

    @Test
    fun `a chunk keeps the session, distinct id, mode and seq of its enqueue after a rotation`() {
        val transport = offline()
        val core = replayCore(transport = transport)
        val sessionAtEnqueue = core.replayController.sessionId
        val distinctAtEnqueue = core.getDistinctId()
        core.replayController.enqueue(replayEvents(1), ReplayMode.ERROR)

        core.reset()
        transport.bytesResponder = { _, _ -> }
        transport.bytePosts.clear()
        core.replayController.flush()

        val sent = transport.replayChunks().single()
        assertEquals(sessionAtEnqueue, sent.getString("session_id"))
        assertEquals(distinctAtEnqueue, sent.getString("distinct_id"))
        assertEquals("error", sent.getString("mode"))
        assertEquals(0L, sent.getLong("seq"))
    }

    @Test
    fun `chunks never enter the track queue`() {
        val transport = offline()
        val core = replayCore(transport = transport)

        repeat(1_200) { core.replayController.enqueue(replayEvents(1)) }

        assertEquals(0, core.pendingEventCount())
        core.flush()
        assertTrue(transport.postsTo("/track").isEmpty())
    }

    @Test
    fun `the queue keeps at most 10 MB of files, dropping the oldest`() {
        val store = InMemoryReplayChunkStore()
        val clock = MockClock()
        val core = replayCore(store = store, clock = clock, transport = offline())
        val random = Random(42)
        // Random base64 barely compresses: about 225 KB of gzip per chunk.
        repeat(60) {
            val noise = Base64.getEncoder().encodeToString(random.nextBytes(225_000))
            core.replayController.enqueue(listOf(JSONObject().put("timestamp", clock.nowMs).put("data", noise)))
            clock.advance(1)
        }

        val total = store.names().sumOf { store.size(it) }
        assertTrue("total $total", total <= 10L * 1024 * 1024)
        assertEquals(10L * 1024 * 1024, ReplayChunkQueue.DEFAULT_MAX_BYTES)
        val seqs = store.names().mapNotNull { ReplayChunkQueue.parseName(it)?.seq }.sorted()
        assertTrue("oldest dropped: $seqs", seqs.first() > 0)
        assertEquals(59L, seqs.last())
    }

    @Test
    fun `remove reports a failed delete and trim only counts bytes it really freed`() {
        val store = FaultyChunkStore()
        val queue = ReplayChunkQueue(store, "0.3.0", maxBytes = 1)
        queue.write(chunk(10, 0, "a", events = 3))
        queue.write(chunk(11, 1, "a"))
        store.failDelete = true

        assertFalse(queue.remove(queue.oldest()!!))
        queue.trim()
        assertEquals(2, queue.entries().size)

        // The first (bigger) file cannot go, so it freed nothing: the second has to.
        store.failDelete = false
        val stuck = queue.oldest()!!.name
        val faulty = object : ReplayChunkStore by store {
            override fun delete(name: String): Boolean = name != stuck && store.delete(name)
        }
        ReplayChunkQueue(faulty, "0.3.0", maxBytes = store.size(stuck)).trim()
        assertEquals(listOf(stuck), store.names())
    }

    @Test
    fun `the trim reports how many chunks it deleted unsent, and nothing when it deletes none`() {
        val queue = ReplayChunkQueue(InMemoryReplayChunkStore(), "0.3.0", maxBytes = 1)
        val reported = mutableListOf<Int>()
        queue.onTrimmed = { reported.add(it) }
        queue.write(chunk(10, 0, "a"))
        queue.write(chunk(11, 1, "a"))
        queue.write(chunk(12, 2, "a"))

        queue.trim(keep = setOf(queue.entries().last().name))
        assertEquals(listOf(2), reported)

        queue.trim(keep = setOf(queue.entries().last().name))
        assertEquals(listOf(2), reported)
    }
}
