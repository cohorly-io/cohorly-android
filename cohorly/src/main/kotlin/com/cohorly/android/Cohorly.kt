package com.cohorly.android

import android.app.Application
import android.content.Context
import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.CohorlyCore
import com.cohorly.android.core.CohorlyTransport
import com.cohorly.android.core.HttpUrlConnectionTransport
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Cohorly Android SDK - Mixpanel-style product analytics for a self-hosted
 * Cohorly server.
 *
 * ```kotlin
 * val cohorly = Cohorly.getInstance(context, "PROJECT_TOKEN", "https://analytics.example.com")
 * cohorly.track("Signed Up", JSONObject().put("plan", "pro"))
 * ```
 *
 * One instance per project token (like mixpanel-android). All work runs on a
 * single background thread; every public method returns immediately.
 */
class Cohorly private constructor(
    private val core: CohorlyCore,
    private val executor: ScheduledExecutorService,
) {
    private val batchSize = core.config.batchSize.coerceIn(1, CohorlyCore.SERVER_MAX_BATCH)

    val people: People = People()

    companion object {
        private val instances = HashMap<String, Cohorly>()

        /**
         * Returns the singleton for [token], creating it on first call.
         *
         * @param context any context; the application context is retained.
         * @param token   the Cohorly project token.
         * @param apiHost the Cohorly server origin, e.g. `https://analytics.example.com`
         *                (events go to `{apiHost}/track`).
         */
        @JvmStatic
        @JvmOverloads
        fun getInstance(
            context: Context,
            token: String,
            apiHost: String,
            config: CohorlyConfig = CohorlyConfig(),
            transport: CohorlyTransport = HttpUrlConnectionTransport(),
        ): Cohorly {
            synchronized(instances) {
                instances[token]?.let { return it }

                val app = context.applicationContext
                val core = CohorlyCore(
                    apiHost = apiHost,
                    token = token,
                    storage = SharedPreferencesStorage(app, token),
                    transport = transport,
                    deviceInfo = AndroidDeviceInfo(app),
                    config = config,
                )
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
                val instance = Cohorly(core, executor)
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
        fun getInstance(
            context: Context,
            token: String,
            apiHost: String,
            trackAutomaticEvents: Boolean,
        ): Cohorly = getInstance(
            context, token, apiHost,
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

    // MARK: identity

    /**
     * Switches identity to [distinctId]. While the previous id is still the
     * anonymous one, an `/alias` linking the two is sent first (like the
     * Cohorly JS core SDK; note mixpanel-android's identify does not alias).
     */
    fun identify(distinctId: String) {
        val alias = core.setIdentity(distinctId)
        if (alias != null) executor.execute { core.sendAlias(alias) }
    }

    /** Current distinct id (anonymous uuid until [identify]). */
    fun getDistinctId(): String = core.getDistinctId()

    /** True until [identify] assigns a real user id. */
    fun isAnonymous(): Boolean = core.isAnonymous()

    /** Clears identity, super properties, and unsent data (call on logout). */
    fun reset() {
        executor.execute { core.reset() }
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
    }

    fun optInTracking() {
        executor.execute { core.optInTracking() }
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
