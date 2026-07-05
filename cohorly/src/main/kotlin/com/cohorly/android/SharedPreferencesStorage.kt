package com.cohorly.android

import android.content.Context
import android.content.SharedPreferences
import com.cohorly.android.core.CohorlyStorage

/**
 * SharedPreferences-backed persistence, namespaced per project token.
 *
 * mixpanel-android persists its queue in SQLite; Cohorly uses
 * SharedPreferences-JSON instead (parity with the iOS SDK's UserDefaults and
 * the React Native SDK's AsyncStorage) - the queue is capped at 1000 events,
 * well within SharedPreferences territory, and this keeps the SDK free of
 * runtime dependencies.
 */
class SharedPreferencesStorage(context: Context, token: String) : CohorlyStorage {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cohorly_$token", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
