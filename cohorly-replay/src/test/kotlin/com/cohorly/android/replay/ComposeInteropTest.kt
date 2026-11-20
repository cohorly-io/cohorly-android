package com.cohorly.android.replay

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cohorly.android.replay.compose.cohorlyNoCapture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val INTEROP_SECRET = "segredo"

/** A View inside a composition (`AndroidView`) under `cohorlyNoCapture()`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeInteropTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: View
    private var interop: TextView? = null

    @Before
    fun setUp() {
        compose.setContent {
            Box(Modifier.size(120.dp).cohorlyNoCapture()) {
                AndroidView(factory = { context ->
                    TextView(context).apply { text = INTEROP_SECRET }.also { interop = it }
                })
            }
        }
        compose.waitForIdle()
        root = compose.activity.window.decorView
        idleMain()
    }

    private fun wireframeJson(builder: WireframeBuilder): String {
        var json = ""
        compose.runOnUiThread {
            builder.density = root.resources.displayMetrics.density
            json = requireNotNull(builder.window(root, secure = false)).toJson().toString()
        }
        return json
    }

    @Test
    fun `the interop view's text is not in the wireframes`() {
        val json = wireframeJson(WireframeBuilder(ViewIdRegistry(), maskAllImages = true))

        assertTrue(json.contains("placeholder"))
        assertFalse(json.contains(INTEROP_SECRET))
    }

    @Test
    fun `with unreadable semantics no child view of the compose view is recorded`() {
        val failing = WireframeBuilder(
            ViewIdRegistry(),
            maskAllImages = true,
            composeNodes = ComposeWireframes(rootOf = { throw IllegalStateException("no semantics") }),
        )

        assertFalse(wireframeJson(failing).contains(INTEROP_SECRET))
    }

    @Test
    fun `the screenshot mask covers the interop view`() {
        val view = requireNotNull(interop) { "the interop view was not created" }
        var rects: List<Rect> = emptyList()
        var viewRect = Rect()
        compose.runOnUiThread {
            rects = ScreenshotMasker(maskAllImages = true).collect(root)
            viewRect = view.windowRect()
        }

        assertFalse("the interop view is laid out", viewRect.isEmpty)
        assertTrue("$viewRect in $rects", rects.any { it.contains(viewRect) })
    }
}
