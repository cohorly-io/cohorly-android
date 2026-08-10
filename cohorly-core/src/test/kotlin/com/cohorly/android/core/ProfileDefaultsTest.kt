package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileDefaultsTest {
    private fun deviceWithProfileDefaults() = StubDeviceInfo(
        profileProps = mapOf(
            "\$android_lib_version" to "0.1.0",
            "\$android_os" to "Android",
            "\$android_os_version" to "14",
            "\$android_manufacturer" to "Google",
            "\$android_brand" to "google",
            "\$android_model" to "Pixel 8",
        ),
    )

    @Test
    fun `set merges default profile properties with user keys winning`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, deviceInfo = deviceWithProfileDefaults())

        core.people.set(mapOf("name" to "Ada", "\$android_model" to "Custom Device"))
        core.flush()

        val set = transport.engagePayloads().first { it.has("\$set") }.getJSONObject("\$set")
        assertEquals("Ada", set.getString("name"))
        assertEquals("Android", set.getString("\$android_os"))
        assertEquals("0.1.0", set.getString("\$android_lib_version"))
        // User-supplied key wins over the default.
        assertEquals("Custom Device", set.getString("\$android_model"))
    }

    @Test
    fun `setOnce also merges default profile properties`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, deviceInfo = deviceWithProfileDefaults())

        core.people.setOnce(mapOf("signup" to "2026-01-01"))
        core.flush()

        val setOnce = transport.engagePayloads().first { it.has("\$set_once") }
            .getJSONObject("\$set_once")
        assertEquals("2026-01-01", setOnce.getString("signup"))
        assertEquals("google", setOnce.getString("\$android_brand"))
    }

    @Test
    fun `increment does not carry profile defaults`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, deviceInfo = deviceWithProfileDefaults())

        core.people.increment(mapOf("logins" to 1))
        core.flush()

        val add = transport.engagePayloads().first { it.has("\$add") }.getJSONObject("\$add")
        assertEquals(1, add.getInt("logins"))
        assertFalse(add.has("\$android_os"))
    }

    @Test
    fun `empty provider adds nothing`() {
        val transport = MockTransport()
        val core = makeCore(transport = transport, deviceInfo = EmptyDeviceInfo())

        core.people.set(mapOf("name" to "Ada"))
        core.flush()

        val set = transport.engagePayloads().first { it.has("\$set") }.getJSONObject("\$set")
        assertEquals(1, set.length())
        assertEquals("Ada", set.getString("name"))
    }
}
