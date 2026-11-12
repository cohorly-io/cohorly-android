package com.cohorly.android.core.crash

import org.json.JSONArray
import org.json.JSONObject

/**
 * Release and identity as they were when the crash happened. They are written
 * into the report and never re-read at send time (docs/NATIVE-SYMBOLICATION.md,
 * "Values captured at crash time").
 */
data class CrashTimeValues(val release: String, val dist: String?, val distinctId: String, val deviceId: String) {
    /** `$user_id` exists only for an identified user, i.e. when the ids differ. */
    val userId: String? get() = distinctId.takeIf { it != deviceId }

    companion object {
        const val UNKNOWN_RELEASE = "android@unknown"
    }
}

object CrashEvents {
    const val EVENT = "\$exception"

    fun baseProperties(time: Long, insertId: String, values: CrashTimeValues, app: CrashAppInfo): JSONObject =
        JSONObject().apply {
            put("distinct_id", values.distinctId)
            put("time", time)
            put("\$insert_id", insertId)
            put("\$lib", "android")
            put("\$lib_version", app.libVersion)
            put("\$device_id", values.deviceId)
            values.userId?.let { put("\$user_id", it) }
            put("\$os", "Android")
            app.osVersion?.let { put("\$os_version", it) }
            app.model?.let { put("\$model", it) }
            app.manufacturer?.let { put("\$manufacturer", it) }
            put("\$exception_release", values.release)
            values.dist?.let { put("\$exception_dist", it) }
        }

    fun event(properties: JSONObject): JSONObject = JSONObject().put("event", EVENT).put("properties", properties)

    /**
     * `$exception_list` for a Java throwable: the thrown exception first, then
     * its causes, each with `mechanism.type = uncaught_exception`.
     */
    fun javaExceptionList(error: Throwable, mechanism: String = "uncaught_exception"): JSONArray {
        val out = JSONArray()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && out.length() < MAX_CAUSES) {
            out.put(
                JSONObject().apply {
                    put("type", current.javaClass.name)
                    put("value", current.message ?: "")
                    put("mechanism", Frames.mechanism(mechanism))
                    put("stacktrace", Frames.rawStacktrace(Frames.javaFrames(current.stackTrace)))
                },
            )
            current = current.cause
        }
        return out
    }

    /** Every live thread but [except], in id order, capped. */
    fun javaThreads(all: Map<Thread, Array<StackTraceElement>>, except: Thread?): JSONArray = Frames.threads(
        all.entries
            .filter { it.key !== except }
            .sortedBy { it.key.id }
            .map { (thread, trace) -> Frames.thread(thread.id, thread.name, Frames.javaFrames(trace)) },
    )

    private const val MAX_CAUSES = 10
}
