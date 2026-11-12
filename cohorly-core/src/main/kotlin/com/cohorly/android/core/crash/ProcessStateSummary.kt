package com.cohorly.android.core.crash

/**
 * The crash-time values carried by `ActivityManager.setProcessStateSummary`
 * (API 30+), which the OS hands back in the `ApplicationExitInfo` of the
 * process that died. It is the only way an ANR or a native crash read on a
 * later launch knows the release and the identity it happened under.
 *
 * Format, UTF-8, at most [MAX_BYTES] bytes:
 *
 * ```
 * v1|<release>|<distinctId>|<deviceId>|<dist>
 * ```
 *
 * An empty field means unknown. `%` and `|` inside a value are written as
 * `%25` and `%7C`. When the whole does not fit, the first of these that
 * does is used: everything; without dist; release + deviceId; release;
 * distinctId + deviceId; deviceId; nothing. Release and deviceId are the
 * last to go; a release too long to fit is dropped, never truncated (a
 * truncated release would symbolicate against the wrong build).
 */
object ProcessStateSummary {
    const val MAX_BYTES = 128
    private const val VERSION = "v1"

    data class Values(val release: String?, val distinctId: String?, val deviceId: String?, val dist: String?)

    fun encode(values: Values): ByteArray {
        val (release, distinctId, deviceId) = values
        val candidates = sequenceOf(
            values,
            Values(release, distinctId, deviceId, null),
            Values(release, null, deviceId, null),
            Values(release, null, null, null),
            Values(null, distinctId, deviceId, null),
            Values(null, null, deviceId, null),
        ).map(::join)
        return candidates.firstOrNull { it.size <= MAX_BYTES } ?: join(Values(null, null, null, null))
    }

    /** Null for absent, empty or foreign bytes. */
    fun decode(bytes: ByteArray?): Values? {
        if (bytes == null || bytes.isEmpty()) return null
        val parts = try {
            String(bytes, Charsets.UTF_8).split('|')
        } catch (_: Exception) {
            return null
        }
        if (parts.size != 5 || parts[0] != VERSION) return null
        fun at(i: Int) = parts[i].takeIf { it.isNotEmpty() }?.let(::unescape)
        return Values(release = at(1), distinctId = at(2), deviceId = at(3), dist = at(4))
    }

    private fun join(v: Values): ByteArray = listOf(VERSION, v.release, v.distinctId, v.deviceId, v.dist)
        .joinToString("|") { escape(it.orEmpty()) }
        .toByteArray(Charsets.UTF_8)

    private fun escape(value: String) = value.replace("%", "%25").replace("|", "%7C")

    private fun unescape(value: String) = value.replace("%7C", "|").replace("%25", "%")
}
