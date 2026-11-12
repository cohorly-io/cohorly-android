package com.cohorly.android.core.crash

import com.cohorly.android.core.CohorlyHttpException
import com.cohorly.android.core.CohorlyTransport
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Reports waiting to be sent: one complete `$exception` event per file,
 * `<dir>/<insertId>.json` (on Android `noBackupFilesDir/cohorly-crash/pending`).
 */
class PendingReportStore(private val dir: File, private val maxReports: Int = DEFAULT_MAX_REPORTS) {
    class Report(val file: File, val event: JSONObject) {
        val insertId: String get() = file.name.removeSuffix(SUFFIX)
        val time: Long get() = event.optJSONObject("properties")?.optLong("time", 0L) ?: 0L
    }

    /** Written through a temp file and a rename, so a half-written report is never read. */
    fun write(insertId: String, event: JSONObject): File {
        dir.mkdirs()
        val tmp = File(dir, "$insertId$TMP_SUFFIX")
        FileOutputStream(tmp).use { out ->
            out.write(event.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        val target = File(dir, "$insertId$SUFFIX")
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("cannot rename $tmp")
        }
        prune()
        return target
    }

    /**
     * Oldest crash first. An unreadable report is deleted, never retried, and
     * so is a temp file a dead process left before its rename (old enough
     * that no live write can own it).
     */
    fun list(now: Long = System.currentTimeMillis()): List<Report> {
        dir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) && now - f.lastModified() > STALE_TMP_MS }
            ?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                Report(file, JSONObject(file.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                file.delete()
                null
            }
        }.sortedWith(compareBy<Report>({ it.time }, { it.file.lastModified() }, { it.file.name }))
    }

    fun read(insertId: String): Report? {
        val file = File(dir, "$insertId$SUFFIX")
        if (!file.isFile) return null
        return try {
            Report(file, JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun prune() {
        val reports = list()
        if (reports.size <= maxReports) return
        reports.take(reports.size - maxReports).forEach { it.file.delete() }
    }

    companion object {
        const val DEFAULT_MAX_REPORTS = 50
        private const val SUFFIX = ".json"
        private const val TMP_SUFFIX = ".json.tmp"

        // A write is a few KB and one fsync; a minute-old temp file is dead.
        const val STALE_TMP_MS = 60_000L
    }
}

/**
 * Sends pending reports, one event per `POST <host>/track` with
 * `X-Cohorly-Token`. 2xx deletes the report; 400 and 413 delete it too (one
 * event cannot be made smaller, and the server will never take it); 401, 429,
 * 5xx and network errors keep it for the next launch and stop the run.
 */
class PendingReportSender(
    private val store: PendingReportStore,
    private val transport: CohorlyTransport,
    private val app: CrashAppInfo,
) {
    enum class Outcome { SENT, DROPPED, KEPT }

    private val lock = ReentrantLock()

    /** Every pending report, oldest first. Returns how many left the disk. */
    fun sendAll(endpoint: CrashEndpoint, shouldContinue: () -> Boolean = { true }): Int {
        lock.lock()
        try {
            var done = 0
            for (report in store.list()) {
                if (!shouldContinue()) break
                if (send(report, endpoint) == Outcome.KEPT) break
                done++
            }
            return done
        } finally {
            lock.unlock()
        }
    }

    /**
     * One report, waiting at most [lockTimeoutMs] for a send already running
     * (the crash handler must not block behind the startup send).
     */
    fun sendOne(insertId: String, endpoint: CrashEndpoint, lockTimeoutMs: Long): Outcome {
        if (!lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS)) return Outcome.KEPT
        try {
            val report = store.read(insertId) ?: return Outcome.SENT
            return send(report, endpoint)
        } finally {
            lock.unlock()
        }
    }

    private fun send(report: PendingReportStore.Report, endpoint: CrashEndpoint): Outcome {
        val outcome = try {
            transport.post(
                endpoint.trackUrl,
                stampSendingLaunch(report.event).toString(),
                mapOf(
                    TOKEN_HEADER to endpoint.token,
                ),
            )
            Outcome.SENT
        } catch (e: CohorlyHttpException) {
            if (e.status == 400 || e.status == 413) Outcome.DROPPED else Outcome.KEPT
        } catch (_: Exception) {
            Outcome.KEPT
        }
        if (outcome != Outcome.KEPT) report.file.delete()
        return outcome
    }

    // The app version is the SENDING launch's, like every event, so crash-free
    // users by release join on the same denominator (docs/NATIVE-SYMBOLICATION.md).
    private fun stampSendingLaunch(event: JSONObject): JSONObject {
        val props = event.optJSONObject("properties") ?: return event
        props.remove("\$app_version_string")
        app.versionName?.let { props.put("\$app_version_string", it) }
        props.put("\$app_build_number", app.versionCode.toString())
        return event
    }

    companion object {
        const val TOKEN_HEADER = "X-Cohorly-Token"
    }
}
