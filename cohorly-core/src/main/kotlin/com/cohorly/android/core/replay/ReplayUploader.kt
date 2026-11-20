package com.cohorly.android.core.replay

import com.cohorly.android.core.Backoff
import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.CohorlyHttpException
import com.cohorly.android.core.CohorlyTransport
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Sends queued chunks to `POST <host>/replay`, oldest first, with its own
 * failure counter and backoff (a replay failure never delays `/track`, nor
 * the other way round). Runs on the upload thread only; not thread-safe,
 * apart from [stop], [start] and the flags, which any thread may touch.
 *
 * - 2xx: the chunk is removed and the failure count reset.
 * - 429 (not quota), 408, 5xx, network: kept, SDK backoff, `Retry-After`
 *   when above 0 (capped at the max delay).
 * - 401: kept, maximum backoff.
 * - 413, or a body over [MAX_CHUNK_JSON_BYTES] (or [MAX_CHUNK_EVENTS] events)
 *   before sending: split in two halves, the first keeps its `seq`, the
 *   second takes a new one from [nextSeqFor] (see [ReplayChunkQueue.split]).
 *   A single event that still does not fit is dropped.
 * - 429 `replay quota exceeded`: [onQuotaExceeded] pauses `session`
 *   recording until `Retry-After` (1 h without it); a refused `session` chunk
 *   is dropped together with every other queued `session` chunk of its
 *   session, while that session's `error` chunks stay. A refused `error`
 *   chunk is retried like any 429.
 * - 404: replay is off until the next launch ([disabled]).
 * - Any other 4xx: the chunk is dropped.
 *
 * A chunk that cannot be removed after a final answer (2xx, dropping 4xx)
 * ends the drain, so the same file is never sent in a loop; the next drain
 * sends it again (the server ignores a duplicate `(session_id, seq)`).
 *
 * [stop] and [start] bump a generation that [drain] checks right before each
 * POST, so an opt-out landing between reading a chunk and posting it sends
 * nothing, even when an opt-in follows at once.
 */
