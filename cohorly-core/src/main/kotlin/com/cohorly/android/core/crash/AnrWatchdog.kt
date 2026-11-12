package com.cohorly.android.core.crash

import com.cohorly.android.core.CohorlyClock

/**
 * Optional in-process ANR detector, off by default. It posts a tick to the
 * main thread and, when the tick has not run [thresholdMs] later while the
 * app is in the foreground, reports once. The next report needs the main
 * thread to run a tick first, so one continuous block is one report.
 *
 * `ApplicationExitInfo` only sees ANRs that killed the process (and only on
 * API 30+); this sees the ones the user waited out.
 */
class AnrWatchdog(
    private val clock: CohorlyClock,
    private val postToMain: (Runnable) -> Unit,
    private val isForeground: () -> Boolean,
    private val onBlocked: (blockedMs: Long) -> Unit,
    val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    @Volatile
    private var tickPending = false

    @Volatile
    private var reportedThisBlock = false

    private var tickPostedAt = 0L

    /** One step of the loop. Returns how long to sleep before the next. */
    fun check(): Long {
        val now = clock.nowMillis()
        if (!tickPending) {
            tickPending = true
            tickPostedAt = now
            postToMain {
                tickPending = false
                reportedThisBlock = false
            }
            // Poll, not sleep the whole threshold: a tick that ran early is
            // replaced at once, so a stall is seen thresholdMs after it starts.
            return pollMs
        }
        if (!reportedThisBlock && now - tickPostedAt >= thresholdMs && isForeground()) {
            reportedThisBlock = true
            onBlocked(thresholdMs)
        }
        return pollMs
    }

    /** Runs [check] forever on a daemon thread. */
    fun startThread(): Thread = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) Thread.sleep(check())
        } catch (_: InterruptedException) {
            // Stopped.
        } catch (_: Throwable) {
            // A watchdog bug must never take the app down.
        }
    }, "cohorly-anr-watchdog").apply {
        isDaemon = true
        start()
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 5_000L
        const val DEFAULT_POLL_MS = 500L
    }
}
