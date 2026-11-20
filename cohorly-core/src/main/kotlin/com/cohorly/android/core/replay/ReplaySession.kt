package com.cohorly.android.core.replay

import org.json.JSONObject

/**
 * The replay session as persisted (key [ReplayController.Keys.SESSION] of the
 * replay state storage, never the SDK's event file):
 * its id, when it started, the last time the app was seen active, when it
 * went to background (null while in foreground), the sampling decision taken
 * once for the session, and the next chunk `seq`.
 *
 * [retiredSeqs] keeps the next `seq` of earlier sessions that may still have
 * chunks in the queue, so splitting one of those chunks after a rotation
 * still takes a `seq` that session never used.
 *
 * With remote settings (VEL-2236), [pending] marks a session born before
 * they were known: it records nothing ([mode] is OFF) until they decide it.
 * [minDurationMs] is decided with the mode and kept for the session. Both
 * are written only when set, so a record from an older build reads as a
 * decided session with no minimum duration.
 */
data class ReplaySession(
    val id: String,
    val startedAt: Long,
    val lastActiveAt: Long,
    val backgroundedAt: Long?,
    val mode: ReplayMode,
    val nextSeq: Long,
    val retiredSeqs: Map<String, Long> = emptyMap(),
    val pending: Boolean = false,
    val minDurationMs: Long = 0L,
) {
    /** 30 min or more in background, measured at [now]. */
    fun backgroundExpired(now: Long): Boolean = backgroundedAt != null && now - backgroundedAt >= IDLE_TIMEOUT_MS

    /**
     * 30 min or more in foreground without activity, measured at [now]
     * against [lastActive]. Only in foreground: a session in background is
     * decided by [backgroundExpired] when the app comes back.
     */
    fun foregroundIdleExpired(now: Long, lastActive: Long = lastActiveAt): Boolean =
        backgroundedAt == null && now - lastActive >= IDLE_TIMEOUT_MS

    /** The session reached its maximum length. */
    fun tooLong(now: Long): Boolean = now - startedAt >= MAX_LENGTH_MS

    /**
     * A new process finds this session: it continues unless the app was last
     * seen (active or going to background) [RELAUNCH_IDLE_TIMEOUT_MS] ago or
     * more, or the session reached 24 h. Activity alone is persisted at most
     * once per [ACTIVITY_PERSIST_WINDOW_MS], so the stored instant may be up
     * to that much early; adding the window means a relaunch never rotates
     * with less than 30 real minutes of inactivity, at the cost of extending
     * a session by up to one window.
     */
    fun expiredOnRelaunch(now: Long): Boolean {
        val lastSeen = maxOf(lastActiveAt, backgroundedAt ?: Long.MIN_VALUE)
        return now - lastSeen >= RELAUNCH_IDLE_TIMEOUT_MS || tooLong(now)
    }

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("started_at", startedAt)
        put("last_active_at", lastActiveAt)
        backgroundedAt?.let { put("backgrounded_at", it) }
        put("mode", mode.wire ?: "off")
        put("next_seq", nextSeq)
        if (retiredSeqs.isNotEmpty()) {
            put("retired_seqs", JSONObject().apply { for ((k, v) in retiredSeqs) put(k, v) })
        }
        if (pending) put("pending", true)
        if (minDurationMs > 0) put("min_duration_ms", minDurationMs)
    }.toString()

    companion object {
        const val IDLE_TIMEOUT_MS = 30L * 60 * 1000
        const val MAX_LENGTH_MS = 24L * 60 * 60 * 1000

        /**
         * Activity alone (a tracked event) is persisted at most this often,
         * so `$session_id` does not add a storage write per event.
         */
        const val ACTIVITY_PERSIST_WINDOW_MS = 60_000L

        /** Inactivity after which a relaunch starts a new session (see [expiredOnRelaunch]). */
        const val RELAUNCH_IDLE_TIMEOUT_MS = IDLE_TIMEOUT_MS + ACTIVITY_PERSIST_WINDOW_MS

        /** At most this many earlier sessions keep a retired `seq`; the oldest goes first. */
        const val MAX_RETIRED_SESSIONS = 50

        /** Null for absent or unparseable JSON (a fresh session is minted then). */
        fun parse(json: String?): ReplaySession? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val id = obj.getString("id").takeIf { it.isNotEmpty() } ?: return null
                val retired = LinkedHashMap<String, Long>()
                obj.optJSONObject("retired_seqs")?.let { r ->
                    for (key in r.keys()) retired[key] = r.getLong(key)
                }
                ReplaySession(
                    id = id,
                    startedAt = obj.getLong("started_at"),
                    lastActiveAt = obj.getLong("last_active_at"),
                    backgroundedAt = if (obj.has("backgrounded_at")) obj.getLong("backgrounded_at") else null,
                    mode = ReplayMode.fromWire(obj.optString("mode")),
                    nextSeq = obj.getLong("next_seq").coerceAtLeast(0),
                    retiredSeqs = retired,
                    pending = obj.optBoolean("pending", false),
                    minDurationMs = obj.optLong(
                        "min_duration_ms",
                        0L,
                    ).coerceIn(0L, RemoteReplayConfig.MAX_MIN_DURATION_MS),
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * The sampling decision, taken once per session: `session` when the
         * first draw falls under `sampleRate`, else `error` when a second one
         * falls under `onErrorSampleRate`, else off. [random] is in [0, 1).
         */
        fun draw(config: SessionReplayConfig, random: () -> Double): ReplayMode =
            draw(config.sampleRate, config.onErrorSampleRate, random)

        fun draw(sampleRate: Double, onErrorSampleRate: Double, random: () -> Double): ReplayMode = when {
            random() < sampleRate -> ReplayMode.SESSION
            random() < onErrorSampleRate -> ReplayMode.ERROR
            else -> ReplayMode.OFF
        }
    }
}
