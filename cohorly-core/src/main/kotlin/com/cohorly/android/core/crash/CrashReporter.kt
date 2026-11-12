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

    private val installFallbackId: String by lazy { fallbackDeviceId(store, newInsertId) }

    /**
     * Returns false, having touched nothing, when the persisted config is
     * absent, invalid or not enabled. [exitRecords] is null below API 30.
     */
    fun start(exitRecords: (() -> List<ExitRecord>)?): Boolean {
        val current = CrashConfig.readEnabled(store) ?: return false
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
        pending.write(insertId, CrashEvents.event(props))
        background { runCatching { sendPending() } }
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
        pending.write(insertId, CrashEvents.event(props))

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
