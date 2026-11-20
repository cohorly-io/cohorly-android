package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.CohorlyStorage
import com.cohorly.android.core.InMemoryStorage
import com.cohorly.android.core.MockClock
import com.cohorly.android.core.MockTransport
import com.cohorly.android.core.makeCore
import org.json.JSONObject

/**
 * Runs tasks on the calling thread, one at a time (a task submitted while
 * another runs waits for it, like the real serial threads). With [immediate]
 * false nothing runs until [runAll]. Scheduled tasks run when [advance] moves
 * [clock] past their due time, in due order; without a clock they are only
 * recorded.
 */
class TestReplayExecutor(var immediate: Boolean = true, private val clock: MockClock? = null) : ReplayExecutor {
    val pending = ArrayDeque<Runnable>()
    val scheduledDelays = mutableListOf<Long>()
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private var running = false

    override fun execute(task: Runnable) {
        pending.addLast(task)
        if (immediate) runAll()
    }

    override fun schedule(delayMs: Long, task: Runnable) {
        scheduledDelays.add(delayMs)
        clock?.let { scheduled.add(it.nowMs + delayMs to task) }
    }

    fun runAll() {
        if (running) return
        running = true
        try {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        } finally {
            running = false
        }
    }

    /** Moves the clock forward by [ms], running each scheduled task at its due time. */
    fun advance(ms: Long) {
        val clock = requireNotNull(clock) { "a clock is needed to run scheduled tasks" }
        val end = clock.nowMs + ms
        while (true) {
            val next = scheduled.filter { it.first <= end }.minByOrNull { it.first } ?: break
            scheduled.remove(next)
            if (next.first > clock.nowMs) clock.nowMs = next.first
            next.second.run()
            runAll()
        }
        clock.nowMs = end
    }
}

/** Records every write, so a test can tell what reached storage and when. */
class RecordingStorage(private val delegate: CohorlyStorage = InMemoryStorage()) : CohorlyStorage {
    val writtenKeys = mutableListOf<String>()

    override fun get(key: String): String? = delegate.get(key)

    override fun set(key: String, value: String?) {
        writtenKeys.add(key)
        delegate.set(key, value)
    }

    fun replayKeysWritten(): List<String> = writtenKeys.filter { it.startsWith("cohorly.replay") }
}

/** Counts every call, to prove a code path never touched the chunk store. */
class CountingChunkStore(private val delegate: ReplayChunkStore = InMemoryReplayChunkStore()) : ReplayChunkStore {
    var calls = 0
        private set

    override fun names(): List<String> = delegate.names().also { calls++ }

    override fun read(name: String): ByteArray? = delegate.read(name).also { calls++ }

    override fun write(name: String, bytes: ByteArray): Boolean = delegate.write(name, bytes).also { calls++ }

    override fun delete(name: String): Boolean = delegate.delete(name).also { calls++ }

    override fun size(name: String): Long = delegate.size(name).also { calls++ }
}

/** A store whose writes and deletes fail on demand; [beforeRead] runs inside each read. */
class FaultyChunkStore(val delegate: ReplayChunkStore = InMemoryReplayChunkStore()) : ReplayChunkStore {
    var failWrite: (name: String) -> Boolean = { false }
    var failDelete = false
    var beforeRead: (name: String) -> Unit = {}

    override fun names(): List<String> = delegate.names()

    override fun read(name: String): ByteArray? {
        beforeRead(name)
        return delegate.read(name)
    }

    override fun write(name: String, bytes: ByteArray): Boolean =
        if (failWrite(name)) false else delegate.write(name, bytes)

    override fun delete(name: String): Boolean = if (failDelete) false else delegate.delete(name)

    override fun size(name: String): Long = delegate.size(name)
}

/**
 * A core with session replay on, one manual executor for both replay threads,
 * an in-memory chunk store and an in-memory replay state by default. A
 * relaunch shares [state] and [store] with the first launch.
 */
fun replayCore(
    storage: CohorlyStorage = InMemoryStorage(),
    transport: MockTransport = MockTransport(),
    clock: MockClock = MockClock(),
    store: ReplayChunkStore = InMemoryReplayChunkStore(),
    state: CohorlyStorage = InMemoryStorage(),
    executor: TestReplayExecutor = TestReplayExecutor(),
    uploadExecutor: ReplayExecutor? = null,
    replay: SessionReplayConfig = SessionReplayConfig(sampleRate = 1.0),
    random: () -> Double = { 0.5 },
    maxRetryDelayMs: Long = 600_000,
    jitter: () -> Double = { 0.0 },
    remoteConfigStorage: CohorlyStorage = InMemoryStorage(),
    configFetcher: ReplayConfigFetcher = MockReplayConfigFetcher(),
    configExecutor: ReplayExecutor = TestReplayExecutor(),
): CohorlyCore = makeCore(
    storage = storage,
    transport = transport,
    clock = clock,
    config = CohorlyConfig(flushIntervalMs = 0, maxRetryDelayMs = maxRetryDelayMs, sessionReplay = replay),
    jitter = jitter,
    replayChunkStore = store,
    replayStateStorage = state,
    replayExecutor = executor,
    replayUploadExecutor = uploadExecutor,
    replayRandom = random,
    replayRemoteConfigStorage = remoteConfigStorage,
    replayConfigFetcher = configFetcher,
    replayConfigExecutor = configExecutor,
)

val CohorlyCore.replayController: ReplayController get() = requireNotNull(replay) { "session replay is off" }

/** Valid rrweb-shaped events at [timestamp]; [padding] chars of filler each. */
fun replayEvents(count: Int, timestamp: Long = 1_000_000L, padding: Int = 0): List<JSONObject> = (0 until count).map {
    JSONObject()
        .put("type", 3)
        .put("timestamp", timestamp)
        .put("data", JSONObject().put("i", it).put("pad", "x".repeat(padding)))
}

/** The decompressed chunk a replay POST carried. */
fun MockTransport.BytesPost.chunk(): JSONObject = JSONObject(String(ReplayChunkQueue.gunzip(body), Charsets.UTF_8))

fun MockTransport.replayChunks(): List<JSONObject> = bytePosts.filter { it.url.endsWith("/replay") }.map { it.chunk() }

fun JSONObject.props(): JSONObject = getJSONObject("properties")

/**
 * `GET /replay/config` for tests: answers [body] (a 2xx body, null for a
 * non-2xx status), or throws [error]. Records every URL it was called with.
 */
class MockReplayConfigFetcher(var body: String? = """{"config":{}}""", var error: Throwable? = null) :
    ReplayConfigFetcher {
    val urls = mutableListOf<String>()

    /** Runs inside each fetch, before it answers (e.g. to move the clock). */
    var during: () -> Unit = {}

    override fun fetch(url: String): String? {
        urls.add(url)
        during()
        error?.let { throw it }
        return body
    }

    val calls: Int get() = urls.size
}

/** A `GET /replay/config` body carrying [config]. */
fun remoteBody(config: String): String = """{"config":$config}"""
