package com.cohorly.android.replay

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import com.cohorly.android.core.CohorlyTransport
import curtains.Curtains
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.zip.GZIPInputStream

/** A replay session the test drives: rotations and opt-out call the listeners on the calling thread. */
internal class FakeSession(var id: String? = "session-1", override var mode: RecorderMode = RecorderMode.SESSION) :
    ReplaySessionSource {
    val enqueued = mutableListOf<Pair<String, List<JSONObject>>>()
    var activity = 0
    private val listeners = mutableListOf<(String?) -> Unit>()

    /** The session the last rotation ended: like ReplayController, its late buffer is still taken. */
    var previousId: String? = null

    /** Refuses every chunk while true (a quota pause, a 404). */
    var refuseAll = false

    /** Chunks the queue dropped after taking them (a quota refusal, a 4xx). */
    @Volatile
    override var droppedChunks = 0L

    /** Runs inside [recordActivity] (a touch that rotates the session). */
    var onActivity: (() -> Unit)? = null

    override val sessionId: String? get() = id

    override fun addSessionListener(listener: (String?) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    override fun recordActivity() {
        activity++
        onActivity?.invoke()
    }

    /** Every chunk offered, queued or refused. */
    val attempts = mutableListOf<Pair<String, List<JSONObject>>>()

    override fun enqueue(events: List<JSONObject>, sessionId: String): Boolean {
        attempts.add(sessionId to events)
        if (refuseAll || events.isEmpty()) return false
        // Like ReplayController: a buffer of a session that is neither current nor the one just ended is refused.
        if (sessionId != id && (sessionId != previousId || id == null)) return false
        enqueued.add(sessionId to events)
        return true
    }

    fun rotate(newId: String?) {
        rotateSilently(newId)
        listeners.toList().forEach { it(newId) }
    }

    /** A rotation whose listeners have not run yet (they run on another thread on a device). */
    fun rotateSilently(newId: String?) {
        previousId = if (newId == null) null else id
        id = newId
    }

    /** Every event queued so far, in order. */
    fun events(): List<JSONObject> = enqueued.flatMap { it.second }

    fun json(): String = JSONArray(events()).toString()
}

/** Root views the test lists itself, bottom to top. */
internal class FakeRootViews(vararg roots: View) : RootViewSource {
    val roots = roots.toMutableList()
    val listeners = mutableListOf<(View, Boolean) -> Unit>()

    override fun rootViews(): List<View> = roots.toList()

    override fun addListener(listener: (view: View, added: Boolean) -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: (view: View, added: Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun add(view: View) {
        roots.add(view)
        listeners.toList().forEach { it(view, true) }
    }
}

/** A transport that never reaches a network. */
class NoNetworkTransport : CohorlyTransport {
    override fun post(url: String, body: String) {}

    override fun postBytes(url: String, body: ByteArray, headers: Map<String, String>) {}
}

/**
 * Runs the main looper, playing the part of the window manager Robolectric
 * leaves out: it never tells a window its app is visible, so every root view
 * reports `windowVisibility == GONE`, started Activity or not. On a device the
 * window manager sends `dispatchAppVisibility(true)` while the window's
 * Activity is started and `false` once it is stopped; this sends the same to
 * every window (Activity, Dialog, popup) by its Activity's state, then lets
 * the traversal it schedules run.
 */
fun idleMain() {
    followThisTestsWindows()
    val looper = shadowOf(Looper.getMainLooper())
    looper.idle()
    if (dispatchAppVisibility()) looper.idle()
}

/**
 * Curtains spies on one `WindowManagerGlobal`: it swaps its `mViews` for a
 * list that reports every window added or removed, once per process. On a
 * device there is one per process; Robolectric makes a new one for each test
 * while Curtains keeps the first, so from the second test on it sees no
 * window. This hands the spy list to the current test's instance (Curtains'
 * listeners stay; each test uninstalls the recorders it installed).
 */
private fun followThisTestsWindows() {
    val spy = Curtains::class.java.getDeclaredMethod("getRootViewsSpy").apply { isAccessible = true }
        .invoke(Curtains)
    val spyList = spy.javaClass.getDeclaredField("delegatingViewList").apply { isAccessible = true }.get(spy)
        as ArrayList<View>
    val global = windowManagerGlobal.getMethod("getInstance").invoke(null)
    val viewsField = windowManagerGlobal.getDeclaredField("mViews").apply { isAccessible = true }
    synchronized(global) {
        val views = viewsField.get(global)
        if (views === spyList) return
        // Bypasses the spy's add/remove, so no listener hears the previous test's windows go.
        val current = ArrayList(views as List<View>)
        spyList.clear()
        spyList.addAll(current)
        viewsField.set(global, spyList)
    }
}

private val viewRootImpl: Class<*> = Class.forName("android.view.ViewRootImpl")
private val windowManagerGlobal: Class<*> = Class.forName("android.view.WindowManagerGlobal")
private val rootsField = windowManagerGlobal.getDeclaredField("mRoots").apply { isAccessible = true }
private val viewField = viewRootImpl.getDeclaredField("mView").apply { isAccessible = true }
private val appVisibleField = viewRootImpl.getDeclaredField("mAppVisible").apply { isAccessible = true }
private val dispatchAppVisibilityMethod = viewRootImpl.getMethod("dispatchAppVisibility", Boolean::class.java)
private val activityStoppedField = Activity::class.java.getDeclaredField("mStopped").apply { isAccessible = true }

/** True when a window's app visibility changed (a traversal is scheduled). */
private fun dispatchAppVisibility(): Boolean {
    var changed = false
    // Read from WindowManagerGlobal itself: Curtains keeps the instance of the first test of the sandbox.
    val global = windowManagerGlobal.getMethod("getInstance").invoke(null)
    val viewRoots = synchronized(global) { (rootsField.get(global) as List<*>).toList() }
    for (viewRoot in viewRoots) {
        val root = viewField.get(viewRoot) as? View ?: continue
        val activity = root.phoneWindowOrNull()?.let { (it.callback as? Activity) ?: it.context.activity() }
            ?: root.context.activity()
        val visible = activity == null || !(activityStoppedField.get(activity) as Boolean)
        if (appVisibleField.get(viewRoot) as Boolean == visible) continue
        dispatchAppVisibilityMethod.invoke(viewRoot, visible)
        changed = true
    }
    return changed
}

private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

val Activity.root: View get() = window.decorView

/** Every wireframe (children included) of the full snapshots and mutations in [events]. */
fun wireframes(events: List<JSONObject>): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    fun walk(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val wireframe = array.getJSONObject(i)
            out.add(wireframe)
            walk(wireframe.optJSONArray("childWireframes"))
        }
    }
    for (event in events) {
        val data = event.optJSONObject("data") ?: continue
        walk(data.optJSONArray("wireframes"))
        for (key in listOf("adds", "updates")) {
            val list = data.optJSONArray(key) ?: continue
            walk(JSONArray((0 until list.length()).map { list.getJSONObject(it).getJSONObject("wireframe") }))
        }
    }
    return out
}

fun JSONObject.children(): List<JSONObject> {
    val array = optJSONArray("childWireframes") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

/** The touch events (type 3, source 2) in [events]. */
fun touches(events: List<JSONObject>): List<JSONObject> = events.filter {
    it.getInt("type") == 3 && it.getJSONObject("data").getInt("source") == 2
}.map { it.getJSONObject("data") }

/**
 * A transport that keeps every `POST /replay` body (decompressed) and can
 * go offline (each post fails like a network error, the chunk stays queued).
 */
class RecordingTransport : CohorlyTransport {
    @Volatile
    var offline = false
    private val posted = Collections.synchronizedList(mutableListOf<JSONObject>())

    override fun post(url: String, body: String) {}

    override fun postBytes(url: String, body: ByteArray, headers: Map<String, String>) {
        if (!url.endsWith("/replay")) return
        if (offline) throw IOException("offline")
        val json = GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
        posted.add(JSONObject(String(json, Charsets.UTF_8)))
    }

    /** Every chunk sent, oldest first. */
    fun chunks(): List<JSONObject> = synchronized(posted) { posted.toList() }
}

fun JSONObject.events(): List<JSONObject> {
    val array = getJSONArray("events")
    return (0 until array.length()).map { array.getJSONObject(it) }
}

/** The chunk files queued on disk for [token] (the real queue of a Cohorly instance). */
fun queuedChunkFiles(noBackupDir: File, token: String): List<String> =
    File(noBackupDir, "cohorly_replay_$token").list()?.filter { it.endsWith(".json.gz") }.orEmpty()

/**
 * Waits for the replay threads of a real Cohorly instance: runs the main
 * looper and polls [condition] until it holds or [timeoutMs] passes.
 */
fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        idleMain()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out: $message")
        Thread.sleep(20)
    }
}
