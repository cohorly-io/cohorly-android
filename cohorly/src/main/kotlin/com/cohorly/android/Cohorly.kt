package com.cohorly.android

import android.app.Application
import android.content.Context
import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.CohorlyFlagsFetcher
import com.cohorly.android.core.CohorlyTransport
import com.cohorly.android.core.FlagDetails
import com.cohorly.android.core.FlagResult
import com.cohorly.android.core.HttpUrlConnectionFlagsFetcher
import com.cohorly.android.core.HttpUrlConnectionTransport
import com.cohorly.android.core.crash.CrashConfig
import com.cohorly.android.crash.CohorlyCrashReporter
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Cohorly Android SDK - Mixpanel-style product analytics for a hosted
 * Cohorly server.
 *
 * ```kotlin
 * val cohorly = Cohorly.getInstance(context, "PROJECT_TOKEN")
 * cohorly.track("Signed Up", JSONObject().put("plan", "pro"))
 * ```
 *
 * One instance per project token (like mixpanel-android). All work runs on a
 * single background thread; every public method returns immediately.
 */
class Cohorly private constructor(
    private val core: CohorlyCore,
    private val executor: ScheduledExecutorService,
    private val app: Context,
    private val token: String,
    private val apiHost: String,
) {
    private val batchSize = core.config.batchSize.coerceIn(1, CohorlyCore.SERVER_MAX_BATCH)

    val people: People = People()

    companion object {
        private val instances = HashMap<String, Cohorly>()

        /** Cohorly's hosted ingestion API, used when no apiHost is supplied. */
        const val DEFAULT_API_HOST = "https://cohorly-service.velloalabs.com"

        /**
         * Returns the singleton for [token], creating it on first call.
         *
         * @param context any context; the application context is retained.
         * @param token   the Cohorly project token.
         * @param apiHost the Cohorly ingestion origin (events go to `{apiHost}/track`).
         *                Defaults to the hosted endpoint [DEFAULT_API_HOST]; only set
         *                this to point at a different deployment.
         */
        @JvmStatic
        @JvmOverloads
        fun getInstance(
            context: Context,
            token: String,
            apiHost: String = DEFAULT_API_HOST,
            config: CohorlyConfig = CohorlyConfig(),
            transport: CohorlyTransport = HttpUrlConnectionTransport(),
            flagsFetcher: CohorlyFlagsFetcher = HttpUrlConnectionFlagsFetcher(),
        ): Cohorly {
            synchronized(instances) {
                instances[token]?.let { return it }

                val app = context.applicationContext
                val storage = NoBackupFileStorage(app, token)
                // getInstance runs on the caller's thread, normally the main
                // one. On a fresh install the core's constructor persists the
                // distinct id, the anonymous flag and the device id; batching
                // makes that one write+fsync instead of three.
                val core = storage.batch {
                    CohorlyCore(
                        apiHost = apiHost,
                        token = token,
                        storage = storage,
                        transport = transport,
                        deviceInfo = AndroidDeviceInfo(app),
                        flagsFetcher = flagsFetcher,
                        config = config,
                    )
                }
                val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "cohorly-worker").apply { isDaemon = true }
                }
                if (config.flushIntervalMs > 0) {
                    executor.scheduleWithFixedDelay(
                        { core.maybeFlush() },
                        config.flushIntervalMs,
                        config.flushIntervalMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
                val instance = Cohorly(core, executor, app, token, apiHost.trimEnd('/'))
                instance.syncCrashConfig()
                // First flag evaluation for the persisted identity, off the
                // caller's thread. Fire and forget - failures keep the cache.
                if (config.loadFeatureFlags) {
                    executor.execute { core.reloadFeatureFlags() }
                }
                (app as? Application)?.registerActivityLifecycleCallbacks(
                    LifecycleWatcher(
                        onForeground = { executor.execute { core.onAppForegrounded() } },
                        onBackground = {
                            executor.execute {
                                core.onAppBackgrounded()
                                core.maybeFlush()
                            }
                        },
                    ),
                )
                instances[token] = instance
                return instance
            }
        }

        /** Convenience overload mirroring mixpanel's trackAutomaticEvents flag. */
        @JvmStatic
        fun getInstance(context: Context, token: String, apiHost: String, trackAutomaticEvents: Boolean): Cohorly =
            getInstance(
                context,
                token,
                apiHost,
                CohorlyConfig(trackAutomaticEvents = trackAutomaticEvents),
            )
    }

    // MARK: track

    fun track(eventName: String) {
        executor.execute {
            core.track(eventName)
            flushIfBatchReady()
        }
    }

    fun track(eventName: String, properties: JSONObject?) {
        // Snapshot on the calling thread so later mutations don't leak in.
        val snapshot = properties?.toString()
        executor.execute {
            core.track(eventName, snapshot?.let { JSONObject(it) })
            flushIfBatchReady()
        }
    }

    fun track(eventName: String, properties: Map<String, Any?>?) {
        val snapshot = properties?.let { HashMap(it) }
        executor.execute {
            core.track(eventName, snapshot)
            flushIfBatchReady()
        }
    }

    // MARK: timed events

    /**
     * Starts timing [eventName]; the next [track] of the same name attaches a
     * `$duration` property (elapsed seconds, 3 decimals). Order is preserved by
     * the single worker thread, so a `timeEvent` then `track` pair is reliable.
     */
    fun timeEvent(eventName: String) {
        executor.execute { core.timeEvent(eventName) }
    }

    /** Cancels a pending [timeEvent] timer for [eventName]. */
    fun clearTimedEvent(eventName: String) {
        executor.execute { core.clearTimedEvent(eventName) }
    }

    /** Cancels all pending [timeEvent] timers. */
    fun clearTimedEvents() {
        executor.execute { core.clearTimedEvents() }
    }

    // MARK: identity

    /**
     * Switches identity to [distinctId]. While the previous id is still the
     * anonymous one, an `/alias` linking the two is sent first (like the
     * Cohorly JS core SDK; note mixpanel-android's identify does not alias).
     */
    fun identify(distinctId: String) {
        val wasDifferent = core.getDistinctId() != distinctId
        val alias = core.setIdentity(distinctId)
        if (alias != null) executor.execute { core.sendAlias(alias) }
        syncCrashConfig()
        // Flags are per-identity: refresh them for the new id on the worker.
        if (wasDifferent && core.getDistinctId() == distinctId) {
            executor.execute { core.reloadFeatureFlags() }
        }
    }

    /** Current distinct id (anonymous uuid until [identify]). */
    fun getDistinctId(): String = core.getDistinctId()

    /** Stable per-device id stamped as `$device_id` on every event. */
    fun getDeviceId(): String = core.getDeviceId()

    /** True until [identify] assigns a real user id. */
    fun isAnonymous(): Boolean = core.isAnonymous()

    /** Clears identity, super properties, and unsent data (call on logout). */
    fun reset() {
        executor.execute { core.reset() }
        syncCrashConfig()
    }

    // MARK: feature flags

    /**
     * Re-evaluates feature flags for the current user on the worker thread.
     * Returns immediately; subscribe with [onFeatureFlags] to be told when the
     * new values landed. Failures are silent and keep the cached flags.
     */
    fun reloadFeatureFlags() {
        executor.execute { core.reloadFeatureFlags() }
    }

    /**
     * The flag's variant (String) when it has one, else its enabled Boolean.
     * `false` for an unknown flag or before flags have loaded. Reads the cache,
     * so it never blocks on the network.
     */
    fun getFeatureFlag(key: String): Any = core.getFeatureFlag(key)

    /**
     * The flag's value, or [fallback] when the flag is unknown or unloaded.
     * A read that resolves to [fallback] reports no `$feature_flag_called`
     * exposure.
     */
    fun getFeatureFlag(key: String, fallback: Any): Any = core.getFeatureFlag(key, fallback)

    /** Whether [key] is enabled. False for an unknown or unloaded flag. */
    fun isFeatureEnabled(key: String): Boolean = core.isFeatureEnabled(key)

    /**
     * Whether [key] is enabled, or [fallback] when the flag is unknown or
     * unloaded. A read that resolves to [fallback] reports no exposure.
     */
    fun isFeatureEnabled(key: String, fallback: Boolean): Boolean = core.isFeatureEnabled(key, fallback)

    /**
     * The full lookup result for [key]: the value plus its [FlagDetails.source]
     * (`network`, `persistence` or `fallback`), `enabled` and `payload`, with
     * `persistedAt`/`ttlMs` populated for values served from the persisted
     * cache.
     */
    fun getFeatureFlagDetails(key: String, fallback: Any = false): FlagDetails =
        core.getFeatureFlagDetails(key, fallback)

    /** The JSON payload attached to [key], or null when it has none. */
    fun getFeatureFlagPayload(key: String): Any? = core.getFeatureFlagPayload(key)

    /** Every flag currently known for the active user. */
    fun getFeatureFlags(): Map<String, FlagResult> = core.getFeatureFlags()

    /**
     * Whether a flag set is currently being served. Goes back to false on
     * [identify]/[reset] until the new identity's flags land.
     */
    fun areFeatureFlagsLoaded(): Boolean = core.areFeatureFlagsLoaded()

    /**
     * Subscribes to flag updates: fires whenever the served set changes -
     * after every successful reload, when [identify]/[reset] drop the
     * previous identity's flags (with an empty map), and immediately when
     * flags are already loaded - including a set adopted from the persisted
     * cache at startup. A failed reload notifies nobody - the persisted
     * cache is re-adopted silently. Listeners run on the worker thread.
     * Returns a function that removes the subscription.
     */
    fun onFeatureFlags(listener: (Map<String, FlagResult>) -> Unit): () -> Unit = core.onFeatureFlags { flags ->
        // identify()/reset() clear the served set on the caller thread
        // while reload callbacks run on the worker - dispatch every
        // delivery through the executor so a listener always runs on the
        // worker thread (and the identity-clear emission stays ordered
        // before the reload queued right after it).
        executor.execute { listener(flags) }
    }

    // MARK: super properties

    fun registerSuperProperties(properties: JSONObject) {
        val snapshot = properties.toString()
        executor.execute { core.registerSuperProperties(JSONObject(snapshot)) }
    }

    fun registerSuperProperties(properties: Map<String, Any?>) {
        val snapshot = HashMap(properties)
        executor.execute { core.registerSuperProperties(snapshot) }
    }

    fun registerSuperPropertiesOnce(properties: JSONObject) {
        val snapshot = properties.toString()
        executor.execute { core.registerSuperPropertiesOnce(JSONObject(snapshot)) }
    }

    fun registerSuperPropertiesOnce(properties: Map<String, Any?>) {
        val snapshot = HashMap(properties)
        executor.execute { core.registerSuperPropertiesOnce(snapshot) }
    }

    fun unregisterSuperProperty(key: String) {
        executor.execute { core.unregisterSuperProperty(key) }
    }

    fun clearSuperProperties() {
        executor.execute { core.clearSuperProperties() }
    }

    // MARK: opt out

    fun optOutTracking() {
        executor.execute { core.optOutTracking() }
        syncCrashConfig()
    }

    fun optInTracking() {
        executor.execute { core.optInTracking() }
        syncCrashConfig()
    }

    fun hasOptedOutTracking(): Boolean = core.hasOptedOutTracking()

    // MARK: flush

    /** Sends queued data now (bypasses retry backoff). */
    fun flush() {
        executor.execute { core.flush() }
    }

    // MARK: people

    // `val people` exposes getPeople() to Java callers, mirroring
    // mixpanel-android's getPeople(); Kotlin callers use `cohorly.people`.

    /** Profile updates, mapped to Cohorly `/engage` ops. */
    inner class People internal constructor() {
        fun set(property: String, value: Any?) = set(mapOf(property to value))

        fun set(properties: JSONObject) = set(toMap(properties))

        fun set(properties: Map<String, Any?>) {
            val snapshot = HashMap(properties)
            executor.execute {
                core.people.set(snapshot)
                flushIfEngageBatchReady()
            }
        }

        fun setOnce(properties: JSONObject) = setOnce(toMap(properties))

        fun setOnce(properties: Map<String, Any?>) {
            val snapshot = HashMap(properties)
            executor.execute {
                core.people.setOnce(snapshot)
                flushIfEngageBatchReady()
            }
        }

        fun increment(property: String, by: Double) = increment(mapOf(property to by))

        fun increment(properties: Map<String, Any?>) {
            val snapshot = HashMap(properties)
            executor.execute {
                core.people.increment(snapshot)
                flushIfEngageBatchReady()
            }
        }

        fun unset(property: String) = unset(listOf(property))

        fun unset(properties: List<String>) {
            val snapshot = ArrayList(properties)
            executor.execute {
                core.people.unset(snapshot)
                flushIfEngageBatchReady()
            }
        }

        fun deleteUser() {
            executor.execute {
                core.people.deleteUser()
                flushIfEngageBatchReady()
            }
        }
    }

    // MARK: internals

    /**
     * With [CohorlyConfig.nativeCrashReporting], writes the crash config for
     * the reporter that starts on the next launch, and updates the identity of
     * one already running. Opting out turns it off: no crash is recorded, and
     * no pending report or exit record is sent, until opt-in. Without
     * nativeCrashReporting, the config is left untouched: a React Native layer
     * may be the one managing it.
     */
    private fun syncCrashConfig() {
        if (!core.config.nativeCrashReporting) return
        // Queued after any identity change already on the worker, so it reads the result.
        executor.execute {
            val config = CrashConfig(
                enabled = !core.hasOptedOutTracking(),
                release = CohorlyCrashReporter.appInfo(app).defaultRelease,
                distinctId = core.getDistinctId(),
                deviceId = core.getDeviceId(),
                token = token,
                apiHost = apiHost,
                anrWatchdog = core.config.anrWatchdog,
            )
            CohorlyCrashReporter.configure(app, config.toJson())
        }
    }

    private fun flushIfBatchReady() {
        if (core.pendingEventCount() >= batchSize) core.maybeFlush()
    }

    private fun flushIfEngageBatchReady() {
        if (core.pendingEngageCount() >= batchSize) core.maybeFlush()
    }

    private fun toMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in json.keys()) map[key] = json.get(key)
        return map
    }
}
