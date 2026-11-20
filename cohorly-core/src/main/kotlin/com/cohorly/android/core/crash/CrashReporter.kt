package com.cohorly.android.core.crash

import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.CohorlyTransport
import com.cohorly.android.core.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Where the process-wide default uncaught exception handler lives. */
interface UncaughtHandlerSlot {
    fun get(): Thread.UncaughtExceptionHandler?

    fun set(handler: Thread.UncaughtExceptionHandler)
}

object DefaultUncaughtHandlerSlot : UncaughtHandlerSlot {
    override fun get(): Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun set(handler: Thread.UncaughtExceptionHandler) = Thread.setDefaultUncaughtExceptionHandler(handler)
}

/**
 * Native crash reporting for one process (ADR-0020). The Android module only
 * adapts platform types into this class; every decision is made here.
 *
 * [start] runs once per launch from the startup provider. It does nothing
 * unless the persisted config enables reporting. When it does, it installs
 * a chained uncaught exception handler, then in the background sends every
 * pending report, only then reads exit records into new reports, and sends
 * those.
 */
class CrashReporter(
    private val store: CrashKeyValueStore,
    private val pending: PendingReportStore,
    transport: CohorlyTransport,
    private val app: CrashAppInfo,
    private val metaToken: String? = null,
    private val metaApiHost: String? = null,
    private val clock: CohorlyClock = SystemClock(),
    private val slot: UncaughtHandlerSlot = DefaultUncaughtHandlerSlot,
    private val background: (Runnable) -> Unit = { Thread(it, "cohorly-crash").apply { isDaemon = true }.start() },
    private val terminate: () -> Unit = { exitProcess(10) },
    private val threadDump: () -> Map<Thread, Array<StackTraceElement>> = { Thread.getAllStackTraces() },
    private val newInsertId: () -> String = { UUID.randomUUID().toString() },
    private val handlerSendTimeoutMs: Long = HANDLER_SEND_TIMEOUT_MS,
) {
    private val sender = PendingReportSender(pending, transport, app)
    private val exitInfo = ExitInfoProcessor(store, pending, app)

    @Volatile
    private var config: CrashConfig? = null

    @Volatile
    var handler: Handler? = null
        private set

    /**
     * The current replay `$session_id`, read in memory when an exception is
     * recorded, so a rotation reaches the next crash without waiting for the
     * config file. The native `Cohorly` sets it when it is created; while it
     * is set, it is the only source (null: no `$session_id`).
     *
     * Without it, only a session id written to the config by this process
     * ([updateConfig]) is stamped. The one [start] reads from disk was written
     * by an earlier process, whose session may have ended, so it is dropped:
     * the reporter starts from the startup provider, before the app creates
     * `Cohorly`, so a sync before the reporter is active cannot be ensured.
     */
    @Volatile
    var sessionIdProvider: (() -> String?)? = null

    private val installFallbackId: String by lazy { fallbackDeviceId(store, newInsertId) }

    /** Copy on write: the crash handler reads it without a lock. */
    @Volatile
    private var listeners: List<CrashListener> = emptyList()

    private val listenersLock = Any()

    /**
     * Calls [listener] after each report this reporter writes (see
     * [CrashListener]); never for a report it did not write (reporting off).
     * Returns the function that removes it. Any thread.
     */
    fun addListener(listener: CrashListener): () -> Unit {
        synchronized(listenersLock) { listeners = listeners + listener }
        return { synchronized(listenersLock) { listeners = listeners - listener } }
    }

    /**
     * The `$session_id` the last report written carried (null: none), set
     * before [CrashListener.onReport], so a listener acts on the session the
     * report names even if the replay session rotated since.
     */
    @Volatile
    var lastReportSessionId: String? = null
        private set

    /** True while the persisted (or updated) config enables reporting: a crash now would be written. */
    val isRecording: Boolean
        get() = isEnabled()

    /**
     * Returns false, having touched nothing, when the persisted config is
     * absent, invalid or not enabled. [exitRecords] is null below API 30.
     */
    fun start(exitRecords: (() -> List<ExitRecord>)?): Boolean {
        // The stored session id belongs to the process that wrote it (see sessionIdProvider).
        val current = CrashConfig.readEnabled(store)?.copy(sessionId = null) ?: return false
        config = current
        // Mint the fallback now rather than inside a crash handler.
        if (current.deviceId == null) installDeviceId()
        install()
        background {
            runCatching { sendPending() }
            // Re-read at every step: an opt-out while this runs stops it.
            if (exitRecords != null && isEnabled()) {
                runCatching { exitInfo.process(exitRecords(), installDeviceId()) }
                runCatching { sendPending() }
            }
        }
        return true
    }

    /**
     * Swaps the config the installed handler reads (after identify, or when
     * reporting is turned off: the handler then only chains).
     */
    fun updateConfig(next: CrashConfig?) {
        config = next
    }

    /**
     * Puts our handler back on top when something installed after us
     * replaced it, chaining to whatever is there now.
     */
    fun reinstallIfReplaced() {
        val ours = handler ?: return
        val current = slot.get()
        if (current === ours) return
        ours.previous = current
        slot.set(ours)
    }

    /** Pending reports, oldest first; nothing without a token and host. */
    fun sendPending(): Int {
        if (!isEnabled()) return 0
        val endpoint = endpoint() ?: return 0
        return sender.sendAll(endpoint, ::isEnabled)
    }

    private fun isEnabled(): Boolean = config?.enabled == true

    /** The bytes for `setProcessStateSummary`, from the live config. */
    fun processStateSummary(): ByteArray = summaryFor(config ?: CrashConfig(enabled = false), app) { installFallbackId }

    /**
     * The watchdog saw the main thread blocked for [blockedMs] while in the
     * foreground: write a report from [mainThread]'s stack and send it in the
     * background. The process lives on, so this is level `error`.
     */
    fun recordWatchdogAnr(mainThread: Thread, blockedMs: Long) {
        val current = config?.takeIf { it.enabled } ?: return
        val insertId = newInsertId()
        val props = CrashEvents.baseProperties(clock.nowMillis(), insertId, crashTimeValues(current), app)
        val entry = JSONObject().apply {
            put("type", "ANR")
            put("value", "Main thread blocked for at least $blockedMs ms (watchdog)")
            put("mechanism", Frames.mechanism("anr"))
            put("stacktrace", Frames.rawStacktrace(Frames.javaFrames(mainThread.stackTrace)))
        }
        props.put("\$exception_list", JSONArray().put(entry))
        props.put("\$exception_level", "error")
        val sessionId = sessionIdFor(current)
        sessionId?.let { props.put("\$session_id", it) }
        pending.write(insertId, CrashEvents.event(props))
        lastReportSessionId = sessionId
        notifyListeners(fatal = false)
        background { runCatching { sendPending() } }
    }

    /** Each listener in turn; one that throws never stops the others nor the caller. */
    private fun notifyListeners(fatal: Boolean) {
        for (listener in listeners) {
            try {
                listener.onReport(fatal)
            } catch (_: Throwable) {
                // A listener must never cost the report.
            }
        }
    }

    /** The provider when set (even when it answers null), else the session id this process configured. */
    private fun sessionIdFor(current: CrashConfig): String? {
        val provider = sessionIdProvider ?: return current.sessionId
        return try {
            provider()
        } catch (_: Throwable) {
            null
        }
    }

    private fun install() {
        val ours = Handler(slot.get())
        handler = ours
        slot.set(ours)
    }

    private fun endpoint(): CrashEndpoint? = CrashEndpoint.resolve(metaToken, metaApiHost, config)

    private fun installDeviceId(): String = config?.deviceId ?: installFallbackId

    private fun crashTimeValues(stored: CrashConfig): CrashTimeValues {
        val current = stored.forBuild(app.versionCode)
        val deviceId = current.deviceId ?: installFallbackId
        return CrashTimeValues(
            // The handler runs in the build that crashed, so its own release is right.
            release = current.release ?: app.defaultRelease ?: CrashTimeValues.UNKNOWN_RELEASE,
            dist = current.dist,
            distinctId = current.distinctId ?: deviceId,
            deviceId = deviceId,
        )
    }

    private fun recordUncaught(thread: Thread, error: Throwable) {
        val current = config?.takeIf { it.enabled } ?: return
        val insertId = newInsertId()
        val props = CrashEvents.baseProperties(clock.nowMillis(), insertId, crashTimeValues(current), app)
        props.put("\$exception_list", CrashEvents.javaExceptionList(error))
        props.put("\$exception_level", "fatal")
        props.put("\$exception_threads", CrashEvents.javaThreads(threadDump(), thread))
        val sessionId = sessionIdFor(current)
        sessionId?.let { props.put("\$session_id", it) }
        pending.write(insertId, CrashEvents.event(props))
        lastReportSessionId = sessionId
        // Before the bounded send: the replay error buffer is written now and
        // sent by the next launch, under the same `$session_id` as this report.
        notifyListeners(fatal = true)

        // Sending only from the next launch delivered 0 of 8 crashes in a
        // crash loop (ADR-0020); a bounded try from here delivered 8 of 8.
        // Network is forbidden on the main thread, hence the helper thread.
        val endpoint = endpoint() ?: return
        val helper = Thread({
            runCatching { sender.sendOne(insertId, endpoint, handlerSendTimeoutMs) }
        }, "cohorly-crash-send")
        helper.isDaemon = true
        helper.start()
        helper.join(handlerSendTimeoutMs)
    }

    /**
     * Records the crash, then ALWAYS hands it to the previous handler (or
     * kills the process like the platform default would). Reentry - the same
     * crash coming back through a handler that chains to us - records
     * nothing and goes to the handler we first replaced, which breaks the
     * loop a reinstall on top of a chaining handler would create.
     */
    inner class Handler internal constructor(private val original: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        @Volatile
        internal var previous: Thread.UncaughtExceptionHandler? = original

        private val handling = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, error: Throwable) {
            val first = handling.compareAndSet(false, true)
            try {
                if (first) recordUncaught(thread, error)
            } catch (_: Throwable) {
                // A failure of ours must never stop the crash from reaching the app's handler.
            } finally {
                val next = if (first) previous else original
                if (next != null) next.uncaughtException(thread, error) else terminate()
            }
        }
    }

    companion object {
        const val HANDLER_SEND_TIMEOUT_MS = 2_000L

        /**
         * Persists [json] through [CrashConfig.merge] and returns what was
         * stored, parsed (null when it is not valid JSON).
         */
        fun writeConfig(store: CrashKeyValueStore, json: String, build: Long? = null): CrashConfig? {
            var merged = CrashConfig.merge(store.getString(CrashConfig.KEY_CONFIG), json)
            if (build != null) {
                merged = try {
                    JSONObject(merged).put(CrashConfig.KEY_BUILD, build).toString()
                } catch (_: Exception) {
                    merged
                }
            }
            store.putString(CrashConfig.KEY_CONFIG, merged)
            return CrashConfig.parse(merged)
        }

        /**
         * The device id used when the config carries none (a native app that
         * never ran `Cohorly.getInstance`), minted once per install.
         */
        fun fallbackDeviceId(
            store: CrashKeyValueStore,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): String = store.getString(CrashConfig.KEY_FALLBACK_DEVICE_ID)
            ?: newId().also { store.putString(CrashConfig.KEY_FALLBACK_DEVICE_ID, it) }

        fun summaryFor(config: CrashConfig, app: CrashAppInfo, fallbackDeviceId: () -> String): ByteArray {
            val current = config.forBuild(app.versionCode)
            return ProcessStateSummary.encode(
                ProcessStateSummary.Values(
                    release = current.release ?: app.defaultRelease,
                    distinctId = current.distinctId,
                    deviceId = current.deviceId ?: fallbackDeviceId(),
                    dist = current.dist,
                ),
            )
        }
    }
}
