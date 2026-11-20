@file:OptIn(CohorlyReplayInternal::class)

package com.cohorly.android.core

import com.cohorly.android.core.replay.CohorlyReplayInternal
import com.cohorly.android.core.replay.InMemoryReplayChunkStore
import com.cohorly.android.core.replay.SessionReplayConfig
import com.cohorly.android.core.replay.TestReplayExecutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stand-ins for two Activity classes: only the instance identity and the class matter. */
private class CheckoutActivity
private class HomeActivity

class ScreenViewsTest {
    private fun config(replay: SessionReplayConfig? = null, on: Boolean = true) =
        CohorlyConfig(flushIntervalMs = 0, trackScreenViews = on, sessionReplay = replay)

    private fun screens(transport: MockTransport): List<JSONObject> =
        transport.trackedEvents().filter { it.getString("event") == "\$screen" }

    private fun name(event: JSONObject) = event.getJSONObject("properties").getString("\$screen_name")

    private fun screenNames(transport: MockTransport) = screens(transport).map(::name)

    @Test
    fun `a resumed activity tracks one screen named after its simple class with only that own property`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())

        core.track("baseline")
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        val event = screens(transport).single()
        assertEquals("CheckoutActivity", name(event))
        // Default props ride along; compared with a plain track, the only extra key is $screen_name.
        val baseline = transport.trackedEvents().single { it.getString("event") == "baseline" }
        val baselineKeys = baseline.getJSONObject("properties").keys().asSequence().toSet()
        val screenKeys = event.getJSONObject("properties").keys().asSequence().toSet()
        assertTrue(screenKeys.containsAll(baselineKeys))
        assertEquals(setOf("\$screen_name"), screenKeys - baselineKeys)
    }

    @Test
    fun `screen carries super properties`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        core.registerSuperProperties(mapOf("plan" to "pro"))

        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals("pro", screens(transport).single().getJSONObject("properties").getString("plan"))
    }

    @Test
    fun `screen carries the session id when replay is on`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = config(replay = SessionReplayConfig()),
            replayChunkStore = InMemoryReplayChunkStore(),
            replayStateStorage = InMemoryStorage(),
            replayExecutor = TestReplayExecutor(),
        )

        core.onScreenResumed(CheckoutActivity())
        core.flush()

        val props = screens(transport).single().getJSONObject("properties")
        assertTrue(props.getString("\$session_id").isNotEmpty())
        assertEquals("CheckoutActivity", props.getString("\$screen_name"))
    }

    @Test
    fun `the same instance resumed again emits nothing`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = CheckoutActivity()

        core.onScreenResumed(a)
        core.onScreenLeft(a, isChangingConfigurations = false) // paused: dialog, background, other app
        core.onScreenResumed(a)
        core.onScreenResumed(a)
        core.flush()

        assertEquals(listOf("CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `a configuration change recreating the activity emits nothing and leaves nothing pending`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val before = CheckoutActivity()

        core.onScreenResumed(before)
        core.onScreenLeft(before, isChangingConfigurations = true) // onPause of a rotation
        core.onScreenLeft(before, isChangingConfigurations = true) // onDestroy of a rotation
        val after = CheckoutActivity()
        core.onScreenResumed(after)
        core.onScreenResumed(after)
        // One rotation is one recreation: a later new instance of the class still emits.
        core.onScreenLeft(after, isChangingConfigurations = false)
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals(listOf("CheckoutActivity", "CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `rotating twice still emits nothing more and a later new instance emits`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        var current = CheckoutActivity()
        core.onScreenResumed(current)
        repeat(2) {
            core.onScreenLeft(current, isChangingConfigurations = true)
            current = CheckoutActivity()
            core.onScreenResumed(current)
        }
        // Not a configuration change: a genuinely new instance of the same class.
        core.onScreenLeft(current, isChangingConfigurations = false)
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals(listOf("CheckoutActivity", "CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `returning to a previous activity after another one emits for it`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = HomeActivity()
        val b = CheckoutActivity()

        core.onScreenResumed(a)
        core.onScreenLeft(a, isChangingConfigurations = false)
        core.onScreenResumed(b)
        core.onScreenLeft(b, isChangingConfigurations = false)
        core.onScreenLeft(b, isChangingConfigurations = false) // destroyed on back
        core.onScreenResumed(a)
        core.flush()

        assertEquals(listOf("HomeActivity", "CheckoutActivity", "HomeActivity"), screenNames(transport))
    }

    @Test
    fun `a new instance of the same class without a configuration change emits again`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val first = CheckoutActivity()

        core.onScreenResumed(first)
        core.onScreenLeft(first, isChangingConfigurations = false)
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals(listOf("CheckoutActivity", "CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `an activity recreated by a configuration change while in background emits nothing on return`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val before = CheckoutActivity()

        core.onScreenResumed(before)
        core.onScreenLeft(before, isChangingConfigurations = false) // paused: app to background
        core.onScreenLeft(before, isChangingConfigurations = true) // relaunch destroy on return
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals(listOf("CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `an activity recreated in the back stack while another is on top emits when going back`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = HomeActivity()
        val b = CheckoutActivity()

        core.onScreenResumed(a)
        core.onScreenLeft(a, isChangingConfigurations = false)
        core.onScreenResumed(b)
        core.onScreenLeft(b, isChangingConfigurations = true) // rotation while B is on top
        val b2 = CheckoutActivity()
        core.onScreenResumed(b2)
        core.onScreenLeft(b2, isChangingConfigurations = false) // back
        core.onScreenLeft(a, isChangingConfigurations = true) // A's lazy relaunch destroy
        core.onScreenResumed(HomeActivity())
        core.flush()

        assertEquals(listOf("HomeActivity", "CheckoutActivity", "HomeActivity"), screenNames(transport))
    }

    @Test
    fun `two visible activities recreated together in multi-window emit nothing`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = HomeActivity()
        val b = CheckoutActivity()
        core.onScreenResumed(a)
        core.onScreenResumed(b) // both resumed at once (Android 10+ multi-resume)

        core.onScreenLeft(a, isChangingConfigurations = true)
        core.onScreenLeft(b, isChangingConfigurations = true)
        core.onScreenLeft(a, isChangingConfigurations = true)
        core.onScreenLeft(b, isChangingConfigurations = true)
        core.onScreenResumed(HomeActivity())
        core.onScreenResumed(CheckoutActivity())
        core.flush()

        assertEquals(listOf("HomeActivity", "CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `the decision is taken at resolve time so a queued track never depends on the activity`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = CheckoutActivity()

        // The glue resolves on the main thread and tracks later on the worker.
        val first = core.resolveScreen(a)
        val again = core.resolveScreen(a)
        core.onScreenLeft(a, isChangingConfigurations = true)
        val recreated = core.resolveScreen(CheckoutActivity())
        core.flush()

        assertEquals("CheckoutActivity", first)
        assertEquals(null, again)
        assertEquals(null, recreated)
        assertEquals(emptyList<String>(), screenNames(transport)) // nothing tracked until trackScreen
        core.trackScreen("CheckoutActivity")
        core.flush()
        assertEquals(listOf("CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `a screen resolved while opted out is not counted as sent so opt-in lets it emit`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())
        val a = CheckoutActivity()

        core.optOutTracking()
        val whileOptedOut = core.resolveScreen(a)
        core.optInTracking()
        core.onScreenResumed(a)
        core.flush()

        assertEquals(null, whileOptedOut)
        assertEquals(listOf("CheckoutActivity"), screenNames(transport))
    }

    @Test
    fun `the screen time is the one given at resolve time, not when it is tracked`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config())

        core.trackScreen("CheckoutActivity", 1_234L)
        core.flush()

        val time = transport.trackedEvents().single { it.getString("event") == "\$screen" }
            .getJSONObject("properties").getLong("time")
        assertEquals(1_234L, time)
    }

    @Test
    fun `off by default there is no automatic screen without replay`() {
        assertFalse(CohorlyConfig().trackScreenViews)
        val transport = MockTransport()
        val core = makeCore(transport = transport, config = config(on = false))

        core.onScreenResumed(CheckoutActivity())
        core.onScreenResumed(HomeActivity())
        core.flush()

        assertTrue(screens(transport).isEmpty())
    }

    @Test
    fun `turning replay on does not turn screen views on`() {
        val replay = SessionReplayConfig(sampleRate = 1.0)
        assertFalse(CohorlyConfig(sessionReplay = replay).trackScreenViews)
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = config(replay = replay, on = false),
            replayChunkStore = InMemoryReplayChunkStore(),
            replayStateStorage = InMemoryStorage(),
            replayExecutor = TestReplayExecutor(),
        )

        core.onScreenResumed(CheckoutActivity())
        core.onScreenResumed(HomeActivity())
        core.flush()

        assertTrue(screens(transport).isEmpty())
    }
}
