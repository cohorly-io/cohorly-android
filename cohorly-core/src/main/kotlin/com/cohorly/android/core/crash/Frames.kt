package com.cohorly.android.core.crash

import org.json.JSONArray
import org.json.JSONObject

/** SDK-side payload caps (docs/NATIVE-SYMBOLICATION.md, "Size caps"). */
object CrashLimits {
    const val MAX_THREADS = 20
    const val MAX_FRAMES = 100
}

/**
 * Frames on the wire (docs/NATIVE-SYMBOLICATION.md section 1): oldest call
 * first, crashing frame last. Every stack keeps its LAST [CrashLimits.MAX_FRAMES]
 * frames - the crashing end - and drops the oldest calls.
 */
object Frames {
    // A negative list on purpose: R8 renames app classes to `a.b.c`, so no
    // package prefix can find them.
    private val SYSTEM_PREFIXES = listOf(
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "android.",
        "androidx.",
        "com.android.",
        "dalvik.",
        "libcore.",
        "sun.",
        "com.facebook.react.",
        "com.google.android.",
    )

    fun javaInApp(className: String): Boolean = SYSTEM_PREFIXES.none { className.startsWith(it) }

    fun javaFrame(className: String, method: String, fileName: String?, lineNumber: Int?): JSONObject =
        JSONObject().apply {
            put("platform", "java")
            put("module", className)
            put("function", method)
            fileName?.let { put("filename", it) }
            if (lineNumber != null && lineNumber >= 0) put("lineno", lineNumber)
            put("in_app", javaInApp(className))
        }

    fun javaFrame(element: StackTraceElement): JSONObject =
        javaFrame(element.className, element.methodName, element.fileName, element.lineNumber)

    /** [trace] is in `Throwable.stackTrace` order (crashing frame first). */
    fun javaFrames(trace: Array<StackTraceElement>): JSONArray =
        capped(trace.take(CrashLimits.MAX_FRAMES).reversed().map(::javaFrame))

    /** [oldestFirst] is already in wire order; keeps its last frames. */
    fun capped(oldestFirst: List<JSONObject>): JSONArray =
        JSONArray().apply { oldestFirst.takeLast(CrashLimits.MAX_FRAMES).forEach { put(it) } }

    fun nativeInApp(path: String, packageName: String): Boolean =
        path.startsWith("/data/app/") && path.contains("/$packageName-")

    /** "/data/app/.../base.apk!libfoo.so" -> "libfoo.so"; "/apex/.../libc.so" -> "libc.so". */
    fun nativeModule(path: String): String = path.substringAfterLast('!').substringAfterLast('/').substringBefore(' ')

    fun hex16(value: Long): String = "0x%016x".format(value)

    fun nativeFrame(path: String, relPc: Long, function: String?, buildId: String?, packageName: String): JSONObject {
        val inApp = nativeInApp(path, packageName)
        return JSONObject().apply {
            put("platform", "native")
            put("module", nativeModule(path))
            // A stripped in-app binary only yields the nearest exported symbol,
            // which is wrong and would poison the fingerprint; the worker fills it.
            if (!inApp && !function.isNullOrEmpty()) put("function", function)
            put("instruction_addr", hex16(relPc))
            put("image_addr", hex16(0))
            if (!buildId.isNullOrEmpty()) put("debug_id", buildId.lowercase())
            put("in_app", inApp)
        }
    }

    fun rawStacktrace(frames: JSONArray): JSONObject = JSONObject().put("type", "raw").put("frames", frames)

    fun thread(id: Long, name: String?, frames: JSONArray): JSONObject = JSONObject().apply {
        put("id", id)
        name?.let { put("name", it) }
        put("stacktrace", rawStacktrace(frames))
    }

    fun threads(entries: List<JSONObject>): JSONArray =
        JSONArray().apply { entries.take(CrashLimits.MAX_THREADS).forEach { put(it) } }

    fun mechanism(type: String): JSONObject =
        JSONObject().put("handled", false).put("type", type).put("synthetic", false)
}
