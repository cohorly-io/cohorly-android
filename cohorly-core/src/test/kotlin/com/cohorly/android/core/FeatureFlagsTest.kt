package com.cohorly.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SAMPLE_FLAGS = """
    {"flags":{
      "new-onboarding":{"enabled":true,"variant":null,"payload":null,"reason":"rollout"},
      "checkout-copy":{"enabled":true,"variant":"bold","payload":{"cta":"Buy now"},"reason":"cohort"},
      "legacy-nav":{"enabled":false,"variant":null,"payload":null,"reason":"default"}
    }}
"""

class FeatureFlagsTest {
    @Test
    fun `reload populates the accessors`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)

        // Unknown / unloaded flags read as false, never null.
        assertEquals(false, core.getFeatureFlag("new-onboarding"))
        assertFalse(core.isFeatureEnabled("new-onboarding"))

        core.reloadFeatureFlags()

        assertEquals(true, core.getFeatureFlag("new-onboarding"))
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        assertEquals(false, core.getFeatureFlag("legacy-nav"))
        assertEquals(false, core.getFeatureFlag("does-not-exist"))

        assertTrue(core.isFeatureEnabled("checkout-copy"))
        assertFalse(core.isFeatureEnabled("legacy-nav"))
        assertFalse(core.isFeatureEnabled("does-not-exist"))

        val payload = core.getFeatureFlagPayload("checkout-copy") as JSONObject
        assertEquals("Buy now", payload.getString("cta"))
        assertNull(core.getFeatureFlagPayload("new-onboarding"))
        assertNull(core.getFeatureFlagPayload("does-not-exist"))

        assertEquals(
            FlagResult(enabled = true, variant = "bold", payload = payload, reason = "cohort"),
            core.getFeatureFlags()["checkout-copy"],
        )
    }

    @Test
    fun `request carries distinct id and token`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)

        core.reloadFeatureFlags()

        val body = JSONObject(fetcher.requests.single())
        assertEquals(core.getDistinctId(), body.getString("distinct_id"))
        assertEquals("test-token", body.getString("token"))
    }

    @Test
    fun `flags are served from the cache on relaunch`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS)).reloadFeatureFlags()

        // Same storage = same distinct id: the relaunched client serves the
        // cache immediately, with no network call.
        val fetcher = MockFlagsFetcher(null)
        val relaunched = makeCore(storage = storage, flagsFetcher = fetcher)
        assertEquals("bold", relaunched.getFeatureFlag("checkout-copy"))
        assertTrue(relaunched.isFeatureEnabled("new-onboarding"))
        assertEquals(
            "Buy now",
            (relaunched.getFeatureFlagPayload("checkout-copy") as JSONObject).getString("cta"),
        )
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun `a cache belonging to another identity is discarded`() {
        val storage = InMemoryStorage()
        storage.set("cohorly.distinct_id", "someone-else")
        storage.set("cohorly.anonymous", "0")
        storage.set(
            "cohorly.flags",
            """{"distinct_id":"a-different-user","flags":{"checkout-copy":{"enabled":true,"variant":"bold"}}}""",
        )

        val core = makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(null))

        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertNull(storage.get("cohorly.flags"))
    }

    @Test
    fun `an unparseable cache is dropped, not reported as loaded`() {
        val storage = InMemoryStorage()
        storage.set("cohorly.distinct_id", "user-1")
        storage.set("cohorly.anonymous", "0")
        // Right identity, but the flags value is not a parseable map: it must
        // be discarded and listeners must NOT fire as if flags were loaded.
        storage.set("cohorly.flags", """{"distinct_id":"user-1","flags":"corrupt"}""")

        val core = makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(null))

        assertNull(storage.get("cohorly.flags"))
        var fired = false
        core.onFeatureFlags { fired = true }
        assertEquals(false, fired)
    }

    @Test
    fun `a failed reload keeps the stale flags`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)
        core.reloadFeatureFlags()

        fetcher.response = null
        core.reloadFeatureFlags()
        assertTrue(core.isFeatureEnabled("new-onboarding"))

        fetcher.response = "not json at all"
        core.reloadFeatureFlags()
        assertTrue(core.isFeatureEnabled("new-onboarding"))

        // A throwing fetcher is swallowed too.
        val core2 = makeCore(
            flagsFetcher = object : CohorlyFlagsFetcher {
                override fun fetch(url: String, body: String): String? = networkError()
            },
        )
        core2.reloadFeatureFlags()
        assertEquals(false, core2.getFeatureFlag("new-onboarding"))
    }

    @Test
    fun `identify reloads flags for the new id`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)

        core.identify("user-42")

        assertEquals(listOf("user-42"), fetcher.requestedDistinctIds())
        assertTrue(core.isFeatureEnabled("new-onboarding"))
    }

    @Test
    fun `reset drops the previous identity's flags before refreshing`() {
        val storage = InMemoryStorage()
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(storage = storage, flagsFetcher = fetcher)
        core.reloadFeatureFlags()
        assertTrue(core.isFeatureEnabled("new-onboarding"))

        // The refresh fails, so nothing replaces the dropped cache: the new
        // anonymous id must still see no flags at all.
        fetcher.response = null
        core.reset()

        assertFalse(core.isFeatureEnabled("new-onboarding"))
        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertNull(storage.get("cohorly.flags"))
        assertEquals(core.getDistinctId(), fetcher.requestedDistinctIds().last())
    }

    @Test
    fun `a response that arrives after an identity change is discarded`() {
        val storage = InMemoryStorage()
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(storage = storage, flagsFetcher = fetcher)
        val anonymousId = core.getDistinctId()

        // The identity switches while the request is "in flight": the response
        // describes the anonymous id, so it must be neither served nor cached.
        fetcher.beforeReturn = { core.setIdentity("user-7") }
        core.reloadFeatureFlags()

        assertNull(storage.get("cohorly.flags"))
        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertEquals(listOf(anonymousId), fetcher.requestedDistinctIds())

        // The reload issued for the new identity is applied normally.
        fetcher.beforeReturn = null
        core.reloadFeatureFlags()
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        assertEquals("user-7", JSONObject(storage.get("cohorly.flags")!!).getString("distinct_id"))
    }

    @Test
    fun `listeners fire on reload and immediately once loaded`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)

        val seen = mutableListOf<Int>()
        val unsubscribe = core.onFeatureFlags { seen.add(it.size) }
        // Nothing loaded yet: no immediate call.
        assertTrue(seen.isEmpty())

        core.reloadFeatureFlags()
        assertEquals(listOf(3), seen)

        // Subscribing after flags are loaded fires immediately.
        val late = mutableListOf<Int>()
        core.onFeatureFlags { late.add(it.size) }
        assertEquals(listOf(3), late)

        unsubscribe()
        core.reloadFeatureFlags()
        assertEquals(listOf(3), seen)
        assertEquals(listOf(3, 3), late)
    }

    // MARK: exposure events

    /** The `$feature_flag_called` events sent to /track, in order. */
    private fun exposures(transport: MockTransport): List<JSONObject> =
        transport.trackedEvents().filter { it.getString("event") == "\$feature_flag_called" }

    private fun assertExposure(event: JSONObject, key: String, response: Any) {
        val props = event.getJSONObject("properties")
        assertEquals(key, props.getString("\$feature_flag"))
        assertEquals(response, props.get("\$feature_flag_response"))
    }

    @Test
    fun `an exposure is tracked once per value`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        // Repeated reads, including via the other accessor, stay silent.
        core.getFeatureFlag("checkout-copy")
        assertTrue(core.isFeatureEnabled("checkout-copy"))
        core.flush()

        val events = exposures(transport)
        assertEquals(1, events.size)
        assertExposure(events.single(), "checkout-copy", "bold")
    }

    @Test
    fun `an exposure reports the enabled boolean for plain flags`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        assertFalse(core.isFeatureEnabled("legacy-nav"))
        assertTrue(core.isFeatureEnabled("new-onboarding"))
        core.flush()

        val events = exposures(transport)
        assertEquals(2, events.size)
        assertExposure(events[0], "legacy-nav", false)
        assertExposure(events[1], "new-onboarding", true)
    }

    @Test
    fun `an exposure refires when the value changes`() {
        val transport = MockTransport()
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(transport = transport, flagsFetcher = fetcher)
        core.reloadFeatureFlags()
        core.getFeatureFlag("checkout-copy")

        fetcher.response =
            """{"flags":{"checkout-copy":{"enabled":true,"variant":"calm","reason":"cohort"}}}"""
        core.reloadFeatureFlags()
        assertEquals("calm", core.getFeatureFlag("checkout-copy"))
        core.flush()

        val events = exposures(transport)
        assertEquals(2, events.size)
        assertExposure(events[1], "checkout-copy", "calm")
    }

    @Test
    fun `identify clears the exposure dedupe`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()
        core.getFeatureFlag("checkout-copy")

        core.identify("user-42")
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        core.flush()

        assertEquals(2, exposures(transport).size)
    }

    @Test
    fun `reset clears the exposure dedupe`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()
        core.getFeatureFlag("checkout-copy")
        core.flush()
        assertEquals(1, exposures(transport).size)

        // reset() drops the queue too, so the count continues from what was
        // already sent above.
        core.reset()
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        core.flush()

        assertEquals(2, exposures(transport).size)
    }

    @Test
    fun `exposure events can be disabled`() {
        val transport = MockTransport()
        val core = makeCore(
            transport = transport,
            config = CohorlyConfig(flushIntervalMs = 0, sendExposureEvents = false),
            flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS),
        )
        core.reloadFeatureFlags()

        core.getFeatureFlag("checkout-copy")
        assertTrue(core.isFeatureEnabled("new-onboarding"))
        core.track("marker")
        core.flush()

        assertEquals(listOf("marker"), transport.trackedEvents().map { it.getString("event") })
    }

    @Test
    fun `payload, bulk and unknown reads are silent`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        core.getFeatureFlagPayload("checkout-copy")
        core.getFeatureFlags()
        assertEquals(false, core.getFeatureFlag("does-not-exist"))
        assertFalse(core.isFeatureEnabled("does-not-exist"))
        core.track("marker")
        core.flush()

        assertEquals(listOf("marker"), transport.trackedEvents().map { it.getString("event") })
    }

    @Test
    fun `a failed reload notifies nobody`() {
        val core = makeCore(flagsFetcher = MockFlagsFetcher(null))
        val seen = mutableListOf<Int>()
        core.onFeatureFlags { seen.add(it.size) }

        core.reloadFeatureFlags()

        assertTrue(seen.isEmpty())
    }
}
