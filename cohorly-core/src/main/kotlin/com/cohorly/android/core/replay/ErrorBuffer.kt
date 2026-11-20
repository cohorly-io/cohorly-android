package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyClock
import org.json.JSONObject

/** What [ErrorBuffer.cut] hands over: one chunk's events, from a Meta + FullSnapshot to the error. */
@CohorlyReplayInternal
data class ErrorCut(val sessionId: String, val events: List<JSONObject>)

/**
 * The error-mode replay buffer (VEL-2151), the rules of `@cohorly/web`'s
 * `pushToBuffer` / `sendErrorBuffer`: a session sampled for `error`
 * recording keeps its recent events in memory, and only an error sends
 * them (nothing leaves the device otherwise).
 *
 * The buffer is a list of segments. Each one starts at a Meta event (type 4,
 * the start of a full snapshot) and holds every event after it until the
 * next Meta. Its leading Meta and FullSnapshot (type 2) are screen state
 * (`lead`); everything else (a Mutation, a touch) is activity. An event that
 * arrives with no segment open is dropped: on its own it is unusable.
 *
 * [cut] returns the window an error sends: it always starts at a Meta +
 * FullSnapshot, covers at least [MIN_WINDOW_MS] when the recording is that
 * old, never holds activity older than [WINDOW_MS], and ends now. The
 * recorder asks for a new full snapshot every [CHECKOUT_EVERY_MS] while
 * there is activity ([checkoutDue]), so such a start exists.
 *
 * Memory is bounded by [maxBytes] of serialized events (UTF-8 bytes of each
 * event's JSON): the oldest segments go whole first, never the current one;
 * a single segment over the bound asks for a checkout instead.
 *
 * Thread-safe: the recorder thread adds and cuts, and the fatal crash
 * handler cuts from the crashing thread, so every method holds the lock.
 */
@CohorlyReplayInternal
class ErrorBuffer(private val clock: CohorlyClock, private val maxBytes: Long = MAX_BYTES) {
    private class Segment(val start: Long) {
        val events = ArrayList<JSONObject>()

        /** How many leading events are screen state (Meta, FullSnapshot), not activity. */
        var lead = 0

        /** Timestamp of the first activity event, or null while there is none. */
        var firstActivity: Long? = null
        var bytes = 0L
    }

    private var sessionId: String? = null
    private val segments = ArrayList<Segment>()
    private var bytes = 0L

    /** Activity was buffered since the last segment started. */
    private var dirtySinceCheckout = false

    /** Activity was buffered since the last cut (true at first: an idle screen is still worth one send). */
    private var dirtySinceSend = true

    /** A new segment is needed now: one segment went over [maxBytes], or activity arrived with none open. */
    private var needsCheckout = false

    /** The `data` of the last full snapshot buffered: a different one is a screen change, so activity. */
    private var lastFullSnapshot: String? = null

    /** When the last [cut] happened, for [SEND_GAP_MS]. Kept across sessions, like the web SDK. */
    private var lastSendAt: Long? = null

    /** The session the buffered events belong to, or null when the buffer is empty and unbound. */
    @get:Synchronized
    val currentSessionId: String?
        get() = sessionId

    /** Serialized bytes held now. */
    @get:Synchronized
    val size: Long
        get() = bytes

    /**
     * Adds one group of events of [sessionId], in time order (a Meta +
     * FullSnapshot, a Mutation, a touch). Each Meta opens a new segment.
     * Events of another session than the buffered one replace everything
     * held (the old session's buffer is useless to the new one).
     */
    @Synchronized
    fun add(sessionId: String, events: List<JSONObject>) {
        if (events.isEmpty()) return
        if (this.sessionId != sessionId) {
            reset()
            this.sessionId = sessionId
        }
        for (event in events) push(event)
        enforceBound()
    }

    /**
     * True when the recorder should force a full snapshot now: activity was
     * buffered since the last segment and that segment started at least
     * [CHECKOUT_EVERY_MS] ago, or [needsCheckout] (a segment over the bound,
     * or activity with no segment open). Never true without activity.
     */
    @Synchronized
    fun checkoutDue(): Boolean {
        if (needsCheckout) return true
        val last = segments.lastOrNull() ?: return false
        return dirtySinceCheckout && clock.nowMillis() - last.start >= CHECKOUT_EVERY_MS
    }

    /** How long until the next error may [cut] after the previous one ([SEND_GAP_MS]); 0 = now. */
    @Synchronized
    fun nextSendDelayMs(): Long {
        val last = lastSendAt ?: return 0L
        return (last + SEND_GAP_MS - clock.nowMillis()).coerceAtLeast(0L)
    }

