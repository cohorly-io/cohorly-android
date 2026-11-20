package com.cohorly.android.core.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionReplayConfigTest {
    @Test
    fun `wireframes at quality 30 unless asked otherwise`() {
        val config = SessionReplayConfig(sampleRate = 1.0)
        assertEquals(ReplayCaptureMode.WIREFRAME, config.captureMode)
        assertEquals(30, config.screenshotQuality)
    }

    @Test
    fun `a screenshot quality outside 0 to 100 is clamped`() {
        assertEquals(0, SessionReplayConfig(screenshotQuality = -5).screenshotQuality)
        assertEquals(100, SessionReplayConfig(screenshotQuality = 250).screenshotQuality)
        assertEquals(55, SessionReplayConfig(screenshotQuality = 55).screenshotQuality)
    }

    @Test
    fun `capture mode and quality take part in equality`() {
        val base = SessionReplayConfig(sampleRate = 1.0)
        val screenshot = SessionReplayConfig(sampleRate = 1.0, captureMode = ReplayCaptureMode.SCREENSHOT)
        val quality = SessionReplayConfig(sampleRate = 1.0, screenshotQuality = 80)

        assertNotEquals(base, screenshot)
        assertNotEquals(base, quality)
        val sameScreenshot = SessionReplayConfig(sampleRate = 1.0, captureMode = ReplayCaptureMode.SCREENSHOT)
        assertEquals(screenshot, sameScreenshot)
        assertEquals(screenshot.hashCode(), sameScreenshot.hashCode())
        val sameQuality = SessionReplayConfig(sampleRate = 1.0, screenshotQuality = 80)
        assertEquals(quality, sameQuality)
        assertEquals(quality.hashCode(), sameQuality.hashCode())
        assertEquals(
            SessionReplayConfig(screenshotQuality = 300).hashCode(),
            SessionReplayConfig(screenshotQuality = 100).hashCode(),
        )
    }
}
