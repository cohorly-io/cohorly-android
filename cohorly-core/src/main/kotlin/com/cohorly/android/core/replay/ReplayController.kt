package com.cohorly.android.core.replay

import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.CohorlyStorage
import com.cohorly.android.core.CohorlyTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Session replay for one core: the replay session (`$session_id`, rotation,
 * the sampling decision, `seq`), the chunk queue and its uploader. Exists
 * only when `CohorlyConfig.sessionReplay` is set.
 *
 * State lives in its own [state] storage (on Android
 * `no_backup/cohorly_replay_<token>/state.json`), never in the SDK's event
 * file: the session, the next `seq` of each session that may still have
 * chunks queued, the quota pause and the sessions an opt-out is still
 * purging. The global opt-out stays where the core keeps it.
 *
 * Threading:
 * - The getters and [enqueue] only touch memory, under a short lock, and may
 *   be called from any thread (the recorder calls them from the main thread).
 *   The current session and the quota pause are published in memory and read
 *   from there; their persistence belongs to the replay threads.
 * - [stateExecutor] (`cohorly-replay` on a device) writes the state and the
 *   chunk files, purges after an opt-out and calls the session listeners. It
 *   never waits on the network.
 * - [uploadExecutor] (`cohorly-replay-upload`) runs `POST /replay` and the
 *   queue changes an answer causes (removal, split).
 * - [remoteConfig] (with [SessionReplayConfig.remoteConfig]) runs
 *   `GET /replay/config` on its own thread; the 3 s wait of a pending
 *   session is a task scheduled on [stateExecutor].
 *
 * Remote settings (VEL-2236, same contract as `@cohorly/web`): new sessions
 * are decided with the project's saved settings over the code's. A session
 * born while they are being fetched is pending: it has its id at once,
 * reads [ReplayMode.OFF], and is decided by the answer, the failure or the
 * 3 s wait, whichever comes first. A pending session restored by a relaunch
 * is decided the same way. A live session keeps the mode and minimum
 * duration it was decided with.
 */
