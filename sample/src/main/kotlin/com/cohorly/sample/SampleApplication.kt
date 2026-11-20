package com.cohorly.sample

import android.app.Application
import com.cohorly.android.Cohorly
import com.cohorly.android.core.CohorlyConfig
import com.cohorly.android.core.replay.ReplayCaptureMode
import com.cohorly.android.core.replay.SessionReplayConfig

class SampleApplication : Application() {
    lateinit var cohorly: Cohorly
        private set

    override fun onCreate() {
        super.onCreate()
        // Token: ./gradlew :sample:installDebug -PcohorlySampleToken=<project token>
        cohorly = Cohorly.getInstance(
            this,
            BuildConfig.COHORLY_TOKEN,
            BuildConfig.COHORLY_API_HOST,
            CohorlyConfig(
                trackScreenViews = true,
                sessionReplay = SessionReplayConfig(
                    sampleRate = BuildConfig.REPLAY_SAMPLE_RATE,
                    onErrorSampleRate = BuildConfig.REPLAY_ON_ERROR_SAMPLE_RATE,
                    captureMode = ReplayCaptureMode.valueOf(BuildConfig.COHORLY_CAPTURE_MODE),
                    remoteConfig = BuildConfig.REPLAY_REMOTE_CONFIG,
                ),
                // Error mode records only while the crash reporter runs (the launch after this flag is set).
                nativeCrashReporting = BuildConfig.NATIVE_CRASH_REPORTING,
            ),
        )
    }
}
