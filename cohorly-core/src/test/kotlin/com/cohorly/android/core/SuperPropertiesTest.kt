package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SuperPropertiesTest {
    @Test
    fun `register merges and persists across instances`() {
        val storage = InMemoryStorage()
        makeCore(storage = storage).registerSuperProperties(mapOf("plan" to "pro"))

        val transport = MockTransport()
        val core2 = makeCore(storage = storage, transport = transport)
        core2.track("evt")
        core2.flush()
        assertEquals(
            "pro",
            transport.trackedEvents()[0].getJSONObject("properties").getString("plan"),
        )
    }

    @Test
    fun `registerOnce does not overwrite existing keys`() {
        val core = makeCore()
        core.registerSuperProperties(mapOf("plan" to "pro"))
        core.registerSuperPropertiesOnce(mapOf("plan" to "free", "cohort" to "A"))
        val props = core.getSuperProperties()
        assertEquals("pro", props.getString("plan"))
        assertEquals("A", props.getString("cohort"))
    }

    @Test
    fun `unregister removes a key`() {
        val core = makeCore()
        core.registerSuperProperties(mapOf("a" to 1, "b" to 2))
        core.unregisterSuperProperty("a")
        val props = core.getSuperProperties()
        assertFalse(props.has("a"))
        assertEquals(2, props.getInt("b"))
    }

    @Test
    fun `clear removes everything`() {
        val core = makeCore()
        core.registerSuperProperties(mapOf("a" to 1))
        core.clearSuperProperties()
        assertEquals(0, core.getSuperProperties().length())
    }
}
