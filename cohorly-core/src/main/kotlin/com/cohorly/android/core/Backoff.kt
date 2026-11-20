package com.cohorly.android.core

import kotlin.math.min
import kotlin.math.pow

/**
 * The SDK retry backoff, shared by `/track` and `/replay`: base 2 s, doubling
 * per consecutive failure, capped at the max delay, with +/-20% jitter. Each
 * caller keeps its own failure counter, so one endpoint failing never delays
 * the other.
 */
internal object Backoff {
    const val BASE_MS = 2_000L

    /** Share of the delay the jitter may add or remove (+/-20%). */
    const val JITTER_FRACTION = 0.2

    /** [jitter] is in [-1, 1]; [failures] counts the failure just seen (>= 1). */
    fun delayMs(failures: Int, maxDelayMs: Long, jitter: Double): Long {
        val exp = BASE_MS * 2.0.pow(failures - 1)
        val capped = min(exp, maxDelayMs.toDouble())
        val spread = capped * JITTER_FRACTION * jitter
        return (capped + spread).toLong().coerceAtLeast(0)
    }
}
