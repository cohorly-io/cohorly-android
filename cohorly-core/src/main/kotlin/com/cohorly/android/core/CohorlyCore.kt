package com.cohorly.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
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
) {
    private object Keys {
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
    }

    companion object {
        /** Cohorly's mp_lib equivalent, stamped as `$lib` on every event. */
        const val LIB_NAME = "android"

        /** Server rejects /track batches above this size with a 400. */
        const val SERVER_MAX_BATCH = 500

        /** Exponential backoff base, ms. */
        const val BACKOFF_BASE_MS = 2_000L
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

    /** Last known flag results for the current distinct id. */
    private var flags: Map<String, FlagResult> = emptyMap()

    /** True once flags were loaded, from the persisted cache or a reload. */
    private var flagsLoaded = false

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

    val people: People = People()

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

        // Feature-flag cache: served only when it belongs to the current
        // distinct id; another identity's cache is dropped, never served.
        val cache = storage.get(Keys.FLAGS)?.let { raw ->
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
        }
        if (cache != null) {
            val parsed = if (cache.optString("distinct_id") == distinctId) {
                parseFlagsResponse(cache.toString())
            } else {
                null
            }
            if (parsed != null) {
                flags = parsed
                flagsLoaded = true
            } else {
                // Another identity's cache, or an unparseable one: drop it
                // rather than reporting flags as loaded-and-empty.
                storage.set(Keys.FLAGS, null)
            }
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
    fun setIdentity(newDistinctId: String): JSONObject? = synchronized(lock) {
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
        storage.set(Keys.DISTINCT_ID, distinctId)
        storage.set(Keys.ANONYMOUS, "0")
        // Exposures are per-identity: the new user must report its own.
        reportedExposures.clear()
        aliasPayload
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
        synchronized(lock) {
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
            // storage) BEFORE asking the server for this id's flags.
            flags = emptyMap()
            flagsLoaded = false
            flagRequestSeq += 1
            storage.set(Keys.FLAGS, null)
            reportedExposures.clear()
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
     * Never throws and never clears the cache on failure - a network error, a
     * non-2xx response or an unparseable body simply leaves the previously
     * known flags in place. Results are discarded when the identity changed,
     * or a newer reload started, while the request was in flight.
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
        val parsed = parseFlagsResponse(raw) ?: return

        val listeners = synchronized(lock) {
            // Identity changed, or a newer reload started, while this request
            // was in flight: these results are stale, discard them.
            if (requestedId != distinctId || seq != flagRequestSeq) return
            flags = parsed
            flagsLoaded = true
            persistFlags(requestedId)
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
     * The flag's variant when it has one, else its enabled boolean. `false`
     * for an unknown flag or before flags have loaded.
     *
     * Reading a *known* flag also tracks `$feature_flag_called` once per
     * distinct value, per identity (see [exposureFor]).
     */
    fun getFeatureFlag(key: String): Any {
        val value: Any
        val exposure: Any?
        synchronized(lock) {
            val flag = flags[key] ?: return false
            value = flag.variant ?: flag.enabled
            exposure = exposureFor(key, flag)
        }
        trackExposure(key, exposure)
        return value
    }

    /**
     * Whether [key] is enabled. False for an unknown or unloaded flag. Reports
     * an exposure on the same terms as [getFeatureFlag].
     */
    fun isFeatureEnabled(key: String): Boolean {
        val enabled: Boolean
        val exposure: Any?
        synchronized(lock) {
            val flag = flags[key] ?: return false
            enabled = flag.enabled
            exposure = exposureFor(key, flag)
        }
        trackExposure(key, exposure)
        return enabled
    }

    /**
     * Marks [key] as exposed and returns the `$feature_flag_response` value to
     * report, or null when nothing should be tracked (exposure events off, or
     * this key/value pair was already reported for the current identity).
     *
     * Must be called while holding [lock].
     */
    private fun exposureFor(key: String, flag: FlagResult): Any? {
        if (!config.sendExposureEvents) return null
        if (!reportedExposures.add("$key:${flag.variant ?: flag.enabled}")) return null
        return flag.variant ?: flag.enabled
    }

    /**
     * Queues the exposure event. Called OUTSIDE [lock] (right after the block
     * that decided it) so the ordering stays lock -> track, never track ->
     * lock, even though Kotlin's `synchronized` would tolerate the nesting.
     */
    private fun trackExposure(key: String, response: Any?) {
        if (response == null) return
        track(
            "\$feature_flag_called",
            mapOf("\$feature_flag" to key, "\$feature_flag_response" to response),
        )
    }

    /** The JSON payload attached to [key], or null when it has none. */
    fun getFeatureFlagPayload(key: String): Any? = synchronized(lock) { flags[key]?.payload }

    /** Every flag currently known for the active distinct id. */
    fun getFeatureFlags(): Map<String, FlagResult> = synchronized(lock) { LinkedHashMap(flags) }

    /**
     * Subscribes to flag updates. [listener] fires after every successful
     * reload, and immediately when flags are already loaded. Returns a function
     * that removes the subscription.
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

    /** Must be called while holding [lock]. */
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
            JSONObject().put("distinct_id", forDistinctId).put("flags", serialized).toString(),
        )
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
    }

    fun optInTracking() {
        synchronized(lock) {
            optedOut = false
            storage.set(Keys.OPT_OUT, "0")
        }
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
    fun track(event: String, properties: Map<String, Any?>?) {
        synchronized(lock) {
            if (optedOut) return
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
            props.put("time", clock.nowMillis())
            props.put("\$insert_id", UUID.randomUUID().toString())
            props.put("\$lib", LIB_NAME)
            if (token != null) props.put("token", token)
            // Reserved identity fields, stamped last but never overriding a value
            // the caller passed explicitly (mirrors Mixpanel's $device_id/$user_id).
            if (!props.has("\$device_id")) props.put("\$device_id", deviceId)
            if (!anonymous && !props.has("\$user_id")) props.put("\$user_id", distinctId)

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
        automaticEvents?.onForeground()
    }

    fun onAppBackgrounded() {
        automaticEvents?.onBackground()
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
    private fun computeBackoffMs(): Long {
        val exp = BACKOFF_BASE_MS * 2.0.pow(consecutiveFailures - 1)
        val capped = min(exp, config.maxRetryDelayMs.toDouble())
        val jitter = capped * 0.2 * jitterSource()
        return (capped + jitter).toLong().coerceAtLeast(0)
    }

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
