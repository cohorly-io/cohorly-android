package com.cohorly.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessStateSummaryTest {
    private val values = ProcessStateSummary.Values(
        release = "com.example.shop@3.8.1+415",
        distinctId = "user-42",
        deviceId = TEST_DEVICE_ID,
        dist = "5f3c2a10-8e4b-4c7d-9a61-0b2e7d4f9c13",
    )

    private fun roundTrip(v: ProcessStateSummary.Values) = ProcessStateSummary.decode(ProcessStateSummary.encode(v))

    @Test
    fun `values round-trip`() {
        assertEquals(values, roundTrip(values))
        assertEquals(
            "v1|com.example.shop@3.8.1+415|user-42|$TEST_DEVICE_ID|5f3c2a10-8e4b-4c7d-9a61-0b2e7d4f9c13",
            String(ProcessStateSummary.encode(values)),
        )
    }

    @Test
    fun `separators inside values survive`() {
        val odd = ProcessStateSummary.Values("r|1%2", "a|b", "%7C", null)

        assertEquals(odd, roundTrip(odd))
    }

    @Test
    fun `it never exceeds 128 bytes and drops dist, then distinctId, keeping release and deviceId`() {
        val longDistinct = values.copy(distinctId = "u".repeat(70))
        val decoded = roundTrip(longDistinct)!!

        assertTrue(ProcessStateSummary.encode(longDistinct).size <= ProcessStateSummary.MAX_BYTES)
        assertNull(decoded.dist)
        assertNull(decoded.distinctId)
        assertEquals(values.release, decoded.release)
        assertEquals(values.deviceId, decoded.deviceId)

        val distOnly = roundTrip(values.copy(dist = "d".repeat(80)))!!
        assertNull(distOnly.dist)
        assertEquals("user-42", distOnly.distinctId)
    }

    @Test
    fun `a release too long to fit is dropped rather than truncated`() {
        val huge = values.copy(release = "r".repeat(200))

        val decoded = roundTrip(huge)!!

        assertTrue(ProcessStateSummary.encode(huge).size <= ProcessStateSummary.MAX_BYTES)
        assertNull(decoded.release)
        assertEquals(values.deviceId, decoded.deviceId)
    }

    @Test
    fun `multi-byte text is measured in bytes`() {
        val wide = values.copy(distinctId = "é".repeat(40))

        assertTrue(ProcessStateSummary.encode(wide).size <= ProcessStateSummary.MAX_BYTES)
    }

    @Test
    fun `absent or foreign bytes decode to nothing`() {
        assertNull(ProcessStateSummary.decode(null))
        assertNull(ProcessStateSummary.decode(ByteArray(0)))
        assertNull(ProcessStateSummary.decode("v2|a|b|c|d".toByteArray()))
        assertNull(ProcessStateSummary.decode("hello".toByteArray()))
    }
}
