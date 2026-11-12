package com.cohorly.android.core.crash

import org.json.JSONObject

/**
 * Crash reporting configuration, persisted as JSON under `config` in the
 * `cohorly-crash/state.json` key-value file of `noBackupFilesDir`:
 *
 * ```json
 * { "enabled": true, "release": "...", "dist": "...", "distinctId": "...", "deviceId": "..." }
 * ```
 *
 * That shape is a contract with the React Native JS layer. [token],
 * [apiHost] and [anrWatchdog] are native-only extras written by the native
 * `Cohorly` SDK; they are never required. Anything else in the JSON is ignored.
 */
data class CrashConfig(
    val enabled: Boolean,
    val release: String? = null,
    val dist: String? = null,
    val distinctId: String? = null,
    val deviceId: String? = null,
    val token: String? = null,
    val apiHost: String? = null,
    val anrWatchdog: Boolean = false,
    val build: Long? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        release?.let { put("release", it) }
        dist?.let { put("dist", it) }
        distinctId?.let { put("distinctId", it) }
        deviceId?.let { put("deviceId", it) }
        token?.let { put(KEY_TOKEN, it) }
        apiHost?.let { put(KEY_API_HOST, it) }
        if (anrWatchdog) put(KEY_ANR_WATCHDOG, true)
        build?.let { put(KEY_BUILD, it) }
    }.toString()

    /**
     * The config as it applies to the build running now. [release] and
     * [dist] name the build that wrote them: after an app update, until the
     * new launch writes its own, they would file a startup crash under the
     * previous build (and its R8 mapping), so the running build's default
     * release wins and the old OTA dist is dropped.
     */
    fun forBuild(versionCode: Long): CrashConfig =
        if (build != null && build != versionCode) copy(release = null, dist = null) else this

    companion object {
        const val STATE_FILE = "state.json"
        const val KEY_CONFIG = "config"
        const val KEY_EXIT_WATERMARK = "exit_watermark"
        const val KEY_FALLBACK_DEVICE_ID = "fallback_device_id"

        private const val KEY_TOKEN = "token"
        private const val KEY_API_HOST = "apiHost"
        private const val KEY_ANR_WATCHDOG = "anrWatchdog"

        // The versionCode of the launch that wrote the config. Stamped on
        // every write, never carried over.
        const val KEY_BUILD = "build"
        private val NATIVE_ONLY_KEYS = listOf(KEY_TOKEN, KEY_API_HOST, KEY_ANR_WATCHDOG)

        /** Null for absent or unparseable JSON. Only a JSON `true` enables. */
        fun parse(json: String?): CrashConfig? {
            if (json.isNullOrBlank()) return null
            val obj = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            return CrashConfig(
                enabled = obj.opt("enabled") == true,
                release = obj.stringOrNull("release"),
                dist = obj.stringOrNull("dist"),
                distinctId = obj.stringOrNull("distinctId"),
                deviceId = obj.stringOrNull("deviceId"),
                token = obj.stringOrNull(KEY_TOKEN),
                apiHost = obj.stringOrNull(KEY_API_HOST),
                anrWatchdog = obj.opt(KEY_ANR_WATCHDOG) == true,
                build = (obj.opt(KEY_BUILD) as? Number)?.toLong(),
            )
        }

        fun read(store: CrashKeyValueStore): CrashConfig? = parse(store.getString(KEY_CONFIG))

        /** The persisted config when it turns crash reporting on, else null. */
        fun readEnabled(store: CrashKeyValueStore): CrashConfig? = read(store)?.takeIf { it.enabled }

        /**
         * The JSON to persist when [incoming] replaces [stored]. Incoming wins
         * field by field, except that the native-only keys (`token`,
         * `apiHost`, `anrWatchdog`) are carried over when incoming lacks them, so the JS layer writing its
         * contract shape never erases what the native SDK wrote. Invalid
         * incoming JSON is stored as is: reading it back means "off".
         */
        fun merge(stored: String?, incoming: String): String {
            val next = try {
                JSONObject(incoming)
            } catch (_: Exception) {
                return incoming
            }
            val previous = try {
                stored?.let { JSONObject(it) }
            } catch (_: Exception) {
                null
            } ?: return next.toString()
            for (key in NATIVE_ONLY_KEYS) {
                if (!next.has(key) && previous.has(key)) next.put(key, previous.get(key))
            }
            return next.toString()
        }

        private fun JSONObject.stringOrNull(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Where reports go. Manifest meta-data wins field by field over the persisted
 * config; without a token or a host nothing is sent (reports stay pending).
 */
data class CrashEndpoint(val token: String, val apiHost: String) {
    val trackUrl: String get() = "$apiHost/track"

    companion object {
        fun resolve(metaToken: String?, metaApiHost: String?, config: CrashConfig?): CrashEndpoint? {
            val token = metaToken?.takeIf { it.isNotBlank() } ?: config?.token ?: return null
            val host = (metaApiHost?.takeIf { it.isNotBlank() } ?: config?.apiHost)?.trimEnd('/') ?: return null
            if (host.isEmpty()) return null
            return CrashEndpoint(token, host)
        }
    }
}

/** Synchronous key-value persistence ([FileCrashKeyValueStore] on Android). */
interface CrashKeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getLong(key: String): Long?

    fun putLong(key: String, value: Long)
}

/**
 * The installed app and device, read once per launch. `versionName` /
 * `versionCode` are the CURRENT launch's, so they feed `$app_version_*` and
 * the default release of a process that is still the one running.
 */
data class CrashAppInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val osVersion: String?,
    val model: String?,
    val manufacturer: String?,
    val libVersion: String,
) {
    /** `<applicationId>@<versionName>+<versionCode>`, the key the CLI's gradle plugin uploads under. */
    val defaultRelease: String?
        get() = versionName?.let { "$packageName@$it+$versionCode" }
}
