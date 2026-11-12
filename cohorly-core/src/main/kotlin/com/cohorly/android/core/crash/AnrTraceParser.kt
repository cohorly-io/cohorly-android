package com.cohorly.android.core.crash

import org.json.JSONArray
import org.json.JSONObject

/** The ART thread dump ("traces.txt" text) handed back for `REASON_ANR`. */
object AnrTraceParser {
    class Thread(val name: String, val id: Long, val frames: JSONArray)

    private val HEADER = Regex("^\"(.*?)\"(.*)$")
    private val TID = Regex("\\btid=(\\d+)\\b")
    private val SYS_TID = Regex("\\bsysTid=(\\d+)\\b")
    private val JAVA = Regex("^\\s+at (.+)\\.([^.(]+)\\((.*)\\)$")
    private val NATIVE = Regex(
        "^\\s+native: #\\d+ pc ([0-9a-f]+)\\s+(\\S+)(?: \\((?!BuildId: )(.+?)\\))?(?: \\(BuildId: ([0-9a-f]+)\\))?",
    )

    fun parse(text: String, packageName: String): List<Thread> {
        val out = mutableListOf<Thread>()
        var name: String? = null
        var id: Long? = null
        val java = mutableListOf<JSONObject>()
        val native = mutableListOf<JSONObject>()

        fun flush() {
            val n = name
            if (n != null) {
                // ART prints a thread's managed and native stacks separately, so
                // they cannot be interleaved: a thread with Java frames sends only those.
                val frames = (if (java.isNotEmpty()) java else native).reversed()
                out.add(Thread(n, id ?: 0L, Frames.capped(frames)))
            }
            name = null
            id = null
            java.clear()
            native.clear()
        }

        for (line in text.lineSequence()) {
            val header = HEADER.matchEntire(line)
            if (header != null) {
                flush()
                name = header.groupValues[1]
                // Threads "(not attached)" to the runtime have no tid; their
                // sysTid follows on the next line.
                id = TID.find(header.groupValues[2])?.groupValues?.get(1)?.toLongOrNull()
                continue
            }
            if (name == null) continue
            if (line.isBlank()) {
                flush()
                continue
            }
            if (id == null) {
                SYS_TID.find(line)?.let { id = it.groupValues[1].toLongOrNull() }
            }
            val javaMatch = JAVA.matchEntire(line)
            if (javaMatch != null) {
                java.add(javaFrame(javaMatch))
                continue
            }
            val nativeMatch = NATIVE.find(line)
            if (nativeMatch != null) native.add(nativeFrame(nativeMatch, packageName))
        }
        flush()
        return out
    }

    private fun javaFrame(m: MatchResult): JSONObject {
        val location = m.groupValues[3]
        var fileName: String? = null
        var lineNumber: Int? = null
        if (location.contains(':')) {
            fileName = location.substringBefore(':')
            lineNumber = location.substringAfter(':').toIntOrNull()
        } else if (location.isNotEmpty() && location != "Native method") {
            fileName = location
        }
        return Frames.javaFrame(m.groupValues[1], m.groupValues[2], fileName, lineNumber)
    }

    private fun nativeFrame(m: MatchResult, packageName: String): JSONObject {
        // "(__pthread_start +232)" and "(art::DumpNativeStack+112)": drop the offset.
        val function = m.groupValues[3].takeIf { it.isNotEmpty() }?.substringBeforeLast('+')?.trim()
        return Frames.nativeFrame(
            path = m.groupValues[2],
            relPc = m.groupValues[1].toLong(16),
            function = function,
            buildId = m.groupValues[4].takeIf { it.isNotEmpty() },
            packageName = packageName,
        )
    }
}
