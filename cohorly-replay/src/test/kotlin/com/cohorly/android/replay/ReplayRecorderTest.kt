package com.cohorly.android.replay

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONArray
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** A second Activity class, so the screen name tells the two apart. */
class OtherActivity : Activity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayRecorderTest {
    private val app = RuntimeEnvironment.getApplication()
    private val worker = TestExecutor()
    private val session = FakeSession()
    private val roots = FakeRootViews()
    private var now = 1_700_000_000_000L
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var content: LinearLayout
    private var recorder: ReplayRecorder? = null

    private fun startActivity() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(content)
        idleMain()
    }

    private fun <T : View> add(view: T): T {
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
        idleMain()
        return view
    }

    private fun install(maskAllImages: Boolean = true): ReplayRecorder {
        // No throttle delay: each requested capture runs on the next main loop.
        val created = ReplayRecorder(
            application = app,
            session = session,
            maskAllImages = maskAllImages,
            worker = worker,
            clock = { now },
            uptimeMillis = { 0L },
            rootViewSource = roots,
            throttleDelayMs = 0,
        )
        created.install()
        idleMain()
        recorder = created
        return created
    }

    private fun capture() {
        now += 1_000
        recorder!!.capture()
        idleMain()
    }

    /** Lets the 10 s chunk timer fire. */
    private fun shipChunk() {
        worker.advance(10_000)
    }

    private fun types(events: List<JSONObject>) = events.map { it.getInt("type") }

    @After
    fun tearDown() {
        recorder?.uninstall()
        idleMain()
    }

    @Test
    fun `the first chunk of a session starts with Meta then FullSnapshot of the top Activity`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        install()

        shipChunk()

        assertEquals(1, session.enqueued.size)
        val (id, events) = session.enqueued[0]
        assertEquals("session-1", id)
        assertEquals(listOf(4, 2), types(events).take(2))
        val meta = events[0].getJSONObject("data")
        assertEquals("Activity", meta.getString("href"))
        assertTrue(meta.getInt("width") > 0 && meta.getInt("height") > 0)
        val top = events[1].getJSONObject("data").getJSONArray("wireframes").getJSONObject(0)
        assertTrue(top.getInt("id") >= 10_000_000)
        assertTrue(session.json().contains("Hello"))
    }

    @Test
    fun `a change is a Mutation, an unchanged screen sends nothing`() {
        startActivity()
        roots.add(activity.root)
        val label = add(TextView(activity).apply { text = "Total 10" })
        install()

        capture()
        capture()
        label.text = "Total 20"
        idleMain()
        capture()
        shipChunk()

        val events = session.events()
        assertEquals(listOf(4, 2, 3), types(events))
        val mutation = events[2].getJSONObject("data")
        assertEquals(0, mutation.getInt("source"))
        val update = mutation.getJSONArray("updates").getJSONObject(0)
        val updated = update.getJSONObject("wireframe")
        assertEquals("Total 20", updated.getString("text"))
        // The update names the label's parent (the content LinearLayout), which the player removes it from.
        val parent = wireframes(events.take(2)).single { w ->
            w.children().any { it.getInt("id") == updated.getInt("id") }
        }
        assertEquals(parent.getInt("id"), update.getInt("parentId"))
    }

    @Test
    fun `a dialog is its own window, on top of the Activity in the same snapshot`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Files" })
        install()

        val dialog = AlertDialog.Builder(activity).setTitle("Delete?").setMessage("This removes the file").show()
        idleMain()
        roots.add(dialog.window!!.decorView)
        idleMain()
        shipChunk()

        val events = session.events()
        // The window set changed: a second Meta + FullSnapshot.
        assertEquals(listOf(4, 2, 4, 2), types(events))
        val windows = events[3].getJSONObject("data").getJSONArray("wireframes")
        assertEquals(2, windows.length())
        val dialogWireframes =
            wireframes(
                listOf(
                    JSONObject().put(
                        "data",
                        JSONObject().put("wireframes", org.json.JSONArray().put(windows.getJSONObject(1))),
                    ),
                ),
            )
        assertTrue(dialogWireframes.any { it.optString("text") == "This removes the file" })
        assertTrue(dialogWireframes.any { it.optString("text") == "Delete?" })
    }

    @Test
    fun `nothing typed in a field reaches a chunk`() {
        startActivity()
        roots.add(activity.root)
        val field = add(EditText(activity))
        install()

        field.setText("my private note")
        idleMain()
        capture()
        shipChunk()

        assertFalse(session.json().contains("my private note"))
        assertTrue(session.json().contains("***************"))
    }

    @Test
    fun `a FLAG_SECURE window is recorded as one placeholder`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Balance 1000" })
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        idleMain()
        install()

        shipChunk()

        val top = session.events()[1].getJSONObject("data").getJSONArray("wireframes").getJSONObject(0)
        assertEquals("placeholder", top.getString("type"))
        assertFalse(session.json().contains("Balance 1000"))
    }

    /** Touches the center of [view]; returns the point in screen px. */
    private fun touch(view: View, action: Int): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        activity.window.callback.dispatchTouchEvent(event)
        event.recycle()
        idleMain()
        return x to y
    }

    @Test
    fun `touches are recorded as touch start and end on the touched wireframe, and count as activity`() {
        startActivity()
        roots.add(activity.root)
        val button = add(Button(activity).apply { text = "Pay" })
        install()

        touch(button, MotionEvent.ACTION_DOWN)
        touch(button, MotionEvent.ACTION_UP)
        shipChunk()

        val buttonId = wireframes(session.events()).single { it.optString("value") == "Pay" }.getInt("id")
        val touches = session.events().filter {
            it.getInt("type") == 3 && it.getJSONObject("data").getInt("source") == 2
        }
        assertEquals(listOf(7, 9), touches.map { it.getJSONObject("data").getInt("type") })
        for (touch in touches) {
            val data = touch.getJSONObject("data")
            assertEquals(buttonId, data.getInt("id"))
            assertEquals(2, data.getInt("pointerType"))
            assertTrue(data.getInt("x") > 0 && data.getInt("y") > 0)
        }
        assertEquals(2, session.activity)
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `a touch is placed in dp on the wireframe the last snapshot has under it`() {
        startActivity()
        roots.add(activity.root)
        val button = add(Button(activity).apply { text = "Pay" })
        install()

        val (x, y) = touch(button, MotionEvent.ACTION_DOWN)
        shipChunk()

        val density = activity.resources.displayMetrics.density
        assertEquals(3f, density)
        val buttonId = wireframes(session.events()).single { it.optString("value") == "Pay" }.getInt("id")
        val data = touches(session.events()).single()
        assertEquals(buttonId, data.getInt("id"))
        assertEquals((x / density).toInt(), data.getInt("x"))
        assertEquals((y / density).toInt(), data.getInt("y"))
    }

    @Test
    fun `a touch that starts a new session follows that session's full snapshot`() {
        startActivity()
        roots.add(activity.root)
        val button = add(Button(activity).apply { text = "Pay" })
        install()
        shipChunk()
        // The touch is the first activity after 30 min idle: recording it rotates the session.
        var rotated = false
        session.onActivity = {
            if (!rotated) {
                rotated = true
                session.rotate("session-2")
            }
        }

        touch(button, MotionEvent.ACTION_DOWN)
        shipChunk()

        val (id, events) = session.enqueued.last()
        assertEquals("session-2", id)
        assertEquals(listOf(4, 2, 3), types(events))
        val buttonId = wireframes(events).single { it.optString("value") == "Pay" }.getInt("id")
        assertEquals(buttonId, touches(events).single().getInt("id"))
    }

    @Test
    fun `a touch on a window that turned FLAG_SECURE is activity only, never an event`() {
        startActivity()
        roots.add(activity.root)
        val button = add(Button(activity).apply { text = "Pay" })
        install()
        shipChunk()
        assertTrue(session.json().contains("Pay"))

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        touch(button, MotionEvent.ACTION_DOWN)
        touch(button, MotionEvent.ACTION_UP)
        shipChunk()

        assertTrue(touches(session.events()).isEmpty())
        assertEquals(2, session.activity)
    }

    @Test
    fun `a touch on a no-capture view keeps its position and lands on the placeholder`() {
        startActivity()
        roots.add(activity.root)
        val secret = add(
            TextView(activity).apply {
                tag = CohorlyReplay.NO_CAPTURE_TAG
                text = "PIN 1234"
            },
        )
        install()

        touch(secret, MotionEvent.ACTION_DOWN)
        shipChunk()

        val placeholderId = wireframes(session.events()).single { it.getString("type") == "placeholder" }.getInt("id")
        val touch = session.events().last().getJSONObject("data")
        assertEquals(placeholderId, touch.getInt("id"))
        assertFalse(session.json().contains("PIN 1234"))
    }

    @Test
    fun `only a session in session mode records`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        session.mode = RecorderMode.OFF
        install()
        capture()
        session.mode = RecorderMode.ERROR
        capture()
        shipChunk()
        assertEquals(0, session.attempts.size)

        // Recording again starts from a full snapshot.
        session.mode = RecorderMode.SESSION
        capture()
        shipChunk()
        assertEquals(listOf(4, 2), types(session.events()))
    }

    @Test
    fun `error mode without an error buffer from the host records nothing`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        // The React Native bridge's host: no createErrorBuffer, so error mode has nowhere to go.
        val host = object : ReplaySessionSource by session {
            override val errorReportsOn: Boolean get() = true
        }
        session.mode = RecorderMode.ERROR
        recorder = ReplayRecorder(
            application = app,
            session = host,
            maskAllImages = true,
            worker = worker,
            clock = { now },
            uptimeMillis = { 0L },
            rootViewSource = roots,
            throttleDelayMs = 0,
        ).also { it.install() }
        idleMain()
        capture()
        shipChunk()
        assertEquals(0, session.attempts.size)
    }

    @Test
    fun `checkout makes the next capture a full snapshot, taken now`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        install()
        shipChunk()
        assertEquals(listOf(4, 2), types(session.events()))

        recorder!!.checkout()
        idleMain()
        idleMain()
        shipChunk()

        assertEquals(2, session.enqueued.size)
        assertEquals(listOf(4, 2), types(session.enqueued[1].second))
    }

    @Test
    fun `flushChunks hands the buffer over before its timer, then runs the callback`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        install()
        assertEquals(0, session.enqueued.size)

        var enqueuedWhenCalled = -1
        recorder!!.flushChunks { enqueuedWhenCalled = session.enqueued.size }

        assertEquals(1, enqueuedWhenCalled)
        assertEquals(listOf(4, 2), types(session.events()))
    }

    @Test
    fun `a rotation delivers the old buffer under the old id, then the new session starts with a full snapshot`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Hello" })
        install()

        session.rotate("session-2")
        idleMain()
        shipChunk()

        // The old buffer is queued under its own id (the controller takes the session it just ended).
        assertEquals(listOf("session-1", "session-2"), session.enqueued.map { it.first })
        assertEquals(listOf(4, 2), types(session.enqueued[0].second))
        assertTrue(JSONArray(session.enqueued[0].second).toString().contains("Hello"))
        assertEquals(listOf(4, 2), types(session.enqueued[1].second))
    }

    @Test
    fun `a rotation seen by a capture before its listener ships the old buffer, then a full snapshot`() {
        startActivity()
        roots.add(activity.root)
        val label = add(TextView(activity).apply { text = "Total 10" })
        install()

        session.rotateSilently("session-2")
        label.text = "Total 20"
        idleMain()
        capture()
        shipChunk()

        assertEquals(listOf("session-1", "session-2"), session.enqueued.map { it.first })
        assertEquals(listOf(4, 2), types(session.enqueued[0].second))
        assertEquals(listOf(4, 2), types(session.enqueued[1].second))
        assertTrue(JSONArray(session.enqueued[1].second).toString().contains("Total 20"))
    }

    @Test
    fun `a refused chunk makes the next capture full, even with no draw in between`() {
        startActivity()
        roots.add(activity.root)
        val label = add(TextView(activity).apply { text = "Total 10" })
        install()

        // The quota pause starts: the chunk with the full snapshot is refused.
        session.refuseAll = true
        shipChunk()
        session.refuseAll = false
        label.text = "Total 20"
        idleMain()
        capture()
        shipChunk()

        assertEquals(1, session.enqueued.size)
        assertEquals(listOf(4, 2), types(session.enqueued[0].second).take(2))
        assertTrue(JSONArray(session.enqueued[0].second).toString().contains("Total 20"))
    }

    @Test
    fun `a chunk the queue drops after taking it makes the next changed capture full`() {
        startActivity()
        roots.add(activity.root)
        val label = add(TextView(activity).apply { text = "Total 10" })
        install()
        shipChunk()
        assertEquals(listOf(4, 2), types(session.enqueued.single().second).take(2))

        // The uploader dropped that chunk (a quota refusal, a 4xx): the server never saw the snapshot.
        session.droppedChunks += 1
        label.text = "Total 20"
        idleMain()
        capture()
        shipChunk()

        assertEquals(2, session.enqueued.size)
        assertEquals(listOf(4, 2), types(session.enqueued[1].second).take(2))
        assertTrue(JSONArray(session.enqueued[1].second).toString().contains("Total 20"))
    }

    @Test
    fun `nothing is captured in background, a pending capture included`() {
        install()
        startActivity()
        roots.add(activity.root)
        idleMain()
        val label = add(TextView(activity).apply { text = "Total 10" })
        capture()

        controller.pause().stop()
        idleMain()
        assertEquals(1, session.attempts.size)

        label.text = "Total 20"
        capture()
        shipChunk()
        assertEquals(1, session.attempts.size)
        assertFalse(session.json().contains("Total 20"))
    }

    @Test
    fun `windows are stacked by type and the screen is named after the highest application window's Activity`() {
        startActivity()
        add(TextView(activity).apply { text = "Screen A" })
        val other = Robolectric.buildActivity(OtherActivity::class.java).setup().get()
        other.setContentView(TextView(other).apply { text = "Screen B" })
        val popup = PopupWindow(TextView(activity).apply { text = "Popup of A" }, 200, 100)
        popup.showAtLocation(activity.root, Gravity.NO_GRAVITY, 0, 0)
        idleMain()
        // The order windows were added in, popup first: not the order they are stacked in.
        roots.roots.addAll(listOf(popup.contentView.rootView, activity.root, other.window.decorView))
        install()

        shipChunk()
        popup.dismiss()

        val events = session.events()
        assertEquals("OtherActivity", events[0].getJSONObject("data").getString("href"))
        val windows = events[1].getJSONObject("data").getJSONArray("wireframes")
        assertEquals(3, windows.length())
        assertTrue(windows.getJSONObject(0).toString().contains("Screen A"))
        assertTrue(windows.getJSONObject(1).toString().contains("Screen B"))
        assertTrue(windows.getJSONObject(2).toString().contains("Popup of A"))
    }

    @Test
    fun `every Meta is named with the Activity's simple class name`() {
        startActivity()
        roots.add(activity.root)
        add(TextView(activity).apply { text = "Files" })
        install()

        val dialog = AlertDialog.Builder(activity).setTitle("Delete?").show()
        idleMain()
        roots.add(dialog.window!!.decorView)
        idleMain()
        controller.pause().stop()
        idleMain()
        controller.restart().resume()
        idleMain()
        shipChunk()

        val hrefs = session.events().filter {
            it.getInt("type") == 4
        }.map { it.getJSONObject("data").getString("href") }
        assertTrue(hrefs.size >= 3)
        assertEquals(hrefs.map { "Activity" }, hrefs)
    }

    @Test
    fun `opt-out stops capturing, removes listeners and drops the buffer, opt-in records from a full snapshot`() {
        startActivity()
        roots.add(activity.root)
        val button = add(Button(activity).apply { text = "Pay" })
        install()

        session.rotate(null)
        idleMain()
        capture()
        touch(button, MotionEvent.ACTION_DOWN)
        shipChunk()

        assertEquals(0, session.attempts.size)
        assertTrue(roots.listeners.isEmpty())
        assertEquals(0, session.activity)

        session.rotate("session-2")
        idleMain()
        shipChunk()
        assertEquals(listOf("session-2"), session.enqueued.map { it.first })
        assertEquals(listOf(4, 2), types(session.enqueued[0].second))
    }

    @Test
    fun `going to background ships the buffer at once and the return sends a full snapshot`() {
        install()
        startActivity()
        roots.add(activity.root)
        idleMain()
        add(TextView(activity).apply { text = "Hello" })
        capture()

        controller.pause().stop()
        idleMain()
        assertEquals(1, session.enqueued.size)
        assertEquals(listOf(4, 2), types(session.enqueued[0].second).take(2))

        controller.restart().resume()
        idleMain()
        capture()
        shipChunk()
        assertEquals(2, session.enqueued.size)
        assertEquals(listOf(4, 2), types(session.enqueued[1].second).take(2))
    }
}
