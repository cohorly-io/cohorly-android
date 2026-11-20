package com.cohorly.android.replay

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cohorly.android.Cohorly
import com.cohorly.android.core.replay.ReplayCaptureMode
import java.util.WeakHashMap

/**
 * Entry point of the recorder. `Cohorly.getInstance` finds this class by name
 * (`Class.forName`) when `CohorlyConfig.sessionReplay` is set and calls
 * [install], so `cohorly-android` never depends on this module; the
 * consumer ProGuard rules keep both. Not meant to be called by apps.
 */
object ReplayInstaller {
    private val recorders = WeakHashMap<Cohorly, ReplayRecorder>()

    /**
     * Starts recording for [cohorly]. Below API 26 it only logs: nothing is
     * recorded and `$session_id` goes on. Once per instance; any thread.
     */
    @JvmStatic
    fun install(cohorly: Cohorly, application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(ReplayRecorder.TAG, "Cohorly session replay needs Android 8.0 (API 26) or newer; not recording.")
            return
        }
        val replay = cohorly.replay ?: return
        val recorder = synchronized(recorders) {
            if (recorders.containsKey(cohorly)) return
            val config = replay.config
            val worker = ThreadReplayWorker(RECORDER_THREAD)
            val mainHandler = Handler(Looper.getMainLooper())
            val screenshots = if (config.captureMode == ReplayCaptureMode.SCREENSHOT) {
                ScreenshotCapturer(
                    maskAllImages = config.maskAllImages,
                    quality = config.screenshotQuality,
                    source = PixelCopySource(mainHandler),
                    worker = worker,
                    mainHandler = mainHandler,
                )
            } else {
                null
            }
            ReplayRecorder(
                application = application,
                session = ControllerSessionSource(replay),
                maskAllImages = config.maskAllImages,
                worker = worker,
                mainHandler = mainHandler,
                screenshots = screenshots,
            ).also { recorders[cohorly] = it }
        }
        recorder.install()
    }

    /** The recorder [install] started for [cohorly], if any. */
    internal fun recorderFor(cohorly: Cohorly): ReplayRecorder? = synchronized(recorders) { recorders[cohorly] }

    /** Serialization, chunk building and the 10 s timer; never the main thread. */
    internal const val RECORDER_THREAD = "cohorly-replay-recorder"
}
