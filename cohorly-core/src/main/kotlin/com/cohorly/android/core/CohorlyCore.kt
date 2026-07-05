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
    private val clock: CohorlyClock = SystemClock(),
    val config: CohorlyConfig = CohorlyConfig(),
    /** Jitter source in [-1, 1]; injectable for deterministic backoff tests. */
    private val jitterSource: () -> Double = { Random.nextDouble() * 2 - 1 },
) {
    private object Keys {
        const val DISTINCT_ID = "cohorly.distinct_id"
        const val ANONYMOUS = "cohorly.anonymous"
        const val SUPER_PROPS = "cohorly.super_properties"
        const val EVENT_QUEUE = "cohorly.event_queue"
        const val ENGAGE_QUEUE = "cohorly.engage_queue"
        const val OPT_OUT = "cohorly.opt_out"
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
    private var anonymous: Boolean
    private var superProps: JSONObject
    private val eventQueue = ArrayDeque<JSONObject>()
    private val engageQueue = ArrayDeque<JSONObject>()
    private var optedOut: Boolean

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
        optedOut = storage.get(Keys.OPT_OUT) == "1"
        superProps = readJsonObject(Keys.SUPER_PROPS)
        readJsonArrayInto(Keys.EVENT_QUEUE, eventQueue)
        readJsonArrayInto(Keys.ENGAGE_QUEUE, engageQueue)
        // Enforce the cap on load in case a larger queue was persisted.
        if (enforceEventQueueCap()) persistEventQueue()

        effectiveBatchSize = batchSize

        automaticEvents = if (config.trackAutomaticEvents) {
            AutomaticEvents(this, storage, clock).also { it.start(deviceInfo.appVersion()) }
        } else {
            null
        }
    }

    // MARK: identity

    fun getDistinctId(): String = synchronized(lock) { distinctId }

    fun isAnonymous(): Boolean = synchronized(lock) { anonymous }

    /**
     * Switches identity. When the current id is still anonymous, first sends
     * `/alias { alias: <anonymousId>, distinct_id: <newId> }` (best effort,
     * blocking - the Android wrapper splits this via [setIdentity] +
     * [sendAlias] so the network hop runs on its background thread).
     */
    fun identify(newDistinctId: String) {
        val alias = setIdentity(newDistinctId)
        if (alias != null) sendAlias(alias)
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
            anonymous = true
            superProps = JSONObject()
            eventQueue.clear()
            engageQueue.clear()
            storage.set(Keys.DISTINCT_ID, distinctId)
            storage.set(Keys.ANONYMOUS, "1")
            persistSuperProps()
            persistEventQueue()
            persistEngageQueue()
        }
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

    fun track(event: String, properties: JSONObject?) =
        track(event, properties?.let { toMap(it) })

    /**
     * Queues an event. Property precedence (lowest to highest): device
     * defaults < super properties < [properties] < reserved (`distinct_id`,
     * `time`, `$insert_id`, `$lib`, `token`).
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
            props.put("distinct_id", distinctId)
            props.put("time", clock.nowMillis())
            props.put("\$insert_id", UUID.randomUUID().toString())
            props.put("\$lib", LIB_NAME)
            if (token != null) props.put("token", token)

            eventQueue.addLast(JSONObject().put("event", event).put("properties", props))
            enforceEventQueueCap()
            persistEventQueue()
        }
    }

    fun pendingEventCount(): Int = synchronized(lock) { eventQueue.size }

    fun pendingEngageCount(): Int = synchronized(lock) { engageQueue.size }

    // MARK: people

    inner class People internal constructor() {
        fun set(properties: Map<String, Any?>) = engage { it.put("\$set", toJson(properties)) }
        fun setOnce(properties: Map<String, Any?>) = engage { it.put("\$set_once", toJson(properties)) }
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
