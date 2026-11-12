@file:Suppress("ktlint:standard:enum-entry-name-case")

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
 * How flag reads resolve values against the persisted cache, mirroring
 * Mixpanel's `variantLookupPolicy` ("Flag Persistence" in the feature-flags
 * docs). The three modes and their names are shared verbatim across all
 * Cohorly client SDKs (sdk-core, sdk-web, sdk-react-native, iOS) - the
 * entries stay camelCase on purpose so the Kotlin API reads identically to
 * the other SDKs' config, at the cost of an unconventional enum style.
 */
enum class FlagPersistenceMode {
    /**
     * Never reads nor writes the persisted cache: flags only ever come from
     * a `/flags/evaluate` response received this session. A failed
     * evaluation leaves nothing to serve, so reads resolve to the fallback.
     */
    networkOnly,

    /**
     * Prefers the network; a valid persisted cache (inside the TTL, same
     * distinct id) is served whenever an evaluation fails or none has
     * succeeded yet, and the cache is still written on every success.
     * Strict about freshness: a cache past the TTL is dropped, never served
     * (unlike `persistenceUntilNetworkSuccess`, which tolerates staleness).
     * Because this API's getters are synchronous - they cannot await a
     * response the way Mixpanel's async lookups do - a valid cache is
     * adopted immediately, like `persistenceUntilNetworkSuccess`.
     */
    networkFirst,

    /**
     * Serves the persisted cache immediately so startup doesn't flicker;
     * the first successful evaluation takes over and re-persists. A failed
     * evaluation keeps the cached values serving. This is the default: it
     * preserves the pre-policy behavior of always restoring the cache.
     * Trades freshness for offline availability: a cache past the TTL still
     * serves while inside `FLAG_CACHE_MAX_STALE_MS` (7 days), and a legacy
     * cache without `stored_at` serves indefinitely.
     */
    persistenceUntilNetworkSuccess,
}

/**
 * Where a served flag value came from. Lowercase entries double as the wire
 * strings shared with the other SDKs (reported as `$feature_flag_source` on
 * `$feature_flag_called`).
 */
enum class FlagSource {
    /** From a `/flags/evaluate` response received this session. */
    network,

    /**
     * From the persisted cache (same distinct id, usable under the active
     * [FlagPersistenceMode]).
     */
    persistence,

    /** The flag is unknown or unloaded, so the caller's fallback was served. */
    fallback,
}

/**
 * The full result of a flag lookup: the value plus its provenance, so a
 * caller can tell a fresh assignment apart from a persisted or fallback one.
 */
data class FlagDetails(
    /** The flag key this result describes. */
    val key: String,
    /**
     * The flag's variant when it has one, else its enabled Boolean; the
     * caller's fallback when the flag is unknown or unloaded.
     */
    val value: Any,
    /**
     * The flag's enabled boolean. When the flag is unknown this is the
     * truthiness of the fallback (`true`, or a non-empty String), matching
     * what `isFeatureEnabled(key, fallback)` would return - the sibling
     * SDKs' `Boolean(fallback)`.
     */
    val enabled: Boolean,
    /**
     * The JSON payload attached to the flag, or null when it has none or
     * the flag is unknown.
     */
    val payload: Any?,
    val source: FlagSource,
    /**
     * Epoch ms when the served flag set was written to the cache; non-null
     * only when [source] is [FlagSource.persistence] AND the cache carries
     * `stored_at` (a legacy cache has no provable age, so this stays null).
     */
    val persistedAt: Long? = null,
    /**
     * The configured cache TTL in ms; non-null only when [source] is
     * [FlagSource.persistence].
     */
    val ttlMs: Long? = null,
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
