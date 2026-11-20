package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.CohorlyStorage
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The project's replay settings, read with [SessionReplayConfig.remoteConfig]
 * (VEL-2236, the contract of `@cohorly/web`'s VEL-2185, docs/SESSION-REPLAY.md
 * "Remote settings"). Only the fields the project saved; an absent field
 * keeps the code's value.
 */
data class RemoteReplayConfig(
    val sampleRate: Double? = null,
    val onErrorSampleRate: Double? = null,
    val minDurationMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        sampleRate?.let { put("sampleRate", it) }
        onErrorSampleRate?.let { put("onErrorSampleRate", it) }
        minDurationMs?.let { put("minDurationMs", it) }
    }

    companion object {
        /** The longest minimum duration the dashboard offers. */
        const val MAX_MIN_DURATION_MS = 30_000L

        /**
         * The only fields read from the server or the stored copy; anything
         * else (`triggers` included) is dropped. An invalid value counts as
         * not saved. Null when [value] is not an object.
         */
        fun parse(value: Any?): RemoteReplayConfig? {
            if (value !is JSONObject) return null
            return RemoteReplayConfig(
                sampleRate = rate(value.opt("sampleRate")),
                onErrorSampleRate = rate(value.opt("onErrorSampleRate")),
                minDurationMs = duration(value.opt("minDurationMs")),
            )
        }

        private fun finite(value: Any?): Double? {
            if (value !is Number) return null
            val d = value.toDouble()
            return if (d.isFinite()) d else null
        }

        private fun rate(value: Any?): Double? = finite(value)?.takeIf { it in 0.0..1.0 }

        private fun duration(value: Any?): Long? =
            finite(value)?.takeIf { it >= 0 }?.let { minOf(it, MAX_MIN_DURATION_MS.toDouble()).toLong() }
    }
}

/** The settings a session is decided and recorded with: the remote fields over the code's. */
data class EffectiveReplayConfig(val sampleRate: Double, val onErrorSampleRate: Double, val minDurationMs: Long) {
    companion object {
        /** Field by field: a saved field wins; the code's minimum duration is 0. */
        fun of(code: SessionReplayConfig, remote: RemoteReplayConfig?): EffectiveReplayConfig = EffectiveReplayConfig(
            sampleRate = remote?.sampleRate ?: code.sampleRate,
            onErrorSampleRate = remote?.onErrorSampleRate ?: code.onErrorSampleRate,
            minDurationMs = remote?.minDurationMs ?: 0L,
        )
    }
}

/** A copy of the settings as stored, with when it was fetched. */
data class StoredReplayConfig(val fetchedAt: Long, val config: RemoteReplayConfig) {
    fun fresh(now: Long): Boolean = now - fetchedAt < ReplayRemoteConfigLoader.REPLAY_CONFIG_TTL_MS

    fun toJson(): String = JSONObject().put("fetchedAt", fetchedAt).put("config", config.toJson()).toString()

