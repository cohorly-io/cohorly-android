package com.cohorly.android.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Drives Mixpanel-style automatic (`$ae_*`) lifecycle events when
 * `trackAutomaticEvents` is enabled - the exact set the Cohorly iOS and
 * React Native SDKs implement:
 *
 * - `$ae_first_open` - fired once ever (guarded by a storage flag), plus
 *   `people.setOnce({$ae_first_app_open_date})`.
 * - `$ae_updated`    - fired when the stored last-seen app version differs
 *   from the current one (prop `$ae_updated_version`).
 * - `$ae_session`    - fired on the foreground -> background edge with
 *   `$ae_session_length` (seconds, integer) when the session ran >= 10s,
 *   plus a single `people.increment` bumping both
 *   `$ae_total_app_sessions` (by 1) and `$ae_total_app_session_length`
 *   (by the session's seconds).
 *
 * `$ae_crashed` is intentionally not implemented (no Cohorly sibling has it).
 * The clock is injected so session-length math is deterministic in tests.
 */
internal class AutomaticEvents(
    private val core: CohorlyCore,
    private val storage: CohorlyStorage,
    private val clock: CohorlyClock,
) {
    /**
     * `internal`, not private, so [RESTORE_CLEARED_KEYS] can name these
     * directly. A restored install that kept `did_first_open` would never fire
     * `$ae_first_open` again on the new device.
     */
    internal object Keys {
        const val DID_FIRST_OPEN = "cohorly.ae.did_first_open"
        const val LAST_VERSION = "cohorly.ae.last_version"
    }

    companion object {
        /** Minimum session duration (ms) that produces an `$ae_session`. */
        const val MIN_SESSION_LENGTH_MS = 10_000L
    }

    private val lock = Any()
    private var sessionStartMs: Long? = null

    /**
     * Runs first-open / updated detection and begins the initial session.
     * [currentVersion] is the host app version (injected in tests).
     */
    fun start(currentVersion: String?) {
        if (storage.get(Keys.DID_FIRST_OPEN) == null) {
            storage.set(Keys.DID_FIRST_OPEN, "1")
            core.track("\$ae_first_open")
            core.people.setOnce(mapOf("\$ae_first_app_open_date" to iso8601(clock.nowMillis())))
        }

        if (currentVersion != null) {
            val lastVersion = storage.get(Keys.LAST_VERSION)
            if (lastVersion != null && lastVersion != currentVersion) {
                core.track("\$ae_updated", mapOf("\$ae_updated_version" to currentVersion))
            }
            if (lastVersion != currentVersion) {
                storage.set(Keys.LAST_VERSION, currentVersion)
            }
        }

        onForeground()
    }

    /** Marks the start of a session (idempotent - resets the start instant). */
    fun onForeground() {
        synchronized(lock) { sessionStartMs = clock.nowMillis() }
    }

    /**
     * Ends the current session, emitting `$ae_session` + a profile increment
     * when it ran for at least [MIN_SESSION_LENGTH_MS].
     */
    fun onBackground() {
        val start = synchronized(lock) {
            val s = sessionStartMs
            sessionStartMs = null
            s
        } ?: return

        val lengthMs = clock.nowMillis() - start
        if (lengthMs < MIN_SESSION_LENGTH_MS) return

        val lengthSec = (lengthMs / 1000).toInt()
        core.track("\$ae_session", mapOf("\$ae_session_length" to lengthSec))
        core.people.increment(
            mapOf(
                "\$ae_total_app_sessions" to 1,
                "\$ae_total_app_session_length" to lengthSec,
            ),
        )
    }

    private fun iso8601(epochMs: Long): String {
        // SimpleDateFormat instead of java.time: java.time needs API 26+ or
        // desugaring, and this SDK supports minSdk 21 without either.
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMs))
    }
}
