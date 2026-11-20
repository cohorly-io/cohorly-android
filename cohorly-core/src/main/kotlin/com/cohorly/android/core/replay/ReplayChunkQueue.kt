package com.cohorly.android.core.replay

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One replay chunk, with the ids it was queued under. They are fixed when the
 * chunk is queued and never re-read at send time, so a chunk queued before a
 * rotation still goes out under the session it was recorded in.
 */
data class ReplayChunk(
    val sessionId: String,
    val distinctId: String,
    val seq: Long,
    val mode: ReplayMode,
    val createdAt: Long,
    val events: List<JSONObject>,
) {
    /** The `POST /replay` body (docs/SESSION-REPLAY.md section 2), before gzip. */
    fun toJson(libVersion: String): String = JSONObject().apply {
        put("session_id", sessionId)
        put("distinct_id", distinctId)
        put("seq", seq)
        put("mode", mode.wire)
        put("lib", LIB)
        put("lib_version", libVersion)
        put("events", JSONArray(events))
    }.toString()

    companion object {
        const val LIB = "android"

        /** Null when [json] is not a chunk this SDK wrote. */
        fun parse(json: String, createdAt: Long): ReplayChunk? {
            return try {
                val obj = JSONObject(json)
                val mode = ReplayMode.fromWire(obj.optString("mode"))
                if (mode == ReplayMode.OFF) return null
                val array = obj.getJSONArray("events")
                ReplayChunk(
                    sessionId = obj.getString("session_id"),
                    distinctId = obj.getString("distinct_id"),
                    seq = obj.getLong("seq"),
                    mode = mode,
                    createdAt = createdAt,
                    events = (0 until array.length()).map { array.getJSONObject(it) },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Where queued chunks live: named blobs, one per chunk. [FileReplayChunkStore]
 * on a device; tests use an in-memory one. Only [ReplayChunkQueue] calls it,
 * always under the queue's lock, from the replay threads.
 */
interface ReplayChunkStore {
    /** Every stored chunk name. */
    fun names(): List<String>

    fun read(name: String): ByteArray?

    /** Atomic: a reader sees the previous blob or the whole new one. Returns false when it did not land. */
    fun write(name: String, bytes: ByteArray): Boolean

    /** True when the blob is gone afterwards (also when it was already absent). */
    fun delete(name: String): Boolean

    /** Stored size in bytes, 0 when absent. */
    fun size(name: String): Long
}

/** In-memory [ReplayChunkStore] for tests and non-Android JVM use (nothing survives the process). */
class InMemoryReplayChunkStore : ReplayChunkStore {
    private val blobs = LinkedHashMap<String, ByteArray>()

    @Synchronized
    override fun names(): List<String> = blobs.keys.toList()

    @Synchronized
    override fun read(name: String): ByteArray? = blobs[name]

    @Synchronized
    override fun write(name: String, bytes: ByteArray): Boolean {
        blobs[name] = bytes
        return true
    }

    @Synchronized
    override fun delete(name: String): Boolean {
        blobs.remove(name)
        return true
    }

    @Synchronized
    override fun size(name: String): Long = blobs[name]?.size?.toLong() ?: 0L
}

/**
 * A directory of gzip files, one per chunk (`no_backup/cohorly_replay_<token>/`
 * on Android). Nothing touches the disk until the first call, and the
 * directory is created only by the first write, so an app without session
 * replay never gets one.
 */
class FileReplayChunkStore internal constructor(
    private val dir: File,
    /** Moves the finished temp file over the chunk name; a test seam. */
    private val rename: (from: File, to: File) -> Boolean,
) : ReplayChunkStore {
    constructor(dir: File) : this(dir, { from, to -> from.renameTo(to) })

    private var cleaned = false

    override fun names(): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        if (!cleaned) {
            // A chunk temp file left by a process death mid-write is never a
            // chunk. Only ours: the replay state file shares the directory.
            for (file in files) {
                if (file.name.endsWith(ReplayChunkQueue.SUFFIX + TMP_SUFFIX)) runCatching { file.delete() }
            }
            cleaned = true
        }
        return files.filter { it.isFile && it.name.endsWith(ReplayChunkQueue.SUFFIX) }.map { it.name }
    }

    override fun read(name: String): ByteArray? = try {
        File(dir, name).takeIf { it.isFile }?.readBytes()
    } catch (_: Exception) {
        null
    }

    override fun write(name: String, bytes: ByteArray): Boolean {
        val target = File(dir, name)
        val tmp = File(dir, name + TMP_SUFFIX)
        return try {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!rename(tmp, target)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    override fun delete(name: String): Boolean = try {
        val file = File(dir, name)
        file.delete() || !file.exists()
    } catch (_: Exception) {
        false
    }

    override fun size(name: String): Long = File(dir, name).length()

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}

/**
 * The replay chunk queue: each chunk is stored gzipped under a name that
 * orders it, `<createdAt>_<seq>_<sessionId>.json.gz`, and sent oldest first
 * by (createdAt, seq). Separate from the `/track` queue on purpose: that one
 * is a single JSON rewritten on every event, and capped at 1000 events.
 *
 * The stored blob is the exact request body, so a send reads the file and
 * posts it as is. Total stored bytes are capped at [maxBytes]; the oldest
 * chunks go first.
 *
 * Thread-safe: every method holds the queue's lock, so the state thread
 * (writes) and the upload thread (reads, removals, splits) never interleave
 * inside the store. No method does network.
 */
class ReplayChunkQueue(
    private val store: ReplayChunkStore,
    private val libVersion: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /** Called with the number of chunks the 10 MB trim deleted unsent (set once by the controller). */
    @Volatile
    internal var onTrimmed: ((Int) -> Unit)? = null

    /** A queued chunk's place in the queue. */
    data class Entry(val name: String, val createdAt: Long, val seq: Long, val sessionId: String)

    /** Stores [chunk], then trims to [maxBytes] (never removing [chunk] itself). False when the write failed. */
    @Synchronized
    fun add(chunk: ReplayChunk): Boolean {
        val name = nameFor(chunk)
        val written = write(chunk)
        trim(if (written) setOf(name) else emptySet())
        return written
    }

    /** Stores [chunk] without trimming; the caller trims once its whole change is in. */
    @Synchronized
    fun write(chunk: ReplayChunk): Boolean =
        store.write(nameFor(chunk), gzip(chunk.toJson(libVersion).toByteArray(Charsets.UTF_8)))

    /**
     * Replaces [original] with [first] (same name: same createdAt and `seq`)
     * and [second] (a new `seq`), in one locked step so no concurrent trim
     * runs in between. [second] is written first and [original] overwritten
     * only once it landed; if either write fails, [original] is left intact
     * (and a landed [second] is removed again). The 10 MB trim runs after
     * the swap and never removes either half.
     *
     * Remaining window: a process death between the two writes leaves
     * [original] and [second] both queued, so the events of [second] are sent
     * twice (under two `seq`); they are never lost.
     */
    @Synchronized
    fun split(original: Entry, first: ReplayChunk, second: ReplayChunk): Boolean {
        if (!write(second)) return false
        if (nameFor(first) != original.name || !write(first)) {
            store.delete(nameFor(second))
            return false
        }
        trim(setOf(original.name, nameFor(second)))
        return true
    }

    /** Oldest first. Names this queue did not write are deleted. */
    @Synchronized
    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        for (name in store.names()) {
            val entry = parseName(name)
            if (entry == null) store.delete(name) else out.add(entry)
        }
        out.sortWith(compareBy<Entry>({ it.createdAt }, { it.seq }, { it.name }))
        return out
    }

    @Synchronized
    fun oldest(): Entry? = entries().firstOrNull()

    @Synchronized
    fun isEmpty(): Boolean = store.names().none { parseName(it) != null }

    /** The stored (gzipped) body, or null when it is gone. */
    @Synchronized
    fun readBytes(entry: Entry): ByteArray? = store.read(entry.name)

    /** The stored chunk decoded, or null when it is gone or unreadable. */
    @Synchronized
    fun read(entry: Entry): ReplayChunk? = readBytes(entry)?.let { decode(it, entry) }

    /** True when the chunk is gone afterwards. */
    @Synchronized
    fun remove(entry: Entry): Boolean = store.delete(entry.name)

    /** Removes every chunk; true when all of them are gone. */
    @Synchronized
    fun clear(): Boolean {
        var all = true
        for (name in store.names()) if (!store.delete(name)) all = false
        return all
    }

    /** Removes every chunk of [sessionIds]; true when all of them are gone. */
    @Synchronized
    fun removeSessions(sessionIds: Set<String>): Boolean {
        var all = true
        for (entry in entries()) if (entry.sessionId in sessionIds && !store.delete(entry.name)) all = false
        return all
    }

    /** Removes the queued chunks of [sessionId] recorded in [mode]; returns how many went. */
    @Synchronized
    fun removeMode(sessionId: String, mode: ReplayMode): Int {
        var removed = 0
        for (entry in entries()) {
            if (entry.sessionId != sessionId) continue
            if (read(entry)?.mode == mode && store.delete(entry.name)) removed++
        }
        return removed
    }

    @Synchronized
    fun totalBytes(): Long = store.names().sumOf { store.size(it) }

    /** Session ids that still have chunks queued. */
    @Synchronized
    fun sessionIds(): Set<String> = entries().map { it.sessionId }.toSet()

    /** One above the highest queued `seq` of [sessionId], 0 when none is queued. Names only. */
    @Synchronized
    fun nextSeqQueued(sessionId: String): Long =
        store.names().mapNotNull { parseName(it) }.filter { it.sessionId == sessionId }.maxOfOrNull { it.seq + 1 } ?: 0L

    /**
     * Drops the oldest chunks until the total fits [maxBytes], never one
     * named in [keep]. Only bytes actually deleted count as freed.
     */
    @Synchronized
    fun trim(keep: Set<String> = emptySet()) {
        val entries = entries()
        var total = entries.sumOf { store.size(it.name) }
        var deleted = 0
        try {
            for (entry in entries) {
                if (total <= maxBytes) return
                if (entry.name in keep) continue
                val size = store.size(entry.name)
                if (store.delete(entry.name)) {
                    total -= size
                    deleted++
                }
            }
        } finally {
            if (deleted > 0) onTrimmed?.invoke(deleted)
        }
    }

    private fun decode(bytes: ByteArray, entry: Entry): ReplayChunk? = try {
        ReplayChunk.parse(String(gunzip(bytes), Charsets.UTF_8), entry.createdAt)
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 10 MiB of gzip files per token. */
        const val DEFAULT_MAX_BYTES = 10L * 1024 * 1024
        const val SUFFIX = ".json.gz"

        fun nameFor(chunk: ReplayChunk): String = "${chunk.createdAt}_${chunk.seq}_${chunk.sessionId}$SUFFIX"

        fun parseName(name: String): Entry? {
            if (!name.endsWith(SUFFIX)) return null
            val parts = name.removeSuffix(SUFFIX).split('_', limit = 3)
            if (parts.size != 3 || parts[2].isEmpty()) return null
            val createdAt = parts[0].toLongOrNull() ?: return null
            val seq = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return Entry(name, createdAt, seq, parts[2])
        }

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }
}