class ReplayUploader(
    private val queue: ReplayChunkQueue,
    private val transport: CohorlyTransport,
    apiHost: String,
    private val token: String?,
    private val clock: CohorlyClock,
    private val maxRetryDelayMs: Long,
    private val jitterSource: () -> Double,
    /** A `seq` the given session never used, persisted before it is returned. */
    private val nextSeqFor: (sessionId: String) -> Long,
    /** A `replay quota exceeded` refusal: `session` recording pauses until the given instant. */
    private val onQuotaExceeded: (untilMs: Long) -> Unit,
    /** Sessions dropped by an opt-out whose chunks could not all be deleted yet: removed, never sent. */
    private val isPurged: (sessionId: String) -> Boolean = { false },
    /**
     * [count] chunks left the queue without reaching the server (a dropping
     * 4xx, a quota refusal, a single event too large, an unreadable file);
     * never called for a sent chunk or an opt-out purge.
     */
    private val onDropped: (count: Int) -> Unit = {},
) {
    private val url = "${apiHost.trimEnd('/')}/replay"

    var consecutiveFailures = 0
        private set

    /** Instant before which a non-forced [drain] sends nothing. */
    var backoffUntilMs = 0L
        private set

    /** Set by a 404 (or a transport without [CohorlyTransport.postBytes]); in memory only. */
    @Volatile
    var disabled = false
        private set

    /** Stopped by opt-out; [start] resumes. */
    @Volatile
    var stopped = false
        private set

    private val generation = AtomicLong()

    fun stop() {
        stopped = true
        generation.incrementAndGet()
    }

    fun start() {
        generation.incrementAndGet()
        stopped = false
    }

    /**
     * Sends until the queue is empty, a failure starts a backoff, or replay
     * is off. The backoff is checked once, before the first attempt, and
     * [force] skips that check; after an answer that settles a chunk (sent,
     * split or dropped) the next one goes at once.
     * Returns the delay after which a retry should run, or null when nothing
     * is left to retry.
     */
    fun drain(force: Boolean): Long? {
        val gen = generation.get()
        if (!force) {
            val now = clock.nowMillis()
            if (now < backoffUntilMs) return backoffUntilMs - now
        }
        // Purged chunks that could not be deleted: passed over for the rest of this drain.
        val skipped = HashSet<String>()
        while (sendable(gen)) {
            val entry = queue.entries().firstOrNull { it.name !in skipped } ?: return null
            if (isPurged(entry.sessionId)) {
                if (!queue.remove(entry)) skipped.add(entry.name)
                continue
            }
            when (sendOne(entry, gen)) {
                Outcome.NEXT -> Unit
                Outcome.BACKOFF -> return backoffUntilMs - clock.nowMillis()
                Outcome.STOP -> return null
            }
        }
        return null
    }

    private fun sendable(gen: Long): Boolean = !disabled && !stopped && generation.get() == gen

    private enum class Outcome { NEXT, BACKOFF, STOP }

    private fun sendOne(entry: ReplayChunkQueue.Entry, gen: Long): Outcome {
        val bytes = queue.readBytes(entry)
        val json = bytes?.let { runCatching { ReplayChunkQueue.gunzip(it) }.getOrNull() }
        val chunk = json?.let { ReplayChunk.parse(String(it, Charsets.UTF_8), entry.createdAt) }
        if (bytes == null || json == null || chunk == null) {
            // Unreadable or not ours: nothing to retry.
            return drop(entry)
        }
        // The stored blob is the exact body, so its inflated size is the JSON size.
        if (json.size > MAX_CHUNK_JSON_BYTES || chunk.events.size > MAX_CHUNK_EVENTS) return split(entry, chunk)

        // Last look before the request: an opt-out since the drain began sends nothing.
        if (!sendable(gen)) return Outcome.STOP
        val error = try {
            transport.postBytes(
                url,
                bytes,
                buildMap {
                    if (token != null) put("X-Cohorly-Token", token)
                    put("Content-Encoding", "gzip")
                    put("Content-Type", "application/json")
                },
            )
            null
        } catch (_: UnsupportedOperationException) {
            // A custom transport that cannot send bytes: nothing will ever go through.
            disabled = true
            return Outcome.STOP
        } catch (e: Exception) {
            e
        }

        if (error == null) {
            consecutiveFailures = 0
            backoffUntilMs = 0
            return removeOrStop(entry)
        }

        val http = error as? CohorlyHttpException ?: return retry(null)
        val status = http.status
        return when {
            status == 429 && http.body?.contains(QUOTA_EXCEEDED) == true -> {
                onQuotaExceeded(clock.nowMillis() + (http.retryAfterMs?.takeIf { it > 0 } ?: DEFAULT_QUOTA_PAUSE_MS))
                if (chunk.mode == ReplayMode.SESSION) {
                    val removed = queue.remove(entry)
                    // The rest of this session's session-mode chunks would be refused too.
                    val others = queue.removeMode(entry.sessionId, ReplayMode.SESSION)
                    val dropped = others + if (removed) 1 else 0
                    if (dropped > 0) onDropped(dropped)
                    if (removed) Outcome.NEXT else Outcome.STOP
                } else {
                    retry(http)
                }
            }
            status == 429 || status == 408 || status in 500..599 -> retry(http)
            status == 401 -> {
                consecutiveFailures += 1
                backoffUntilMs = clock.nowMillis() + maxRetryDelayMs
                Outcome.BACKOFF
            }
            status == 413 -> split(entry, chunk)
            status == 404 -> {
                disabled = true
                Outcome.STOP
            }
            status in 400..499 -> drop(entry)
            else -> retry(http)
        }
    }

    /** A chunk that cannot be deleted ends the drain instead of being read again and again. */
    private fun removeOrStop(entry: ReplayChunkQueue.Entry): Outcome =
        if (queue.remove(entry)) Outcome.NEXT else Outcome.STOP

    /** [removeOrStop] for a chunk that never reached the server: [onDropped] hears of it. */
    private fun drop(entry: ReplayChunkQueue.Entry): Outcome {
        if (!queue.remove(entry)) return Outcome.STOP
        onDropped(1)
        return Outcome.NEXT
    }

    private fun retry(http: CohorlyHttpException?): Outcome {
        consecutiveFailures += 1
        val retryAfter = http?.retryAfterMs?.takeIf { it > 0 }
        val delay = if (retryAfter != null) {
            min(retryAfter, maxRetryDelayMs)
        } else {
            Backoff.delayMs(consecutiveFailures, maxRetryDelayMs, jitterSource())
        }
        backoffUntilMs = clock.nowMillis() + delay
        return Outcome.BACKOFF
    }

    /**
     * Replaces the chunk with its two halves; a single event cannot be split
     * and is dropped. A failed write keeps the original and backs off like a
     * network failure, so a full disk never spins.
     */
    private fun split(entry: ReplayChunkQueue.Entry, chunk: ReplayChunk): Outcome {
        if (chunk.events.size <= 1) return drop(entry)
        val half = chunk.events.size / 2
        val first = chunk.copy(events = chunk.events.subList(0, half))
        val second = chunk.copy(
            seq = nextSeqFor(chunk.sessionId),
            events = chunk.events.subList(half, chunk.events.size),
        )
        return if (queue.split(entry, first, second)) Outcome.NEXT else retry(null)
    }

    companion object {
        /** The server's wire limit is 1 MB; the SDK splits on the uncompressed JSON, like the web SDK. */
        const val MAX_CHUNK_JSON_BYTES = 1_000_000

        /** Server limit per chunk (400 above it). */
        const val MAX_CHUNK_EVENTS = 5_000

        const val QUOTA_EXCEEDED = "replay quota exceeded"
        const val DEFAULT_QUOTA_PAUSE_MS = 60L * 60 * 1000
    }
}