    /**
     * The window an error at now sends, or null when [sessionId] is not the
     * buffered session, nothing was buffered since the last cut, or no
     * segment can start it (the recorder then takes a full snapshot).
     *
     * It starts at the newest segment that began at least [MIN_WINDOW_MS]
     * ago when that one is usable (began inside [WINDOW_MS], or had no
     * activity before it), else at the oldest segment that began less than
     * [MIN_WINDOW_MS] ago. A start before the [WINDOW_MS] cutoff is an idle
     * screen whose snapshot is still the state at the cutoff, so its state
     * events are stamped at the cutoff.
     *
     * A cut empties the buffer (the next one needs a new full snapshot) and
     * starts the [SEND_GAP_MS] interval. It ignores that interval itself:
     * the caller decides when to wait ([nextSendDelayMs]).
     */
    @Synchronized
    fun cut(sessionId: String): ErrorCut? {
        if (sessionId != this.sessionId || !dirtySinceSend) return null
        val now = clock.nowMillis()
        val cutoff = now - WINDOW_MS
        val target = now - MIN_WINDOW_MS
        fun usable(s: Segment): Boolean {
            val activity = s.firstActivity
            return s.start >= cutoff || activity == null || activity >= cutoff
        }
        var first = segments.indexOfLast { it.start <= target }
        if (first < 0 || !usable(segments[first])) first = segments.indexOfFirst { it.start > target }
        if (first < 0) {
            needsCheckout = true
            return null
        }
        val out = ArrayList<JSONObject>()
        for ((i, segment) in segments.withIndex()) {
            if (i < first) continue
            if (i == first && segment.start < cutoff) {
                for ((j, event) in segment.events.withIndex()) {
                    if (j < segment.lead) {
                        out.add(copyAt(event, maxOf(timestampOf(event, cutoff), cutoff)))
                    } else if (timestampOf(event, now) >= cutoff) {
                        // Out-of-order activity from before the cutoff never goes.
                        out.add(event)
                    }
                }
            } else {
                out.addAll(segment.events)
            }
        }
        // The recording ends at the error, not at the last change before it, so
        // the player shows the error inside the recording.
        out.add(errorMarker(now))
        segments.clear()
        bytes = 0L
        dirtySinceSend = false
        dirtySinceCheckout = false
        needsCheckout = false
        lastSendAt = now
        return ErrorCut(sessionId, out)
    }

    /** Drops everything (a change of mode, a rotation, opt-out); the next event kept is a Meta. */
    @Synchronized
    fun clear() {
        reset()
    }

    private fun reset() {
        sessionId = null
        segments.clear()
        bytes = 0L
        dirtySinceCheckout = false
        dirtySinceSend = true
        needsCheckout = false
        lastFullSnapshot = null
    }

    private fun push(event: JSONObject) {
        val size = event.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val type = event.optInt(TYPE, -1)
        val now = clock.nowMillis()
        if (type == META) {
            val segment = Segment(timestampOf(event, now))
            segment.events.add(event)
            segment.lead = 1
            segment.bytes = size
            segments.add(segment)
            bytes += size
            dirtySinceCheckout = false
            needsCheckout = false
            // A cut starts at the newest segment at least MIN_WINDOW_MS old, or a
            // later one: nothing before the newest such segment can be sent again.
            val anchor = segments.indexOfLast { it.start <= now - MIN_WINDOW_MS }
            if (anchor > 0) dropOldest(anchor)
            return
        }
        val current = segments.lastOrNull()
        if (current == null) {
            // No snapshot to build on: unusable on its own, and only a checkout fixes it.
            if (type != FULL_SNAPSHOT) needsCheckout = true
            return
        }
        current.events.add(event)
        current.bytes += size
        bytes += size
        val state = type == FULL_SNAPSHOT
        if (state && current.firstActivity == null) {
            current.lead += 1
            // A full snapshot of a changed screen (another Activity, a reshaped
            // tree) is something an error send must carry, unlike a checkout
            // of the same screen.
            val data = event.optJSONObject(DATA)?.toString()
            if (lastFullSnapshot != null && data != lastFullSnapshot) dirtySinceSend = true
            lastFullSnapshot = data
        } else if (!state) {
            if (current.firstActivity == null) current.firstActivity = timestampOf(event, now)
            dirtySinceCheckout = true
            dirtySinceSend = true
        }
    }

    private fun enforceBound() {
        if (bytes <= maxBytes) return
        if (segments.size > 1) {
            var drop = 0
            var left = bytes
            while (drop < segments.size - 1 && left > maxBytes) {
                left -= segments[drop].bytes
                drop++
            }
            dropOldest(drop)
        }
        // One segment over the bound: a checkout starts a new one, the old one then goes.
        if (bytes > maxBytes) needsCheckout = true
    }

    private fun dropOldest(count: Int) {
        repeat(count) { bytes -= segments.removeAt(0).bytes }
    }

    private fun timestampOf(event: JSONObject, fallback: Long): Long =
        if (event.has(TIMESTAMP)) event.optLong(TIMESTAMP, fallback) else fallback

    private fun errorMarker(at: Long): JSONObject = JSONObject()
        .put(TYPE, CUSTOM)
        .put(TIMESTAMP, at)
        .put(DATA, JSONObject().put("tag", ERROR_TAG).put("payload", JSONObject()))

    private fun copyAt(event: JSONObject, timestamp: Long): JSONObject =
        JSONObject(event.toString()).put(TIMESTAMP, timestamp)

    companion object {
        /** No activity older than this before the error is ever sent. */
        const val WINDOW_MS = 60_000L

        /** A cut covers at least this much when the recording is that old. */
        const val MIN_WINDOW_MS = 30_000L

        /** A full snapshot is forced this often while there is activity. */
        const val CHECKOUT_EVERY_MS = 30_000L

        /** Minimum time between two error-triggered sends (a later error waits, it is not lost). */
        const val SEND_GAP_MS = 5_000L

        /** 10 MiB of serialized events. */
        const val MAX_BYTES = 10L * 1024 * 1024

        /** rrweb event types (docs/SESSION-REPLAY.md "Mobile"). */
        const val META = 4
        const val FULL_SNAPSHOT = 2
        const val CUSTOM = 5

        /** Tag of the Custom event that closes every cut, at the moment of the error. */
        const val ERROR_TAG = "error"

        private const val TYPE = "type"
        private const val TIMESTAMP = "timestamp"
        private const val DATA = "data"
    }
}
