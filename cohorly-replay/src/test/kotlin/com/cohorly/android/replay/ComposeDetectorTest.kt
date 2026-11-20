package com.cohorly.android.replay

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An app without Compose: the View path never reaches a Compose class. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDetectorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What an app without Compose sees: the class lookup fails. */
    private val noCompose = ComposeDetector { throw ClassNotFoundException(it) }

    @Test
    fun `without the AndroidComposeView class there is no Compose and no compose view`() {
        assertFalse(noCompose.isAvailable)
        assertTrue(ComposeDetector.Default.isAvailable)

        compose.setContent { SensitiveScreen() }
        compose.waitForIdle()
        val composeView = findComposeView(compose.activity.window.decorView)
        assertFalse(noCompose.isComposeView(composeView))
        assertTrue(ComposeDetector.Default.isComposeView(composeView))
        assertFalse(ComposeDetector.Default.isComposeView(TextView(compose.activity)))
    }

    @Test
    fun `with no Compose detected, the wireframe and the mask walk never call a Compose reader`() {
        compose.setContent { SensitiveScreen() }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        idleMain()

        val builder = WireframeBuilder(
            ViewIdRegistry(),
            maskAllImages = true,
            compose = noCompose,
            composeNodes = { _, _, _, _ -> throw AssertionError("Compose read without Compose") },
        )
        val masker = ScreenshotMasker(
            maskAllImages = true,
            compose = noCompose,
            composeMasking = { _, _, _ -> throw AssertionError("Compose read without Compose") },
        )
        compose.runOnUiThread {
            assertTrue(builder.window(root, secure = false) != null)
            masker.collect(root)
        }
    }
}
