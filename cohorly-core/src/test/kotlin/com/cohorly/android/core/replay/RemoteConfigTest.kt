@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.CohorlyStorage
import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.JsonFileStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

const val T = 10_000_000_000L
const val TTL = 3_600_000L
const val WAIT_MS = 3_000L

/** A stored copy as `remote_config.json` holds it. */
fun storedCopy(fetchedAt: Long, config: String): String = """{"fetchedAt":$fetchedAt,"config":$config}"""

/**
 * One app install: replay state, the stored copy, the fetcher and the
 * threads, shared by every [launch]. The config thread runs only on
 * [answer] unless [configImmediate].
 */
class RemoteRig(
    copy: String? = null,
    val code: SessionReplayConfig = SessionReplayConfig(remoteConfig = true),
    val fetcher: MockReplayConfigFetcher = MockReplayConfigFetcher(),
    configImmediate: Boolean = false,
    val state: CohorlyStorage = InMemoryStorage(),
    val transport: MockTransport = MockTransport(),
    val store: ReplayChunkStore = InMemoryReplayChunkStore(),
) {
    val clock = MockClock(T)
    val remote: CohorlyStorage = InMemoryStorage()
    val executor = TestReplayExecutor(clock = clock)
    val configExecutor = TestReplayExecutor(immediate = configImmediate)

    init {
        copy?.let { remote.set(ReplayRemoteConfigLoader.KEY, it) }
    }

    fun launch(): CohorlyCore = replayCore(
        clock = clock,
        state = state,
        store = store,
        transport = transport,
        executor = executor,
        replay = code,
        remoteConfigStorage = remote,
        configFetcher = fetcher,
        configExecutor = configExecutor,
    )

    fun session(): ReplaySession = requireNotNull(ReplaySession.parse(state.get(ReplayController.Keys.SESSION)))

    fun answer() = configExecutor.runAll()
}

class RemoteConfigTest {
    @Test
    fun `remote settings are off by default and never fetched, even with a stored copy`() {
        assertFalse(SessionReplayConfig().remoteConfig)
        val rig = RemoteRig(
            copy = storedCopy(T - 1000, """{"sampleRate":0}"""),
            code = SessionReplayConfig(sampleRate = 1.0),
            configImmediate = true,
        )
        val core = rig.launch()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        core.reset()
        rig.executor.advance(2 * TTL)
        core.track("x")
        core.reset()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(0, rig.fetcher.calls)
        assertTrue(rig.configExecutor.pending.isEmpty())
    }

    @Test
    fun `a fresh copy decides the session at once with no request`() {
        val rig = RemoteRig(copy = storedCopy(T - 1000, """{"sampleRate":1}"""))
        val core = rig.launch()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertFalse(rig.session().pending)
        core.reset()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertTrue(rig.configExecutor.pending.isEmpty())
        assertEquals(0, rig.fetcher.calls)
        assertFalse(WAIT_MS in rig.executor.scheduledDelays)
    }

