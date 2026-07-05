package com.cohorly.android.core

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
)
