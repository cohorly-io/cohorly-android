package com.cohorly.android.replay

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cohorly.android.replay.rr.RRWireframe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A composition in the wireframe recording, from its semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeWireframesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ids = ViewIdRegistry()
    private lateinit var root: View

    @Before
    fun setUp() {
        compose.setContent { SensitiveScreen() }
        compose.waitForIdle()
        root = compose.activity.window.decorView
        idleMain()
    }

    private fun build(builder: WireframeBuilder = WireframeBuilder(ids, maskAllImages = true)): JSONObject {
        var json: JSONObject? = null
        compose.runOnUiThread {
            builder.density = root.resources.displayMetrics.density
            json = requireNotNull(builder.window(root, secure = false)).toJson()
        }
        return json!!
    }

    private fun all(wireframe: JSONObject): List<JSONObject> =
        listOf(wireframe) + wireframe.children().flatMap { all(it) }

    @Test
    fun `texts, fields, images and no-capture blocks come from the semantics, nothing typed is recorded`() {
        val tree = build()
        val json = tree.toString()
        val nodes = all(tree)

        val text = nodes.single { it.optString("text") == PLAIN }
        assertEquals("text", text.getString("type"))

        val inputs = nodes.filter { it.getString("type") == "input" }
        val field = inputs.single { it.optString("inputType") == "text" }
        assertEquals("*".repeat(TYPED.length), field.getString("value"))
        val password = inputs.single { it.optString("inputType") == "password" }
        assertEquals("*".repeat(PASSWORD.length), password.getString("value"))
        assertFalse(json.contains(TYPED))
        assertFalse(json.contains(PASSWORD))

        val image = nodes.single { it.getString("type") == "image" }
        assertFalse(image.has("base64"))

        // The no-capture block and the merged parent whose child has the modifier.
        val placeholders = nodes.filter { it.getString("type") == "placeholder" }
        assertEquals(2, placeholders.size)
        assertTrue(placeholders.all { it.children().isEmpty() })
        assertFalse(json.contains(SECRET))
        assertFalse(json.contains(MERGED_SECRET))

        // The plain box, found by its bounds: screen dp, as ComposeWireframes computes them.
        val box = compose.onNodeWithTag("box", useUnmergedTree = true).windowBounds()
        val composeView = findComposeView(root)
        val density = root.resources.displayMetrics.density
        val screen = IntArray(2).also { composeView.getLocationOnScreen(it) }
        val window = IntArray(2).also { composeView.getLocationInWindow(it) }
        val rectangle = nodes.single {
            it.getInt("x") == ((box.left + screen[0] - window[0]) / density).toInt() &&
                it.getInt("y") == ((box.top + screen[1] - window[1]) / density).toInt() &&
                it.getInt("width") == (box.width / density).toInt() &&
                it.getInt("height") == (box.height / density).toInt() &&
                it.getString("type") != "div"
        }
        assertEquals("rectangle", rectangle.getString("type"))
        assertTrue(nodes.all { it.getInt("id") >= RRWireframe.FIRST_ID })
        assertEquals(nodes.size, nodes.map { it.getInt("id") }.toSet().size)
    }

    @Test
    fun `a node keeps its id from one capture to the next`() {
        val first = all(build())
        val second = all(build())

        fun idOf(nodes: List<JSONObject>, match: (JSONObject) -> Boolean) = nodes.filter(match).map { it.getInt("id") }
        val isText: (JSONObject) -> Boolean = { it.optString("text") == PLAIN }
        val isPassword: (JSONObject) -> Boolean = { it.optString("inputType") == "password" }
        val isPlaceholder: (JSONObject) -> Boolean = { it.getString("type") == "placeholder" }
        assertEquals(idOf(first, isText), idOf(second, isText))
        assertEquals(idOf(first, isPassword), idOf(second, isPassword))
        assertEquals(idOf(first, isPlaceholder), idOf(second, isPlaceholder))
    }

    @Test
    fun `semantics that cannot be read turn the compose view into one placeholder its size`() {
        val failing = WireframeBuilder(
            ids,
            maskAllImages = true,
            composeNodes = ComposeWireframes(rootOf = { throw IllegalStateException("no semantics") }),
        )
        val tree = build(failing)
        val composeView = findComposeView(root)
        val wireframe = all(tree).single { it.getInt("id") == ids.existingId(composeView) }

        assertEquals("placeholder", wireframe.getString("type"))
        assertTrue(wireframe.children().isEmpty())
        val density = root.resources.displayMetrics.density
        assertEquals((composeView.width / density).toInt(), wireframe.getInt("width"))
        assertEquals((composeView.height / density).toInt(), wireframe.getInt("height"))
        assertFalse(tree.toString().contains(PLAIN))
    }
}
