package com.cohorly.android.replay

import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.replay.ErrorBuffer
import com.cohorly.android.core.replay.ReplayController
import com.cohorly.android.core.replay.ReplayMode
import com.cohorly.android.crash.CohorlyCrashReporter
import org.json.JSONObject

// The Cohorly SDK side of the recorder (VEL-2142): the replay controller, the
// crash reporter and the core error buffer behind the recorder's own types
// (RecorderTypes.kt). Not copied to React Native (ANDROID_REPLAY_EXCLUDED in
// packages/sdk-react-native/scripts/sync-replay-core.mjs).

/** The recorder's host in the Cohorly SDK: a [ReplayController] and the crash reporter. */
internal class ControllerSessionSource(private val replay: ReplayController) : ReplaySessionSource {
    override val sessionId: String? get() = replay.sessionId
    override val mode: RecorderMode get() = replay.mode.toRecorderMode()
    override val droppedChunks: Long get() = replay.droppedChunks

    override fun addSessionListener(listener: (String?) -> Unit): () -> Unit = replay.addSessionListener(listener)

    override fun recordActivity() {
        replay.recordActivity()
    }

    override fun enqueue(events: List<JSONObject>, sessionId: String): Boolean =
        replay.enqueue(events, ReplayMode.SESSION, sessionId)

    override val errorReportsOn: Boolean get() = CohorlyCrashReporter.isRunning

    override val reportSessionId: String? get() = CohorlyCrashReporter.lastReportSessionId

    override fun addCrashListener(listener: (fatal: Boolean) -> Unit): () -> Unit =
        CohorlyCrashReporter.addListener { fatal -> listener(fatal) }

    override fun enqueueError(events: List<JSONObject>, sessionId: String): Boolean =
        replay.enqueue(events, ReplayMode.ERROR, sessionId)

    override fun enqueueErrorNow(events: List<JSONObject>, sessionId: String): Boolean =
        replay.enqueueNow(events, ReplayMode.ERROR, sessionId)

    override fun createErrorBuffer(clock: () -> Long): RecorderErrorBuffer = CoreErrorBuffer(clock)
}

internal fun ReplayMode.toRecorderMode(): RecorderMode = when (this) {
    ReplayMode.SESSION -> RecorderMode.SESSION
    ReplayMode.ERROR -> RecorderMode.ERROR
    ReplayMode.OFF -> RecorderMode.OFF
}

/** The core [ErrorBuffer] (VEL-2151) as the recorder's [RecorderErrorBuffer]. Thread-safe like it. */
internal class CoreErrorBuffer(clock: () -> Long) : RecorderErrorBuffer {
    private val buffer = ErrorBuffer(
        object : CohorlyClock {
            override fun nowMillis(): Long = clock()
        },
    )

    override val currentSessionId: String? get() = buffer.currentSessionId
    override val checkoutEveryMs: Long get() = ErrorBuffer.CHECKOUT_EVERY_MS

    override fun add(sessionId: String, events: List<JSONObject>) {
        buffer.add(sessionId, events)
    }

    override fun checkoutDue(): Boolean = buffer.checkoutDue()

    override fun nextSendDelayMs(): Long = buffer.nextSendDelayMs()

    override fun cut(sessionId: String): RecorderErrorCut? =
        buffer.cut(sessionId)?.let { RecorderErrorCut(it.sessionId, it.events) }

    override fun clear() {
        buffer.clear()
    }
}
