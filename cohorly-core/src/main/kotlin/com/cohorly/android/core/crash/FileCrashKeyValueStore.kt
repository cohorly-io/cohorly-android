package com.cohorly.android.core.crash

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * [CrashKeyValueStore] as one JSON object in [file] (on Android
 * `noBackupFilesDir/cohorly-crash/state.json`). Not SharedPreferences: Auto
 * Backup copies `shared_prefs/`, and a restore on a second device would send
 * its crashes under the first device's `$device_id` and user. Every write is
 * synchronous (temp file, fsync, rename), so the next launch sees it even
 * when this process dies right after.
 */
class FileCrashKeyValueStore(private val file: File) : CrashKeyValueStore {
    private val lock = Any()

    override fun getString(key: String): String? = synchronized(lock) { read().opt(key) as? String }

    override fun putString(key: String, value: String) = update { it.put(key, value) }

    override fun getLong(key: String): Long? = synchronized(lock) { (read().opt(key) as? Number)?.toLong() }

    override fun putLong(key: String, value: Long) = update { it.put(key, value) }

    private fun update(change: (JSONObject) -> Unit) {
        synchronized(lock) {
            val obj = read()
            change(obj)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("cannot rename $tmp")
            }
        }
    }

    // Unreadable state reads as empty: crash reporting off until rewritten.
    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }
}
