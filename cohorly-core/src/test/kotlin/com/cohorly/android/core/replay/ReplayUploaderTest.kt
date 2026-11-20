@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.CohorlyHttpException
import com.cohorly.android.core.CohorlyTransport
import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.JsonFileStorage
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

class ReplayUploaderTest {
    private val quotaBody = """{"status":0,"error":"replay quota exceeded"}"""

    /** A transport that fails every replay POST with [failure] until [failure] is set to null. */
    private class ScriptedTransport(var failure: (() -> Nothing)?) {
        val transport = MockTransport().also { mock -> mock.bytesResponder = { _, _ -> failure?.invoke() } }
    }

    private fun status(code: Int, retryAfterMs: Long? = null, body: String? = null): () -> Nothing =
        { throw CohorlyHttpException(code, retryAfterMs, body) }

    @Test
    fun `a chunk is posted gzipped to replay with the token, gzip encoding and the chunk shape`() {
        val transport = MockTransport()
        val clock = MockClock()
        val core = replayCore(transport = transport, clock = clock)

        val events = replayEvents(3, clock.nowMs, padding = 5)
        core.replayController.enqueue(events)

        val post = transport.bytePosts.single()
        assertEquals("http://localhost:4000/replay", post.url)
        assertEquals("test-token", post.headers["X-Cohorly-Token"])
        assertEquals("gzip", post.headers["Content-Encoding"])
        assertEquals("application/json", post.headers["Content-Type"])
        val chunk = post.chunk()
        assertEquals(core.replayController.sessionId, chunk.getString("session_id"))
        assertEquals(core.getDistinctId(), chunk.getString("distinct_id"))
        assertEquals(0L, chunk.getLong("seq"))
        assertEquals("session", chunk.getString("mode"))
        assertEquals("android", chunk.getString("lib"))
        assertEquals("0.3.0", chunk.getString("lib_version"))
        val sent = chunk.getJSONArray("events")
        assertEquals(events.map { it.toString() }, (0 until sent.length()).map { sent.getJSONObject(it).toString() })
        assertEquals(setOf("session_id", "distinct_id", "seq", "mode", "lib", "lib_version", "events"), chunk.keySet())
    }

