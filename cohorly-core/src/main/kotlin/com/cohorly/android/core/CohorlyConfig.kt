package com.cohorly.android.core

import com.cohorly.android.core.replay.SessionReplayConfig

/**
 * Client configuration. Defaults follow the Cohorly sibling SDKs
 * (sdk-core / sdk-react-native / sdks/ios), not mixpanel-android:
 * flush interval is 5s (mixpanel uses 60s) and batch size is 20.
 */
data class CohorlyConfig(
    /** Auto-flush interval in ms. <= 0 disables the timer. */
    val flushIntervalMs: Long = 5_000,
    /**
     * Events per /track request; also the queue-depth threshold that triggers
     * an early flush. Coerced into 1..[CohorlyCore.SERVER_MAX_BATCH] (the
     * server rejects batches over 500 with a 400).
     */
    val batchSize: Int = 20,
    /** Max events retained in the persisted queue; oldest dropped on overflow. */
    val maxQueueSize: Int = 1_000,
    /** Upper bound on the retry backoff, in ms (default 10 minutes). */
    val maxRetryDelayMs: Long = 600_000,
    /** Enables $ae_first_open / $ae_updated / $ae_session lifecycle events. */
    val trackAutomaticEvents: Boolean = false,
    /**
     * Evaluate feature flags on init (POST /flags/evaluate for the current
     * distinct id, on the worker thread). Default true; set false to call
     * `reloadFeatureFlags()` yourself. Mirrors sdk-react-native's option.
     */
    val loadFeatureFlags: Boolean = true,
    /**
     * Track `$feature_flag_called` the first time each flag value is read via
     * `getFeatureFlag` / `isFeatureEnabled`. Default true; set false to opt out
     * of client-side exposure events.
     */
    val sendExposureEvents: Boolean = true,
    /**
     * How flag reads resolve values against the persisted cache
     * (`networkOnly` / `networkFirst` / `persistenceUntilNetworkSuccess`).
     * Defaults to [FlagPersistenceMode.persistenceUntilNetworkSuccess], which
     * preserves the pre-policy behavior of always restoring the cache.
     */
    val flagPersistence: FlagPersistenceMode = FlagPersistenceMode.persistenceUntilNetworkSuccess,
    /**
     * Max age of the persisted flag cache, in ms. Defaults to 24 hours - the
     * Mixpanel reference default for flag persistence, shared with the
     * sibling SDKs. How an older cache is treated depends on
     * [flagPersistence]: `networkFirst` drops it, while
     * `persistenceUntilNetworkSuccess` still serves it inside
     * [CohorlyCore.FLAG_CACHE_MAX_STALE_MS] (7 days). A legacy cache with no
     * `stored_at` has no provable age and is served indefinitely. A
     * non-positive value falls back to the default.
     */
    val flagCacheTtlMs: Long = DEFAULT_FLAG_CACHE_TTL_MS,
    /**
     * Native crash reporting (uncaught exceptions, and on API 30+ ANRs,
     * native crashes and foreground low-memory kills). Default false. When
     * true, `getInstance` persists the crash config (release
     * `<applicationId>@<versionName>+<versionCode>`, identity, token, host)
     * and keeps it current on identify/reset; the reporter starts on the NEXT
     * launch. False leaves the persisted config alone (a React Native layer
     * may own it).
     */
    val nativeCrashReporting: Boolean = false,
    /**
     * In-process ANR watchdog (main thread blocked 5 s in the foreground,
     * reported as level `error`). Default false. Only used with
     * [nativeCrashReporting]; takes effect on the next launch.
     */
    val anrWatchdog: Boolean = false,
    /**
     * Session replay. Null (the default) turns it off: no event carries
     * `$session_id` and nothing replay-related is stored. Set, even with both
     * rates at 0, every tracked event (and every `$exception` the in-process
     * crash reporter records) carries the current `$session_id`.
     */
    val sessionReplay: SessionReplayConfig? = null,
    /**
     * Tracks a `$screen` event (property `$screen_name`, the Activity's simple
     * class name) each time an Activity becomes visible. Off by default and
     * independent of session replay: turning replay on never turns this on.
     * Each `$screen` counts against the monthly event quota like any `track`.
     * Fragments and Navigation Compose destinations are not tracked. Last in
     * the list so positional callers of earlier versions keep their meaning.
     */
    val trackScreenViews: Boolean = false,
) {
    companion object {
        const val DEFAULT_FLAG_CACHE_TTL_MS: Long = 86_400_000
    }
}
