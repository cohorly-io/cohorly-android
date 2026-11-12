package com.cohorly.android.crash

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Started-activity count for the ANR watchdog, plus a one-shot hook on the
 * first resume: by then every SDK the app initializes in `onCreate` has run,
 * so a handler installed over ours without chaining can be undone.
 */
internal class ForegroundTracker(private val onFirstResume: () -> Unit) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0

    @Volatile
    private var resumedOnce = false

    val isForeground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityResumed(activity: Activity) {
        if (resumedOnce) return
        resumedOnce = true
        try {
            onFirstResume()
        } catch (_: Throwable) {
            // Never break the app's lifecycle.
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