    @Test
    fun `a 2xx removes the chunk and resets the failure count`() {
        val scripted = ScriptedTransport(status(500))
        val store = InMemoryReplayChunkStore()
        val core = replayCore(transport = scripted.transport, store = store)
        core.replayController.enqueue(replayEvents(1))
        assertEquals(1, core.replayController.uploader.consecutiveFailures)
        assertEquals(1, store.names().size)

        scripted.failure = null
        core.replayController.flush()

        assertEquals(0, core.replayController.uploader.consecutiveFailures)
        assertEquals(0L, core.replayController.uploader.backoffUntilMs)
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `429, 408, 5xx and network failures keep the chunk and back off from 2 s, doubling`() {
        val network: () -> Nothing = { throw IOException("offline") }
        val failures = listOf(status(429), status(408), status(500), status(503), network)
        for (failure in failures) {
            val clock = MockClock()
            val store = InMemoryReplayChunkStore()
            val core = replayCore(transport = ScriptedTransport(failure).transport, store = store, clock = clock)
            val uploader = core.replayController.uploader

            core.replayController.enqueue(replayEvents(1))
            assertEquals(clock.nowMs + 2_000, uploader.backoffUntilMs)
            core.replayController.flush()
            assertEquals(clock.nowMs + 4_000, uploader.backoffUntilMs)
            core.replayController.flush()
            assertEquals(clock.nowMs + 8_000, uploader.backoffUntilMs)
            assertEquals(1, store.names().size)
        }
    }

    @Test
    fun `backoff is capped at maxRetryDelayMs with 20 percent jitter`() {
        val clock = MockClock()
        val core = replayCore(
            transport = ScriptedTransport(status(500)).transport,
            clock = clock,
            maxRetryDelayMs = 5_000,
            jitter = { 1.0 },
        )
        core.replayController.enqueue(replayEvents(1))
        assertEquals(clock.nowMs + 2_400, core.replayController.uploader.backoffUntilMs)

        repeat(5) { core.replayController.flush() }

        assertEquals(clock.nowMs + 6_000, core.replayController.uploader.backoffUntilMs)
    }

    @Test
    fun `Retry-After above 0 sets the delay, capped at maxRetryDelayMs`() {
        val clock = MockClock()
        val scripted = ScriptedTransport(status(503, retryAfterMs = 7_000))
        val core = replayCore(transport = scripted.transport, clock = clock, maxRetryDelayMs = 60_000)
        val uploader = core.replayController.uploader

        core.replayController.enqueue(replayEvents(1))
        assertEquals(clock.nowMs + 7_000, uploader.backoffUntilMs)

        scripted.failure = status(429, retryAfterMs = 0)
        core.replayController.flush()
        assertEquals(clock.nowMs + 4_000, uploader.backoffUntilMs)

        scripted.failure = status(429, retryAfterMs = 3_600_000)
        core.replayController.flush()
        assertEquals(clock.nowMs + 60_000, uploader.backoffUntilMs)
    }

    @Test
    fun `a non-forced drain waits for the backoff, then retries the same chunk`() {
        val clock = MockClock()
        val scripted = ScriptedTransport(status(500))
        val core = replayCore(transport = scripted.transport, clock = clock)
        core.replayController.enqueue(replayEvents(1))
        val uploader = core.replayController.uploader

        clock.advance(1_999)
        assertEquals(1L, uploader.drain(force = false))
        assertEquals(1, scripted.transport.bytePosts.size)

        scripted.failure = null
        clock.advance(1)
        assertNull(uploader.drain(force = false))
        val (first, retried) = scripted.transport.bytePosts.map { it.chunk().toString() }
        assertEquals(first, retried)
    }

    @Test
    fun `a 401 keeps the chunk at the maximum backoff`() {
        val clock = MockClock()
        val store = InMemoryReplayChunkStore()
        val core = replayCore(transport = ScriptedTransport(status(401)).transport, clock = clock, store = store)

        core.replayController.enqueue(replayEvents(1))

        assertEquals(clock.nowMs + 600_000, core.replayController.uploader.backoffUntilMs)
        assertEquals(1, store.names().size)
    }

    @Test
    fun `a 413 splits the chunk in two halves, the second with a new seq of its session`() {
        val clock = MockClock()
        val scripted = ScriptedTransport({ throw IOException("offline") })
        val core = replayCore(transport = scripted.transport, clock = clock)
        core.replayController.enqueue(replayEvents(4, clock.nowMs))
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        var calls = 0
        scripted.failure = null
        scripted.transport.bytesResponder = { _, _ -> if (calls++ == 0) throw CohorlyHttpException(413) }
        scripted.transport.bytePosts.clear()

        core.replayController.flush()

        val sent = scripted.transport.replayChunks().map { it.getLong("seq") to it.getJSONArray("events").length() }
        // 413 on seq 0 (4 events), then its halves: seq 0 keeps 2, the second half takes seq 2.
        assertEquals(listOf(0L to 4, 0L to 2, 2L to 2, 1L to 1), sent)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        assertEquals(3L, scripted.transport.replayChunks().last().getLong("seq"))
    }

    @Test
    fun `a split after a rotation takes a seq the old session never used`() {
        val clock = MockClock()
        val scripted = ScriptedTransport({ throw IOException("offline") })
        val core = replayCore(transport = scripted.transport, clock = clock)
        val oldSession = core.replayController.sessionId
        core.replayController.enqueue(replayEvents(2, clock.nowMs))
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        core.reset()
        var calls = 0
        scripted.failure = null
        scripted.transport.bytesResponder = { _, _ -> if (calls++ == 0) throw CohorlyHttpException(413) }
        scripted.transport.bytePosts.clear()

        core.replayController.flush()

        val sent = scripted.transport.replayChunks().drop(1).map { it.getString("session_id") to it.getLong("seq") }
        assertEquals(listOf(oldSession to 0L, oldSession to 2L, oldSession to 1L), sent)
    }

    @Test
    fun `a single event that still gets a 413 is dropped`() {
        val store = InMemoryReplayChunkStore()
        val scripted = ScriptedTransport(status(413))
        val core = replayCore(transport = scripted.transport, store = store)

        core.replayController.enqueue(replayEvents(1))

        assertEquals(1, scripted.transport.bytePosts.size)
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `a chunk whose JSON is over 1 MB is split before it is sent`() {
        val transport = MockTransport()
        val core = replayCore(transport = transport)

        core.replayController.enqueue(replayEvents(4, padding = 300_000))

        val chunks = transport.replayChunks()
        assertEquals(listOf(0L to 2, 1L to 2), chunks.map { it.getLong("seq") to it.getJSONArray("events").length() })
        assertTrue(chunks.all { it.toString().toByteArray().size <= ReplayUploader.MAX_CHUNK_JSON_BYTES })
    }

    @Test
    fun `a single event over 1 MB is dropped without being sent`() {
        val transport = MockTransport()
        val store = InMemoryReplayChunkStore()
        val core = replayCore(transport = transport, store = store)

        core.replayController.enqueue(replayEvents(1, padding = 1_100_000))

        assertTrue(transport.bytePosts.isEmpty())
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `any other 4xx drops the chunk and the next one is sent`() {
        val clock = MockClock()
        val scripted = ScriptedTransport({ throw IOException("offline") })
        val store = InMemoryReplayChunkStore()
        val core = replayCore(transport = scripted.transport, clock = clock, store = store)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs))
        var calls = 0
        scripted.failure = null
        scripted.transport.bytesResponder = { _, _ -> if (calls++ == 0) throw CohorlyHttpException(400) }
        scripted.transport.bytePosts.clear()

        core.replayController.flush()

        assertEquals(listOf(0L, 1L), scripted.transport.replayChunks().map { it.getLong("seq") })
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `chunks a 4xx or the quota drop without delivery count in droppedChunks, sent chunks do not`() {
        val clock = MockClock()
        val transport = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }
        val core = replayCore(clock = clock, transport = transport)
        val replay = core.replayController
        assertEquals(0L, replay.droppedChunks)

        transport.bytesResponder = { _, _ -> }
        replay.enqueue(replayEvents(1, clock.nowMs))
        assertEquals(0L, replay.droppedChunks)

        transport.bytesResponder = { _, _ -> throw CohorlyHttpException(400) }
        replay.enqueue(replayEvents(1, clock.nowMs))
        assertEquals(1L, replay.droppedChunks)

        // Two session chunks queued while offline: the quota refusal drops both.
        transport.bytesResponder = { _, _ -> throw IOException("offline") }
        replay.enqueue(replayEvents(1, clock.nowMs))
        clock.advance(1)
        replay.enqueue(replayEvents(1, clock.nowMs))
        transport.bytesResponder = { _, _ -> throw CohorlyHttpException(429, 60_000, quotaBody) }
        replay.flush()
        assertEquals(3L, replay.droppedChunks)
    }

    @Test
    fun `a 404 turns replay off until the next launch`() {
        val state = InMemoryStorage()
        val store = InMemoryReplayChunkStore()
        val scripted = ScriptedTransport(status(404))
        val core = replayCore(state = state, transport = scripted.transport, store = store)
        core.replayController.enqueue(replayEvents(1))

        assertEquals(ReplayMode.OFF, core.replayController.mode)
        assertFalse(core.replayController.enqueue(replayEvents(1)))
        core.replayController.flush()
        assertEquals(1, scripted.transport.bytePosts.size)
        assertEquals(1, store.names().size)

        val transport = MockTransport()
        val nextLaunch = replayCore(state = state, transport = transport, store = store)
        assertEquals(ReplayMode.SESSION, nextLaunch.replayController.mode)
        assertEquals(1, transport.replayChunks().size)
    }

    @Test
    fun `replay quota exceeded pauses session recording until Retry-After, buffering like error mode`() {
        val clock = MockClock()
        val store = InMemoryReplayChunkStore()
        val scripted = ScriptedTransport(status(429, retryAfterMs = 120_000, body = quotaBody))
        val core = replayCore(clock = clock, transport = scripted.transport, store = store)

        core.replayController.enqueue(replayEvents(1))

        // The session chunk the quota refused is dropped, not retried.
        assertTrue(store.names().isEmpty())
        // Like @cohorly/web: a session sampled for session mode buffers as error mode meanwhile.
        assertEquals(ReplayMode.ERROR, core.replayController.mode)
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION))
        scripted.failure = null
        assertTrue(core.replayController.enqueue(replayEvents(1)))
        assertEquals("error", scripted.transport.replayChunks().last().getString("mode"))

