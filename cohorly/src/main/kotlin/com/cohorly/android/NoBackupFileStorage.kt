package com.cohorly.android

import android.content.Context
import android.os.Build
import com.cohorly.android.core.CohorlyStorage
import com.cohorly.android.core.INSTALL_STAMP_KEY
import com.cohorly.android.core.JsonFileStorage
import com.cohorly.android.core.guardRestoredInstall
import java.io.File

/**
 * Default persistence: a JSON file under [Context.getNoBackupFilesDir],
 * namespaced per project token.
 *
 * Android's Auto Backup is on by default (`android:allowBackup` defaults to
 * true) and copies `shared_prefs/` to Google Drive and to the new device on a
 * device-to-device transfer. That is wrong for analytics state: a restored
 * `$device_id` makes two physical devices report as one, and a restored event
 * queue re-sends the old device's unsent events. The platform excludes
 * `no_backup/` from backup and transfer, so storing here fixes it for every
 * integrator without them editing their manifest or backup rules - which a
 * library cannot do on their behalf anyway, since `allowBackup` and
 * `dataExtractionRules` are `<application>` attributes that a library manifest
 * would either silently hijack or fail to merge.
 *
 * On first use it migrates any state left by [SharedPreferencesStorage], so an
 * app upgrading the SDK keeps its device id and unsent queue.
 */
class NoBackupFileStorage(context: Context, token: String) : CohorlyStorage {
    private val delegate: JsonFileStorage

    init {
        val app = context.applicationContext
        val stamp = installStamp(app)
        delegate = JsonFileStorage(File(app.noBackupFilesDir, "cohorly_${fileSafe(token)}.json"))
        // One batch, so a fresh install pays a single write+fsync here rather
        // than one for the migration and another for the stamp.
        delegate.batch {
            if (delegate.isEmpty()) migrateSharedPreferences(app, token, stamp)
            if (stamp != null) guardRestoredInstall(delegate, stamp)
        }
    }

    override fun get(key: String): String? = delegate.get(key)

    override fun set(key: String, value: String?) = delegate.set(key, value)

    /**
     * Runs [block] with persistence deferred, writing once at the end.
     *
     * `Cohorly.getInstance` wraps the [com.cohorly.android.core.CohorlyCore]
     * constructor in this: on a fresh install that constructor writes the
     * distinct id, the anonymous flag and the device id, and it runs on the
     * caller's thread - normally the main thread, before the SDK's worker
     * exists. Three write+fsync cycles there are a startup stall; one is not.
     */
    fun <T> batch(block: () -> T): T = delegate.batch(block)

    /**
     * One-time move out of `shared_prefs/`. The migrated state predates the
     * install stamp, so it is stamped as the CURRENT install: an upgrade must
     * not look like a restore, or every existing user would lose their device
     * id the day they ship the new SDK.
     *
     * State and stamp go in ONE [JsonFileStorage.persistNow] - hence one
     * temp-file rename - so a process death here can only leave the file fully
     * migrated or untouched, never migrated-but-unstamped.
     *
     * The old preferences are cleared only once that write is confirmed on
     * disk. They are the only durable copy until then: clearing them after a
     * failed write (a full or read-only data dir) would lose the device id and
     * the unsent queues outright.
     */
    private fun migrateSharedPreferences(app: Context, token: String, stamp: String?) {
        val legacyName = "cohorly_$token"
        val prefs = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val carried = prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        if (carried.isEmpty()) return

        val entries = if (stamp != null) carried + (INSTALL_STAMP_KEY to stamp) else carried
        // Outside the caller's batch on purpose: the return value has to mean
        // "on disk", and inside a batch nothing is written yet. This is the
        // once-per-install upgrade path, so the extra write is not a cost that
        // recurs.
        if (!delegate.persistNow(entries)) return

        prefs.edit().clear().apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Removes the backed-up XML itself, not just its contents.
            runCatching { app.deleteSharedPreferences(legacyName) }
        }
    }

    /**
     * `firstInstallTime` changes only when the app is installed anew - it
     * survives app updates and differs after a restore into a fresh install,
     * which is exactly the signal [guardRestoredInstall] needs. Null when the
     * package cannot be read, in which case the guard is skipped rather than
     * risking a wipe on a bad reading.
     */
    private fun installStamp(app: Context): String? = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime.toString()
    }.getOrNull()

    internal companion object {
        /** [token] as a file name part (also names the replay chunk directory). */
        fun fileSafe(token: String): String = token.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
    }
}
