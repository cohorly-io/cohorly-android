package com.cohorly.android.replay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A window copy the test controls: a solid color, answered at once or when the test says. */
private class FakePixels : WindowPixelSource {
    var color = Color.RED
    var deferred = false
    var failing = false
    var copies = 0
    private val pending = mutableListOf<() -> Unit>()

    override fun copy(root: View, dest: Bitmap, done: (Boolean) -> Unit) {
        copies++
        val answer = {
            dest.eraseColor(color)
            done(!failing)
        }
        if (deferred) pending.add(answer) else answer()
    }

    fun answerAll() {
        val answers = pending.toList()
        pending.clear()
        answers.forEach { it() }
    }
}

/** The recorder in screenshot mode (VEL-2141). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Real bitmaps and WebP encoding.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotRecorderTest {
    private val app = RuntimeEnvironment.getApplication()
    private val worker = TestExecutor()
    private val session = FakeSession()
    private val roots = FakeRootViews()
    private val pixels = FakePixels()
    private var now = 1_700_000_000_000L
    private lateinit var activity: Activity
    private lateinit var content: LinearLayout
    private lateinit var capturer: ScreenshotCapturer
    private var recorder: ReplayRecorder? = null

    private fun startAndInstall(more: (LinearLayout) -> Unit = {}) {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(activity).apply { text = "Hello" })
        more(content)
        activity.setContentView(content)
        idleMain()
        roots.add(activity.root)
        val mainHandler = Handler(Looper.getMainLooper())
        capturer = ScreenshotCapturer(
            maskAllImages = true,
            quality = 30,
            source = pixels,
            worker = worker,
            mainHandler = mainHandler,
            hasPendingDraw = { false },
        )
        recorder = ReplayRecorder(
            application = app,
            session = session,
            maskAllImages = true,
            worker = worker,
            mainHandler = mainHandler,
            clock = { now },
            uptimeMillis = { 0L },
            rootViewSource = roots,
            throttleDelayMs = 0,
            screenshots = capturer,
        ).also { it.install() }
        idleMain()
    }

    private fun capture() {
        now += 1_000
        recorder!!.capture()
        idleMain()
    }

    private fun shipChunk() = worker.advance(10_000)

    private fun types(events: List<JSONObject>) = events.map { it.getInt("type") }

    /** The image of a `screenshot` wireframe, decoded from its WebP. */
    private fun decode(wireframe: JSONObject): Bitmap {
        val data = wireframe.getString("base64").removePrefix("data:image/webp;base64,")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not an image" }
    }

    /** True when [actual] is [expected] give or take the lossy encoding. */
    private fun near(expected: Int, actual: Int, tolerance: Int = 40): Boolean =
        Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    @After
    fun tearDown() {
        recorder?.uninstall()
        idleMain()
    }

    @Test
    fun `the first capture is Meta then a full snapshot of one WebP screenshot, the next a Mutation of it`() {
        startAndInstall()
        shipChunk()

        val first = session.events()
        assertEquals(listOf(4, 2), types(first))
        val meta = first[0].getJSONObject("data")
        val screenshots = first[1].getJSONObject("data").getJSONArray("wireframes")
        assertEquals(1, screenshots.length())
        val screenshot = screenshots.getJSONObject(0)
        assertEquals("screenshot", screenshot.getString("type"))
        assertTrue(screenshot.getString("base64").startsWith("data:image/webp;base64,"))
        assertEquals(0, screenshot.getInt("x"))
        assertEquals(0, screenshot.getInt("y"))
        assertEquals(meta.getInt("width"), screenshot.getInt("width"))
        assertEquals(meta.getInt("height"), screenshot.getInt("height"))
        assertTrue(screenshot.getInt("id") >= 10_000_000)
        assertFalse(session.json().contains("Hello"))

        pixels.color = Color.BLUE
        capture()
        shipChunk()

        val events = session.events()
        assertEquals(listOf(4, 2, 3), types(events))
        val mutation = events[2].getJSONObject("data")
        assertEquals(0, mutation.getInt("source"))
        assertFalse(mutation.has("adds"))
        assertFalse(mutation.has("removes"))
        val updates = mutation.getJSONArray("updates")
        assertEquals(1, updates.length())
        val updated = updates.getJSONObject(0).getJSONObject("wireframe")
        assertEquals(screenshot.getInt("id"), updated.getInt("id"))
        assertTrue(updated.getString("base64").startsWith("data:image/webp;base64,"))
        assertFalse(updated.getString("base64") == screenshot.getString("base64"))
    }

    @Test
    fun `the payload's image has the text field covered and the rest of the window as copied`() {
        lateinit var field: EditText
        startAndInstall { content ->
            field = EditText(activity).apply { setText("my typed secret") }
            content.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120))
        }
        shipChunk()

        val screenshot = wireframes(session.events()).single { it.getString("type") == "screenshot" }
        val image = decode(screenshot)
        // The image is one px per dp of the screen.
        val pxPerImagePx = activity.root.width.toFloat() / image.width
        val onScreen = IntArray(2).also { field.getLocationOnScreen(it) }
        val rect = Rect(
            (onScreen[0] / pxPerImagePx).toInt(),
            (onScreen[1] / pxPerImagePx).toInt(),
            ((onScreen[0] + field.width) / pxPerImagePx).toInt(),
            ((onScreen[1] + field.height) / pxPerImagePx).toInt(),
        )
        // Inside, away from the edges the lossy encoding blurs.
        val inner = Rect(rect).apply { inset(4, 4) }
        assertFalse("the field is on the image", inner.isEmpty)
        for (y in inner.top until inner.bottom step 2) {
            for (x in inner.left until inner.right step 2) {
                val pixel = image.getPixel(x, y)
                assertTrue(
                    "the field at ($x, $y) is ${Integer.toHexString(pixel)}",
                    near(ScreenshotCapturer.MASK_COLOR, pixel),
                )
            }
        }
        // Below the field, the window's own color.
        val below = image.getPixel(image.width / 2, minOf(image.height - 3, rect.bottom + 10))
        assertTrue("below the field is ${Integer.toHexString(below)}", near(Color.RED, below))
    }

    @Test
    fun `the same image twice sends nothing`() {
        startAndInstall()
        shipChunk()
        val sent = session.events().size
        val copies = pixels.copies

        capture()
        capture()
        shipChunk()

        assertEquals("both captures ran", copies + 2, pixels.copies)
        assertEquals(sent, session.events().size)
    }

    @Test
    fun `a still screen whose frames keep failing is retried at most three times`() {
        startAndInstall()
        shipChunk()
        pixels.failing = true
        val copies = pixels.copies

        capture()
        idleMain()
        idleMain()

        assertEquals(1 + ReplayRecorder.MAX_SCREENSHOT_RETRIES, pixels.copies - copies)
    }

    @Test
    fun `a frame whose masks moved while the copy ran is discarded, the next one goes out`() {
        startAndInstall()
        shipChunk()
        assertEquals(listOf(4, 2), types(session.events()))

        pixels.deferred = true
        pixels.color = Color.GREEN
        capture()
        // While the copy runs, a field appears and is drawn, then goes: the copy may hold
        // that draw's pixels, which the masks taken before and after do not cover.
        val field = EditText(activity).apply { setText("my typed secret") }
        content.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
        idleMain()
        capturer.onDraw(activity.root)
        content.removeView(field)
        idleMain()
        pixels.answerAll()
        idleMain()
        shipChunk()

        assertEquals(listOf(4, 2), types(session.events()))

        // The discarded frame is taken again; with no draw in between it goes out.
        pixels.answerAll()
        idleMain()
        shipChunk()

        assertEquals(listOf(4, 2, 3), types(session.events()))
    }
}
