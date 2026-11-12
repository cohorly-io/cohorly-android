package com.cohorly.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FileCrashKeyValueStoreTest {
    @Test
    fun `what one launch writes, the next one reads`() {
        val file = File(tempDir(), "cohorly-crash/state.json")
        FileCrashKeyValueStore(file).apply {
            putString(CrashConfig.KEY_CONFIG, """{"enabled":true}""")
            putLong(CrashConfig.KEY_EXIT_WATERMARK, 42L)
        }

        val next = FileCrashKeyValueStore(file)

        assertEquals("""{"enabled":true}""", next.getString(CrashConfig.KEY_CONFIG))
        assertEquals(42L, next.getLong(CrashConfig.KEY_EXIT_WATERMARK))
    }

    @Test
    fun `a missing or unreadable file reads as empty, so reporting stays off`() {
        val file = File(tempDir(), "state.json")
        assertNull(FileCrashKeyValueStore(file).getString(CrashConfig.KEY_CONFIG))

        file.writeText("{not json")

        assertNull(FileCrashKeyValueStore(file).getString(CrashConfig.KEY_CONFIG))
        assertNull(CrashConfig.readEnabled(FileCrashKeyValueStore(file)))
    }
}
