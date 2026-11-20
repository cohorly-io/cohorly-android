package com.cohorly.android.core

import com.cohorly.android.core.replay.HttpUrlConnectionReplayConfigFetcher
import com.cohorly.android.core.replay.InMemoryReplayChunkStore
import com.cohorly.android.core.replay.ReplayChunkQueue
import com.cohorly.android.core.replay.ReplayChunkStore
import com.cohorly.android.core.replay.ReplayConfigFetcher
import com.cohorly.android.core.replay.ReplayController
import com.cohorly.android.core.replay.ReplayExecutor
import com.cohorly.android.core.replay.ReplayRemoteConfigLoader
import com.cohorly.android.core.replay.ThreadReplayExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure-JVM Cohorly client core: queue, batching, retry contract, identity,
 * super properties, automatic events. Platform glue (the `cohorly` Android
 * module) injects storage/transport/device-info and owns threading; every
 * public method here is synchronous and thread-safe (network I/O in
 * [flush]/[maybeFlush]/[sendAlias] runs on the calling thread, outside the
 * internal lock).
 *
 * Retry contract (shared by all Cohorly SDKs):
 * - 429/5xx/network: keep queue, exponential backoff base 2s, doubling,
 *   cap [CohorlyConfig.maxRetryDelayMs], +/-20% jitter; `Retry-After`
 *   honored on 429 (capped).
 * - 413: halve effective batch size (floor 1). A single event that alone
 *   413s is dropped to avoid livelock.
 * - 400: drop the batch. 401: keep queue, back off at max delay.
 * - Queue capped at [CohorlyConfig.maxQueueSize] events, dropping OLDEST.
 */
