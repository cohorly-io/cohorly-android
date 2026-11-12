package com.cohorly.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonFileStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "no_backup/cohorly_tok.json")

    @Test
    fun `values survive a relaunch`() {
        JsonFileStorage(file()).set("cohorly.device_id", "dev-1")

        assertEquals("dev-1", JsonFileStorage(file()).get("cohorly.device_id"))
    }

    @Test
    fun `null removes the key`() {
        val storage = JsonFileStorage(file())
        storage.set("k", "v")
        storage.set("k", null)

        assertNull(storage.get("k"))
        assertNull(JsonFileStorage(file()).get("k"))
    }

    @Test
    fun `creates the parent directory on first write`() {
        assertFalse(File(tmp.root, "no_backup").exists())
        JsonFileStorage(file()).set("k", "v")

        assertTrue(file().isFile)
    }

    @Test
    fun `a corrupt file reads as empty instead of throwing`() {
        file().parentFile.mkdirs()
        file().writeText("{not json")

        val storage = JsonFileStorage(file())
        assertTrue(storage.isEmpty())
        // And the next write repairs it.
        storage.set("k", "v")
        assertEquals("v", JsonFileStorage(file()).get("k"))
    }

    @Test
    fun `putAll writes every entry at once`() {
        JsonFileStorage(file()).putAll(mapOf("a" to "1", "b" to "2"))

        val reopened = JsonFileStorage(file())
        assertEquals("1", reopened.get("a"))
        assertEquals("2", reopened.get("b"))
    }

    @Test
    fun `isEmpty distinguishes a fresh file from one carrying state`() {
        assertTrue(JsonFileStorage(file()).isEmpty())
        JsonFileStorage(file()).set("k", "v")
        assertFalse(JsonFileStorage(file()).isEmpty())
    }

    @Test
    fun `batch writes once, at the end`() {
        val storage = JsonFileStorage(file())
        storage.set("seed", "0")
        val writesBefore = storage.writeCount

        storage.batch {
            storage.set("a", "1")
            storage.set("b", "2")
            storage.set("c", "3")
        }

        assertEquals(1, storage.writeCount - writesBefore)
        val reopened = JsonFileStorage(file())
        assertEquals("1", reopened.get("a"))
        assertEquals("3", reopened.get("c"))
    }

    @Test
    fun `a nested batch joins the outer one`() {
        val storage = JsonFileStorage(file())
        storage.set("seed", "0")
        val writesBefore = storage.writeCount

        storage.batch {
            storage.set("a", "1")
            storage.batch { storage.set("b", "2") }
            storage.set("c", "3")
        }

        assertEquals(1, storage.writeCount - writesBefore)
        assertEquals("2", JsonFileStorage(file()).get("b"))
    }

    @Test
    fun `a batch that changes nothing writes nothing`() {
        val storage = JsonFileStorage(file())
        storage.set("a", "1")
        val writesBefore = storage.writeCount

        storage.batch { storage.get("a") }

        assertEquals(0, storage.writeCount - writesBefore)
    }

    @Test
    fun `a throwing batch still persists what it changed`() {
        val storage = JsonFileStorage(file())
        runCatching {
            storage.batch {
                storage.set("a", "1")
                throw IllegalStateException("boom")
            }
        }
        assertEquals("1", JsonFileStorage(file()).get("a"))
    }

    @Test
    fun `persistNow reports the write and bypasses an open batch`() {
        val storage = JsonFileStorage(file())
        storage.batch {
            assertTrue(storage.persistNow(mapOf("migrated" to "yes")))
            // Already on disk, without waiting for the batch to close.
            assertEquals("yes", JsonFileStorage(file()).get("migrated"))
        }
    }

    @Test
    fun `persistNow reports false when the write cannot land`() {
        // A path whose parent is a FILE cannot be created as a directory, so
        // the write fails the way a read-only or full data dir would.
        val blocker = File(tmp.root, "blocked")
        blocker.writeText("not a directory")
        val storage = JsonFileStorage(File(blocker, "cohorly_tok.json"))

        assertFalse(storage.persistNow(mapOf("device_id" to "dev-1")))
        // In-memory state stays usable for this run.
        assertEquals("dev-1", storage.get("device_id"))
    }

    @Test
    fun `a core relaunched on the same file keeps its identity and queue`() {
        val first = makeCore(storage = JsonFileStorage(file()))
        first.track("persisted")
        val deviceId = first.getDeviceId()

        val second = makeCore(storage = JsonFileStorage(file()))
        assertEquals(deviceId, second.getDeviceId())
        assertEquals(1, second.pendingEventCount())
    }
}
