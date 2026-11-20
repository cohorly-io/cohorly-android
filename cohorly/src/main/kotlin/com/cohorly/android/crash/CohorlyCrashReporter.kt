package com.cohorly.android.crash

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.cohorly.android.AndroidDeviceInfo
import com.cohorly.android.core.HttpUrlConnectionTransport
import com.cohorly.android.core.SystemClock
import com.cohorly.android.core.crash.AnrWatchdog
import com.cohorly.android.core.crash.CrashAppInfo
import com.cohorly.android.core.crash.CrashConfig
import com.cohorly.android.core.crash.CrashKeyValueStore
import com.cohorly.android.core.crash.CrashListener
import com.cohorly.android.core.crash.CrashReporter
import com.cohorly.android.core.crash.ExitRecord
import com.cohorly.android.core.crash.FileCrashKeyValueStore
import com.cohorly.android.core.crash.PendingReportStore
import com.cohorly.android.core.replay.CohorlyReplayInternal
import java.io.File
import kotlin.system.exitProcess

/**
 * Native crash reporting entry point (ADR-0020). The reporter itself starts
 * from [CohorlyCrashProvider] on every launch whose persisted config enables
 * it; this object is how the app (or the React Native layer) writes that
 * config.
 */
object CohorlyCrashReporter {
    const val META_PROJECT_TOKEN = "com.cohorly.PROJECT_TOKEN"
    const val META_API_HOST = "com.cohorly.API_HOST"
    const val META_ANR_WATCHDOG = "com.cohorly.ANR_WATCHDOG"

    @Volatile
    private var reporter: CrashReporter? = null

    @Volatile
    private var sessionIdProvider: (() -> String?)? = null

    /**
     * Where the running reporter reads `$session_id` at capture time (the
     * native `Cohorly` points it at its replay session). Applies to a
     * reporter started later in this process too.
     */
    internal fun setSessionIdProvider(provider: (() -> String?)?) {
        sessionIdProvider = provider
        reporter?.sessionIdProvider = provider
    }

    /** Listeners added before the reporter started; each is also on the running reporter once it exists. */
    private val listeners = LinkedHashMap<CrashListener, () -> Unit>()

    /**
     * True when the crash reporter runs in this process: reporting was on
     * in the persisted config when this launch started (a launch after the
     * one that turned `nativeCrashReporting` on) and is still on. Session
     * replay's error mode records only then, since only a written crash
     * report sends its buffer (VEL-2151).
     */
    @CohorlyReplayInternal
    val isRunning: Boolean
        get() = reporter?.isRecording == true

    /** The `$session_id` of the last crash report this process wrote (null: none, or no reporter). */
    @CohorlyReplayInternal
    val lastReportSessionId: String?
        get() = reporter?.lastReportSessionId

    /**
     * Calls [listener] after each crash report this process writes (see
     * [CrashListener]), on the reporter already running or on the one a
     * later start creates. Returns the function that removes it. Any thread.
     */
    @CohorlyReplayInternal
    fun addListener(listener: CrashListener): () -> Unit {
        synchronized(listeners) {
            listeners[listener] = reporter?.addListener(listener) ?: {}
        }
        return {
            synchronized(listeners) { listeners.remove(listener) }?.invoke()
        }
    }

    /**
     * Persists the crash config JSON
     * (`{ "enabled", "release"?, "dist"?, "distinctId"?, "deviceId"? }`).
     * It never installs the handler in the running process - that happens on
     * the next launch - but a handler already installed reads the new values
     * at once (a new distinctId after identify; `enabled: false` makes it only
     * chain). Also refreshes the process state summary. Never throws.
     */
    @JvmStatic
    fun configure(context: Context, json: String) {
        try {
            val app = context.applicationContext ?: context
            val store = crashStore(app)
            val info = appInfo(app)
            val config = CrashReporter.writeConfig(store, json, build = info.versionCode)
            val running = reporter
            running?.updateConfig(config)
            val summary = running?.processStateSummary()
                ?: CrashReporter.summaryFor(config ?: CrashConfig(enabled = false), info) {
                    CrashReporter.fallbackDeviceId(store)
                }
            setProcessStateSummary(app, summary)
        } catch (_: Throwable) {
            // Crash reporting must never break the app.
        }
    }

