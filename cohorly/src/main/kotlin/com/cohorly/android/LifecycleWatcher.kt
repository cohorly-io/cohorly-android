package com.cohorly.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * App foreground/background detection via started-activity counting
 * (no androidx.lifecycle dependency). Configuration changes (rotation) do
 * not produce a background/foreground edge.
 */
internal class LifecycleWatcher(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var startedCount = 0
    private var changingConfigurations = false

    override fun onActivityStarted(activity: Activity) {
        if (startedCount == 0 && !changingConfigurations) onForeground()
        changingConfigurations = false
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount--
        changingConfigurations = activity.isChangingConfigurations
        if (startedCount == 0 && !changingConfigurations) onBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
