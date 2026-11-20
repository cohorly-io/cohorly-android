package com.cohorly.android.core

import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Decides when an Activity becomes a `$screen` event, when
 * `trackScreenViews` is enabled. The Android glue only forwards the
 * lifecycle callbacks; the dedupe lives here so it is testable on the JVM.
 *
 * Each screen that becomes visible produces one `$screen` whose only own
 * property is `$screen_name` (the Activity's simple class name). The event
 * goes through [CohorlyCore.track], so it carries default and super
 * properties and `$session_id` like any other event.
 *
 * Dedupe rules:
 * - the instance that produced the last `$screen` never produces another one
 *   (return from background, a dialog dismissed, back from another app);
 * - an instance recreated by a configuration change emits nothing: a visible
 *   instance (resumed, or the last one emitted) that is paused or destroyed
 *   with `isChangingConfigurations == true` leaves a pending recreation for
 *   its class, which the next instance of that class consumes. Several
 *   pending recreations can coexist (multi-window resizes recreate every
 *   visible Activity);
 * - a different instance emits, including A -> B -> A and A -> A (a new
 *   instance of the same class without a configuration change). An Activity
 *   in the back stack recreated while another one is on top was not visible,
 *   so going back to it emits.
 *
 * Instances are held weakly: a destroyed Activity is never leaked. Callers
 * pass the instance as an opaque identity, compared with `===`.
 */
internal class ScreenViews(private val core: CohorlyCore) {
    private val lock = Any()
    private var lastEmitted: WeakReference<Any>? = null

    /** Instances resumed and not yet paused or destroyed. */
    private val resumed: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    /** Instances already counted as leaving for a configuration change (pause and destroy both report it). */
    private val counted: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    /** Pending recreations per class, consumed by the next instance of that class. */
    private val pendingRecreations = HashMap<Class<*>, Int>()

    /** Resolves and tracks in one call (the JVM tests); the Android glue uses [resolve] and tracks on the worker. */
    fun onResumed(instance: Any) {
        resolve(instance)?.let(::track)
    }

    fun track(name: String, timeMs: Long? = null) {
        core.trackAt("\$screen", mapOf("\$screen_name" to name), timeMs)
    }

    /**
     * Applies the dedupe rules and returns the screen name to track, or null.
     * Cheap and thread-safe, so the glue calls it on the main thread where the
     * Activity is alive, and only the resulting track goes to the worker.
     */
    fun resolve(instance: Any): String? {
        // Opted out: nothing is tracked, so the screen must not count as sent
        // (an Activity open at opt-in would never emit). Read before our lock.
        if (core.hasOptedOutTracking()) {
            synchronized(lock) { resumed.add(instance) }
            return null
        }
        val name: String
        synchronized(lock) {
            resumed.add(instance)
            if (lastEmitted?.get() === instance) return null
            lastEmitted = WeakReference(instance)
            val pending = pendingRecreations[instance.javaClass] ?: 0
            if (pending > 0) {
                if (pending == 1) {
                    pendingRecreations.remove(instance.javaClass)
                } else {
                    pendingRecreations[instance.javaClass] = pending - 1
                }
                return null
            }
            // A genuine new screen: any recreation still pending never came back.
            pendingRecreations.clear()
            name = instance.javaClass.simpleName
        }
        return name
    }

    /** Call from onPause and onDestroy; only a configuration change of a visible instance matters. */
    fun onLeft(instance: Any, isChangingConfigurations: Boolean) {
        synchronized(lock) {
            val wasVisible = resumed.remove(instance) || lastEmitted?.get() === instance
            if (!isChangingConfigurations || !wasVisible || !counted.add(instance)) return
            pendingRecreations[instance.javaClass] = (pendingRecreations[instance.javaClass] ?: 0) + 1
        }
    }
}
