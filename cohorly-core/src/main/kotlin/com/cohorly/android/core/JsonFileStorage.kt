package com.cohorly.android.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed [CohorlyStorage]: one JSON object of string keys, held in memory
 * and rewritten on every change.
 *
 * This exists so the Android module can persist outside `shared_prefs/`, which
 * Android's Auto Backup copies to Google Drive and to a new device on transfer.
 * Backing up SDK state is wrong for analytics: a restored `$device_id` makes
 * two physical devices report as one. Putting the file under
 * `Context.getNoBackupFilesDir()` gets that exclusion from the platform without
 * the host app touching its manifest (see `NoBackupFileStorage`).
 *
 * Not thread-safe by accident - every method is synchronized, because the
 * Android module reads on the caller's thread at init and writes from its
 * worker thread afterwards.
 *
 * Every [set] rewrites the whole file, so persisting a 1000-event backlog
 * costs a 1000-event write per tracked event. That write amplification is the
 * same as the SharedPreferences it replaces (which also rewrites its entire
 * XML per `apply()`); what differs is that the write happens on the SDK's own
 * worker thread instead of a framework background thread. That is deliberate:
 * the worker is already the thread that flushes, so a slow disk applies
 * backpressure instead of queueing writes behind a growing backlog. Split the
 * queue into its own file before optimising this - do not make writes async
 * and lose the ordering the queue depends on.
 */
class JsonFileStorage(private val file: File) : CohorlyStorage {
    private val values = LinkedHashMap<String, String>()

    /** True while a [batch] is open: mutations accumulate, one write at the end. */
    private var batching = false

    /** Set by a mutation made while batching, so [batch] knows to write. */
    private var dirty = false

    /**
     * Persist attempts made so far. A test seam: the whole point of [batch] is
     * how MANY writes happen, which is otherwise unobservable from outside.
     */
    internal var writeCount: Int = 0
        private set

    init {
        load()
    }

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun set(key: String, value: String?) {
        val changed = if (value == null) values.remove(key) != null else values.put(key, value) != value
        if (changed) persistUnlessBatching()
    }

    /**
     * Bulk write, one persist. Returns whether the new state reached the disk -
     * the caller must not drop its own durable copy on `false` (see the
     * SharedPreferences migration in `NoBackupFileStorage`).
     */
    @Synchronized
    fun putAll(entries: Map<String, String>): Boolean {
        if (entries.isEmpty()) return true
        values.putAll(entries)
        return persistUnlessBatching()
    }

    /**
     * Runs [block] with persistence deferred, then writes once.
     *
     * Bootstrapping a fresh install would otherwise be four separate
     * write+fsync cycles (install stamp, distinct id, anonymous flag, device
     * id), and it happens on the caller's thread inside `Cohorly.getInstance` -
     * usually the main thread, before the worker exists. One fsync there is a
     * cost worth paying; four is a startup stall.
     *
     * Reentrant: a nested call joins the outer batch and does not write.
     */
    @Synchronized
    fun <T> batch(block: () -> T): T {
        if (batching) return block()
        batching = true
        try {
            return block()
        } finally {
            batching = false
            if (dirty) persist()
        }
    }

    /**
     * Merges [entries] and writes NOW, even inside a [batch], returning
     * whether the write reached the disk.
     *
     * For the one caller that must know: the SharedPreferences migration
     * cannot clear the legacy copy on the strength of a deferred write.
     */
    @Synchronized
    fun persistNow(entries: Map<String, String>): Boolean {
        values.putAll(entries)
        return persist()
    }

    /** True when nothing has ever been stored (fresh install, or wiped). */
    @Synchronized
    fun isEmpty(): Boolean = values.isEmpty()

    private fun load() {
        if (!file.isFile) return
        val json = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            // A truncated or unreadable file must not crash the host app's
            // startup, so it reads as a fresh install. Move it aside first:
            // the next write would otherwise overwrite it, and if the read
            // failure was transient rather than real corruption that would
            // destroy recoverable state with no trace.
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
            return
        }
        for (key in json.keys()) {
            (json.opt(key) as? String)?.let { values[key] = it }
        }
    }

    /**
     * Write a sibling temp file, fsync it, then rename over the target, so a
     * process death mid-write leaves the previous contents intact instead of a
     * truncated file that loses the device id on the next boot.
     *
     * The fsync is what makes that hold across a power cut rather than only
     * across a process kill - `SharedPreferencesImpl.writeToFile` syncs for the
     * same reason, and dropping it here would have been a real durability
     * regression against the storage this replaces.
     */
    private fun persistUnlessBatching(): Boolean {
        if (batching) {
            dirty = true
            // Not on disk yet, but the open batch owns writing it. Callers that
            // check this value do so outside a batch.
            return true
        }
        return persist()
    }

    /** @return true when the new contents reached the disk. */
    private fun persist(): Boolean {
        val json = JSONObject()
        for ((key, value) in values) json.put(key, value)
        val payload = json.toString().toByteArray()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        writeCount++
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Rename can fail on exotic filesystems; the direct write is
                // not atomic, but losing atomicity beats losing the write.
                file.writeBytes(payload)
                tmp.delete()
            }
            dirty = false
            return true
        } catch (_: Exception) {
            // Persistence is best-effort: an unwritable data dir must not take
            // the host app down. In-memory state stays correct for this run,
            // and the caller is told the write did not land.
            runCatching { tmp.delete() }
            return false
        }
    }
}