class CohorlyCore(
    apiHost: String,
    private val token: String?,
    private val storage: CohorlyStorage,
    private val transport: CohorlyTransport,
    private val deviceInfo: DeviceInfoProvider = EmptyDeviceInfo(),
    private val flagsFetcher: CohorlyFlagsFetcher = HttpUrlConnectionFlagsFetcher(),
    private val clock: CohorlyClock = SystemClock(),
    val config: CohorlyConfig = CohorlyConfig(),
    /** Jitter source in [-1, 1]; injectable for deterministic backoff tests. */
    private val jitterSource: () -> Double = { Random.nextDouble() * 2 - 1 },
    /** SDK version sent as a replay chunk's `lib_version` (the Android module passes its own). */
    libVersion: String = "unknown",
    /**
     * Where replay chunks are queued; only used with [CohorlyConfig.sessionReplay].
     * Null keeps them in memory (the Android module passes a directory under `no_backup/`).
     */
    replayChunkStore: ReplayChunkStore? = null,
    /**
     * Replay state, separate from [storage] (the Android module passes
     * `no_backup/cohorly_replay_<token>/state.json`); only used with
     * [CohorlyConfig.sessionReplay]. Null keeps it in memory.
     */
    replayStateStorage: CohorlyStorage? = null,
    /** Replay state thread; null starts a `cohorly-replay` thread when replay is configured. */
    replayExecutor: ReplayExecutor? = null,
    /**
     * Replay upload thread; null starts `cohorly-replay-upload`, or reuses
     * [replayExecutor] when only that one is given (tests).
     */
    replayUploadExecutor: ReplayExecutor? = null,
    /** Sampling draws in [0, 1); injectable for deterministic replay tests. */
    replayRandom: () -> Double = { Random.nextDouble() },
    /**
     * The stored copy of the remote replay settings (the Android module
     * passes `no_backup/cohorly_replay_<token>/remote_config.json`); only
     * used with [com.cohorly.android.core.replay.SessionReplayConfig.remoteConfig].
     * Null keeps it in memory.
     */
    replayRemoteConfigStorage: CohorlyStorage? = null,
    /** `GET /replay/config`; injectable for tests. */
    replayConfigFetcher: ReplayConfigFetcher = HttpUrlConnectionReplayConfigFetcher(),
    /** The thread that runs [replayConfigFetcher]; null starts `cohorly-replay-config` when needed. */
    replayConfigExecutor: ReplayExecutor? = null,
) {
    /**
     * Storage keys. `internal` rather than private so InstallGuard.kt can clear
     * exactly these on a restored install without duplicating the names.
     * Kotlin's `internal` is compiler-enforced but not JVM-private, so these
     * constants are technically reachable from Java in the published artifact -
     * an acceptable trade for one source of truth, since the key names are
     * already visible in the persisted JSON file itself.
     */
    internal object Keys {
        const val DISTINCT_ID = "cohorly.distinct_id"
        const val DEVICE_ID = "cohorly.device_id"
        const val ANONYMOUS = "cohorly.anonymous"
        const val SUPER_PROPS = "cohorly.super_properties"
        const val EVENT_QUEUE = "cohorly.event_queue"
        const val ENGAGE_QUEUE = "cohorly.engage_queue"
        const val OPT_OUT = "cohorly.opt_out"

        /**
         * Feature-flag cache (mirrors sdk-core's `chl_flags`), scoped by
         * distinct id so one user's flags are never served to another.
         */
        const val FLAGS = "cohorly.flags"

        /**
         * Identifies the install this state belongs to (see [InstallGuard]).
         * Written by the platform module, never read by the core.
         */
        const val INSTALL_STAMP = "cohorly.install_stamp"
    }

    companion object {
        /** Cohorly's mp_lib equivalent, stamped as `$lib` on every event. */
        const val LIB_NAME = "android"

        /** Server rejects /track batches above this size with a 400. */
        const val SERVER_MAX_BATCH = 500

        /** Exponential backoff base, ms (shared with session replay, see [Backoff]). */
        const val BACKOFF_BASE_MS = Backoff.BASE_MS

        /**
         * Hard staleness bound for the persisted flag cache under
         * [FlagPersistenceMode.persistenceUntilNetworkSuccess]: 7 days. That
         * policy trades freshness for offline availability, so a cache past
         * [CohorlyConfig.flagCacheTtlMs] is still served while it is within
         * this window - the served window is the wider of the configured TTL
         * and 7 days.
         */
        const val FLAG_CACHE_MAX_STALE_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val apiHost: String = apiHost.trimEnd('/')
    private val lock = Any()
    private val batchSize: Int = config.batchSize.coerceIn(1, SERVER_MAX_BATCH)

    private var distinctId: String
    private var deviceId: String
    private var anonymous: Boolean
    private var superProps: JSONObject
    private val eventQueue = ArrayDeque<JSONObject>()
    private val engageQueue = ArrayDeque<JSONObject>()
    private var optedOut: Boolean

    /** Event name -> start instant (ms) for timed events; in-memory only. */
    private val timedEvents = HashMap<String, Long>()

    /** Consecutive /track flush failures; drives backoff. Reset on success. */
    private var consecutiveFailures = 0

    /** Instant (ms) before which non-forced flushes are skipped. */
    internal var backoffUntilMs = 0L
        private set

    /** Current per-flush batch size; halved on 413 (floor 1). */
    internal var effectiveBatchSize: Int
        private set

    /** Guards against concurrent/reentrant flushes. */
    private var flushing = false

    private val automaticEvents: AutomaticEvents?

    private val screenViews: ScreenViews? = if (config.trackScreenViews) ScreenViews(this) else null

    /** Last known flag results for the current distinct id. */
    private var flags: Map<String, FlagResult> = emptyMap()

    /** True once flags were loaded, from the persisted cache or a reload. */
    private var flagsLoaded = false

    /**
     * Effective cache TTL: [CohorlyConfig.flagCacheTtlMs] must be a positive
     * duration; a non-positive value falls back to the 24h default so it
     * cannot disable or unbound the cache window.
     */
    private val flagCacheTtlMs: Long =
        if (config.flagCacheTtlMs > 0) {
            config.flagCacheTtlMs
        } else {
            CohorlyConfig.DEFAULT_FLAG_CACHE_TTL_MS
        }

    /** Bumped per reload; a response carrying an older value is stale. */
    private var flagRequestSeq = 0L

    /** Bumped per subscription, keys [flagListeners] for unsubscribe. */
    private var flagListenerSeq = 0L
    private val flagListeners = LinkedHashMap<Long, (Map<String, FlagResult>) -> Unit>()

    /**
     * `"<key>:<value>"` pairs already reported as `$feature_flag_called` for
     * the current identity. In-memory only (never persisted) and cleared by
     * [setIdentity]/[reset], so a new identity re-reports its exposures once.
     */
    private val reportedExposures = HashSet<String>()

    /**
     * Where the currently served flag set came from (null until flags load).
     * One source covers the whole map because the set is swapped atomically:
     * a cache load or a network success replaces it wholesale, never per key.
     */
    private var flagSource: FlagSource? = null

    /** Epoch ms the served flag set was persisted; non-null only while [flagSource] is [FlagSource.persistence]. */
    private var flagPersistedAt: Long? = null

    val people: People = People()

    /**
     * Session replay (`$session_id`, chunk queue and upload); null without
     * [CohorlyConfig.sessionReplay].
     */
    val replay: ReplayController?

    init {
        val storedId = storage.get(Keys.DISTINCT_ID)
        if (storedId != null) {
            distinctId = storedId
            anonymous = storage.get(Keys.ANONYMOUS) == "1"
        } else {
            distinctId = UUID.randomUUID().toString()
            anonymous = true
            storage.set(Keys.DISTINCT_ID, distinctId)
            storage.set(Keys.ANONYMOUS, "1")
        }
        // Device id: persisted separately so it survives identify(). On a fresh
        // install it equals the anon distinct id (Mixpanel's `$device:<uuid>`
        // convention); older installs migrating in mint a fresh one.
        val storedDeviceId = storage.get(Keys.DEVICE_ID)
        if (storedDeviceId != null) {
            deviceId = storedDeviceId
        } else {
            deviceId = if (anonymous) distinctId else UUID.randomUUID().toString()
            storage.set(Keys.DEVICE_ID, deviceId)
        }
        optedOut = storage.get(Keys.OPT_OUT) == "1"
        superProps = readJsonObject(Keys.SUPER_PROPS)
        readJsonArrayInto(Keys.EVENT_QUEUE, eventQueue)
        readJsonArrayInto(Keys.ENGAGE_QUEUE, engageQueue)
        // Enforce the cap on load in case a larger queue was persisted.
        if (enforceEventQueueCap()) persistEventQueue()

        effectiveBatchSize = batchSize

        // Before the automatic events, whose first track already carries $session_id.
        replay = config.sessionReplay?.let { replayConfig ->
            val stateExecutor = replayExecutor ?: ThreadReplayExecutor(ThreadReplayExecutor.STATE_THREAD)
            ReplayController(
                config = replayConfig,
                state = replayStateStorage ?: InMemoryStorage(),
                clock = clock,
                stateExecutor = stateExecutor,
                uploadExecutor = replayUploadExecutor
                    ?: replayExecutor
                    ?: ThreadReplayExecutor(ThreadReplayExecutor.UPLOAD_THREAD),
                queue = ReplayChunkQueue(replayChunkStore ?: InMemoryReplayChunkStore(), libVersion),
                transport = transport,
                apiHost = this.apiHost,
                token = token,
                maxRetryDelayMs = config.maxRetryDelayMs,
                jitterSource = jitterSource,
                random = replayRandom,
                distinctId = distinctId,
                optedOut = optedOut,
                remoteConfig = if (replayConfig.remoteConfig && token != null) {
                    ReplayRemoteConfigLoader(
                        apiHost = this.apiHost,
                        token = token,
                        storage = replayRemoteConfigStorage ?: InMemoryStorage(),
                        fetcher = replayConfigFetcher,
                        executor = replayConfigExecutor ?: ThreadReplayExecutor(ThreadReplayExecutor.CONFIG_THREAD),
                        clock = clock,
                    )
                } else {
                    null
                },
            )
        }

        // Feature-flag cache, per config.flagPersistence: the persistent
        // modes adopt a valid cache immediately (synchronous reads cannot
        // await the network the way Mixpanel's async lookups do, so
        // networkFirst serves identically to persistenceUntilNetworkSuccess -
        // see the enum doc); networkOnly never reads nor writes the key on
        // the serving path (identify()/reset() still remove it - that is
        // identity hygiene, not flag serving). Safe to run outside the lock -
        // the instance is not published yet.
        if (config.flagPersistence != FlagPersistenceMode.networkOnly) {
            loadPersistedFlagsLocked()
        }

        automaticEvents = if (config.trackAutomaticEvents) {
            AutomaticEvents(this, storage, clock).also { it.start(deviceInfo.appVersion()) }
        } else {
            null
        }
    }

    // MARK: identity

    fun getDistinctId(): String = synchronized(lock) { distinctId }

    /** Stable per-device id stamped as `$device_id` on every event. */
    fun getDeviceId(): String = synchronized(lock) { deviceId }

    fun isAnonymous(): Boolean = synchronized(lock) { anonymous }

    /**
     * Switches identity. When the current id is still anonymous, first sends
     * `/alias { alias: <anonymousId>, distinct_id: <newId> }` (best effort,
     * blocking - the Android wrapper splits this via [setIdentity] +
     * [sendAlias] so the network hop runs on its background thread).
     */
    fun identify(newDistinctId: String) {
        val wasDifferent = getDistinctId() != newDistinctId
        val alias = setIdentity(newDistinctId)
        if (alias != null) sendAlias(alias)
        // Flags are per-identity: refresh them for the new id (best effort).
        // Skipped when no switch happened (same id, or opted out).
        if (wasDifferent && getDistinctId() == newDistinctId) reloadFeatureFlags()
    }

    /**
     * Applies the identity switch synchronously and returns the `/alias`
     * payload to send when the previous id was anonymous, else null.
     */
    fun setIdentity(newDistinctId: String): JSONObject? {
        val (aliasPayload, listeners) = synchronized(lock) {
            if (optedOut || newDistinctId == distinctId) return null
            val aliasPayload = if (anonymous) {
                JSONObject()
                    .put("alias", distinctId)
                    .put("distinct_id", newDistinctId)
                    .also { if (token != null) it.put("token", token) }
            } else {
                null
            }
            distinctId = newDistinctId
            anonymous = false
            replay?.identityChanged(newDistinctId)
            storage.set(Keys.DISTINCT_ID, distinctId)
            storage.set(Keys.ANONYMOUS, "0")
            // Exposures are per-identity: the new user must report its own.
            reportedExposures.clear()
            // Flags are per-identity too: drop the previous identity's values
            // (memory and disk) BEFORE the new id's reload runs, so they can
            // never be served to the new identity - including under networkOnly,
            // where the in-memory set is all there is. The persisted entry is
            // removed under every policy: identity hygiene, not flag serving.
            // The seq bump also marks any in-flight reload as stale.
            flags = emptyMap()
            flagsLoaded = false
            flagSource = null
            flagPersistedAt = null
            flagRequestSeq += 1
            storage.set(Keys.FLAGS, null)
            aliasPayload to flagListeners.values.toList()
        }
        // The served set changed to empty: notify listeners outside the
        // lock, like reloadFeatureFlags, so subscribers drop the previous
        // identity's values immediately instead of holding them until the
        // next reload lands.
        for (listener in listeners) {
            try {
                listener(emptyMap())
            } catch (_: Exception) {
                // A listener must not break the identity switch for the others.
            }
        }
        return aliasPayload
    }

    /** POSTs an /alias payload produced by [setIdentity]. Best effort. */
    fun sendAlias(payload: JSONObject) {
        try {
            transport.post("$apiHost/alias", payload.toString())
        } catch (_: Exception) {
            // Best effort, like sdk-core: an alias failure is not retried.
        }
    }

    /**
     * Clears identity, super properties, and queued-but-unsent data, then
     * assigns a fresh anonymous distinct id. Mirrors mixpanel's reset().
     */
    fun reset() {
        val listeners = synchronized(lock) {
            distinctId = UUID.randomUUID().toString()
            deviceId = distinctId
            anonymous = true
            superProps = JSONObject()
            eventQueue.clear()
            engageQueue.clear()
            timedEvents.clear()
            storage.set(Keys.DISTINCT_ID, distinctId)
            storage.set(Keys.DEVICE_ID, deviceId)
            storage.set(Keys.ANONYMOUS, "1")
            persistSuperProps()
            persistEventQueue()
            persistEngageQueue()
            // Flags are per-identity: the previous identity's flags must never
            // be served to the fresh anonymous id, so drop them (memory and
            // storage, under every policy - identity hygiene) BEFORE asking
            // the server for this id's flags.
            flags = emptyMap()
            flagsLoaded = false
            flagSource = null
            flagPersistedAt = null
            flagRequestSeq += 1
            storage.set(Keys.FLAGS, null)
            reportedExposures.clear()
            // Inside the lock, with the new id: a tracked event or a replay
            // chunk never pairs the new identity with the old session.
            replay?.rotate(distinctId)
            flagListeners.values.toList()
        }
        // The served set changed to empty: notify listeners outside the
        // lock, like reloadFeatureFlags, so subscribers drop the previous
        // identity's values immediately instead of holding them until the
        // next reload lands.
        for (listener in listeners) {
            try {
                listener(emptyMap())
            } catch (_: Exception) {
                // A listener must not break the reset for the others.
            }
        }
        reloadFeatureFlags()
    }

    // MARK: feature flags

    /**
     * Re-evaluates feature flags for the current distinct id via
     * `POST {apiHost}/flags/evaluate`, caching the result in memory and in
     * [storage] (scoped by distinct id). Blocking, like [flush]: the Android
     * wrapper runs it on its worker thread.
     *
     * Never throws and never clears the served flags on failure - a network
     * error, a non-2xx response or an unparseable body re-adopts the
     * persisted cache under the persistent `flagPersistence` modes (a
     * safety net - it was already adopted at init), and leaves whatever the
     * network previously served under `networkOnly`. Results are discarded
     * when the identity changed, or a newer reload started, while the
     * request was in flight.
     */
    fun reloadFeatureFlags() {
        val (requestedId, seq) = synchronized(lock) {
            flagRequestSeq += 1
            distinctId to flagRequestSeq
        }
        val body = JSONObject().put("distinct_id", requestedId)
        if (token != null) body.put("token", token)
        val raw = try {
            flagsFetcher.fetch("$apiHost/flags/evaluate", body.toString())
        } catch (_: Exception) {
            null
        }
        val parsed = parseFlagsResponse(raw)
        if (parsed == null) {
            servePersistedCacheAfterFailure()
            return
        }

        val listeners = synchronized(lock) {
            // Identity changed, or a newer reload started, while this request
            // was in flight: these results are stale, discard them.
            if (requestedId != distinctId || seq != flagRequestSeq) return
            flags = parsed
            flagsLoaded = true
            flagSource = FlagSource.network
            flagPersistedAt = null
            if (config.flagPersistence != FlagPersistenceMode.networkOnly) {
                persistFlags(requestedId)
            }
            flagListeners.values.toList()
        }
        for (listener in listeners) {
            try {
                listener(parsed)
            } catch (_: Exception) {
                // A listener must not break the reload for the others.
            }
        }
    }

    /**
     * Evaluation failed: re-adopt the persisted cache under the persistent
     * policies. Both persistent modes already adopted it at init, so this is
     * normally an idempotent safety net (it also covers a cache written
     * between init and now by another path). Once a network response has
     * landed this session its values are fresher than any cache the last
     * success wrote, so they win and the cache is not re-read. `networkOnly`
     * never touches it. Silent, like the sibling SDKs: a failed reload
     * notifies nobody.
     */
    private fun servePersistedCacheAfterFailure() {
        synchronized(lock) {
            if (config.flagPersistence == FlagPersistenceMode.networkOnly ||
                flagSource == FlagSource.network
            ) {
                return
            }
            loadPersistedFlagsLocked()
        }
    }

    /**
     * The flag's variant when it has one, else its enabled boolean. `false`
     * for an unknown flag or before flags have loaded.
     *
     * Reading a *known* flag also tracks `$feature_flag_called` once per
     * distinct value, per identity (see [exposureFor]).
     */
    fun getFeatureFlag(key: String): Any = getFeatureFlag(key, fallback = false)

    /**
     * The flag's value, or [fallback] when the flag is unknown or unloaded.
     *
     * A read that resolves to [fallback] is not an exposure: nothing was
     * assigned, so no `$feature_flag_called` is tracked (Mixpanel parity -
     * fallback variants never fire the exposure event).
     */
    fun getFeatureFlag(key: String, fallback: Any): Any = getFeatureFlagDetails(key, fallback).value

    /**
     * Whether [key] is enabled. False for an unknown or unloaded flag. Reports
     * an exposure on the same terms as [getFeatureFlag].
     */
    fun isFeatureEnabled(key: String): Boolean = isFeatureEnabled(key, fallback = false)

    /**
     * Whether [key] is enabled, or [fallback] when the flag is unknown or
     * unloaded. A read that resolves to [fallback] reports no exposure.
     */
    fun isFeatureEnabled(key: String, fallback: Boolean): Boolean {
        val enabled: Boolean
        val exposure: FlagExposure?
        synchronized(lock) {
            val flag = flags[key] ?: return fallback
            enabled = flag.enabled
            exposure = exposureFor(key, flag)
        }
        trackExposure(key, exposure)
        return enabled
    }

    /**
     * The full lookup result for [key]: the value plus `enabled`, `payload`
     * and where it came from ([FlagDetails.source] is [FlagSource.network],
     * [FlagSource.persistence] or [FlagSource.fallback], with `persistedAt`/
     * `ttlMs` populated for persistence-served values). A fallback result
     * reports `enabled` = the fallback's truthiness and `payload` = null.
     *
     * Reports an exposure on the same terms as [getFeatureFlag]: only a read
     * of a *known* flag counts, so a result sourced from the fallback never
     * reports one.
     */
    fun getFeatureFlagDetails(key: String, fallback: Any = false): FlagDetails {
        val details: FlagDetails
        val exposure: FlagExposure?
        synchronized(lock) {
            val flag = flags[key]
            if (flag == null) {
                details = FlagDetails(
                    key = key,
                    value = fallback,
                    enabled = truthy(fallback),
                    payload = null,
                    source = FlagSource.fallback,
                )
                exposure = null
            } else {
                val persisted = flagSource == FlagSource.persistence
                details = FlagDetails(
                    key = key,
                    value = flag.variant ?: flag.enabled,
                    enabled = flag.enabled,
                    payload = flag.payload,
                    source = flagSource ?: FlagSource.network,
                    persistedAt = if (persisted) flagPersistedAt else null,
                    ttlMs = if (persisted) flagCacheTtlMs else null,
                )
                exposure = exposureFor(key, flag)
            }
        }
        trackExposure(key, exposure)
        return details
    }

    /**
     * JavaScript-style truthiness of a fallback, for the `enabled` field of
     * a fallback [FlagDetails] (the sibling SDKs compute `Boolean(fallback)`):
     * `true`, non-zero numbers and non-empty strings count as "the feature
     * is on".
     */
    private fun truthy(fallback: Any): Boolean = when (fallback) {
        is Boolean -> fallback
        is String -> fallback.isNotEmpty()
        is Number -> fallback.toDouble() != 0.0
        else -> false
    }

    /** Everything a `$feature_flag_called` event needs beyond the flag key. */
    private data class FlagExposure(
        val response: Any,
        val source: FlagSource,
        val persistedAt: Long?,
        val ttlMs: Long?,
    )

    /**
     * Marks [key] as exposed and returns the `$feature_flag_response` value
     * plus the provenance to report, or null when nothing should be tracked
     * (exposure events off, or this key/value pair was already reported for
     * the current identity). Dedupe keys on (key, value) only, so the same
     * value served first from persistence and then from the network is
     * reported exactly once.
     *
     * Must be called while holding [lock].
     */
    private fun exposureFor(key: String, flag: FlagResult): FlagExposure? {
        if (!config.sendExposureEvents) return null
        if (!reportedExposures.add("$key:${flag.variant ?: flag.enabled}")) return null
        val persisted = flagSource == FlagSource.persistence
        return FlagExposure(
            response = flag.variant ?: flag.enabled,
            source = flagSource ?: FlagSource.network,
            persistedAt = if (persisted) flagPersistedAt else null,
            ttlMs = if (persisted) flagCacheTtlMs else null,
        )
    }

    /**
     * Queues the exposure event. Called OUTSIDE [lock] (right after the block
     * that decided it) so the ordering stays lock -> track, never track ->
     * lock, even though Kotlin's `synchronized` would tolerate the nesting.
     *
     * `$feature_flag_source` is always stamped ("network" or "persistence"),
     * matching Mixpanel's always-present `$variant_source`, so dashboards
     * never have to guess whether a missing property means "network" or
     * "old SDK". Persistence reads also carry `$feature_flag_persisted_at`
     * (epoch ms) and `$feature_flag_ttl_ms`, matching the sibling SDKs'
     * exposure payloads.
     */
    private fun trackExposure(key: String, exposure: FlagExposure?) {
        if (exposure == null) return
        val properties = mutableMapOf<String, Any>(
            "\$feature_flag" to key,
            "\$feature_flag_response" to exposure.response,
            "\$feature_flag_source" to exposure.source.name,
        )
        exposure.persistedAt?.let { properties["\$feature_flag_persisted_at"] = it }
        exposure.ttlMs?.let { properties["\$feature_flag_ttl_ms"] = it }
        track("\$feature_flag_called", properties)
    }

    /** The JSON payload attached to [key], or null when it has none. */
    fun getFeatureFlagPayload(key: String): Any? = synchronized(lock) { flags[key]?.payload }

    /** Every flag currently known for the active distinct id. */
    fun getFeatureFlags(): Map<String, FlagResult> = synchronized(lock) { LinkedHashMap(flags) }

    /**
     * Whether a flag set is currently being served (a successful network
     * evaluation or an adopted persisted cache). Goes back to false on
     * [setIdentity]/[reset] until the new identity's flags land -
     * subscribers use it to keep serving "unloaded" instead of getter
     * fallbacks while the next set is still loading.
     */
    fun areFeatureFlagsLoaded(): Boolean = synchronized(lock) { flagsLoaded }

    /**
     * Subscribes to flag updates. [listener] fires whenever the served set
     * changes: after every successful reload, when [identify]/[reset] drop
     * the previous identity's flags (with an empty map), and immediately at
     * subscription when flags are already loaded - including a set adopted
     * from the persisted cache at startup. A failed reload notifies nobody
     * (the persisted cache is re-adopted silently, matching the sibling
     * SDKs). Returns a function that removes the subscription.
     */
    fun onFeatureFlags(listener: (Map<String, FlagResult>) -> Unit): () -> Unit {
        val (id, loaded) = synchronized(lock) {
            flagListenerSeq += 1
            flagListeners[flagListenerSeq] = listener
            flagListenerSeq to (if (flagsLoaded) flags else null)
        }
        if (loaded != null) {
            try {
                listener(loaded)
            } catch (_: Exception) {
            }
        }
        return { synchronized(lock) { flagListeners.remove(id) } }
    }

    /** Must be called while holding [lock]. Never called under `networkOnly`. */
    private fun persistFlags(forDistinctId: String) {
        val serialized = JSONObject()
        for ((key, flag) in flags) {
            serialized.put(
                key,
                JSONObject()
                    .put("enabled", flag.enabled)
                    .put("variant", flag.variant ?: JSONObject.NULL)
                    .put("payload", flag.payload ?: JSONObject.NULL)
                    .put("reason", flag.reason),
            )
        }
        storage.set(
            Keys.FLAGS,
            JSONObject()
                .put("distinct_id", forDistinctId)
                .put("flags", serialized)
                .put("stored_at", clock.nowMillis())
                .toString(),
        )
    }

    /**
     * Reads the persisted flag cache and installs it as the served set when
     * it belongs to the current distinct id AND is usable under
     * [CohorlyConfig.flagPersistence] (see [isFlagCacheUsable]). Returns true
     * when the cache was served.
     *
     * A foreign-identity, too-stale or unparseable entry is dropped, never
     * served. A legacy cache with no `stored_at` (written before the TTL
     * existed) cannot be dated, so it is served indefinitely - the
     * graceful-upgrade path preserving the pre-TTL behavior - until the next
     * successful reload replaces it with the stamped format. Identity
     * switches adopt nothing: the single-slot entry is removed by
     * identify()/reset(), so it can only ever hold the outgoing identity's
     * cache. Must be called while holding [lock] - the constructor calls it
     * before the instance is published.
     */
    private fun loadPersistedFlagsLocked(): Boolean {
        val raw = storage.get(Keys.FLAGS)
        val cache = raw?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }
        // isNull covers both a missing key and an explicit JSON null - only
        // a numeric stored_at is a provable age (typeof === "number" in the
        // sibling SDKs); anything else is a legacy v1 cache.
        val storedAt = if (cache != null && !cache.isNull("stored_at")) {
            cache.optLong("stored_at")
        } else {
            null
        }
        val parsed = if (cache != null && cache.optString("distinct_id") == distinctId) {
            parseFlagsResponse(cache.toString())
        } else {
            null
        }
        if (cache == null || parsed == null || !isFlagCacheUsable(storedAt)) {
            // Another identity's, too-stale or corrupt: drop it rather than
            // reporting flags as loaded-and-empty.
            if (raw != null) storage.set(Keys.FLAGS, null)
            return false
        }
        flags = parsed
        flagsLoaded = true
        flagSource = FlagSource.persistence
        // null for a legacy cache without `stored_at` (unprovable age):
        // persistedAt stays absent on FlagDetails and the exposure, while
        // ttlMs is still reported.
        flagPersistedAt = storedAt
        // A future stored_at (clock moved backwards after the write) would
        // stay fresh on every adoption until real time catches up, outliving
        // the TTL bound. Normalize the stamp to now and rewrite the entry so
        // subsequent launches age it honestly.
        if (storedAt != null && storedAt > clock.nowMillis()) {
            flagPersistedAt = clock.nowMillis()
            persistFlags(distinctId)
        }
        return true
    }

    /**
     * True when a persisted cache may be served under
     * [CohorlyConfig.flagPersistence]. `networkFirst` is strict: only while
     * `stored_at` is within [CohorlyConfig.flagCacheTtlMs].
     * `persistenceUntilNetworkSuccess` trades freshness for offline
     * availability and serves until the wider of `flagCacheTtlMs` and
     * [FLAG_CACHE_MAX_STALE_MS] (inside the TTL always serves; expired still
     * serves up to 7 days stale). A legacy cache without `stored_at` has no
     * provable age: it is served indefinitely, matching the pre-TTL behavior
     * it was written under.
     */
    private fun isFlagCacheUsable(storedAt: Long?): Boolean {
        if (storedAt == null) return true
        // A future `stored_at` (clock moved backwards after the write)
        // yields a negative age that would bypass every bound below; clamp
        // it to fresh. The stamp itself is normalized to now on adoption
        // so later launches age it honestly instead of staying fresh until
        // the clock catches up.
        val age = maxOf(0L, clock.nowMillis() - storedAt)
        if (config.flagPersistence == FlagPersistenceMode.networkFirst) {
            return age <= flagCacheTtlMs
        }
        return age <= maxOf(flagCacheTtlMs, FLAG_CACHE_MAX_STALE_MS)
    }

    // MARK: super properties

    fun registerSuperProperties(properties: Map<String, Any?>) {
        synchronized(lock) {
            for ((key, value) in properties) superProps.put(key, wrap(value))
            persistSuperProps()
        }
    }

    fun registerSuperProperties(properties: JSONObject) {
        registerSuperProperties(toMap(properties))
    }

    fun registerSuperPropertiesOnce(properties: Map<String, Any?>) {
        synchronized(lock) {
            for ((key, value) in properties) {
                if (!superProps.has(key)) superProps.put(key, wrap(value))
            }
            persistSuperProps()
        }
    }

    fun registerSuperPropertiesOnce(properties: JSONObject) {
        registerSuperPropertiesOnce(toMap(properties))
    }

    fun unregisterSuperProperty(key: String) {
        synchronized(lock) {
            superProps.remove(key)
            persistSuperProps()
        }
    }

    fun clearSuperProperties() {
        synchronized(lock) {
            superProps = JSONObject()
            persistSuperProps()
        }
    }

    fun getSuperProperties(): JSONObject = synchronized(lock) { JSONObject(superProps.toString()) }

    // MARK: opt out

    fun optOutTracking() {
        synchronized(lock) {
            optedOut = true
            storage.set(Keys.OPT_OUT, "1")
            eventQueue.clear()
            engageQueue.clear()
            persistEventQueue()
            persistEngageQueue()
        }
        replay?.optOut()
    }

    fun optInTracking() {
        synchronized(lock) {
            optedOut = false
            storage.set(Keys.OPT_OUT, "0")
        }
        replay?.optIn()
    }

    fun hasOptedOutTracking(): Boolean = synchronized(lock) { optedOut }

    // MARK: track

    fun track(event: String) = track(event, null as Map<String, Any?>?)

    fun track(event: String, properties: JSONObject?) = track(event, properties?.let { toMap(it) })

    /**
     * Queues an event. Property precedence (lowest to highest): device
     * defaults < super properties < [properties] < reserved (`distinct_id`,
     * `time`, `$insert_id`, `$lib`, `token`). `$device_id` (always) and
     * `$user_id` (only when identified) are stamped last but never override a
     * value the caller passed explicitly in [properties].
     */
    fun track(event: String, properties: Map<String, Any?>?) = trackAt(event, properties, null)

    /** Same as [track] with the event `time` (and session lookup) taken from [timeMs] instead of now. */
    internal fun trackAt(event: String, properties: Map<String, Any?>?, timeMs: Long?) {
        synchronized(lock) {
            if (optedOut) return
            val now = timeMs ?: clock.nowMillis()
            val props = JSONObject()
            for ((key, value) in deviceInfo.defaultProperties()) {
                if (value != null) props.put(key, wrap(value))
            }
            for (key in superProps.keys()) props.put(key, superProps.get(key))
            if (properties != null) {
                for ((key, value) in properties) props.put(key, wrap(value))
            }
            val startMs = timedEvents.remove(event)
            if (startMs != null) {
                val durationSec = (clock.nowMillis() - startMs) / 1000.0
                props.put("\$duration", roundTo3(durationSec))
            }
            props.put("distinct_id", distinctId)
            props.put("time", now)
            props.put("\$insert_id", UUID.randomUUID().toString())
            props.put("\$lib", LIB_NAME)
            if (token != null) props.put("token", token)
            // Reserved identity fields, stamped last but never overriding a value
            // the caller passed explicitly (mirrors Mixpanel's $device_id/$user_id).
            if (!props.has("\$device_id")) props.put("\$device_id", deviceId)
            if (!anonymous && !props.has("\$user_id")) props.put("\$user_id", distinctId)
            // Read before the caller check: a tracked event is activity either way.
            val sessionId = replay?.sessionIdForEvent(now)
            if (sessionId != null && !props.has("\$session_id")) props.put("\$session_id", sessionId)

            eventQueue.addLast(JSONObject().put("event", event).put("properties", props))
            enforceEventQueueCap()
            persistEventQueue()
        }
    }

    // MARK: timed events

    /**
     * Starts a timer for [event]. The next [track] of the same event name
     * attaches `$duration` (elapsed seconds, rounded to 3 decimals) and clears
     * the timer. A second [timeEvent] before that track restarts the timer.
     */
    fun timeEvent(event: String) {
        synchronized(lock) { timedEvents[event] = clock.nowMillis() }
    }

    /** Cancels a pending timer so no `$duration` is attached. */
    fun clearTimedEvent(event: String) {
        synchronized(lock) { timedEvents.remove(event) }
    }

    /** Cancels all pending timers. */
    fun clearTimedEvents() {
        synchronized(lock) { timedEvents.clear() }
    }

    fun pendingEventCount(): Int = synchronized(lock) { eventQueue.size }

    fun pendingEngageCount(): Int = synchronized(lock) { engageQueue.size }

    // MARK: people

    inner class People internal constructor() {
        fun set(properties: Map<String, Any?>) = engage { it.put("\$set", toJson(withProfileDefaults(properties))) }
        fun setOnce(properties: Map<String, Any?>) =
            engage { it.put("\$set_once", toJson(withProfileDefaults(properties))) }
        fun increment(properties: Map<String, Any?>) = engage { it.put("\$add", toJson(properties)) }
        fun unset(keys: List<String>) = engage { it.put("\$unset", JSONArray(keys)) }
        fun deleteUser() = engage { it.put("\$delete", true) }
    }

    private fun engage(build: (JSONObject) -> Unit) {
        synchronized(lock) {
            if (optedOut) return
            val payload = JSONObject().put("distinct_id", distinctId)
            build(payload)
            if (token != null) payload.put("token", token)
            engageQueue.addLast(payload)
            persistEngageQueue()
        }
    }

    // MARK: automatic events (called by the Android lifecycle watcher)

    fun onAppForegrounded() {
        // Rotation first, so the foreground's first events carry the new session.
        replay?.onForeground()
        automaticEvents?.onForeground()
    }

    fun onAppBackgrounded() {
        automaticEvents?.onBackground()
        replay?.onBackground()
    }

    // MARK: screen views (called by the Android lifecycle watcher)

    /** An Activity became visible; a no-op unless `trackScreenViews` is on. */
    fun onScreenResumed(instance: Any) {
        screenViews?.onResumed(instance)
    }

    /** Dedupe decision only (call on the thread that owns the Activity); null when nothing is to be tracked. */
    fun resolveScreen(instance: Any): String? = screenViews?.resolve(instance)

    /** Tracks the `$screen` for a name from [resolveScreen] (call on the worker). */
    fun trackScreen(name: String, timeMs: Long? = null) {
        screenViews?.track(name, timeMs)
    }

    /** The clock of event times, so the glue can stamp a screen when it appears, not when the worker runs. */
    fun nowMillis(): Long = clock.nowMillis()

    /** An Activity was paused or destroyed; [isChangingConfigurations] marks a rotation-style recreation. */
    fun onScreenLeft(instance: Any, isChangingConfigurations: Boolean) {
        screenViews?.onLeft(instance, isChangingConfigurations)
    }

    // MARK: flush

    /** Forced flush: always attempts a send, even while backing off. */
    fun flush() = flushInternal(force = true)

    /**
     * Backoff-aware flush used by the auto-flush timer, size triggers, and
     * app-background hook; skipped until the backoff deadline passes.
     */
    fun maybeFlush() = flushInternal(force = false)

    private fun flushInternal(force: Boolean) {
        var events: List<JSONObject> = emptyList()
        var engages: List<JSONObject> = emptyList()
        synchronized(lock) {
            if (flushing) return
            if (!force && clock.nowMillis() < backoffUntilMs) return
            if (eventQueue.isEmpty() && engageQueue.isEmpty()) return
            flushing = true

            val size = maxOf(1, effectiveBatchSize)
            val batch = ArrayList<JSONObject>(min(size, eventQueue.size))
            while (batch.size < size && eventQueue.isNotEmpty()) {
                batch.add(eventQueue.removeFirst())
            }
            events = batch
            if (events.isNotEmpty()) persistEventQueue()

            engages = engageQueue.toList()
            engageQueue.clear()
            if (engages.isNotEmpty()) persistEngageQueue()
        }

        var eventError: Exception? = null
        if (events.isNotEmpty()) {
            try {
                transport.post("$apiHost/track", JSONArray(events).toString())
            } catch (e: Exception) {
                eventError = e
            }
        }
        // Skip the engage attempt when /track just failed (we are backing off).
        var engageFailed = false
        if (engages.isNotEmpty() && eventError == null) {
            try {
                transport.post("$apiHost/engage", JSONArray(engages).toString())
            } catch (_: Exception) {
                engageFailed = true
            }
        }

        synchronized(lock) {
            if (events.isNotEmpty()) handleEventFlushResult(events, eventError)
            if (engages.isNotEmpty() && (eventError != null || engageFailed)) {
                // Re-queue engage payloads at the front for the next attempt.
                for (payload in engages.asReversed()) engageQueue.addFirst(payload)
                persistEngageQueue()
            }
            flushing = false
        }
    }

    /** Applies the retry contract to the just-attempted /track batch. */
    private fun handleEventFlushResult(events: List<JSONObject>, error: Exception?) {
        if (error == null) {
            consecutiveFailures = 0
            backoffUntilMs = 0
            return
        }

        val status = (error as? CohorlyHttpException)?.status

        if (status == 400) {
            // Permanently rejected: already removed from the queue, so drop.
            return
        }

        if (status == 413) {
            if (events.size == 1) {
                // A single event alone exceeds the server body cap; dropping it
                // is the only way to avoid a retry livelock.
                effectiveBatchSize = 1
                return
            }
            for (event in events.asReversed()) eventQueue.addFirst(event)
            persistEventQueue()
            effectiveBatchSize = maxOf(1, effectiveBatchSize / 2)
            return
        }

        // 429 / 401 / 5xx / network: re-queue at the front for the next attempt.
        for (event in events.asReversed()) eventQueue.addFirst(event)
        persistEventQueue()

        consecutiveFailures += 1
        var delayMs = computeBackoffMs()
        if (status == 401) {
            delayMs = config.maxRetryDelayMs
        } else if (status == 429) {
            val retryAfterMs = (error as CohorlyHttpException).retryAfterMs
            if (retryAfterMs != null) delayMs = min(retryAfterMs, config.maxRetryDelayMs)
        }
        backoffUntilMs = clock.nowMillis() + delayMs
    }

    /**
     * Exponential backoff: base 2s, doubling per consecutive failure, capped
     * at [CohorlyConfig.maxRetryDelayMs], with +/-20% jitter.
     */
    private fun computeBackoffMs(): Long = Backoff.delayMs(consecutiveFailures, config.maxRetryDelayMs, jitterSource())

    /** Trims the queue to maxQueueSize, dropping the OLDEST events. */
    private fun enforceEventQueueCap(): Boolean {
        var dropped = false
        while (eventQueue.size > config.maxQueueSize) {
            eventQueue.removeFirst()
            dropped = true
        }
        return dropped
    }

    // MARK: persistence

    private fun persistEventQueue() {
        storage.set(Keys.EVENT_QUEUE, JSONArray(eventQueue.toList()).toString())
    }

    private fun persistEngageQueue() {
        storage.set(Keys.ENGAGE_QUEUE, JSONArray(engageQueue.toList()).toString())
    }

    private fun persistSuperProps() {
        storage.set(Keys.SUPER_PROPS, superProps.toString())
    }

    private fun readJsonObject(key: String): JSONObject {
        val raw = storage.get(key) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun readJsonArrayInto(key: String, target: ArrayDeque<JSONObject>) {
        val raw = storage.get(key) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) target.addLast(array.getJSONObject(i))
        } catch (_: Exception) {
            target.clear()
        }
    }

    // MARK: JSON helpers

    /**
     * Merges the device provider's default profile properties under
     * [properties] so user-supplied keys win. Only non-null defaults are added,
     * and only keys the caller did not already provide.
     */
    private fun withProfileDefaults(properties: Map<String, Any?>): Map<String, Any?> {
        val defaults = deviceInfo.profileProperties()
        if (defaults.isEmpty()) return properties
        val merged = LinkedHashMap<String, Any?>()
        for ((key, value) in defaults) {
            if (value != null && !properties.containsKey(key)) merged[key] = value
        }
        merged.putAll(properties)
        return merged
    }

    /** Rounds a seconds value to 3 decimal places for `$duration`. */
    private fun roundTo3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    private fun wrap(value: Any?): Any = JSONObject.wrap(value) ?: JSONObject.NULL

    private fun toJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) json.put(key, wrap(value))
        return json
    }

    private fun toMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in json.keys()) map[key] = json.get(key)
        return map
    }
}
