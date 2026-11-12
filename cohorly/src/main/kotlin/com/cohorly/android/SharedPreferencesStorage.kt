package com.cohorly.android

import android.content.Context
import android.content.SharedPreferences
import com.cohorly.android.core.CohorlyStorage

/**
 * SharedPreferences-backed persistence, namespaced per project token.
 *
 * No longer the default: `shared_prefs/` is copied by Android's Auto Backup,
 * so SDK state followed a Google Drive restore or a device-to-device transfer
 * onto a second device and both reported the same `$device_id`.
 * [NoBackupFileStorage] stores under `no_backup/` instead, which the platform
 * excludes, and migrates anything this class left behind on first run.
 *
 * Kept only so the class does not vanish from a published artifact. Nothing in
 * the SDK constructs it.
 */
@Deprecated(
    "Backed up by Android Auto Backup, which duplicates \$device_id across devices. " +
        "Use NoBackupFileStorage; it migrates existing state automatically.",
    ReplaceWith("NoBackupFileStorage(context, token)"),
)
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