    companion object {
        /** Null for absent or unreadable JSON, and for a copy fetched after [now] (no copy). */
        fun parse(json: String?, now: Long): StoredReplayConfig? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val fetchedAt = obj.opt("fetchedAt") as? Number ?: return null
                val config = RemoteReplayConfig.parse(obj.opt("config")) ?: return null
                val at = fetchedAt.toLong()
                if (at > now) null else StoredReplayConfig(at, config)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Runs `GET /replay/config`. Returns the body of a 2xx answer, null for any
 * other status; may throw on a network error (the caller treats both alike).
 * Called on the replay config thread, never the main thread.
 */
interface ReplayConfigFetcher {
    fun fetch(url: String): String?
}

/**
 * Default fetcher, with no credential. The whole request (connecting,
 * waiting and reading) gives up after [timeoutMs]: the connection is
 * closed from a watchdog thread, which makes the blocked read throw.
 */
class HttpUrlConnectionReplayConfigFetcher(
    private val timeoutMs: Int = ReplayRemoteConfigLoader.REPLAY_CONFIG_ABORT_MS.toInt(),
) : ReplayConfigFetcher {
    override fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        var watchdog: ScheduledFuture<*>? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            val opened = connection
            watchdog = WATCHDOG.schedule({ opened.disconnect() }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val status = connection.responseCode
            if (status < 200 ||
                status >= 300
            ) {
                null
            } else {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } finally {
            watchdog?.cancel(false)
            connection?.disconnect()
        }
    }

    private companion object {
        val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cohorly-replay-config-watchdog").apply { isDaemon = true }
        }
    }
}

/**
 * The stored copy (key [KEY] of its own storage, on Android
 * `no_backup/cohorly_replay_<token>/remote_config.json`) and the request
 * that refreshes it. The copy is read once, at construction, then mirrored
 * in memory, so nothing on the caller's path touches the disk. At most one
 * request is in flight; a [load] made meanwhile waits for it.
 */
class ReplayRemoteConfigLoader(
    apiHost: String,
    token: String,
    private val storage: CohorlyStorage,
    private val fetcher: ReplayConfigFetcher,
    private val executor: ReplayExecutor,
    private val clock: CohorlyClock,
) {
    private val url = "${apiHost.trimEnd('/')}/replay/config?token=${URLEncoder.encode(token, "UTF-8")}"
    private val lock = Any()

    @Volatile
    private var stored: StoredReplayConfig? = StoredReplayConfig.parse(storage.get(KEY), clock.nowMillis())

    private var inFlight = false
    private val waiters = mutableListOf<(RemoteReplayConfig?) -> Unit>()

    /** The stored copy, fresh or expired; null without one (or one fetched in the future). */
    fun stored(): StoredReplayConfig? = stored?.takeIf { it.fetchedAt <= clock.nowMillis() }

    /**
     * Requests the settings on [executor]; [onResult] gets the answer, or
     * null on any failure (network, non-2xx, invalid JSON). A successful
     * answer is stored unless a copy fetched after the request began is
     * there already; a failure stores nothing. Never blocks the caller.
     */
    fun load(onResult: (RemoteReplayConfig?) -> Unit) {
        synchronized(lock) {
            waiters.add(onResult)
            if (inFlight) return
            inFlight = true
        }
        executor.execute { request() }
    }

    private fun request() {
        val config = try {
            val startedAt = clock.nowMillis()
            val body = fetcher.fetch(url)
            val parsed = body?.let { RemoteReplayConfig.parse(strictObject(it)?.opt("config")) }
            if (parsed != null) {
                synchronized(lock) {
                    val now = clock.nowMillis()
                    val onDisk = StoredReplayConfig.parse(storage.get(KEY), now)
                    val latest = listOfNotNull(stored(), onDisk).maxByOrNull { it.fetchedAt }
                    if (latest == null || latest.fetchedAt <= startedAt) {
                        val copy = StoredReplayConfig(now, parsed)
                        stored = copy
                        storage.set(KEY, copy.toJson())
                    } else {
                        stored = latest
                    }
                }
            }
            parsed
        } catch (_: Throwable) {
            null
        }
        val done = synchronized(lock) {
            inFlight = false
            waiters.toList().also { waiters.clear() }
        }
        for (waiter in done) {
            try {
                waiter(config)
            } catch (_: Exception) {
                // One waiter must not break the others.
            }
        }
    }

    companion object {
        /** [body] as one JSON object and nothing after it; null otherwise. */
        internal fun strictObject(body: String): JSONObject? {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue() as? JSONObject ?: return null
            return if (tokener.nextClean() == 0.toChar()) value else null
        }

        const val KEY = "cohorly.replay.remote_config"

        /** A stored copy younger than this is used with no request. */
        const val REPLAY_CONFIG_TTL_MS = 60L * 60 * 1000

        /** How long a session's decision waits for the request (the request itself keeps going). */
        const val REPLAY_CONFIG_TIMEOUT_MS = 3000L

        /** The request gives up after this, so a late answer can still refresh the copy. */
        const val REPLAY_CONFIG_ABORT_MS = 30_000L
    }
}
