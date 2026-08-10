package com.cohorly.android.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One evaluated feature flag, matching the `/flags/evaluate` response shape:
 * `{ "flags": { "<key>": { enabled, variant, payload, reason } } }`.
 *
 * [payload] is a raw org.json value (JSONObject / JSONArray / String / Number /
 * Boolean), or null when the flag carries none.
 */
data class FlagResult(
    val enabled: Boolean,
    /** Variant key for a multivariate flag; null for a plain on/off flag. */
    val variant: String? = null,
    val payload: Any? = null,
    /** Why the server decided this way (e.g. "cohort", "rollout", "override"). */
    val reason: String = "",
)

/**
 * Fetcher used for feature-flag evaluation: POSTs [body] to [url] and returns
 * the raw response body.
 *
 * It MUST return null on any failure (network, non-2xx, bad body) and never
 * throw: a failed evaluation keeps the previously cached flags rather than
 * surfacing an error to the app.
 */
interface CohorlyFlagsFetcher {
    fun fetch(url: String, body: String): String?
}

/** Default fetcher using HttpURLConnection (works on Android and plain JVM). */
class HttpUrlConnectionFlagsFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : CohorlyFlagsFetcher {
    override fun fetch(url: String, body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status < 200 || status >= 300) {
                null
            } else {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (_: Exception) {
            // Never throws: the caller keeps its stale cache.
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** Parses a `/flags/evaluate` response body; null when it is not usable. */
internal fun parseFlagsResponse(raw: String?): Map<String, FlagResult>? {
    if (raw == null) return null
    return try {
        val flags = JSONObject(raw).optJSONObject("flags") ?: return null
        val parsed = LinkedHashMap<String, FlagResult>()
        for (key in flags.keys()) {
            val flag = flags.optJSONObject(key) ?: continue
            parsed[key] = FlagResult(
                enabled = flag.optBoolean("enabled", false),
                variant = if (flag.isNull("variant")) null else flag.optString("variant", ""),
                payload = if (flag.isNull("payload")) null else flag.opt("payload"),
                reason = if (flag.isNull("reason")) "" else flag.optString("reason", ""),
            )
        }
        parsed
    } catch (_: Exception) {
        null
    }
}