        clock.advance(120_000)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertTrue(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION))
    }

    @Test
    fun `the quota pause reaches a new instance that opens the same state file`() {
        val dir = Files.createTempDirectory("cohorly-replay-state").toFile().apply { deleteOnExit() }
        val file = File(dir, "state.json")
        val clock = MockClock()
        val first = replayCore(
            state = JsonFileStorage(file),
            clock = clock,
            transport = ScriptedTransport(status(429, retryAfterMs = 120_000, body = quotaBody)).transport,
        )
        first.replayController.enqueue(replayEvents(1))
        assertEquals(ReplayMode.ERROR, first.replayController.mode)

        val second = replayCore(state = JsonFileStorage(file), clock = clock, transport = MockTransport())

        assertEquals(first.replayController.sessionId, second.replayController.sessionId)
        assertEquals(ReplayMode.ERROR, second.replayController.mode)
        assertFalse(second.replayController.enqueue(replayEvents(1), ReplayMode.SESSION))
        clock.advance(120_000)
        assertEquals(ReplayMode.SESSION, second.replayController.mode)
    }

    @Test
    fun `a quota refusal drops every queued session chunk of that session and keeps its error chunks`() {
        val clock = MockClock()
        val store = InMemoryReplayChunkStore()
        val transport = MockTransport().apply { bytesResponder = { _, _ -> throw IOException("offline") } }
        val core = replayCore(clock = clock, transport = transport, store = store)
        core.replayController.enqueue(replayEvents(1, clock.nowMs), ReplayMode.SESSION)
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs), ReplayMode.SESSION)
        clock.advance(1)
        core.replayController.enqueue(replayEvents(1, clock.nowMs), ReplayMode.ERROR)
        transport.bytePosts.clear()
        transport.bytesResponder = { _, body ->
            val chunk = JSONObject(String(ReplayChunkQueue.gunzip(body), Charsets.UTF_8))
            if (chunk.getString("mode") == "session") throw CohorlyHttpException(429, 60_000, quotaBody)
        }

        core.replayController.flush()

        // seq 1 is never sent: it went with seq 0. The error chunk still goes.
        val sent = transport.replayChunks().map { it.getLong("seq") to it.getString("mode") }
        assertEquals(listOf(0L to "session", 2L to "error"), sent)
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `without Retry-After the quota pause lasts one hour`() {
        val state = InMemoryStorage()
        val clock = MockClock()
        val core = replayCore(
            state = state,
            clock = clock,
            transport = ScriptedTransport(status(429, body = quotaBody)).transport,
        )

        core.replayController.enqueue(replayEvents(1))

        assertEquals((clock.nowMs + 3_600_000).toString(), state.get(ReplayController.Keys.QUOTA_PAUSED_UNTIL))
        clock.advance(3_600_000 - 1)
        assertEquals(ReplayMode.ERROR, core.replayController.mode)
        clock.advance(1)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
    }

    @Test
    fun `an error chunk refused by the quota is kept and retried`() {
        val store = InMemoryReplayChunkStore()
        val clock = MockClock()
        val core = replayCore(
            clock = clock,
            store = store,
            transport = ScriptedTransport(status(429, retryAfterMs = 30_000, body = quotaBody)).transport,
        )

        core.replayController.enqueue(replayEvents(1), ReplayMode.ERROR)

        assertEquals(1, store.names().size)
        assertEquals(clock.nowMs + 30_000, core.replayController.uploader.backoffUntilMs)
    }

    @Test
    fun `a replay failure never delays track, nor a track failure replay`() {
        val clock = MockClock()
        val transport = MockTransport()
        val core = replayCore(transport = transport, clock = clock)

        transport.bytesResponder = { _, _ -> throw CohorlyHttpException(429) }
        core.replayController.enqueue(replayEvents(1))
        core.track("Opened")
        core.maybeFlush()
        assertEquals(0L, core.backoffUntilMs)
        assertEquals(1, transport.trackBatches().size)
        assertTrue(core.replayController.uploader.backoffUntilMs > clock.nowMs)

        transport.bytesResponder = { _, _ -> }
        transport.responder = { url, _ -> if (url.endsWith("/track")) throw CohorlyHttpException(500) }
        core.track("Again")
        core.maybeFlush()
        assertTrue(core.backoffUntilMs > clock.nowMs)
        core.replayController.flush()
        assertEquals(0, core.replayController.uploader.consecutiveFailures)
        assertEquals(0L, core.replayController.uploader.backoffUntilMs)
    }

    @Test
    fun `a transport without postBytes turns replay off instead of retrying`() {
        val plain = object : CohorlyTransport {
            override fun post(url: String, body: String) {}
        }
        val plainCore = CohorlyCore(
            apiHost = "http://localhost:4000",
            token = "test-token",
            storage = InMemoryStorage(),
            transport = plain,
            config = CohorlyConfig(flushIntervalMs = 0, sessionReplay = SessionReplayConfig(sampleRate = 1.0)),
            replayExecutor = TestReplayExecutor(),
            replayRandom = { 0.5 },
        )
        assertEquals(ReplayMode.SESSION, plainCore.replayController.mode)

        plainCore.replayController.enqueue(replayEvents(1))

        assertEquals(ReplayMode.OFF, plainCore.replayController.mode)
        assertEquals(0, plainCore.replayController.uploader.consecutiveFailures)
    }

    @Test
    fun `a split whose second half cannot be written keeps the original chunk intact`() {
        for (failing in listOf("second half", "first half")) {
            val store = FaultyChunkStore()
            val scripted = ScriptedTransport({ throw IOException("offline") })
            val core = replayCore(transport = scripted.transport, store = store)
            core.replayController.enqueue(replayEvents(4))
            val original = store.names().single()
            val originalBytes = store.read(original)!!.toList()
            store.failWrite = { name ->
                val isOriginal = name == original
                if (failing == "second half") !isOriginal else isOriginal
            }
            scripted.failure = status(413)

            core.replayController.flush()

            assertEquals(failing, listOf(original), store.names())
            assertEquals(failing, originalBytes, store.read(original)!!.toList())
            assertTrue(failing, core.replayController.uploader.backoffUntilMs > 0)
        }
    }

    @Test
    fun `a split near the 10 MB limit keeps both halves and trims an older chunk instead`() {
        val random = Random(7)
        fun noisy(count: Int) = (0 until count).map {
            JSONObject().put("timestamp", 1L).put("data", Base64.getEncoder().encodeToString(random.nextBytes(20_000)))
        }
        val big = ReplayChunk("s", "u", 0, ReplayMode.SESSION, 10, noisy(4))
        val newer = ReplayChunk("s", "u", 1, ReplayMode.SESSION, 20, noisy(1))
        val store = InMemoryReplayChunkStore()
        ReplayChunkQueue(store, "0.3.0").apply {
            write(big)
            write(newer)
        }
        val max = store.names().sumOf { store.size(it) }
        val queue = ReplayChunkQueue(store, "0.3.0", maxBytes = max)
        var calls = 0
        val transport = MockTransport().apply {
            bytesResponder = { _, _ -> if (calls++ == 0) throw CohorlyHttpException(413) else throw IOException("x") }
        }
        val uploader = ReplayUploader(
            queue = queue,
            transport = transport,
            apiHost = "http://localhost:4000",
            token = "test-token",
            clock = MockClock(),
            maxRetryDelayMs = 600_000,
            jitterSource = { 0.0 },
            nextSeqFor = { 100L },
            onQuotaExceeded = {},
        )

        uploader.drain(force = true)

        assertEquals(listOf(10L to 0L, 10L to 100L), queue.entries().map { it.createdAt to it.seq })
        assertEquals(listOf(2, 2), queue.entries().map { queue.read(it)!!.events.size })
        assertTrue(queue.totalBytes() <= max)
    }

    @Test
    fun `a chunk that cannot be removed after a 2xx ends the drain instead of looping on it`() {
        val store = FaultyChunkStore().apply { failDelete = true }
        val transport = MockTransport()
        val core = replayCore(transport = transport, store = store)

        core.replayController.enqueue(replayEvents(1))

        assertEquals(1, transport.bytePosts.size)
        assertEquals(1, store.names().size)
        // The next drain sends it once more (the server ignores the duplicate seq).
        core.replayController.flush()
        assertEquals(2, transport.bytePosts.size)
    }

    @Test
    fun `after a success a new failure retries 2 s later, not at the old scheduled time`() {
        val clock = MockClock()
        val executor = TestReplayExecutor(clock = clock)
        val store = InMemoryReplayChunkStore()
        val scripted = ScriptedTransport(status(401))
        val core = replayCore(clock = clock, executor = executor, transport = scripted.transport, store = store)

        // A 401 schedules a retry at the maximum delay (10 min).
        core.replayController.enqueue(replayEvents(1))
        scripted.failure = null
        core.replayController.flush()
        assertTrue(store.names().isEmpty())

        scripted.failure = status(500)
        core.replayController.enqueue(replayEvents(1))
        assertEquals(3, scripted.transport.bytePosts.size)
        scripted.failure = null

        executor.advance(1_999)
        assertEquals(3, scripted.transport.bytePosts.size)
        executor.advance(1)
        assertEquals(4, scripted.transport.bytePosts.size)
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `a track backoff never delays a replay post or its scheduled retry`() {
        val clock = MockClock()
        val executor = TestReplayExecutor(clock = clock)
        val transport = MockTransport().apply {
            responder = { url, _ -> if (url.endsWith("/track")) throw CohorlyHttpException(500) }
        }
        val core = replayCore(clock = clock, executor = executor, transport = transport, maxRetryDelayMs = 60_000)
        core.track("Opened")
        core.flush()
        core.flush()
        assertTrue(core.backoffUntilMs >= clock.nowMs + 4_000)

        // Sent at once, with no forced flush.
        core.replayController.enqueue(replayEvents(1))
        assertEquals(1, transport.bytePosts.size)

        transport.bytesResponder = { _, _ -> throw CohorlyHttpException(503) }
        core.replayController.enqueue(replayEvents(1))
        assertEquals(2, transport.bytePosts.size)
        transport.bytesResponder = { _, _ -> }
        executor.advance(2_000)
        assertEquals(3, transport.bytePosts.size)
        assertTrue(core.backoffUntilMs > clock.nowMs)
    }
}
