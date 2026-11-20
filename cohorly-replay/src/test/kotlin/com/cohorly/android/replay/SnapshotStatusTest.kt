package com.cohorly.android.replay

import com.cohorly.android.replay.internal.ScreenCapture
import com.cohorly.android.replay.internal.ViewTreeSnapshotStatus
import com.cohorly.android.replay.rr.RRWireframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for the platform's org.json.
@RunWith(RobolectricTestRunner::class)
class SnapshotStatusTest {
    private val status = ViewTreeSnapshotStatus()

    /** A row of a vertical list: each id on its own 20 dp row, so no two labels overlap unless [y] says so. */
    private fun label(id: Int, text: String, parentId: Int, y: Int = (id % 100) * 20) =
        RRWireframe(id = id, x = 0, y = y, width = 100, height = 20, type = "text", text = text, parentId = parentId)

    private fun screen(vararg children: RRWireframe) = RRWireframe(
        id = 10_000_000,
        x = 0,
        y = 0,
        width = 360,
        height = 640,
        type = "div",
        childWireframes = children.toList(),
        parentId = RRWireframe.SCREEN_ROOT_ID,
    )

    private fun capture(
        root: RRWireframe,
        sessionId: String = "s1",
        href: String = "MainActivity",
        forceFull: Boolean = false,
    ) = ScreenCapture(
        sessionId = sessionId,
        timestamp = 1_000,
        width = 360,
        height = 640,
        href = href,
        screenKey = listOf(root.id, href, 360, 640),
        wireframes = listOf(root),
        forceFull = forceFull,
    )

    @Test
    fun `the first capture is Meta then FullSnapshot`() {
        val events = status.snapshotEvents(capture(screen(label(10_000_001, "Hi", 10_000_000))))

        assertEquals(listOf(4, 2), events.map { it.getInt("type") })
        val meta = events[0].getJSONObject("data")
        assertEquals("MainActivity", meta.getString("href"))
        assertEquals(360, meta.getInt("width"))
        assertEquals(640, meta.getInt("height"))
        val data = events[1].getJSONObject("data")
        assertEquals(0, data.getJSONObject("initialOffset").getInt("top"))
        assertEquals(10_000_000, data.getJSONArray("wireframes").getJSONObject(0).getInt("id"))
        assertTrue(status.hasFullSnapshot("s1"))
    }

    @Test
    fun `later captures are one Mutation with the diff by id, and nothing when nothing changed`() {
        status.snapshotEvents(capture(screen(label(10_000_001, "A", 10_000_000), label(10_000_002, "B", 10_000_000))))

        assertEquals(
            emptyList<Any>(),
            status.snapshotEvents(
                capture(screen(label(10_000_001, "A", 10_000_000), label(10_000_002, "B", 10_000_000))),
            ),
        )

        val events = status.snapshotEvents(
            capture(screen(label(10_000_001, "A changed", 10_000_000), label(10_000_003, "C", 10_000_000))),
        )
        assertEquals(1, events.size)
        assertEquals(3, events[0].getInt("type"))
        val data = events[0].getJSONObject("data")
        assertEquals(0, data.getInt("source"))
        val add = data.getJSONArray("adds").getJSONObject(0)
        assertEquals(10_000_000, add.getInt("parentId"))
        assertEquals(10_000_003, add.getJSONObject("wireframe").getInt("id"))
        val update = data.getJSONArray("updates").getJSONObject(0)
        assertEquals("A changed", update.getJSONObject("wireframe").getString("text"))
        assertEquals(10_000_000, update.getInt("parentId"))
        assertEquals(1, data.getJSONArray("updates").length())
        val remove = data.getJSONArray("removes").getJSONObject(0)
        assertEquals(10_000_002, remove.getInt("id"))
        assertEquals(10_000_000, remove.getInt("parentId"))
    }

    @Test
    fun `a new screen structure, a new session or a forced capture is a full snapshot again`() {
        val root = screen(label(10_000_001, "A", 10_000_000))
        status.snapshotEvents(capture(root))

        assertEquals(
            listOf(4, 2),
            status.snapshotEvents(capture(root, href = "SecondActivity")).map {
                it.getInt("type")
            },
        )
        assertEquals(
            listOf(4, 2),
            status.snapshotEvents(capture(root, href = "SecondActivity", sessionId = "s2")).map {
                it.getInt("type")
            },
        )
        assertFalse(status.hasFullSnapshot("s1"))
        assertEquals(
            listOf(4, 2),
            status.snapshotEvents(capture(root, href = "SecondActivity", sessionId = "s2", forceFull = true)).map {
                it.getInt("type")
            },
        )
    }

    private fun group(id: Int, parentId: Int, vararg children: RRWireframe) = RRWireframe(
        id = id,
        x = 0,
        y = 0,
        width = 360,
        height = 100,
        type = "div",
        childWireframes = children.toList(),
        parentId = parentId,
    )

