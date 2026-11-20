package com.cohorly.android.core.replay

/**
 * Marks the API the session replay recorder uses to feed chunks into the
 * SDK. Public so the recorder can live in another module, but not meant for
 * apps: it can change in any release.
 */
@RequiresOptIn(
    message = "Internal session replay API, used by the Cohorly recorder. It can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class CohorlyReplayInternal

/** How a session is recorded; [wire] is the chunk's `mode` (null: not recorded). */
enum class ReplayMode(val wire: String?) {
    SESSION("session"),
    ERROR("error"),
    OFF(null),
    ;

    companion object {
        fun fromWire(value: String?): ReplayMode = entries.firstOrNull { it.wire != null && it.wire == value } ?: OFF
    }
}

/** How the recorder captures the screen. */
enum class ReplayCaptureMode {
    /** Wireframes: the position, size and style of each view, never its pixels. */
    WIREFRAME,

    /** A masked WebP image of the screen each capture. */
    SCREENSHOT,
}

/**
 * Session replay settings, same meaning as `@cohorly/web`'s `sessionReplay`.
 * Setting it at all (even with both rates at 0) turns on `$session_id` on
 * every event.
 *
 * - [sampleRate]: share of sessions recorded in full (`mode: "session"`).
 * - [onErrorSampleRate]: share of the other sessions whose recent buffer is
 *   sent when an error happens (`mode: "error"`).
 * - [maskAllImages]: images are replaced by placeholders in the recording.
 * - [captureMode]: wireframes (default) or masked screenshots.
 * - [screenshotQuality]: WebP quality of a screenshot, 0 to 100 (clamped).
 * - [remoteConfig]: the project's saved `sampleRate`, `onErrorSampleRate`
 *   and `minDurationMs` (`GET /replay/config`) win field by field over the
 *   code's for new sessions. Default false: no request is made. Saved
 *   triggers are web only and ignored here; masking, capture mode and
 *   quality always come from the code.
 *
 * Rates outside [0, 1] are clamped; NaN reads as 0.
 */
class SessionReplayConfig(
    sampleRate: Double = 0.0,
    onErrorSampleRate: Double = 0.0,
    val maskAllImages: Boolean = true,
    val captureMode: ReplayCaptureMode = ReplayCaptureMode.WIREFRAME,
    screenshotQuality: Int = DEFAULT_SCREENSHOT_QUALITY,
    val remoteConfig: Boolean = false,
) {
    val sampleRate: Double = clampRate(sampleRate)
    val onErrorSampleRate: Double = clampRate(onErrorSampleRate)
    val screenshotQuality: Int = screenshotQuality.coerceIn(0, 100)

    override fun equals(other: Any?): Boolean = other is SessionReplayConfig &&
        other.sampleRate == sampleRate &&
        other.onErrorSampleRate == onErrorSampleRate &&
        other.maskAllImages == maskAllImages &&
        other.captureMode == captureMode &&
        other.screenshotQuality == screenshotQuality &&
        other.remoteConfig == remoteConfig

    override fun hashCode(): Int {
        var result = (sampleRate.hashCode() * 31 + onErrorSampleRate.hashCode()) * 31 + maskAllImages.hashCode()
        result = result * 31 + captureMode.hashCode()
        result = result * 31 + screenshotQuality
        return result * 31 + remoteConfig.hashCode()
    }

    override fun toString(): String = "SessionReplayConfig(sampleRate=$sampleRate, " +
        "onErrorSampleRate=$onErrorSampleRate, maskAllImages=$maskAllImages, " +
        "captureMode=$captureMode, screenshotQuality=$screenshotQuality, remoteConfig=$remoteConfig)"

    companion object {
        const val DEFAULT_SCREENSHOT_QUALITY = 30

        private fun clampRate(value: Double): Double = if (value.isNaN()) 0.0 else value.coerceIn(0.0, 1.0)
    }
}