    internal fun start(context: Context) {
        val app = context.applicationContext ?: context
        val store = crashStore(app)
        // Cheapest possible exit for the default (off) case: one prefs read.
        val config = CrashConfig.readEnabled(store) ?: return
        val meta = metaData(app)
        val created = CrashReporter(
            store = store,
            pending = PendingReportStore(File(app.noBackupFilesDir, "cohorly-crash/pending")),
            transport = HttpUrlConnectionTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
            app = appInfo(app),
            metaToken = meta.value(META_PROJECT_TOKEN),
            metaApiHost = meta.value(META_API_HOST),
            terminate = {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            },
        )
        val exitRecords: (() -> List<ExitRecord>)? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ({ ExitInfoReader.read(app) }) else null
        created.sessionIdProvider = sessionIdProvider
        if (!created.start(exitRecords)) return
        synchronized(listeners) {
            for (listener in listeners.keys.toList()) listeners[listener] = created.addListener(listener)
            reporter = created
        }
        setProcessStateSummary(app, created.processStateSummary())

        val foreground = ForegroundTracker(onFirstResume = { created.reinstallIfReplaced() })
        (app as? Application)?.registerActivityLifecycleCallbacks(foreground)

        val watchdogOn = meta.value(META_ANR_WATCHDOG)?.toBoolean() == true || config.anrWatchdog
        if (watchdogOn) {
            val main = Handler(Looper.getMainLooper())
            AnrWatchdog(
                clock = SystemClock(),
                postToMain = { main.post(it) },
                isForeground = { foreground.isForeground },
                onBlocked = { blockedMs ->
                    try {
                        created.recordWatchdogAnr(Looper.getMainLooper().thread, blockedMs)
                    } catch (_: Throwable) {
                        // Never let a report failure kill the watchdog's host.
                    }
                },
            ).startThread()
        }
    }

    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (_: Exception) {
        null
    }

    // A manifest value is typed by its text ("true" is a Boolean), hence get().
    @Suppress("DEPRECATION")
    private fun Bundle?.value(key: String): String? = this?.get(key)?.toString()?.takeIf { it.isNotBlank() }

    internal fun appInfo(context: Context): CrashAppInfo {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }

        @Suppress("DEPRECATION")
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        return CrashAppInfo(
            packageName = context.packageName,
            versionName = info?.versionName,
            versionCode = code,
            osVersion = Build.VERSION.RELEASE,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            libVersion = AndroidDeviceInfo.LIB_VERSION,
        )
    }

    private fun setProcessStateSummary(context: Context, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            ExitInfoReader.setSummary(context, bytes)
        } catch (_: Throwable) {
            // Missing summary only degrades exit reports to an unknown release.
        }
    }
}

// noBackupFilesDir, never SharedPreferences: see FileCrashKeyValueStore.
internal fun crashStore(context: Context): CrashKeyValueStore =
    FileCrashKeyValueStore(File(context.noBackupFilesDir, "cohorly-crash/${CrashConfig.STATE_FILE}"))

@TargetApi(Build.VERSION_CODES.R)
internal object ExitInfoReader {
    fun read(context: Context): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 0).map { info ->
            ExitRecord(
                pid = info.pid,
                timestamp = info.timestamp,
                reason = info.reason,
                importance = info.importance,
                description = info.description,
                processName = info.processName,
                rssKb = info.rss,
                pssKb = info.pss,
                processStateSummary = info.processStateSummary,
                readTrace = { info.traceInputStream?.use { it.readBytes() } },
            )
        }
    }

    fun setSummary(context: Context, bytes: ByteArray) {
        context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(bytes)
    }
}
