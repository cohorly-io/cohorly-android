package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallGuardTest {
    @Test
    fun `a fresh install is stamped, not cleared`() {
        val storage = InMemoryStorage()

        assertEquals(InstallState.FIRST_RUN, guardRestoredInstall(storage, "100"))
        assertEquals(InstallState.SAME_INSTALL, guardRestoredInstall(storage, "100"))
    }

    @Test
    fun `the same install keeps its identity and queue`() {
        val storage = InMemoryStorage()
        guardRestoredInstall(storage, "100")
        val core = makeCore(storage = storage)
        core.track("kept")
        val deviceId = core.getDeviceId()

        assertEquals(InstallState.SAME_INSTALL, guardRestoredInstall(storage, "100"))
        val relaunched = makeCore(storage = storage)
        assertEquals(deviceId, relaunched.getDeviceId())
        assertEquals(1, relaunched.pendingEventCount())
    }

    @Test
    fun `a restored install gets a new device id and an empty queue`() {
        val storage = InMemoryStorage()
        guardRestoredInstall(storage, "100")
        val old = makeCore(storage = storage)
        old.identify("user-1")
        old.registerSuperProperties(mapOf("plan" to "pro"))
        old.track("unsent")
        val oldDeviceId = old.getDeviceId()

        assertEquals(InstallState.RESTORED, guardRestoredInstall(storage, "200"))

        val restored = makeCore(storage = storage)
        assertNotEquals(oldDeviceId, restored.getDeviceId())
        assertNotEquals("user-1", restored.getDistinctId())
        assertEquals(true, restored.isAnonymous())
        assertEquals(0, restored.pendingEventCount())
        assertNull(storage.get("cohorly.super_properties"))
        assertNull(storage.get("cohorly.flags"))
    }

    @Test
    fun `opt-out survives a restore`() {
        val storage = InMemoryStorage()
        guardRestoredInstall(storage, "100")
        makeCore(storage = storage).optOutTracking()

        assertEquals(InstallState.RESTORED, guardRestoredInstall(storage, "200"))

        assertEquals(true, makeCore(storage = storage).hasOptedOutTracking())
    }

    @Test
    fun `automatic-event bookkeeping is cleared so first-open fires again`() {
        val storage = InMemoryStorage()
        guardRestoredInstall(storage, "100")
        makeCore(storage = storage, config = CohorlyConfig(flushIntervalMs = 0, trackAutomaticEvents = true))
            .onAppForegrounded()
        assertEquals("1", storage.get("cohorly.ae.did_first_open"))

        assertEquals(InstallState.RESTORED, guardRestoredInstall(storage, "200"))

        assertNull(storage.get("cohorly.ae.did_first_open"))
        assertNull(storage.get("cohorly.ae.last_version"))
    }

    @Test
    fun `unstamped state is adopted, never wiped`() {
        // The SharedPreferences migration writes state that predates stamping.
        // Wiping it would cost every upgrading user their device id.
        val storage = InMemoryStorage()
        val core = makeCore(storage = storage)
        core.track("migrated")
        val deviceId = core.getDeviceId()

        assertEquals(InstallState.ADOPTED, guardRestoredInstall(storage, "100"))

        val relaunched = makeCore(storage = storage)
        assertEquals(deviceId, relaunched.getDeviceId())
        assertEquals(1, relaunched.pendingEventCount())
        assertEquals(InstallState.SAME_INSTALL, guardRestoredInstall(storage, "100"))
    }

    @Test
    fun `INSTALL_STAMP_KEY lets the platform stamp in the same write`() {
        val storage = InMemoryStorage()
        val core = makeCore(storage = storage)
        core.track("migrated")
        val deviceId = core.getDeviceId()
        storage.set(INSTALL_STAMP_KEY, "100")

        assertEquals(InstallState.SAME_INSTALL, guardRestoredInstall(storage, "100"))
        assertEquals(deviceId, makeCore(storage = storage).getDeviceId())
    }
}