class ReplayController internal constructor(
    val config: SessionReplayConfig,
    private val state: CohorlyStorage,
    private val clock: CohorlyClock,
    private val stateExecutor: ReplayExecutor,
    private val uploadExecutor: ReplayExecutor,
    private val queue: ReplayChunkQueue,
    transport: CohorlyTransport,
    apiHost: String,
    token: String?,
    maxRetryDelayMs: Long,
    jitterSource: () -> Double,
    private val random: () -> Double,
    distinctId: String,
    optedOut: Boolean,
    private val remoteConfig: ReplayRemoteConfigLoader? = null,
) {
    /** Keys of the replay state storage. */
    object Keys {
        const val SESSION = "cohorly.replay.session"
        const val QUOTA_PAUSED_UNTIL = "cohorly.replay.quota_paused_until"

        /** Session ids an opt-out dropped whose chunks are not all deleted yet (JSON array). */
        const val PURGE = "cohorly.replay.purge_sessions"
    }

    private val lock = Any()

    /** Held while a state snapshot is taken and written, so the last write is always the newest snapshot. */
    private val persistLock = Any()

    /** Null while opted out. Written under [lock]; published for lock-free reads. */
    @Volatile
    private var session: ReplaySession? = null

    /** The distinct id chunks are queued under; changes with the session on `reset()`. Under [lock]. */
    private var distinctId: String = distinctId

    /** Under [lock]. */
    private var optedOut: Boolean = optedOut

    /** Bumped by every opt-out: a chunk taken before it is never written, even after an opt-in. */
    private var optOutGeneration = 0L

    /** `session` recording is paused until this instant (0: not paused). */
    @Volatile
    private var quotaPausedUntil: Long = 0L

    /** Bumped by the upload thread and by the queue's 10 MB trim (state or upload thread); read by [droppedChunks]. */
    private val droppedChunksCount = AtomicLong()

    /** Sessions whose chunks are removed, never sent (see [Keys.PURGE]). */
    @Volatile
    private var purgeSessions: Set<String> = emptySet()

    /** When activity was last sent to storage; activity alone is persisted at most once a minute. Under [lock]. */
    private var lastPersistedActivity: Long? = null

    /** The session's lastActiveAt was read from storage and no activity of this process updated it yet. Under [lock]. */
    private var activityRestored = false

    /**
     * The session the last rotation ended, for [PREVIOUS_SESSION_GRACE_MS]:
     * the recorder hears of a rotation after it happened, so the buffer it
     * held for the old session arrives late. Memory only; cleared by opt-out
     * and replaced by the next rotation. Under [lock].
     */
    private var previous: PreviousSession? = null

    /** [nextSeq] mirrors `retiredSeqs[id]`, which [pruneRetired] may drop while a chunk of it is still on its way. */
    private class PreviousSession(
        val id: String,
        val distinctId: String,
        val mode: ReplayMode,
        val rotatedAt: Long,
        var nextSeq: Long,
        /** Its recording ended shorter than its minimum duration; its last buffers may still reach it. */
        var shortRecording: Boolean = false,
    )

    private var listenerSeq = 0L
    private val listeners = LinkedHashMap<Long, (String?) -> Unit>()

    /** Rotations not yet delivered to the listeners, in rotation order. Under [lock]. */
    private val pendingNotifications = ArrayDeque<Notification>()

    private class Notification(val sessionId: String?)

    internal val uploader = ReplayUploader(
        queue = queue,
        transport = transport,
        apiHost = apiHost,
        token = token,
        clock = clock,
        maxRetryDelayMs = maxRetryDelayMs,
        jitterSource = jitterSource,
        nextSeqFor = ::takeSeqFor,
        onQuotaExceeded = ::pauseQuota,
        isPurged = { it in purgeSessions },
        onDropped = { droppedChunksCount.addAndGet(it.toLong()) },
    )

    /** The pending scheduled retry; touched on the upload thread only. */
    private var retryGeneration = 0L
    private var retryDueAt: Long? = null

    /** The settings new sessions are decided with; null while the remote ones are awaited. Under [lock]. */
    private var effective: EffectiveReplayConfig? =
        if (remoteConfig == null) EffectiveReplayConfig.of(config, null) else null

    /** When [effective] was fetched (a fresh copy) or resolved (an answer, or the fallback). Under [lock]. */
    private var resolvedAt = 0L

    /** The stored copy [effective] was taken from at once (fresh), so it is not taken again. Under [lock]. */
    private var adopted: StoredReplayConfig? = null

    /** Bumped by each load: an older load resolving late changes nothing. Under [lock]. */
    private var loads = 0L

    /** A load to start once [lock] is released, with the copy it falls back on. Under [lock]. */
    private var loadToStart: Load? = null

    private class Load(val id: Long, val base: StoredReplayConfig?)

    /**
     * Session-mode chunks of a recording kept in memory until it is
     * [ReplaySession.minDurationMs] long (or over
     * [MIN_DURATION_HOLD_MAX_BYTES] in UTF-8); never written to disk.
     */
    private class Hold(val sessionId: String, val minDurationMs: Long) {
        val chunks = ArrayList<List<JSONObject>>()
        private var bytes = 0L
        private var firstAt: Long? = null
        private var lastAt: Long? = null

        /**
         * Holds [events] of [size] UTF-8 bytes (measured by the caller outside the lock, null: measure here); true when the
         * recording reached the minimum duration (or the size bound).
         */
        fun add(events: List<JSONObject>, size: Long?): Boolean {
            chunks.add(events.toList())
            bytes += size ?: events.sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }
            if (firstAt == null) firstAt = events.firstNotNullOfOrNull(::timestampOf)
            events.lastOrNull { timestampOf(it) != null }?.let { lastAt = timestampOf(it) }
            val first = firstAt
            val last = lastAt
            return (first != null && last != null && last - first >= minDurationMs) ||
                bytes > MIN_DURATION_HOLD_MAX_BYTES
        }

        private fun timestampOf(event: JSONObject): Long? = (event.opt("timestamp") as? Number)?.toLong()
    }

    /** The session whose hold was last set up; [hold] is null once it needs none (or reached it). Under [lock]. */
    private var holdSessionId: String? = null
    private var hold: Hold? = null

    /** The hold of the session the last rotation ended short: its last buffers can still make it long enough. Under [lock]. */
    private var previousHold: Hold? = null

    init {
        queue.onTrimmed = { droppedChunksCount.addAndGet(it.toLong()) }
        // The state file is read here, on the caller's thread, like the core's own storage.
        quotaPausedUntil = state.get(Keys.QUOTA_PAUSED_UNTIL)?.toLongOrNull() ?: 0L
        purgeSessions = parseIds(state.get(Keys.PURGE))
        // An opted-out boot asks nothing: [optIn] adopts the settings when the person comes back.
        if (remoteConfig != null && !optedOut) adoptLocked(clock.nowMillis())
        if (optedOut) {
            uploader.stop()
        } else {
            val now = clock.nowMillis()
            val stored = ReplaySession.parse(state.get(Keys.SESSION))
            if (stored == null || stored.expiredOnRelaunch(now)) {
                session = newSession(now, stored)
                persistSoon()
            } else {
                // A chunk file may be ahead of the persisted counter (an older
                // build, or a death between the two writes): never reuse its seq.
                // Names only, no chunk is read.
                val queuedNext = queue.nextSeqQueued(stored.id)
                session = if (queuedNext > stored.nextSeq) stored.copy(nextSeq = queuedNext) else stored
                activityRestored = true
                if (queuedNext > stored.nextSeq) persistSoon()
                // Left pending by a launch that ended before deciding it.
                if (decidePendingLocked(notify = false)) persistSoon()
            }
        }
        startLoad()
        // On the state thread first, so an opt-out (or a purge the last launch
        // could not finish) is applied before the first upload.
        stateExecutor.execute {
            if (isOptedOut()) purge(all = true) else purge(all = false)
            scheduleDrain(force = false)
        }
    }

    // MARK: public API

    /** The current session's id; null after opt-out. */
    val sessionId: String?
        get() = session?.id

    /**
     * The mode the current session records in now. [ReplayMode.OFF] when it
     * was sampled out, after opt-out, or after a 404 turned replay off for
     * the launch. Like `@cohorly/web`, a session sampled for `session`
     * recording reads [ReplayMode.ERROR] while the quota pause lasts (it
     * buffers like error mode, whatever `onErrorSampleRate` is), and
     * [ReplayMode.SESSION] again once the pause ends.
     */
    val mode: ReplayMode
        get() {
            if (uploader.disabled) return ReplayMode.OFF
            val current = session ?: return ReplayMode.OFF
            if (current.pending) return ReplayMode.OFF
            val drawn = current.mode
            if (drawn == ReplayMode.SESSION && quotaPaused()) return ReplayMode.ERROR
            return drawn
        }

    /**
     * Queues [events] as one chunk of the current session. The session id,
     * distinct id and `seq` are taken in one snapshot, under the lock every
     * rotation holds, so a chunk never pairs a new identity with an old
     * session or the other way round.
     *
     * Returns false, queuing nothing, for an empty list, [ReplayMode.OFF],
     * after opt-out or a 404, for [ReplayMode.SESSION] during the quota
     * pause, and when [sessionId] is given and is no longer the current
     * session (the recorder passes the id it captured with the buffer, so a
     * buffer of session A never lands in session B). One exception: the
     * session the last rotation ended is still accepted for
     * [PREVIOUS_SESSION_GRACE_MS] after it, with its own distinct id (never
     * the one a `reset()` gave the new session) and its own next `seq`, so
     * the recorder's last buffer of it is not lost. Never blocks on disk:
     * the write runs on the state thread. The events must not be mutated
     * after the call.
     */
    @CohorlyReplayInternal
    fun enqueue(events: List<JSONObject>, mode: ReplayMode = this.mode, sessionId: String? = null): Boolean {
        val (chunks, generation) = takeChunks(events, mode, sessionId) ?: return false
        if (chunks.isEmpty()) return true
        stateExecutor.execute {
            if (synchronized(lock) { optedOut || generation != optOutGeneration }) return@execute
            // The next seq reaches the state file before the chunk file does.
            persistNow()
            for (chunk in chunks) queue.add(chunk)
            scheduleDrain(force = false)
        }
        return true
    }

    /**
     * [enqueue] for the fatal crash handler (VEL-2151): the chunk takes its
     * `seq` the same way, but the state and the chunk file are written here,
     * on the calling thread, before it returns, because the process dies
     * right after. No upload is scheduled: the next launch's queue sends the
     * chunk (its session may have expired by then; a queued chunk keeps the
     * session it was recorded in). Returns true only when the chunk file
     * landed. Never throws.
     */
    @CohorlyReplayInternal
    fun enqueueNow(events: List<JSONObject>, mode: ReplayMode, sessionId: String?): Boolean = try {
        val taken = takeChunks(events, mode, sessionId)
        if (taken == null ||
            taken.first.isEmpty() ||
            synchronized(lock) { optedOut || taken.second != optOutGeneration }
        ) {
            false
        } else {
            // The next seq reaches the state file before the chunk file does, like on the state thread.
            persistNow()
            var written = true
            for (chunk in taken.first) written = queue.add(chunk) && written
            // An opt-out that ran meanwhile may have purged before this file
            // landed: remove it ourselves (an opt-out after this check finds
            // the file in its own purge).
            if (written && synchronized(lock) { optedOut || taken.second != optOutGeneration }) {
                for (chunk in taken.first) {
                    ReplayChunkQueue.parseName(ReplayChunkQueue.nameFor(chunk))?.let { queue.remove(it) }
                }
                false
            } else {
                written
            }
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * The chunks [enqueue] and [enqueueNow] queue, in `seq` order, with the
     * opt-out generation they were taken in; null when refused (see
     * [enqueue]). Empty when a session-mode chunk is held for the minimum
     * duration; the chunk that reaches it releases every held one before it.
     */
    private fun takeChunks(
        events: List<JSONObject>,
        mode: ReplayMode,
        sessionId: String?,
    ): Pair<List<ReplayChunk>, Long>? {
        if (events.isEmpty() || mode == ReplayMode.OFF || uploader.disabled) return null
        // Only remote settings can hold a recording; the serializing stays out of the lock.
        val size = if (mayHold(mode)) utf8Size(events) else null
        return synchronized(lock) {
            if (optedOut) return null
            val generation = optOutGeneration
            val current = session ?: return null
            if (mode == ReplayMode.SESSION && quotaPaused()) return null
            if (sessionId != null && sessionId != current.id) {
                val chunks = previousChunksLocked(sessionId, mode, events, size) ?: return null
                return chunks to generation
            }
            if (current.pending) return null
            if (mode != ReplayMode.SESSION || !holdingLocked(current)) {
                return listOf(nextChunkLocked(events, mode)) to generation
            }
            val held = hold!!
            if (!held.add(events, size)) return emptyList<ReplayChunk>() to generation
            hold = null
            held.chunks.map { nextChunkLocked(it, ReplayMode.SESSION) } to generation
        }
    }

    /** Whether a session-mode chunk may join a hold now; a stale answer only costs a measure inside the lock. */
    private fun mayHold(mode: ReplayMode): Boolean = remoteConfig != null &&
        mode == ReplayMode.SESSION &&
        synchronized(lock) {
            session?.let { it.minDurationMs > 0 && it.nextSeq == 0L } == true || previousHold != null
        }

    private fun utf8Size(events: List<JSONObject>): Long =
        events.sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }

    /** A chunk of the current session with its next `seq`. Must hold [lock]. */
    private fun nextChunkLocked(events: List<JSONObject>, mode: ReplayMode): ReplayChunk {
        val current = session!!
        session = current.copy(nextSeq = current.nextSeq + 1)
        return ReplayChunk(
            sessionId = current.id,
            distinctId = distinctId,
            seq = current.nextSeq,
            mode = mode,
            createdAt = clock.nowMillis(),
            events = events.toList(),
        )
    }

    /**
     * Whether session-mode chunks of [current] are still held. A session
     * that already queued a chunk (a relaunch continuing it) reached its
     * minimum duration before. Must hold [lock].
     */
    private fun holdingLocked(current: ReplaySession): Boolean {
        if (holdSessionId != current.id) {
            holdSessionId = current.id
            hold =
                if (current.minDurationMs > 0 &&
                    current.nextSeq == 0L
                ) {
                    Hold(current.id, current.minDurationMs)
                } else {
                    null
                }
        }
        return hold != null
    }

    /**
     * The chunks of the session the last rotation ended, or null when
     * [sessionId] is not that session or its grace period is over. Each
     * takes its next `seq` like [takeSeqFor] does for a retired session (the
     * `retiredSeqs` entry moves with it, so the state written before the
     * chunk holds it). A session-mode chunk of a recording that ended short
     * joins its hold: empty until the hold reaches the minimum duration,
     * then every held chunk in order. Must hold [lock].
     */
    private fun previousChunksLocked(
        sessionId: String,
        mode: ReplayMode,
        events: List<JSONObject>,
        size: Long?,
    ): List<ReplayChunk>? {
        val prev = previous ?: return null
        if (session == null) return null
        val now = clock.nowMillis()
        if (prev.id != sessionId || prev.mode == ReplayMode.OFF) return null
        if (now - prev.rotatedAt > PREVIOUS_SESSION_GRACE_MS) {
            previous = null
            previousHold = null
            return null
        }
        if (prev.shortRecording && mode == ReplayMode.SESSION) {
            val held = previousHold?.takeIf { it.sessionId == sessionId } ?: return null
            if (!held.add(events, size)) return emptyList()
            previousHold = null
            prev.shortRecording = false
            return held.chunks.map { previousChunkLocked(prev, ReplayMode.SESSION, it, now) }
        }
        return listOf(previousChunkLocked(prev, mode, events, now))
    }

    /** One chunk of [prev] with its next `seq`. Must hold [lock]. */
    private fun previousChunkLocked(
        prev: PreviousSession,
        mode: ReplayMode,
        events: List<JSONObject>,
        now: Long,
    ): ReplayChunk {
        val current = session!!
        val seq = maxOf(prev.nextSeq, current.retiredSeqs[prev.id] ?: 0L)
        prev.nextSeq = seq + 1
        session = current.copy(retiredSeqs = current.retiredSeqs + (prev.id to seq + 1))
        return ReplayChunk(
            sessionId = prev.id,
            distinctId = prev.distinctId,
            seq = seq,
            mode = mode,
            createdAt = now,
            events = events.toList(),
        )
    }

    /**
     * Calls [listener] with the new session id on every rotation, with the
     * current id again when a pending session is decided (its mode may now
     * record), and with null on opt-out, in order, on the state thread
     * (`cohorly-replay`), which never waits on an upload. Returns the
     * function that removes it.
     */
    @CohorlyReplayInternal
    fun addSessionListener(listener: (sessionId: String?) -> Unit): () -> Unit {
        val id = synchronized(lock) {
            listenerSeq += 1
            listeners[listenerSeq] = listener
            listenerSeq
        }
        return { synchronized(lock) { listeners.remove(id) } }
    }

    /**
     * How many queued chunks this process removed without delivering them
     * (a quota refusal and the session chunks it takes along, another 4xx,
     * a single event too large, an unreadable file, the 10 MB trim). Memory only, never
     * decreases. The recorder compares it between captures: a change means
     * the server may lack the snapshot its next Mutation would build on, so
     * it sends Meta + FullSnapshot instead. Any thread.
     */
    @CohorlyReplayInternal
    val droppedChunks: Long
        get() = droppedChunksCount.get()

    /** Sends the queue now, ignoring the current backoff for one attempt. */
    @CohorlyReplayInternal
    fun flush() {
        scheduleDrain(force = true)
    }

    /**
     * Activity the recorder saw (a recorded touch), the same as a tracked
     * event for the session: rotates first when the session reached 24 h or
     * spent 30 min in foreground without activity, then marks the session
     * active. Memory only, with the same throttled persistence as
     * [sessionIdForEvent]. Any thread.
     */
    @CohorlyReplayInternal
    fun recordActivity() {
        touch(clock.nowMillis())
    }

    // MARK: core hooks

    /**
     * The `$session_id` for an event tracked at [now] (null while opted out).
     * Rotates first when the session reached 24 h, or after 30 min in
     * foreground without activity. Memory only.
     */
    internal fun sessionIdForEvent(now: Long): String? = touch(now)

    /** One activity at [now]: the rotation checks, then the activity mark. Returns the session id. */
    private fun touch(now: Long): String? {
        var persist = false
        val id = synchronized(lock) {
            if (optedOut) return null
            val current = session ?: return null
            // A restored session's lastActiveAt may be up to one persist
            // window early (activity alone is written once a minute), so
            // its first idle check adds the window: never rotate with less
            // than 30 real minutes of inactivity.
            val lastActive = current.lastActiveAt + if (activityRestored) ACTIVITY_PERSIST_INTERVAL_MS else 0L
            val rotated = current.tooLong(now) || current.foregroundIdleExpired(now, lastActive)
            if (rotated) {
                rotateLocked(now)
            } else {
                // Activity is not always handled in time order (an event tracked
                // with an earlier time, a touch read on another thread): never
                // move the mark back, which would bring the idle rotation forward.
                session = current.copy(lastActiveAt = maxOf(current.lastActiveAt, now))
            }
            activityRestored = false
            val last = lastPersistedActivity
            persist = rotated || last == null || now - last >= ACTIVITY_PERSIST_INTERVAL_MS
            if (persist) lastPersistedActivity = now
            session!!.id
        }
        if (persist) persistSoon()
        deliverSoon()
        startLoad()
        return id
    }

    /** `identify()`: later chunks carry [newDistinctId]; the session goes on. */
    internal fun identityChanged(newDistinctId: String) {
        synchronized(lock) { distinctId = newDistinctId }
    }

    internal fun onForeground() {
        val now = clock.nowMillis()
        synchronized(lock) {
            if (optedOut) return
            val current = session ?: return
            if (current.backgroundExpired(now) || current.tooLong(now)) {
                rotateLocked(now)
            } else {
                session = current.copy(backgroundedAt = null, lastActiveAt = now)
            }
            activityRestored = false
        }
        persistSoon()
        deliverSoon()
        startLoad()
    }

    internal fun onBackground() {
        val now = clock.nowMillis()
        synchronized(lock) {
            if (optedOut) return
            val current = session ?: return
            session = current.copy(backgroundedAt = current.backgroundedAt ?: now, lastActiveAt = now)
            activityRestored = false
        }
        persistSoon()
        scheduleDrain(force = false)
    }

    /**
     * `reset()`: the new identity and a new session together, in one step
     * under the lock, so no chunk is queued with one and not the other.
     */
    internal fun rotate(newDistinctId: String) {
        synchronized(lock) {
            val oldDistinctId = distinctId
            distinctId = newDistinctId
            if (optedOut) return
            rotateLocked(clock.nowMillis(), oldDistinctId)
        }
        persistSoon()
        deliverSoon()
        startLoad()
    }

    /**
     * Drops every queued chunk and the session; nothing is queued or sent
     * until [optIn]. A chunk that cannot be deleted now is never sent and is
     * deleted again on the next launch (or opt-in).
     */
    internal fun optOut() {
        synchronized(lock) {
            if (!optedOut) pendingNotifications.addLast(Notification(null))
            optedOut = true
            optOutGeneration++
            session = null
            previous = null
            holdSessionId = null
            hold = null
            previousHold = null
        }
        uploader.stop()
        stateExecutor.execute {
            purge(all = true)
            persistNow()
        }
        deliverSoon()
    }

    /** Back from opt-out, with a new session; uploads resume once the opt-out's purge is done. */
    internal fun optIn() {
        synchronized(lock) {
            if (!optedOut) return
            optedOut = false
            if (remoteConfig != null && loads == 0L) adoptLocked(clock.nowMillis())
            session = newSession(clock.nowMillis(), null)
            previous = null
            activityRestored = false
            pendingNotifications.addLast(Notification(session!!.id))
        }
        persistSoon()
        deliverSoon()
        startLoad()
        stateExecutor.execute {
            purge(all = false)
            if (!isOptedOut()) {
                uploader.start()
                scheduleDrain(force = false)
            }
        }
    }

    // MARK: internals

    private fun isOptedOut(): Boolean = synchronized(lock) { optedOut }

    private fun quotaPaused(): Boolean = clock.nowMillis() < quotaPausedUntil

    /** Called under [lock] (or from the constructor). */
    private fun newSession(now: Long, previous: ReplaySession?): ReplaySession {
        refreshIfStaleLocked(now)
        val decided = effective
        val retired = LinkedHashMap<String, Long>()
        if (previous != null) {
            retired.putAll(previous.retiredSeqs)
            retired.remove(previous.id)
            retired[previous.id] = previous.nextSeq
            while (retired.size > ReplaySession.MAX_RETIRED_SESSIONS) retired.remove(retired.keys.first())
        }
        return ReplaySession(
            id = UUID.randomUUID().toString(),
            startedAt = now,
            lastActiveAt = now,
            backgroundedAt = null,
            mode = decided?.let { ReplaySession.draw(it.sampleRate, it.onErrorSampleRate, random) } ?: ReplayMode.OFF,
            nextSeq = 0,
            retiredSeqs = retired,
            pending = decided == null,
            minDurationMs = decided?.minDurationMs ?: 0L,
        )
    }

    // MARK: remote settings

    /**
     * Takes the stored copy when it is fresh (no request); otherwise new
     * sessions wait for a load, started by [startLoad] once the lock is
     * released. Must hold [lock] (or run in the constructor).
     */
    private fun adoptLocked(now: Long) {
        val remote = remoteConfig ?: return
        val stored = remote.stored()
        if (stored != null && stored.fresh(now)) {
            effective = EffectiveReplayConfig.of(config, stored.config)
            resolvedAt = stored.fetchedAt
            adopted = stored
            loads += 1
            loadToStart = null
            return
        }
        effective = null
        loads += 1
        loadToStart = Load(loads, stored)
    }

    /**
     * Before a new session is decided: loads the settings again when they
     * were resolved an hour ago or more, or a newer copy was stored since
     * (an answer that arrived after its 3 s wait). Must hold [lock].
     */
    private fun refreshIfStaleLocked(now: Long) {
        val remote = remoteConfig ?: return
        if (effective == null) return
        val copy = remote.stored()
        if (now - resolvedAt >= ReplayRemoteConfigLoader.REPLAY_CONFIG_TTL_MS ||
            (copy != null && copy !== adopted)
        ) {
            adoptLocked(now)
        }
    }

    /**
     * Starts the load [adoptLocked] asked for: the request on the config
     * thread and the 3 s wait on the state thread. The first to finish
     * resolves it; a failure or the wait fall back on the stored copy (even
     * expired), else on the code. Never blocks.
     */
    private fun startLoad() {
        val remote = remoteConfig ?: return
        val load = synchronized(lock) { loadToStart.also { loadToStart = null } } ?: return
        val done = AtomicBoolean(false)
        val finish = { answer: RemoteReplayConfig? ->
            if (!done.getAndSet(true)) stateExecutor.execute { resolveLoad(load, answer) }
        }
        val startedAt = clock.nowMillis()
        stateExecutor.schedule(ReplayRemoteConfigLoader.REPLAY_CONFIG_TIMEOUT_MS) { finish(null) }
        remote.load { answer ->
            // Past the wait, even if the timer has not run yet (a busy state thread), the answer only updates the copy.
            finish(answer?.takeIf { clock.nowMillis() - startedAt < ReplayRemoteConfigLoader.REPLAY_CONFIG_TIMEOUT_MS })
        }
    }

    /** On the state thread: [load]'s settings decide new sessions and the pending one. */
    private fun resolveLoad(load: Load, answer: RemoteReplayConfig?) {
        val decided = synchronized(lock) {
            if (load.id != loads) return
            effective = EffectiveReplayConfig.of(config, answer ?: load.base?.config)
            resolvedAt = clock.nowMillis()
            // The copy the decision rests on: any other one stored later is a late answer for the next session.
            adopted = if (answer != null) remoteConfig?.stored() else load.base
            decidePendingLocked(notify = true)
        }
        if (decided) persistNow()
        deliverSoon()
    }

    /**
     * Decides the current session when it is pending and the settings are
     * known. True when it did. Must hold [lock] (or run in the constructor).
     */
    private fun decidePendingLocked(notify: Boolean): Boolean {
        val decided = effective ?: return false
        val current = session ?: return false
        if (!current.pending) return false
        session = current.copy(
            pending = false,
            mode = ReplaySession.draw(decided.sampleRate, decided.onErrorSampleRate, random),
            minDurationMs = decided.minDurationMs,
        )
        if (holdSessionId == current.id) {
            holdSessionId = null
            hold = null
        }
        if (notify) pendingNotifications.addLast(Notification(current.id))
        return true
    }

    /** Must hold [lock]. [endedDistinctId] is the identity the ending session's chunks carry. */
    private fun rotateLocked(now: Long, endedDistinctId: String = distinctId) {
        val ended = session
        val next = newSession(now, ended)
        // A recording that ends shorter than its minimum duration sends nothing and takes no seq,
        // unless the recorder's last buffers of it, within the grace period, make it long enough.
        val short = ended != null &&
            ended.minDurationMs > 0 &&
            if (holdSessionId == ended.id) hold != null else ended.nextSeq == 0L
        previousHold = if (short && ended != null) {
            hold?.takeIf { holdSessionId == ended.id } ?: Hold(ended.id, ended.minDurationMs)
        } else {
            null
        }
        holdSessionId = null
        hold = null
        val retiring = ended?.let { PreviousSession(it.id, endedDistinctId, it.mode, now, it.nextSeq, short) }
        previous = retiring
        if (previousHold != null) {
            // Once the grace period is over no late buffer can come: free the held chunks now, not at the next rotation.
            stateExecutor.schedule(PREVIOUS_SESSION_GRACE_MS + 1) {
                synchronized(lock) { if (previous === retiring) previousHold = null }
            }
        }
        session = next
        lastPersistedActivity = now
        activityRestored = false
        pendingNotifications.addLast(Notification(next.id))
    }

    /**
     * A `seq` [sessionId] never used, for the second half of a split chunk:
     * the live counter of the current session, the retired counter of an
     * earlier one, or (unknown session) one above every queued `seq` of it.
     * Persisted before it is returned, so the chunk file never gets ahead of
     * the state. Runs on the upload thread.
     */
    private fun takeSeqFor(sessionId: String): Long {
        val queuedNext = queue.nextSeqQueued(sessionId)
        val seq = synchronized(lock) {
            val current = session
            if (current != null && current.id == sessionId) {
                val next = maxOf(current.nextSeq, queuedNext)
                session = current.copy(nextSeq = next + 1)
                next
            } else if (current != null) {
                val prev = previous?.takeIf { it.id == sessionId }
                val next = maxOf(current.retiredSeqs[sessionId] ?: 0L, queuedNext, prev?.nextSeq ?: 0L)
                prev?.nextSeq = next + 1
                session = current.copy(retiredSeqs = current.retiredSeqs + (sessionId to next + 1))
                next
            } else {
                queuedNext
            }
        }
        persistNow()
        return seq
    }

    /** `replay quota exceeded`: published at once, persisted on the state thread. */
    private fun pauseQuota(untilMs: Long) {
        quotaPausedUntil = untilMs
        stateExecutor.execute { state.set(Keys.QUOTA_PAUSED_UNTIL, untilMs.toString()) }
    }

    /**
     * On the state thread. [all]: every queued chunk goes (opt-out); else
     * only the sessions a previous purge could not finish. The sessions are
     * recorded first, so a failed delete is retried at the next launch and
     * their chunks are never sent meanwhile.
     */
    private fun purge(all: Boolean) {
        val ids = if (all) queue.sessionIds() + purgeSessions else purgeSessions
        if (ids.isEmpty()) return
        setPurgeSessions(ids)
        val done = if (all) queue.clear() else queue.removeSessions(ids)
        if (done) setPurgeSessions(emptySet())
    }

    private fun setPurgeSessions(ids: Set<String>) {
        purgeSessions = ids
        state.set(Keys.PURGE, if (ids.isEmpty()) null else JSONArray(ids.toList()).toString())
    }

    private fun scheduleDrain(force: Boolean) {
        uploadExecutor.execute { drainOnUploader(force) }
    }

    /**
     * On the upload thread. A new retry deadline replaces the scheduled one
     * (a generation makes the old callback a no-op), so a success that
     * resets the backoff, or a shorter delay, is never stuck behind an
     * earlier, longer wait.
     */
    private fun drainOnUploader(force: Boolean) {
        val delay = uploader.drain(force)
        pruneRetired()
        if (delay == null) {
            retryDueAt = null
            retryGeneration += 1
            return
        }
        val due = clock.nowMillis() + delay
        if (retryDueAt == due) return
        retryDueAt = due
        val generation = ++retryGeneration
        uploadExecutor.schedule(delay) {
            if (generation != retryGeneration) return@schedule
            retryDueAt = null
            drainOnUploader(force = false)
        }
    }

    /** Forgets the retired `seq` of sessions with nothing left in the queue. */
    private fun pruneRetired() {
        if (session?.retiredSeqs.isNullOrEmpty()) return
        val queued = queue.sessionIds()
        val changed = synchronized(lock) {
            val current = session ?: return
            val kept = current.retiredSeqs.filterKeys { it in queued }
            if (kept.size == current.retiredSeqs.size) return
            session = current.copy(retiredSeqs = kept)
            true
        }
        if (changed) persistNow()
    }

    private fun persistSoon() {
        stateExecutor.execute { persistNow() }
    }

    /** Writes the newest session snapshot; safe from either replay thread. */
    private fun persistNow() {
        synchronized(persistLock) {
            val snapshot = synchronized(lock) { session }
            state.set(Keys.SESSION, snapshot?.toJson())
        }
    }

    private fun deliverSoon() {
        if (synchronized(lock) { pendingNotifications.isEmpty() }) return
        stateExecutor.execute { deliverPending() }
    }

    /** On the state thread: every queued rotation, oldest first. */
    private fun deliverPending() {
        while (true) {
            val (notification, current) = synchronized(lock) {
                val next = pendingNotifications.removeFirstOrNull() ?: return
                next to listeners.values.toList()
            }
            for (listener in current) {
                try {
                    listener(notification.sessionId)
                } catch (_: Exception) {
                    // One listener must not break the others.
                }
            }
        }
    }

    private fun parseIds(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    companion object {
        /**
         * Activity alone (a tracked event) is written at most this often, so
         * `$session_id` does not add a storage write per event. A relaunch
         * therefore waits this much longer than 30 min before rotating
         * ([ReplaySession.RELAUNCH_IDLE_TIMEOUT_MS]).
         */
        const val ACTIVITY_PERSIST_INTERVAL_MS = ReplaySession.ACTIVITY_PERSIST_WINDOW_MS

        /** How long after a rotation [enqueue] still accepts a chunk of the session it ended. */
        const val PREVIOUS_SESSION_GRACE_MS = 60_000L

        /** Held session-mode chunks over this many bytes (their JSON in UTF-8) are sent even if the recording is short. */
        const val MIN_DURATION_HOLD_MAX_BYTES = 5L * 1024 * 1024
    }
}
