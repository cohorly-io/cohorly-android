package com.cohorly.android.replay

import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a screenshot covers in a composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeMaskTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: View

    @Before
    fun setUp() {
        compose.setContent { SensitiveScreen() }
        compose.waitForIdle()
        view = findComposeView(compose.activity.window.decorView)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).windowBounds()

    private fun collect(mask: ComposeMasking = ComposeMask(), maskAllImages: Boolean = true): List<Rect> {
        val rects = mutableListOf<Rect>()
        compose.runOnUiThread { mask.collect(view, maskAllImages, rects) }
        return rects
    }

    @Test
    fun `text fields, passwords, images with or without a description and no-capture blocks are covered`() {
        val rects = collect()

        assertTrue("text field", rects.covers(bounds("field")))
        assertTrue("password", rects.covers(bounds("password")))
        assertTrue("image with a description", rects.covers(bounds("described")))
        assertTrue("image with no description", rects.covers(bounds("undescribed")))
        assertTrue("no-capture block", rects.covers(bounds("secret")))
        assertTrue("merged parent of a no-capture child", rects.covers(bounds("merged")))
        assertFalse("plain text", rects.touches(bounds("plain")))
        assertFalse("box", rects.touches(bounds("box")))
    }

    @Test
    fun `without maskAllImages images are not covered, text fields and no-capture still are`() {
        val rects = collect(maskAllImages = false)

        assertFalse("image with a description", rects.touches(bounds("described")))
        assertFalse("image with no description", rects.touches(bounds("undescribed")))
        assertTrue("text field", rects.covers(bounds("field")))
        assertTrue("password", rects.covers(bounds("password")))
        assertTrue("no-capture block", rects.covers(bounds("secret")))
    }

    @Test
    fun `the reflection on Compose internals works on the pinned Compose and finds an image semantics cannot see`() {
        // Fails when a Compose upgrade moves the internals ComposeImageMask.kt reads.
        val rects = mutableListOf<Rect>()
        compose.runOnUiThread {
            val root = (view as RootForTest).semanticsOwner.unmergedRootSemanticsNode.layoutInfo
            collectComposeImageRects(root, emptySet(), rects)
        }
        assertTrue(rects.covers(bounds("undescribed")))
    }

    @Test
    fun `fractional bounds are rounded outward, the mask holds the whole node`() {
        val bounds = androidx.compose.ui.geometry.Rect(10.4f, 20.6f, 30.2f, 40.9f)

        val rect = requireNotNull(bounds.toMaskRect())

        assertEquals(Rect(10, 20, 31, 41), rect)
        assertTrue(listOf(rect).covers(bounds))
        assertEquals(null, androidx.compose.ui.geometry.Rect(5f, 5f, 5f, 9f).toMaskRect())
    }

    @Test
    fun `a failing reflection covers the whole compose view`() {
        val rects = collect(ComposeMask(imageRects = { _, _ -> throw NoSuchMethodException("getChildren\$ui") }))

        assertEquals(listOf(view.windowRect()), rects)
    }

    @Test
    fun `a failing semantics read covers the whole compose view`() {
        val rects = collect(ComposeMask(ownerOf = { throw IllegalStateException("no owner") }))

        assertEquals(listOf(view.windowRect()), rects)
    }

    @Test
    fun `the screenshot mask walk covers a compose view whose mask fails`() {
        val masker = ScreenshotMasker(
            maskAllImages = true,
            composeMasking = ComposeMask(imageRects = { _, _ -> throw NoSuchMethodException("moved") }),
        )
        var rects: List<Rect> = emptyList()
        compose.runOnUiThread { rects = masker.collect(compose.activity.window.decorView) }

        assertTrue(rects.contains(view.windowRect()))
    }
}
