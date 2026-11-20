package com.cohorly.android.core

import com.cohorly.android.core.replay.MockReplayConfigFetcher
import com.cohorly.android.core.replay.ReplayChunkStore
import com.cohorly.android.core.replay.ReplayConfigFetcher
import com.cohorly.android.core.replay.ReplayExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Records every POST; behavior per call is programmable via [responder]. */
class MockTransport : CohorlyTransport {
    data class Post(val url: String, val body: String)

    val posts = mutableListOf<Post>()

    /** Called for each post AFTER recording; throw to simulate a failure. */
    var responder: (url: String, body: String) -> Unit = { _, _ -> }

    override fun post(url: String, body: String) {
        posts.add(Post(url, body))
        responder(url, body)
    }

    data class BytesPost(val url: String, val body: ByteArray, val headers: Map<String, String>)

    /** Every [postBytes] call (session replay chunks). */
    val bytePosts = mutableListOf<BytesPost>()

    /** Called for each [postBytes] AFTER recording; throw to simulate a failure. */
    var bytesResponder: (url: String, body: ByteArray) -> Unit = { _, _ -> }

    override fun postBytes(url: String, body: ByteArray, headers: Map<String, String>) {
        bytePosts.add(BytesPost(url, body, headers))
        bytesResponder(url, body)
    }

    fun postsTo(path: String): List<Post> = posts.filter { it.url.endsWith(path) }

    fun trackBatches(): List<JSONArray> = postsTo("/track").map { JSONArray(it.body) }

    fun trackedEvents(): List<JSONObject> =
        trackBatches().flatMap { batch -> (0 until batch.length()).map { batch.getJSONObject(it) } }

    fun engagePayloads(): List<JSONObject> = postsTo("/engage")
        .map { JSONArray(it.body) }
        .flatMap { batch -> (0 until batch.length()).map { batch.getJSONObject(it) } }
}

class MockClock(var nowMs: Long = 1_000_000L) : CohorlyClock {
    override fun nowMillis(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}

class StubDeviceInfo(
    private val props: Map<String, Any?> = mapOf(
        "\$lib_version" to "0.1.0",
        "\$os" to "Android",
        "\$os_version" to "14",
        "\$manufacturer" to "Google",
        "\$brand" to "google",
        "\$model" to "Pixel 8",
        "\$screen_dpi" to 420,
        "\$screen_height" to 2400,
        "\$screen_width" to 1080,
    ),
    private val version: String? = "1.0.0",
    private val profileProps: Map<String, Any?> = emptyMap(),
) : DeviceInfoProvider {
    override fun defaultProperties(): Map<String, Any?> = props
    override fun profileProperties(): Map<String, Any?> = profileProps
    override fun appVersion(): String? = version
}

fun networkError(): Nothing = throw IOException("connection refused")

/**
 * Records every /flags/evaluate request body and replies with a programmable
 * response (`null` simulates a failed evaluation).
 */
class MockFlagsFetcher(var response: String? = null) : CohorlyFlagsFetcher {
    val requests = mutableListOf<String>()

    /** Run inside [fetch] before returning, to mutate state "in flight". */
    var beforeReturn: (() -> Unit)? = null

    override fun fetch(url: String, body: String): String? {
        requests.add(body)
        beforeReturn?.invoke()
        return response
    }

    fun requestedDistinctIds(): List<String> = requests.map { JSONObject(it).getString("distinct_id") }
}

/** Builds a core with sensible test defaults. */
fun makeCore(
    storage: CohorlyStorage = InMemoryStorage(),
    transport: MockTransport = MockTransport(),
    clock: MockClock = MockClock(),
    config: CohorlyConfig = CohorlyConfig(flushIntervalMs = 0),
    deviceInfo: DeviceInfoProvider = StubDeviceInfo(),
    token: String? = "test-token",
    jitter: () -> Double = { 0.0 },
    apiHost: String = "http://localhost:4000",
    flagsFetcher: CohorlyFlagsFetcher = MockFlagsFetcher(),
    libVersion: String = "0.3.0",
    replayChunkStore: ReplayChunkStore? = null,
    replayStateStorage: CohorlyStorage? = null,
    replayExecutor: ReplayExecutor? = null,
    replayUploadExecutor: ReplayExecutor? = null,
    replayRandom: () -> Double = { 0.5 },
    replayRemoteConfigStorage: CohorlyStorage? = null,
    replayConfigFetcher: ReplayConfigFetcher = MockReplayConfigFetcher(),
    replayConfigExecutor: ReplayExecutor? = null,
): CohorlyCore = CohorlyCore(
    apiHost = apiHost,
    token = token,
    storage = storage,
    transport = transport,
    deviceInfo = deviceInfo,
    flagsFetcher = flagsFetcher,
    clock = clock,
    config = config,
    jitterSource = jitter,
    libVersion = libVersion,
    replayChunkStore = replayChunkStore,
    replayStateStorage = replayStateStorage,
    replayExecutor = replayExecutor,
    replayUploadExecutor = replayUploadExecutor,
    replayRandom = replayRandom,
    replayRemoteConfigStorage = replayRemoteConfigStorage,
    replayConfigFetcher = replayConfigFetcher,
    replayConfigExecutor = replayConfigExecutor,
)
