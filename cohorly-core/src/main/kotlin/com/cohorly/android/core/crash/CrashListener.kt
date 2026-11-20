package com.cohorly.android.core.crash

/**
 * Told when [CrashReporter] has written a report to disk (VEL-2151: the
 * session replay recorder sends its error buffer then).
 *
 * [fatal] true: an uncaught exception, called on the crashing thread, after
 * the report is written and before the handler's bounded send; the process
 * dies right after, so the listener works synchronously and never waits on
 * the network. False: a watchdog ANR, called on the watchdog thread; the
 * process lives on. A listener that throws never stops the report nor the
 * handler chain.
 */
fun interface CrashListener {
    fun onReport(fatal: Boolean)
}
