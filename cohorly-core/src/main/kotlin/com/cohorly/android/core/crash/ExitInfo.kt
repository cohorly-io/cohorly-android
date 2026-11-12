package com.cohorly.android.core.crash

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One `ApplicationExitInfo`, copied out of the Android type so the logic runs
 * on the JVM. [readTrace] is only called for records that become events
 * (reading a trace costs 50 ms to seconds).
 */
class ExitRecord(
    val pid: Int,
    val timestamp: Long,
    val reason: Int,
    val importance: Int,
    val description: String?,
    val processName: String?,
    val rssKb: Long,
    val pssKb: Long,
    val processStateSummary: ByteArray?,
    private val readTrace: () -> ByteArray? = { null },
) {
    val traceBytes: ByteArray? by lazy {
        try {
            readTrace()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // Values of android.app.ApplicationExitInfo / RunningAppProcessInfo.
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        const val IMPORTANCE_FOREGROUND = 100
    }
}

/**
 * Turns new exit records into pending `$exception` reports, once each.
 *
 * Dedup is a timestamp watermark (`exit_watermark`): only records newer than
 * it are read, and it advances only after their reports are on disk. The
 * first run with reporting on (no watermark yet) only sets it, so exits from
 * before the SDK was on are never sent. Each event's `$insert_id` is a
 * name-based UUID of `pid:timestamp:reason`, so a resend dedups on the server.
 */
class ExitInfoProcessor(
    private val store: CrashKeyValueStore,
    private val pending: PendingReportStore,
    private val app: CrashAppInfo,
) {
    /** [installDeviceId] stands in for identity when a record has no summary. Returns the reports written. */
    fun process(records: List<ExitRecord>, installDeviceId: String): Int {
        val watermark = store.getLong(CrashConfig.KEY_EXIT_WATERMARK)
        if (watermark == null) {
            store.putLong(CrashConfig.KEY_EXIT_WATERMARK, records.maxOfOrNull { it.timestamp } ?: 0L)
            return 0
        }
        var max: Long = watermark
        var written = 0
        for (record in records.filter { it.timestamp > watermark }.sortedBy { it.timestamp }) {
            max = maxOf(max, record.timestamp)
            val event = toEvent(record, installDeviceId) ?: continue
            pending.write(insertId(record), event)
            written++
        }
        if (max > watermark) store.putLong(CrashConfig.KEY_EXIT_WATERMARK, max)
        return written
    }

    fun toEvent(record: ExitRecord, installDeviceId: String): JSONObject? = when (record.reason) {
        ExitRecord.REASON_ANR -> anr(record, installDeviceId)
        ExitRecord.REASON_CRASH_NATIVE -> native(record, installDeviceId)
        // A background kill is the OS reclaiming memory, not an app defect.
        ExitRecord.REASON_LOW_MEMORY ->
            if (record.importance == ExitRecord.IMPORTANCE_FOREGROUND) {
                resource(record, installDeviceId, "LowMemoryKill", "killed by lmkd")
            } else {
                null
            }
        ExitRecord.REASON_EXCESSIVE_RESOURCE_USAGE ->
            resource(record, installDeviceId, "ExcessiveResourceUsage", "killed for excessive resource usage")
        // REASON_CRASH belongs to the uncaught handler; the rest is not an error.
        else -> null
    }

    private fun base(record: ExitRecord, installDeviceId: String, level: String): JSONObject {
        val props = CrashEvents.baseProperties(
            time = record.timestamp,
            insertId = insertId(record),
            values = crashTimeValues(record.processStateSummary, installDeviceId),
            app = app,
        )
        return props.put("\$exception_level", level)
    }

    private fun anr(record: ExitRecord, installDeviceId: String): JSONObject {
        val text = record.traceBytes?.toString(Charsets.UTF_8)
        val threads = text?.let { AnrTraceParser.parse(it, app.packageName) }.orEmpty()
        val main = threads.firstOrNull { it.name == "main" }
        val props = base(record, installDeviceId, "fatal")
        val entry = JSONObject().apply {
            put("type", "ANR")
            put("value", record.description?.takeIf { it.isNotBlank() } ?: "Application Not Responding")
            put("mechanism", Frames.mechanism("anr"))
            if (main != null) put("stacktrace", Frames.rawStacktrace(main.frames))
        }
        props.put("\$exception_list", JSONArray().put(entry))
        props.put(
            "\$exception_threads",
            Frames.threads(threads.filter { it !== main }.map { Frames.thread(it.id, it.name, it.frames) }),
        )
        return CrashEvents.event(props)
    }

    private fun native(record: ExitRecord, installDeviceId: String): JSONObject {
        val props = base(record, installDeviceId, "fatal")
        val tombstone = record.traceBytes?.let {
            try {
                TombstoneParser.parse(it)
            } catch (_: Exception) {
                null
            }
        }
        val entry = JSONObject().put("mechanism", Frames.mechanism("exit_info"))
        if (tombstone == null) {
            entry.put("type", "NativeCrash")
            entry.put("value", record.description?.takeIf { it.isNotBlank() } ?: "native crash")
        } else {
            entry.put("type", tombstone.signalName ?: "NativeCrash")
            val value = buildString {
                append(tombstone.codeName.orEmpty())
                tombstone.faultAddress?.let { append(" at 0x%x".format(it)) }
                if (!tombstone.abortMessage.isNullOrEmpty()) append(": ").append(tombstone.abortMessage)
            }.trim()
            entry.put("value", value.ifEmpty { record.description ?: "native crash" })
            tombstone.threads.firstOrNull { it.id == tombstone.tid }?.let {
                entry.put("stacktrace", Frames.rawStacktrace(nativeFrames(it)))
            }
            props.put(
                "\$exception_threads",
                Frames.threads(
                    tombstone.threads
                        .filter { it.id != tombstone.tid }
                        .map { Frames.thread(it.id, it.name, nativeFrames(it)) },
                ),
            )
        }
        props.put("\$exception_list", JSONArray().put(entry))
        return CrashEvents.event(props)
    }

    // Tombstone frames are innermost first.
    private fun nativeFrames(thread: TombstoneParser.Thread): JSONArray = Frames.capped(
        thread.frames.reversed().map {
            Frames.nativeFrame(it.fileName, it.relPc, it.functionName, it.buildId, app.packageName)
        },
    )

    private fun resource(record: ExitRecord, installDeviceId: String, type: String, fallback: String): JSONObject {
        // A kill by the OS, not a defect: it must not count as a crash.
        val props = base(record, installDeviceId, "warning")
        val reason = record.description?.takeIf { it.isNotBlank() } ?: fallback
        val value = "$reason (importance=${record.importance}, pss=${record.pssKb}kB, rss=${record.rssKb}kB)"
        props.put(
            "\$exception_list",
            JSONArray().put(
                JSONObject().put("type", type).put("value", value).put("mechanism", Frames.mechanism("exit_info")),
            ),
        )
        return CrashEvents.event(props)
    }

    companion object {
        fun insertId(record: ExitRecord): String =
            UUID.nameUUIDFromBytes("${record.pid}:${record.timestamp}:${record.reason}".toByteArray()).toString()

        /**
         * From the record's own summary. Without one (the process died before
         * it was set) the release is [CrashTimeValues.UNKNOWN_RELEASE] and the
         * install's device id stands in for identity: never the sending
         * launch's release or user.
         */
        fun crashTimeValues(summary: ByteArray?, installDeviceId: String): CrashTimeValues {
            val decoded = ProcessStateSummary.decode(summary)
                ?: return CrashTimeValues(CrashTimeValues.UNKNOWN_RELEASE, null, installDeviceId, installDeviceId)
            val deviceId = decoded.deviceId ?: installDeviceId
            return CrashTimeValues(
                release = decoded.release ?: CrashTimeValues.UNKNOWN_RELEASE,
                dist = decoded.dist,
                distinctId = decoded.distinctId ?: deviceId,
                deviceId = deviceId,
            )
        }
    }
}
