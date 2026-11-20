package com.cohorly.android.replay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.floor

/** The pixels of a screenshot: the masked views are covered before the image leaves the capturer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotMaskTest {
    private lateinit var activity: Activity
    private lateinit var content: LinearLayout
    private val worker = TestExecutor()

    /** Software drawing in place of PixelCopy, which Robolectric does not run. */
    private val drawSource = WindowPixelSource { root, dest, done -> done(drawWindow(root, dest)) }

    private lateinit var field: EditText
    private lateinit var password: EditText
    private lateinit var image: ImageView
    private lateinit var secret: FrameLayout
    private lateinit var secretChild: TextView
    private lateinit var plain: TextView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(content)
        field = add(
            EditText(activity).apply {
                setBackgroundColor(Color.YELLOW)
                setTextColor(Color.RED)
                setText("my typed secret")
            },
        )
        password = add(
            EditText(activity).apply {
                setBackgroundColor(Color.CYAN)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("hunter2")
            },
        )
        image = add(
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                setImageDrawable(BitmapDrawable(resources, bitmap))
            },
        )
        secretChild = TextView(activity).apply {
            text = "PIN 1234"
            setTextColor(Color.WHITE)
        }
        secret = add(
            FrameLayout(activity).apply {
                tag = "Cohorly-No-Capture"
                setBackgroundColor(Color.MAGENTA)
                addView(secretChild)
            },
        )
        plain = add(
            TextView(activity).apply {
                text = "Hello"
                setBackgroundColor(Color.BLUE)
                setTextColor(Color.WHITE)
            },
        )
        idleMain()
    }

    private fun <T : View> add(view: T): T {
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60))
        return view
    }

    private fun capturer(
        maskAllImages: Boolean = true,
        source: WindowPixelSource = drawSource,
        executor: ReplayWorker = worker,
        scale: Float = 1f,
        copyTimeoutMs: Long = ScreenshotCapturer.COPY_TIMEOUT_MS,
    ) = ScreenshotCapturer(
        maskAllImages = maskAllImages,
        quality = 30,
        source = source,
        worker = executor,
        mainHandler = Handler(Looper.getMainLooper()),
        hasPendingDraw = { false },
        scaleFor = { scale },
        copyTimeoutMs = copyTimeoutMs,
    ).apply { start(RRWireframeIds.SCREENSHOT) }

    /** One capture of [roots]: the image, or null when the frame was discarded. */
    private fun captureOrNull(capturer: ScreenshotCapturer, roots: List<View> = listOf(activity.root)): Bitmap? {
        val root = activity.root
        var answered = false
        var result: Bitmap? = null
        val accepted = capturer.capture(roots, root.width, root.height, 1f) {
            answered = true
            result = it
        }
        idleMain()
        assertTrue(accepted)
        assertTrue("the capture answered", answered)
        return result
    }

    private fun capture(maskAllImages: Boolean = true): Bitmap =
        requireNotNull(captureOrNull(capturer(maskAllImages))) { "the frame was discarded" }

    /** What the screen looks like with no mask: the window drawn the same way, over the same black. */
    private fun reference(): Bitmap {
        val root = activity.root
        val window = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        assertTrue(drawWindow(root, window))
        val screen = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(window, 0f, 0f, null)
        return screen
    }

    private fun View.windowVisibleRect(): Rect {
        val rect = Rect()
        getGlobalVisibleRect(rect)
        return rect
    }

    private fun assertMasked(image: Bitmap, rect: Rect, what: String) {
        assertTrue("$what is on screen", !rect.isEmpty)
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                assertEquals("$what at ($x, $y)", ScreenshotCapturer.MASK_COLOR, image.getPixel(x, y))
            }
        }
    }

    /** Every pixel outside [masked] is the window's own. */
    private fun assertUntouchedOutside(image: Bitmap, reference: Bitmap, masked: List<Rect>) {
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                // Masks reach MASK_BLEED_PX past their rect.
                val bleed = ScreenshotCapturer.MASK_BLEED_PX
                if (masked.any { Rect(it).apply { inset(-bleed, -bleed) }.contains(x, y) }) continue
                assertEquals("pixel ($x, $y)", reference.getPixel(x, y), image.getPixel(x, y))
            }
        }
    }

    @Test
    fun `text fields, passwords, images and no-capture views are covered, everything else keeps its pixels`() {
        val reference = reference()
        val image = capture()

        val masked = listOf(field, password, this.image, secret).map { it.windowVisibleRect() }
        assertMasked(image, field.windowVisibleRect(), "the text field")
        assertMasked(image, password.windowVisibleRect(), "the password field")
        assertMasked(image, this.image.windowVisibleRect(), "the image")
        assertMasked(image, secret.windowVisibleRect(), "the no-capture view")
        assertMasked(image, secretChild.windowVisibleRect(), "the no-capture view's child")
        assertUntouchedOutside(image, reference, masked)
        // The plain label is drawn as it is: its blue background is there.
        val label = plain.windowVisibleRect()
        assertEquals(Color.BLUE, image.getPixel(label.left + 1, label.top + 1))
    }

    @Test
    fun `without maskAllImages an image keeps its pixels, text fields are still covered`() {
        val reference = reference()
        val image = capture(maskAllImages = false)

        val imageRect = this.image.windowVisibleRect()
        assertEquals(Color.GREEN, image.getPixel(imageRect.centerX(), imageRect.centerY()))
        assertMasked(image, field.windowVisibleRect(), "the text field")
        assertMasked(image, password.windowVisibleRect(), "the password field")
        assertUntouchedOutside(
            image,
            reference,
            listOf(field, password, secret).map { it.windowVisibleRect() },
        )
    }

    @Test
    fun `windows are placed from the origin of the captured window, as in a bottom split-screen pane`() {
        val root = activity.root
        val offset = root.height / 2
        val unshifted = capture()
        var image: Bitmap? = null
        assertTrue(capturer().capture(listOf(root), root.width, root.height, 1f, 0, offset) { image = it })
        idleMain()
        val shot = requireNotNull(image)

        // The window starts `offset` px above the image: its middle row is the image's top row.
        for (y in listOf(1, offset / 2, offset - 2)) {
            for (x in listOf(1, root.width / 2, root.width - 2)) {
                assertEquals("($x, $y)", unshifted.getPixel(x, y + offset), shot.getPixel(x, y))
            }
        }
        // Below the window's end there is nothing but the black around the screen.
        assertEquals(Color.BLACK, shot.getPixel(root.width / 2, root.height - 2))
    }

    @Test
    fun `a FLAG_SECURE window is covered whole, none of its pixels copied`() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        idleMain()
        var copies = 0
        val capturer = ScreenshotCapturer(
            maskAllImages = true,
            quality = 30,
            source = { root, dest, done ->
                copies++
                done(drawWindow(root, dest))
            },
            worker = worker,
            mainHandler = Handler(Looper.getMainLooper()),
            hasPendingDraw = { false },
            scaleFor = { 1f },
        )
        capturer.start(RRWireframeIds.SCREENSHOT)
        val root = activity.root
        var result: Bitmap? = null
        capturer.capture(listOf(root), root.width, root.height, 1f) { result = it }
        idleMain()

        assertEquals(0, copies)
        assertMasked(requireNotNull(result), Rect(0, 0, root.width, root.height), "the secure window")
    }

    @Test
    fun `a photo inside a wrapper, layer or state list is covered, colors and gradients inside them are not`() {
        val photo = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        fun photo(): Drawable = BitmapDrawable(activity.resources, photo)
        image.setImageDrawable(InsetDrawable(photo(), 2))
        idleMain()

        assertMasked(capture(), image.windowVisibleRect(), "the inset photo")

        val masker = ScreenshotMasker(maskAllImages = true)
        fun ruleFor(drawable: Drawable) =
            masker.maskRule(ImageView(activity).apply { setImageDrawable(drawable) }, false)
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), photo())
            addState(intArrayOf(), ColorDrawable(Color.RED))
        }
        assertEquals(ScreenshotMasker.MaskRule.MASK_VIEW, ruleFor(states))
        assertEquals(
            ScreenshotMasker.MaskRule.MASK_VIEW,
            ruleFor(LayerDrawable(arrayOf(ColorDrawable(Color.RED), photo()))),
        )
        val unknown = object : Drawable() {
            override fun draw(canvas: Canvas) = Unit
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
        assertEquals(ScreenshotMasker.MaskRule.MASK_VIEW, ruleFor(InsetDrawable(unknown, 0)))
        assertEquals(ScreenshotMasker.MaskRule.NONE, ruleFor(InsetDrawable(ColorDrawable(Color.RED), 2)))
        assertEquals(
            ScreenshotMasker.MaskRule.NONE,
            ruleFor(LayerDrawable(arrayOf(ColorDrawable(Color.RED), GradientDrawable()))),
        )
    }

    @Test
    fun `at a fractional scale the mask covers the field's whole scaled rect`() {
        val scale = 0.37f
        val image = requireNotNull(captureOrNull(capturer(scale = scale))) { "the frame was discarded" }
        val field = field.windowVisibleRect()
        val scaled = Rect(
            floor(field.left * scale).toInt(),
            floor(field.top * scale).toInt(),
            ceil(field.right * scale).toInt(),
            ceil(field.bottom * scale).toInt(),
        )
        for (y in scaled.top until minOf(scaled.bottom, image.height)) {
            for (x in scaled.left until minOf(scaled.right, image.width)) {
                val pixel = image.getPixel(x, y)
                assertTrue(
                    "the scaled field at ($x, $y) is ${Integer.toHexString(pixel)}",
                    Color.red(pixel) <= 8 && Color.green(pixel) <= 8 && Color.blue(pixel) <= 8,
                )
            }
        }
    }

    @Test
    fun `a copy failing in the second of two windows discards the whole frame`() {
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        second.setContentView(TextView(second).apply { text = "second window" })
        idleMain()
        val secondRoot = second.root
        val copied = mutableListOf<View>()
        val source = WindowPixelSource { root, dest, done ->
            copied.add(root)
            if (root === secondRoot) done(false) else done(drawWindow(root, dest))
        }

        val result = captureOrNull(capturer(source = source), listOf(activity.root, secondRoot))

        assertEquals(listOf(activity.root, secondRoot), copied)
        assertNull(result)
    }

    @Test
    fun `a mask walk that throws discards the frame`() {
        var broken = false
        add(
            object : EditText(activity) {
                override fun getGlobalVisibleRect(r: Rect?, globalOffset: Point?): Boolean {
                    if (broken) throw IllegalStateException("cannot read")
                    return super.getGlobalVisibleRect(r, globalOffset)
                }
            },
        )
        idleMain()
        broken = true

        assertNull(captureOrNull(capturer()))
    }

    @Test
    fun `a capture asked for while the previous image is still being composed is refused, only one job waits`() {
        val held = HoldingExecutor()
        val capturer = capturer(executor = held)
        val root = activity.root
        var images = 0

        assertTrue(capturer.capture(listOf(root), root.width, root.height, 1f) { if (it != null) images++ })
        idleMain()
        assertEquals("the first frame waits for the recorder thread", 1, held.pending)
        assertFalse(capturer.capture(listOf(root), root.width, root.height, 1f) { images++ })
        idleMain()
        assertEquals(1, held.pending)

        held.runAll()
        idleMain()
        assertEquals(1, images)
        assertTrue(capturer.capture(listOf(root), root.width, root.height, 1f) { if (it != null) images++ })
        idleMain()
        held.runAll()
        assertEquals(2, images)
    }

    @Test
    fun `a copy answering after its timeout never has its bitmap handed to the next copy`() {
        val late = mutableListOf<Pair<Bitmap, (Boolean) -> Unit>>()
        var hold = true
        val dests = mutableListOf<Bitmap>()
        val source = WindowPixelSource { root, dest, done ->
            dests.add(dest)
            if (hold) late.add(dest to done) else done(drawWindow(root, dest))
        }
        val capturer = capturer(source = source, copyTimeoutMs = 50)
        val root = activity.root
        var first: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        var firstAnswered = false
        assertTrue(
            capturer.capture(listOf(root), root.width, root.height, 1f) {
                firstAnswered = true
                first = it
            },
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue("the timed-out frame was discarded", firstAnswered)
        assertNull(first)

        hold = false
        val second = requireNotNull(captureOrNull(capturer)) { "the second frame was discarded" }
        assertNotSame("the timed-out copy's bitmap was reused", dests[0], dests[1])

        // The late copy writes into its own bitmap only, then answers.
        val (lateDest, lateDone) = late.single()
        lateDest.eraseColor(Color.RED)
        lateDone(true)
        idleMain()
        val reference = reference()
        assertMasked(second, field.windowVisibleRect(), "the text field")
        val label = plain.windowVisibleRect()
        assertEquals(reference.getPixel(label.left + 1, label.top + 1), second.getPixel(label.left + 1, label.top + 1))
    }
}

/** Holds the recorder thread's jobs until the test runs them. */
private class HoldingExecutor : ReplayWorker {
    private val jobs = ArrayDeque<Runnable>()
    val pending: Int get() = jobs.size

    override fun execute(task: Runnable) {
        jobs.addLast(task)
    }

    override fun schedule(delayMs: Long, task: Runnable) {
        jobs.addLast(task)
    }

    fun runAll() {
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
    }
}

/** Ids for screenshots in tests. */
internal object RRWireframeIds {
    const val SCREENSHOT = 10_000_000
}
