package com.cohorly.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `identify and reset notify listeners with an empty set first`() {
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(flagsFetcher = fetcher)
        core.reloadFeatureFlags()

        val seen = mutableListOf<Int>()
        core.onFeatureFlags { seen.add(it.size) }
        assertEquals(listOf(3), seen)

        // Clearing the previous identity's served set emits an empty map
        // before the new identity's reload lands.
        core.identify("user-9")
        assertEquals(listOf(3, 0, 3), seen)

        core.reset()
        assertEquals(listOf(3, 0, 3, 0, 3), seen)
    }

    // MARK: lookup policies and TTL

    @Test
    fun `network only never reads nor writes the persisted cache`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
            .reloadFeatureFlags()
        val seeded = storage.get("cohorly.flags")

        // Offline startup under networkOnly: nothing is served and the
        // seeded cache is left untouched.
        val fetcher = MockFlagsFetcher(null)
        val core = makeCore(
            storage = storage,
            flagsFetcher = fetcher,
            config = CohorlyConfig(
                flushIntervalMs = 0,
                flagPersistence = FlagPersistenceMode.networkOnly,
            ),
        )
        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertEquals(seeded, storage.get("cohorly.flags"))

        // A network success serves values this session but persists nothing.
        fetcher.response = SAMPLE_FLAGS
        core.reloadFeatureFlags()
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        assertEquals(FlagSource.network, core.getFeatureFlagDetails("checkout-copy").source)
        assertEquals(seeded, storage.get("cohorly.flags"))
    }

    @Test
    fun `network first serves the cache like persistence until network success`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
            .reloadFeatureFlags()

        // Offline relaunch under networkFirst: synchronous getters cannot
        // await the network, so - like the sibling SDKs - the valid cache is
        // served immediately, identically to persistenceUntilNetworkSuccess.
        val fetcher = MockFlagsFetcher(null)
        val core = makeCore(
            storage = storage,
            flagsFetcher = fetcher,
            config = CohorlyConfig(
                flushIntervalMs = 0,
                flagPersistence = FlagPersistenceMode.networkFirst,
            ),
        )
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        assertEquals(FlagSource.persistence, core.getFeatureFlagDetails("checkout-copy").source)

        // The first network success still takes over and re-persists.
        fetcher.response = SAMPLE_FLAGS
        core.reloadFeatureFlags()
        assertEquals(FlagSource.network, core.getFeatureFlagDetails("checkout-copy").source)
    }

    @Test
    fun `persistence until network success transitions to network`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
            .reloadFeatureFlags()

        // Offline relaunch serves the persisted cache...
        val fetcher = MockFlagsFetcher(null)
        val core = makeCore(storage = storage, flagsFetcher = fetcher)
        assertEquals(FlagSource.persistence, core.getFeatureFlagDetails("checkout-copy").source)

        // ...until the first network success takes over and re-persists.
        fetcher.response = SAMPLE_FLAGS
        core.reloadFeatureFlags()
        assertEquals(FlagSource.network, core.getFeatureFlagDetails("checkout-copy").source)
        assertNull(core.getFeatureFlagDetails("checkout-copy").persistedAt)

        // A later failure keeps the network values; the older cache is never
        // a downgrade target once the network has answered this session.
        fetcher.response = null
        core.reloadFeatureFlags()
        val details = core.getFeatureFlagDetails("checkout-copy")
        assertEquals(FlagSource.network, details.source)
        assertEquals("bold", details.value)
    }

    @Test
    fun `an expired cache is served under puns but dropped under network first`() {
        val storage = InMemoryStorage()
        val clock = MockClock()
        val config = CohorlyConfig(flushIntervalMs = 0, flagCacheTtlMs = 60_000)
        makeCore(
            storage = storage,
            clock = clock,
            config = config,
            flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS),
        )
            .reloadFeatureFlags()
        val persistedAt = clock.nowMs

        // Past the TTL but inside the 7-day stale window: PUNS trades
        // freshness for availability, so the expired cache still serves
        // (source persistence, real persistedAt, configured ttlMs).
        clock.advance(61_000)
        val punsCore = makeCore(
            storage = storage,
            clock = clock,
            config = config,
            flagsFetcher = MockFlagsFetcher(null),
        )
        val details = punsCore.getFeatureFlagDetails("checkout-copy")
        assertEquals("bold", details.value)
        assertEquals(FlagSource.persistence, details.source)
        assertEquals(persistedAt, details.persistedAt)
        assertEquals(60_000L, details.ttlMs)

        // networkFirst is strict: the same expired cache is dropped instead.
        val firstCore = makeCore(
            storage = storage,
            clock = clock,
            config = config.copy(flagPersistence = FlagPersistenceMode.networkFirst),
            flagsFetcher = MockFlagsFetcher(null),
        )
        assertEquals(false, firstCore.getFeatureFlag("checkout-copy"))
        assertNull(storage.get("cohorly.flags"))
    }

    @Test
    fun `a cache older than seven days is dropped`() {
        val storage = InMemoryStorage()
        val clock = MockClock()
        makeCore(
            storage = storage,
            clock = clock,
            flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS),
        )
            .reloadFeatureFlags()

        // Past the 7-day stale window even PUNS refuses it.
        clock.advance(CohorlyCore.FLAG_CACHE_MAX_STALE_MS + 1)
        val core = makeCore(
            storage = storage,
            clock = clock,
            flagsFetcher = MockFlagsFetcher(null),
        )
        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertNull(storage.get("cohorly.flags"))
    }

    @Test
    fun `a legacy cache without stored_at is served indefinitely`() {
        val storage = InMemoryStorage()
        storage.set("cohorly.distinct_id", "user-1")
        storage.set("cohorly.anonymous", "0")
        // Written by a pre-TTL SDK: no stored_at means the age cannot be
        // proven, so the cache is served (graceful upgrade - pre-TTL
        // behavior served it indefinitely) until a successful reload
        // rewrites it in the stamped format.
        storage.set(
            "cohorly.flags",
            """{"distinct_id":"user-1","flags":{"checkout-copy":{"enabled":true,"variant":"bold"}}}""",
        )

        val core = makeCore(storage = storage, flagsFetcher = MockFlagsFetcher(null))

        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        val details = core.getFeatureFlagDetails("checkout-copy")
        assertEquals(FlagSource.persistence, details.source)
        // Unprovable age: persistedAt stays absent, ttlMs is still reported.
        assertNull(details.persistedAt)
        assertEquals(CohorlyConfig().flagCacheTtlMs, details.ttlMs)
        // The v1 entry is kept on disk - a successful reload replaces it.
        assertNotNull(storage.get("cohorly.flags"))
    }

    @Test
    fun `a future stored_at is normalized to now on adoption`() {
        val storage = InMemoryStorage()
        storage.set("cohorly.distinct_id", "user-1")
        storage.set("cohorly.anonymous", "0")
        // Clock moved backwards after the write: a raw negative age would
        // satisfy every bound indefinitely, so it is clamped to fresh and
        // bounded by the normal TTL.
        val futureMs = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        storage.set(
            "cohorly.flags",
            """{"distinct_id":"user-1","flags":{"checkout-copy":{"enabled":true,"variant":"bold"}},"stored_at":$futureMs}""",
        )

        val core = makeCore(
            storage = storage,
            flagsFetcher = MockFlagsFetcher(null),
            config = CohorlyConfig(
                flushIntervalMs = 0,
                flagPersistence = FlagPersistenceMode.networkFirst,
            ),
        )

        assertEquals(FlagSource.persistence, core.getFeatureFlagDetails("checkout-copy").source)
        val rewritten = storage.get("cohorly.flags")
        assertNotNull(rewritten)
        assertFalse(rewritten!!.contains("\"stored_at\":$futureMs"))
    }

    @Test
    fun `identify drops the previous identity's flags before reloading`() {
        val storage = InMemoryStorage()
        val fetcher = MockFlagsFetcher(SAMPLE_FLAGS)
        val core = makeCore(storage = storage, flagsFetcher = fetcher)
        core.reloadFeatureFlags()
        assertTrue(core.isFeatureEnabled("new-onboarding"))

        // The previous identity's flags must never leak to the new one:
        // memory and disk are cleared before the new id's reload lands.
        fetcher.response = null
        core.identify("user-9")

        assertFalse(core.isFeatureEnabled("new-onboarding"))
        assertEquals(false, core.getFeatureFlag("checkout-copy"))
        assertNull(storage.get("cohorly.flags"))
    }

    // MARK: details and explicit fallbacks

    @Test
    fun `details report the source and cache metadata`() {
        val storage = InMemoryStorage()
        val clock = MockClock()
        val seeder = makeCore(
            storage = storage,
            clock = clock,
            flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS),
        )
        seeder.reloadFeatureFlags()

        // Network-served values report source network and no cache metadata.
        val network = seeder.getFeatureFlagDetails("checkout-copy")
        assertEquals("bold", network.value)
        assertEquals(FlagSource.network, network.source)
        assertNull(network.persistedAt)
        assertNull(network.ttlMs)

        // Persistence-served values carry when they were stored and the TTL.
        val cached = makeCore(storage = storage, clock = clock, flagsFetcher = MockFlagsFetcher(null))
        val persisted = cached.getFeatureFlagDetails("checkout-copy")
        assertEquals("bold", persisted.value)
        assertEquals(FlagSource.persistence, persisted.source)
        assertEquals(clock.nowMs, persisted.persistedAt)
        assertEquals(CohorlyConfig().flagCacheTtlMs, persisted.ttlMs)

        // Unknown flags resolve to the caller's fallback, sourced fallback.
        val missing = seeder.getFeatureFlagDetails("does-not-exist", fallback = "control")
        // A truthy fallback reports enabled = true, like Boolean("control").
        assertEquals(
            FlagDetails(
                key = "does-not-exist",
                value = "control",
                enabled = true,
                payload = null,
                source = FlagSource.fallback,
            ),
            missing,
        )
    }

    @Test
    fun `explicit fallbacks`() {
        val core = makeCore(flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        assertEquals("control", core.getFeatureFlag("does-not-exist", fallback = "control"))
        assertTrue(core.isFeatureEnabled("does-not-exist", fallback = true))
        // Known flags ignore the fallback entirely.
        assertEquals("bold", core.getFeatureFlag("checkout-copy", fallback = "control"))
        assertFalse(core.isFeatureEnabled("legacy-nav", fallback = true))
    }

    // MARK: exposure source

    @Test
    fun `a network exposure reports the network source`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        core.getFeatureFlag("checkout-copy")
        core.flush()

        assertEquals(
            "network",
            exposures(transport).single()
                .getJSONObject("properties").getString("\$feature_flag_source"),
        )
    }

    @Test
    fun `a persistence exposure reports the persistence source once`() {
        val transport = MockTransport()
        val storage = InMemoryStorage()
        makeCore(
            storage = storage,
            transport = MockTransport(),
            flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS),
        ).reloadFeatureFlags()

        // Offline relaunch: the exposure reports source "persistence".
        val fetcher = MockFlagsFetcher(null)
        val core = makeCore(storage = storage, transport = transport, flagsFetcher = fetcher)
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))

        // The same value arriving over the network does NOT re-report:
        // dedupe keys on (key, value), not on source.
        fetcher.response = SAMPLE_FLAGS
        core.reloadFeatureFlags()
        assertEquals("bold", core.getFeatureFlag("checkout-copy"))
        core.flush()

        val events = exposures(transport)
        assertEquals(1, events.size)
        val properties = events.single().getJSONObject("properties")
        assertEquals("persistence", properties.getString("\$feature_flag_source"))
        // Persistence reads also carry when the cache was written and its TTL.
        assertTrue(properties.has("\$feature_flag_persisted_at"))
        assertEquals(
            CohorlyConfig().flagCacheTtlMs,
            properties.getLong("\$feature_flag_ttl_ms"),
        )
    }

    @Test
    fun `a read that resolves to the fallback tracks no exposure`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, flagsFetcher = MockFlagsFetcher(SAMPLE_FLAGS))
        core.reloadFeatureFlags()

        // Reads that resolve to the fallback assign nothing, so they report
        // no exposure - through any of the flag getters.
        assertEquals("control", core.getFeatureFlag("does-not-exist", fallback = "control"))
        assertTrue(core.isFeatureEnabled("does-not-exist", fallback = true))
        assertEquals(FlagSource.fallback, core.getFeatureFlagDetails("does-not-exist").source)
        core.track("marker")
        core.flush()

        assertEquals(listOf("marker"), transport.trackedEvents().map { it.getString("event") })
    }
}