    @Test
    fun `a stale copy makes one request, and new sessions meanwhile join it`() {
        val rig = RemoteRig(
            copy = storedCopy(T - TTL, """{"sampleRate":0}"""),
            fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}""")),
        )
        val core = rig.launch()
        assertTrue(rig.session().pending)
        core.track("a")
        core.reset()
        core.reset()
        assertEquals(1, rig.configExecutor.pending.size)
        rig.answer()
        assertEquals(1, rig.fetcher.calls)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals("http://localhost:4000/replay/config?token=test-token", rig.fetcher.urls.single())
    }

    @Test
    fun `the loader keeps one request in flight and answers every caller, token encoded`() {
        val fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":0.25}"""))
        val executor = TestReplayExecutor(immediate = false)
        val loader =
            ReplayRemoteConfigLoader(
                "https://api.example/",
                "a b&c",
                InMemoryStorage(),
                fetcher,
                executor,
                MockClock(T),
            )
        val answers = mutableListOf<Double?>()
        loader.load { answers.add(it?.sampleRate) }
        loader.load { answers.add(it?.sampleRate) }
        assertEquals(1, executor.pending.size)
        executor.runAll()
        assertEquals(listOf("https://api.example/replay/config?token=a+b%26c"), fetcher.urls)
        assertEquals(listOf(0.25, 0.25), answers)
    }

    @Test
    fun `each saved field wins over the code, each unsaved one keeps it`() {
        fun mode(code: SessionReplayConfig, saved: String): ReplayMode {
            val rig = RemoteRig(copy = storedCopy(T - 1000, saved), code = code)
            return rig.launch().replayController.mode
        }
        // random() is 0.5 for every draw.
        assertEquals(
            ReplayMode.SESSION,
            mode(SessionReplayConfig(0.0, 1.0, remoteConfig = true), """{"sampleRate":1}"""),
        )
        assertEquals(
            ReplayMode.OFF,
            mode(SessionReplayConfig(0.0, 1.0, remoteConfig = true), """{"onErrorSampleRate":0}"""),
        )
        assertEquals(
            ReplayMode.SESSION,
            mode(SessionReplayConfig(1.0, 0.0, remoteConfig = true), """{"onErrorSampleRate":1}"""),
        )
        assertEquals(ReplayMode.OFF, mode(SessionReplayConfig(1.0, 0.0, remoteConfig = true), """{"sampleRate":0}"""))
        assertEquals(
            ReplayMode.ERROR,
            mode(SessionReplayConfig(0.0, 0.0, remoteConfig = true), """{"onErrorSampleRate":1}"""),
        )

        val withMin = RemoteRig(copy = storedCopy(T - 1000, """{"minDurationMs":5000}"""))
        withMin.launch()
        assertEquals(5000L, withMin.session().minDurationMs)
        val withoutMin = RemoteRig(copy = storedCopy(T - 1000, """{"sampleRate":1}"""))
        withoutMin.launch()
        assertEquals(0L, withoutMin.session().minDurationMs)
    }

    @Test
    fun `a failed request falls back on the expired copy, else the code, and writes nothing`() {
        val failures = listOf<Pair<String, (RemoteRig) -> Unit>>(
            "exception" to {
                it.fetcher.error = IOException("offline")
                it.answer()
            },
            "non-2xx" to {
                it.fetcher.body = null
                it.answer()
            },
            "invalid json" to {
                it.fetcher.body = "not json"
                it.answer()
            },
            "config not an object" to {
                it.fetcher.body = """{"config":"x"}"""
                it.answer()
            },
            "3 s timeout" to { it.executor.advance(WAIT_MS) },
        )
        for ((name, fail) in failures) {
            val expired = storedCopy(T - 2 * TTL, """{"sampleRate":1}""")
            val withCopy = RemoteRig(copy = expired)
            val core = withCopy.launch()
            assertEquals(name, ReplayMode.OFF, core.replayController.mode)
            fail(withCopy)
            assertEquals(name, ReplayMode.SESSION, core.replayController.mode)
            assertFalse(name, withCopy.session().pending)
            assertEquals(name, expired, withCopy.remote.get(ReplayRemoteConfigLoader.KEY))

            val noCopy = RemoteRig(code = SessionReplayConfig(sampleRate = 1.0, remoteConfig = true))
            val coreNoCopy = noCopy.launch()
            fail(noCopy)
            assertEquals(name, ReplayMode.SESSION, coreNoCopy.replayController.mode)
            assertNull(name, noCopy.remote.get(ReplayRemoteConfigLoader.KEY))

            // The fallback stands for an hour, like an answer.
            coreNoCopy.reset()
            noCopy.answer()
            assertEquals(name, ReplayMode.SESSION, coreNoCopy.replayController.mode)
            assertTrue(name, noCopy.fetcher.calls <= 1)
        }
    }

    @Test
    fun `the session waits 3 s for the answer, not a millisecond less`() {
        val rig = RemoteRig(copy = storedCopy(T - 2 * TTL, """{"sampleRate":1}"""))
        val core = rig.launch()
        rig.executor.advance(WAIT_MS - 1)
        assertTrue(rig.session().pending)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        rig.executor.advance(1)
        assertFalse(rig.session().pending)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
    }

    @Test
    fun `an answer after the 3 s wait only updates the copy, which the next session takes`() {
        val rig = RemoteRig()
        val core = rig.launch()
        val id = core.replayController.sessionId
        rig.executor.advance(WAIT_MS)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        assertFalse(rig.session().pending)

        rig.fetcher.body = remoteBody("""{"sampleRate":1,"minDurationMs":5000}""")
        rig.clock.advance(1)
        rig.answer()
        assertEquals(id, core.replayController.sessionId)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        assertEquals(0L, rig.session().minDurationMs)
        val copy = StoredReplayConfig.parse(rig.remote.get(ReplayRemoteConfigLoader.KEY), rig.clock.nowMs)
        assertEquals(1.0, copy?.config?.sampleRate)

        core.reset()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(5000L, rig.session().minDurationMs)
        assertEquals(1, rig.fetcher.calls)
    }

    @Test
    fun `an answer past the wait never decides the session, even before the timer runs`() {
        val rig = RemoteRig()
        val core = rig.launch()
        rig.fetcher.body = remoteBody("""{"sampleRate":1}""")
        rig.clock.advance(WAIT_MS)
        rig.answer()
        assertEquals(ReplayMode.OFF, core.replayController.mode)

        // Stored in the same millisecond the wait resolved, the copy still reaches the next session.
        core.reset()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(1, rig.fetcher.calls)
    }

    @Test
    fun `a copy stored before a busy state thread runs the fallback still reaches the next session`() {
        val rig = RemoteRig()
        val core = rig.launch()
        rig.fetcher.body = remoteBody("""{"sampleRate":1}""")
        rig.clock.advance(WAIT_MS)
        rig.executor.immediate = false
        rig.answer()

        // The busy state thread runs the fallback a millisecond after the copy was stored: the copy is not lost.
        rig.clock.advance(1)
        rig.executor.runAll()
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        core.reset()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(1, rig.fetcher.calls)
    }

    @Test
    fun `a boot opted out asks nothing, and opting back in decides the new session from the settings`() {
        val storage = InMemoryStorage().apply { set(CohorlyCore.Keys.OPT_OUT, "1") }
        val rig = RemoteRig(fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}""")))
        val core = replayCore(
            storage = storage,
            clock = rig.clock,
            state = rig.state,
            executor = rig.executor,
            replay = rig.code,
            remoteConfigStorage = rig.remote,
            configFetcher = rig.fetcher,
            configExecutor = rig.configExecutor,
        )
        rig.answer()
        assertEquals(0, rig.fetcher.calls)

        core.optInTracking()
        rig.answer()
        assertEquals(1, rig.fetcher.calls)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
    }

    @Test
    fun `out-of-range values count as not saved, a long minimum duration is capped`() {
        fun parse(json: String) = RemoteReplayConfig.parse(org.json.JSONObject(json))!!
        assertNull(parse("""{"sampleRate":-0.1}""").sampleRate)
        assertNull(parse("""{"sampleRate":1.5}""").sampleRate)
        assertNull(parse("""{"sampleRate":"0.5"}""").sampleRate)
        assertNull(parse("""{"onErrorSampleRate":-0.1}""").onErrorSampleRate)
        assertNull(parse("""{"onErrorSampleRate":1.5}""").onErrorSampleRate)
        assertNull(parse("""{"onErrorSampleRate":"1"}""").onErrorSampleRate)
        assertNull(parse("""{"minDurationMs":-1}""").minDurationMs)
        assertNull(parse("""{"minDurationMs":"100"}""").minDurationMs)
        assertEquals(30_000L, parse("""{"minDurationMs":45000}""").minDurationMs)
        assertEquals(
            RemoteReplayConfig(0.0, 1.0, 0L),
            parse("""{"sampleRate":0,"onErrorSampleRate":1,"minDurationMs":0,"triggers":[]}"""),
        )
        assertNull(RemoteReplayConfig.parse("x"))
        // The answer is one JSON object and nothing after it.
        assertTrue(ReplayRemoteConfigLoader.strictObject(" {\"config\":{}} \n") != null)
        assertNull(ReplayRemoteConfigLoader.strictObject("{\"config\":{\"sampleRate\":1}} trailing"))
        assertNull(ReplayRemoteConfigLoader.strictObject("[1]"))

        val rig = RemoteRig(
            code = SessionReplayConfig(sampleRate = 1.0, remoteConfig = true),
            fetcher = MockReplayConfigFetcher(
                body = remoteBody("""{"sampleRate":1.5,"onErrorSampleRate":"1","minDurationMs":45000}"""),
            ),
            configImmediate = true,
        )
        val core = rig.launch()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(30_000L, rig.session().minDurationMs)
    }

    @Test
    fun `a copy fetched in the future counts as no copy`() {
        val future = storedCopy(T + 60_000, """{"sampleRate":1}""")
        assertNull(StoredReplayConfig.parse(future, T))
        val rig = RemoteRig(copy = future)
        val core = rig.launch()
        assertTrue(rig.session().pending)
        assertEquals(1, rig.configExecutor.pending.size)
        rig.executor.advance(WAIT_MS)
        // The code's rates (0), not the future copy's.
        assertEquals(ReplayMode.OFF, core.replayController.mode)
    }

    @Test
    fun `an answer never overwrites a copy stored after its request began`() {
        val clock = MockClock(T)
        val storage = InMemoryStorage()
        val fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":0}"""))
        val loader = ReplayRemoteConfigLoader("http://h", "t", storage, fetcher, TestReplayExecutor(), clock)
        fetcher.during = {
            clock.advance(10)
            storage.set(ReplayRemoteConfigLoader.KEY, storedCopy(clock.nowMs, """{"sampleRate":1}"""))
        }
        var answer: RemoteReplayConfig? = null
        loader.load { answer = it }
        assertEquals(0.0, answer?.sampleRate)
        assertEquals(
            1.0,
            StoredReplayConfig.parse(storage.get(ReplayRemoteConfigLoader.KEY), clock.nowMs)?.config?.sampleRate,
        )
        assertEquals(1.0, loader.stored()?.config?.sampleRate)

        // A copy stored before the request began is replaced.
        fetcher.during = { clock.advance(10) }
        loader.load { }
        assertEquals(
            0.0,
            StoredReplayConfig.parse(storage.get(ReplayRemoteConfigLoader.KEY), clock.nowMs)?.config?.sampleRate,
        )
    }

    @Test
    fun `a live session keeps its mode and minimum duration when the settings change`() {
        val rig = RemoteRig(copy = storedCopy(T - 1000, """{"sampleRate":0}"""))
        val first = rig.launch()
        val id = first.replayController.sessionId
        assertEquals(ReplayMode.OFF, first.replayController.mode)

        rig.clock.advance(60_000)
        rig.remote.set(
            ReplayRemoteConfigLoader.KEY,
            storedCopy(rig.clock.nowMs, """{"sampleRate":1,"minDurationMs":5000}"""),
        )
        val relaunch = rig.launch()
        assertEquals(id, relaunch.replayController.sessionId)
        assertEquals(ReplayMode.OFF, relaunch.replayController.mode)
        assertEquals(0L, rig.session().minDurationMs)
        assertEquals(0, rig.fetcher.calls)
    }

    @Test
    fun `code rates at 0 still record when the saved rate is 1, and nothing changes with remote settings off`() {
        val on = RemoteRig(
            code = SessionReplayConfig(remoteConfig = true),
            fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}""")),
            configImmediate = true,
        )
        val core = on.launch()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertTrue(core.replayController.enqueue(replayEvents(1)))
        assertEquals(1, on.transport.replayChunks().size)

        val off = RemoteRig(
            code = SessionReplayConfig(),
            fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}""")),
            configImmediate = true,
        )
        val coreOff = off.launch()
        assertEquals(ReplayMode.OFF, coreOff.replayController.mode)
        assertFalse(coreOff.replayController.enqueue(replayEvents(1)))
        assertEquals(0, off.fetcher.calls)
        assertTrue(off.transport.replayChunks().isEmpty())
    }

    @Test
    fun `a pending session has its id at once and reads off until the answer decides it`() {
        val fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}"""))
        val rig = RemoteRig(fetcher = fetcher)
        val core = rig.launch()
        val id = core.replayController.sessionId
        assertNotNull(id)
        assertEquals(id, core.replayController.sessionIdForEvent(rig.clock.nowMs))
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        assertFalse(core.replayController.enqueue(replayEvents(1), ReplayMode.SESSION, id))
        val heard = mutableListOf<String?>()
        core.replayController.addSessionListener { heard.add(it) }

        rig.answer()
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertEquals(id, core.replayController.sessionId)
        // The recorder hears the same session again and starts capturing.
        assertEquals(listOf(id), heard)
        assertTrue(rig.transport.replayChunks().isEmpty())
    }

    @Test
    fun `a state file written before remote settings reads as a decided session with no minimum duration`() {
        val file = File.createTempFile("state", ".json").apply { deleteOnExit() }
        file.writeText(
            """{"cohorly.replay.session":"{\"id\":\"old-session\",\"started_at\":9999940000,\"last_active_at\":9999940000,\"mode\":\"session\",\"next_seq\":3}"}""",
        )
        val rig = RemoteRig(state = JsonFileStorage(file))
        val core = rig.launch()
        assertEquals("old-session", core.replayController.sessionId)
        assertFalse(rig.session().pending)
        assertEquals(0L, rig.session().minDurationMs)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertTrue(core.replayController.enqueue(replayEvents(1)))
        assertEquals(listOf(3L), rig.transport.replayChunks().map { it.getLong("seq") })
    }

    @Test
    fun `a session left pending by the last launch is decided again at boot`() {
        val pending =
            """{"id":"pending-session","started_at":9999940000,"last_active_at":9999940000,""" +
                """"mode":"off","next_seq":0,"pending":true}"""

        val waiting = RemoteRig(fetcher = MockReplayConfigFetcher(body = remoteBody("""{"sampleRate":1}""")))
        waiting.state.set(ReplayController.Keys.SESSION, pending)
        val core = waiting.launch()
        assertEquals("pending-session", core.replayController.sessionId)
        assertEquals(ReplayMode.OFF, core.replayController.mode)
        val heard = mutableListOf<String?>()
        core.replayController.addSessionListener { heard.add(it) }
        waiting.answer()
        assertEquals("pending-session", core.replayController.sessionId)
        assertEquals(ReplayMode.SESSION, core.replayController.mode)
        assertFalse(waiting.session().pending)
        assertEquals(listOf<String?>("pending-session"), heard)

        val fresh = RemoteRig(copy = storedCopy(T - 1000, """{"sampleRate":1}"""))
        fresh.state.set(ReplayController.Keys.SESSION, pending)
        val freshCore = fresh.launch()
        assertEquals("pending-session", freshCore.replayController.sessionId)
        assertEquals(ReplayMode.SESSION, freshCore.replayController.mode)
        assertFalse(fresh.session().pending)
        assertEquals(0, fresh.fetcher.calls)

        val remoteOff = RemoteRig(code = SessionReplayConfig(sampleRate = 1.0))
        remoteOff.state.set(ReplayController.Keys.SESSION, pending)
        assertEquals(ReplayMode.SESSION, remoteOff.launch().replayController.mode)
    }
}
