package com.cohorly.android.core.crash

import com.cohorly.android.core.replay.replayCore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CrashSessionIdTest {
    private val sessionId = "7d3b0c62-2f0e-4c35-9a8e-0d8f7b2a4e11"

    private fun recordedEvents(dir: File): List<JSONObject> = pendingFiles(dir).map { JSONObject(it.readText()) }

    private fun recordedEvent(dir: File): JSONObject = recordedEvents(dir).single()

    @Test
    fun `the session id an earlier process left in the config is never stamped`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig().copy(sessionId = sessionId)) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)

        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)
        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val events = recordedEvents(dir)
        assertEquals(2, events.size)
        assertFalse(events.any { it.props().has("\$session_id") })
    }

    @Test
    fun `a session id this process wrote to the config reaches the running reporter`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)

        reporter.updateConfig(CrashReporter.writeConfig(store, enabledConfig().copy(sessionId = sessionId).toJson()))
        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(sessionId, recordedEvent(dir).props().getString("\$session_id"))
    }

    @Test
    fun `after a rotation the next uncaught exception and ANR read the new session id, with no config sync`() {
        val core = replayCore()
        val store = MemoryCrashStore().apply { putConfig(enabledConfig().copy(sessionId = core.replay!!.sessionId)) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.sessionIdProvider = { core.replay?.sessionId }
        reporter.start(null)
        val before = core.replay!!.sessionId

        core.reset()
        val after = core.replay!!.sessionId
        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)
        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertNotEquals(before, after)
        val stamped = recordedEvents(dir).map { it.props().getString("\$session_id") }
        assertEquals(listOf(after, after), stamped)
        // Nothing synced the config: it still names the old session.
        assertEquals(before, CrashConfig.readEnabled(store)!!.sessionId)
    }

    @Test
    fun `with a provider that answers null no exception carries a session id`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.sessionIdProvider = { null }
        reporter.start(null)
        reporter.updateConfig(CrashReporter.writeConfig(store, enabledConfig().copy(sessionId = sessionId).toJson()))

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertFalse(recordedEvent(dir).props().has("\$session_id"))
    }

    @Test
    fun `without a session id no exception carries one`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertFalse(recordedEvent(dir).props().has("\$session_id"))
    }

    /** The JSON `buildNativeCrashConfig` in @cohorly/react-native hands to `CohorlyCrashReporter.configure`. */
    private fun reactNativeConfig(sessionId: String) =
        """{"enabled":true,"metricKit":true,"release":"com.example@1.0+7","dist":null,""" +
            """"distinctId":"rn-user","deviceId":"rn-device","build":"7","sessionId":"$sessionId"}"""

    @Test
    fun `the React Native config JSON stamps its session id, and a rotation's rewrite stamps the new one`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val slot = FakeSlot(RecordingHandler())
        val reporter = newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot)
        reporter.start(null)
        val rotated = "0b6f8f5e-3c1a-4d2e-8f00-6a1c2d3e4f50"

        reporter.updateConfig(CrashReporter.writeConfig(store, reactNativeConfig(sessionId), build = 7))
        reporter.recordWatchdogAnr(Thread.currentThread(), AnrWatchdog.DEFAULT_THRESHOLD_MS)
        reporter.updateConfig(CrashReporter.writeConfig(store, reactNativeConfig(rotated), build = 7))
        slot.current!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val stamped = recordedEvents(dir).associate {
            it.props().getString("\$exception_level") to it.props().getString("\$session_id")
        }
        assertEquals(mapOf("error" to sessionId, "fatal" to rotated), stamped)
    }

    @Test
    fun `the session id round-trips the config JSON and a writer without it clears it`() {
        val withSession = enabledConfig().copy(sessionId = sessionId)
        assertEquals(sessionId, CrashConfig.parse(withSession.toJson())!!.sessionId)
        assertNull(CrashConfig.parse(enabledConfig().toJson())!!.sessionId)

        val merged = CrashConfig.merge(withSession.toJson(), """{"enabled":true,"distinctId":"rn-user"}""")
        assertNull(CrashConfig.parse(merged)!!.sessionId)
    }
}