    @Test
    fun `children in another order are a full snapshot, not a Mutation`() {
        status.snapshotEvents(capture(screen(label(10_000_001, "A", 10_000_000), label(10_000_002, "B", 10_000_000))))

        val events = status.snapshotEvents(
            capture(screen(label(10_000_002, "B", 10_000_000), label(10_000_001, "A", 10_000_000))),
        )

        assertEquals(listOf(4, 2), events.map { it.getInt("type") })
    }

    @Test
    fun `a view moved to another parent is a full snapshot, not a Mutation`() {
        val a = 10_000_010
        val b = 10_000_020
        status.snapshotEvents(capture(screen(group(a, 10_000_000, label(10_000_001, "Item", a)), group(b, 10_000_000))))

        val events = status.snapshotEvents(
            capture(screen(group(a, 10_000_000), group(b, 10_000_000, label(10_000_001, "Item", b)))),
        )

        assertEquals(listOf(4, 2), events.map { it.getInt("type") })
    }

    @Test
    fun `adding and removing children keeps the order of the rest, still a Mutation`() {
        status.snapshotEvents(capture(screen(label(10_000_001, "A", 10_000_000), label(10_000_002, "B", 10_000_000))))

        val events = status.snapshotEvents(
            capture(screen(label(10_000_003, "New", 10_000_000), label(10_000_002, "B", 10_000_000))),
        )

        assertEquals(listOf(3), events.map { it.getInt("type") })
    }

    private val root = 10_000_000

    /** A banner (id 1) under a badge (id 2) drawn over its corner, then a row (id 3) below both. */
    private fun stacked(banner: String = "Banner", badgeX: Int = 80, extra: RRWireframe? = null) = screen(
        *listOfNotNull(
            label(10_000_001, banner, root, y = 0),
            extra,
            RRWireframe(id = 10_000_002, x = badgeX, y = 0, width = 20, height = 20, type = "div", parentId = root),
            label(10_000_003, "Row", root, y = 100),
        ).toTypedArray(),
    )

    @Test
    fun `an update of a view a later sibling overlaps is a full snapshot, the player would draw it on top`() {
        status.snapshotEvents(capture(stacked()))

        val events = status.snapshotEvents(capture(stacked(banner = "Banner changed")))

        assertEquals(listOf(4, 2), events.map { it.getInt("type") })
    }

    @Test
    fun `an update of a view no later sibling overlaps stays a Mutation`() {
        status.snapshotEvents(capture(stacked(badgeX = 200)))

        val events = status.snapshotEvents(capture(stacked(banner = "Banner changed", badgeX = 200)))

        assertEquals(listOf(3), events.map { it.getInt("type") })
    }

    @Test
    fun `a view added before a sibling it overlaps is a full snapshot, added last it is a Mutation`() {
        status.snapshotEvents(capture(stacked()))
        val under = RRWireframe(id = 10_000_050, x = 70, y = 0, width = 20, height = 20, type = "div", parentId = root)

        assertEquals(listOf(4, 2), status.snapshotEvents(capture(stacked(extra = under))).map { it.getInt("type") })

        status.reset()
        status.snapshotEvents(capture(stacked()))
        val last = screen(*stacked().childWireframes!!.toTypedArray(), under.copy(y = 100))
        val events = status.snapshotEvents(capture(last))
        assertEquals(listOf(3), events.map { it.getInt("type") })
        val add = events[0].getJSONObject("data").getJSONArray("adds").getJSONObject(0)
        assertEquals(10_000_050, add.getJSONObject("wireframe").getInt("id"))
    }

    @Test
    fun `a touch lands on the deepest wireframe of the last snapshot under it, the top window and last child first`() {
        assertEquals(0, status.hitTest(10, 10))
        val bottomWindow = screen(
            RRWireframe(id = 10_000_001, x = 0, y = 0, width = 100, height = 50, type = "text", parentId = 10_000_000),
            // Drawn after (above) the first one, overlapping it.
            RRWireframe(id = 10_000_002, x = 50, y = 0, width = 100, height = 50, type = "text", parentId = 10_000_000),
        )
        val dialog = RRWireframe(id = 10_000_100, x = 0, y = 300, width = 200, height = 100, type = "div")
        status.snapshotEvents(
            ScreenCapture("s1", 1_000, 360, 640, "Main", listOf(1), listOf(bottomWindow, dialog), forceFull = false),
        )

        assertEquals(10_000_001, status.hitTest(10, 10))
        assertEquals(10_000_002, status.hitTest(60, 10))
        assertEquals(10_000_000, status.hitTest(10, 200))
        assertEquals(10_000_100, status.hitTest(10, 350))
        assertEquals(0, status.hitTest(400, 700))
    }
}
