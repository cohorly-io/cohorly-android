package com.cohorly.android.replay

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.method.TextKeyListener
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import com.cohorly.android.replay.rr.RRWireframe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Real drawing and JPEG encoding, so images decode and drawables can be sampled.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WireframeBuilderTest {
    private lateinit var activity: Activity
    private lateinit var content: LinearLayout
    private val ids = ViewIdRegistry()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(content)
    }

    private fun <T : View> add(view: T, height: Int = 60): T {
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        return view
    }

    private fun build(maskAllImages: Boolean = true, secure: Boolean = false): JSONObject {
        idleMain()
        val builder = WireframeBuilder(ids, maskAllImages).apply { density = activity.resources.displayMetrics.density }
        return requireNotNull(builder.window(activity.root, secure)).toJson()
    }

    private fun all(root: JSONObject): List<JSONObject> = listOf(root) + root.children().flatMap { all(it) }

    private fun find(root: JSONObject, view: View): JSONObject =
        all(root).single { it.getInt("id") == ids.existingId(view) }

    @Test
    fun `a sample hierarchy becomes wireframes with stable ids from 10000000, parents and screen positions`() {
        val title = add(TextView(activity).apply { text = "Checkout" })
        val button = add(Button(activity).apply { text = "Pay" })
        val check = add(
            CheckBox(activity).apply {
                text = "Remember me"
                isChecked = true
            },
        )

        val first = build()
        assertTrue(all(first).all { it.getInt("id") >= RRWireframe.FIRST_ID })
        assertEquals(all(first).size, all(first).map { it.getInt("id") }.toSet().size)

        val titleJson = find(first, title)
        assertEquals("text", titleJson.getString("type"))
        assertEquals("Checkout", titleJson.getString("text"))
        assertTrue(titleJson.getJSONObject("style").has("color"))

        val buttonJson = find(first, button)
        assertEquals("input", buttonJson.getString("type"))
        assertEquals("button", buttonJson.getString("inputType"))
        assertEquals("Pay", buttonJson.getString("value"))

        val checkJson = find(first, check)
        assertEquals("checkbox", checkJson.getString("inputType"))
        assertTrue(checkJson.getBoolean("checked"))
        assertEquals("Remember me", checkJson.getString("label"))

        // Positions are dp from the screen's top left: the button sits below the title.
        assertTrue(buttonJson.getInt("y") > titleJson.getInt("y"))

        // Same views, same ids, on the next capture.
        val second = build()
        assertEquals(find(first, button).getInt("id"), find(second, button).getInt("id"))
        assertEquals(first.getInt("id"), second.getInt("id"))
    }

    @Test
    fun `invisible, transparent and empty views are left out`() {
        val gone = add(
            TextView(activity).apply {
                text = "gone"
                visibility = View.GONE
            },
        )
        val invisible = add(
            TextView(activity).apply {
                text = "invisible"
                visibility = View.INVISIBLE
            },
        )
        val transparent = add(
            TextView(activity).apply {
                text = "transparent"
                alpha = 0f
            },
        )
        val empty = add(TextView(activity).apply { text = "empty" }, height = 0)

        val json = build().toString()

        listOf(gone, invisible, transparent, empty).forEach { assertNull(ids.existingId(it)) }
        assertFalse(json.contains("invisible"))
        assertFalse(json.contains("transparent"))
    }

    @Test
    fun `typed text never leaves the device, in an EditText or any password field`() {
        val name = add(
            EditText(activity).apply {
                isSingleLine = true
                setText("Ada Lovelace")
            },
        )
        val password = add(
            EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("hunter2")
            },
        )
        val pin = add(
            EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setText("4321")
            },
        )
        val passwordLabel = add(
            TextView(activity).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
                text = "s3cret"
            },
        )
        val emptyWithHint = add(EditText(activity).apply { hint = "Email" })

        val root = build()
        val json = root.toString()

        listOf("Ada Lovelace", "hunter2", "4321", "s3cret").forEach { assertFalse("$it leaked", json.contains(it)) }
        assertEquals("************", find(root, name).getString("value"))
        assertEquals("text", find(root, name).getString("inputType"))
        assertEquals("*******", find(root, password).getString("value"))
        assertEquals("password", find(root, password).getString("inputType"))
        assertEquals("****", find(root, pin).getString("value"))
        assertEquals("password", find(root, pin).getString("inputType"))
        assertEquals("******", find(root, passwordLabel).getString("text"))
        // Empty stays empty; the hint is the app's text and may show.
        assertEquals("Email", find(root, emptyWithHint).getString("value"))
        // A plain EditText is multi-line (TYPE_TEXT_FLAG_MULTI_LINE by default).
        assertEquals("text_area", find(root, emptyWithHint).getString("inputType"))
    }

    @Test
    fun `text typed into a TextView made editable with a KeyListener never leaves the device`() {
        val editable = add(
            TextView(activity).apply {
                keyListener = TextKeyListener.getInstance()
                setText("call me at 555 0100")
            },
        )

        val root = build()

        assertFalse(root.toString().contains("555 0100"))
        val json = find(root, editable)
        assertEquals("input", json.getString("type"))
        assertEquals("*".repeat("call me at 555 0100".length), json.getString("value"))
    }

    private fun redBitmap() = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private fun imageView(): ImageView = add(
        ImageView(activity).apply {
            setImageDrawable(BitmapDrawable(activity.resources, redBitmap()))
        },
        height = 40,
    )

    /** A picture as a background: no single color, so it is an image or nothing. */
    private fun pictureBackground(): View =
        add(View(activity).apply { background = BitmapDrawable(activity.resources, redBitmap()) }, height = 40)

    /** Decodes a `data:image/jpeg;base64,` value and checks it is a red picture of the view's size in dp. */
    private fun assertRedJpeg(value: String, view: View) {
        assertTrue(value.startsWith(WireframeBuilder.JPEG_PREFIX))
        val bytes = Base64.decode(value.removePrefix(WireframeBuilder.JPEG_PREFIX), Base64.DEFAULT)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not a JPEG" }
        val density = activity.resources.displayMetrics.density
        assertEquals((view.width / density).toInt(), bitmap.width)
        assertEquals((view.height / density).toInt(), bitmap.height)
        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue(
            "red expected, got ${Integer.toHexString(center)}",
            Color.red(center) > 200 && Color.green(center) < 60,
        )
    }

    @Test
    fun `with maskAllImages an image is an image wireframe without base64, and a picture background is dropped`() {
        val image = imageView()
        val backdrop = pictureBackground()

        val root = build(maskAllImages = true)

        val json = find(root, image)
        assertEquals("image", json.getString("type"))
        assertFalse(json.has("base64"))
        assertFalse(find(root, backdrop).optJSONObject("style")?.has("backgroundImage") ?: false)
        assertFalse(root.toString().contains(WireframeBuilder.JPEG_PREFIX))
    }

    @Test
    fun `without maskAllImages an image and a picture background are JPEGs that decode`() {
        val image = imageView()
        val backdrop = pictureBackground()

        val root = build(maskAllImages = false)

        val json = find(root, image)
        assertEquals("image", json.getString("type"))
        assertRedJpeg(json.getString("base64"), image)
        assertRedJpeg(find(root, backdrop).getJSONObject("style").getString("backgroundImage"), backdrop)
    }

    private fun backgroundColor(view: View, maskAllImages: Boolean = true): String? =
        find(build(maskAllImages), view).optJSONObject("style")?.optString("backgroundColor")?.takeIf {
            it.isNotEmpty()
        }

    @Test
    fun `a toolbar with a layer list background keeps its color, and its white title stays readable`() {
        val shadow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0))
        val toolbar = add(
            Toolbar(activity).apply {
                // The shadow sits in the bottom 6 px as a layer inset: an InsetDrawable would pad the
                // toolbar by its inset (a background's padding is the view's), leaving the title no room.
                background =
                    LayerDrawable(arrayOf(ColorDrawable(0xFF3F51B5.toInt()), shadow)).apply { setLayerInsetTop(1, 50) }
                title = "Inbox"
                setTitleTextColor(Color.WHITE)
            },
            height = 56,
        )

        val root = build()

        assertEquals("#3F51B5", find(root, toolbar).getJSONObject("style").getString("backgroundColor"))
        val title = all(root).single { it.optString("text") == "Inbox" }
        assertEquals("#FFFFFF", title.getJSONObject("style").getString("color"))
    }

    @Test
    fun `background colors are read through ripples, state lists, insets, gradients and tints`() {
        val ripple = add(
            View(activity).apply {
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(Color.GRAY),
                        ColorDrawable(0xFF00AA00.toInt()),
                        ColorDrawable(Color.WHITE),
                    )
            },
        )
        val stateList = add(
            View(activity).apply {
                background = StateListDrawable().apply { addState(intArrayOf(), ColorDrawable(0xFF0000AA.toInt())) }
            },
        )
        val inset = add(View(activity).apply { background = InsetDrawable(ColorDrawable(0xFFAA0000.toInt()), 4) })
        val gradient =
            add(View(activity).apply { background = GradientDrawable().apply { setColor(0xFF123456.toInt()) } })
        val tinted = add(
            View(activity).apply {
                background = GradientDrawable().apply { setColor(Color.WHITE) }
                backgroundTintList = ColorStateList.valueOf(0xFF654321.toInt())
            },
        )
        val transparent = add(View(activity).apply { background = ColorDrawable(Color.TRANSPARENT) })

        assertEquals("#00AA00", backgroundColor(ripple))
        assertEquals("#0000AA", backgroundColor(stateList))
        assertEquals("#AA0000", backgroundColor(inset))
        assertEquals("#123456", backgroundColor(gradient))
        assertEquals("#654321", backgroundColor(tinted))
        assertNull(backgroundColor(transparent))
    }

    /** Like the ActionBar's private background drawable: a type that says nothing about its color. */
    private class OpaqueWrapper(private val color: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(color)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    @Test
    fun `a drawable of unknown type is sampled for its color`() {
        val bar = add(View(activity).apply { background = OpaqueWrapper(0xFF112233.toInt()) })

        assertEquals("#112233", backgroundColor(bar, maskAllImages = true))
    }

    @Test
    fun `the window background is the color of the window's root wireframe`() {
        activity.window.setBackgroundDrawable(LayerDrawable(arrayOf(ColorDrawable(0xFFFAFAFA.toInt()))))

        val root = build()

        assertEquals("#FAFAFA", root.getJSONObject("style").getString("backgroundColor"))
    }

    @Test
    fun `the no-capture tag turns the view and its subtree into one empty placeholder`() {
        val exact = add(
            FrameLayout(activity).apply {
                tag = CohorlyReplay.NO_CAPTURE_TAG
                addView(TextView(activity).apply { text = "Card 4242" })
            },
        )
        val containing = add(
            TextView(activity).apply {
                tag = "header cohorly-no-capture"
                text = "Balance 1000"
            },
        )
        val other = add(
            TextView(activity).apply {
                tag = "something-else"
                text = "Visible"
            },
        )

        val root = build()
        val json = root.toString()

        assertFalse(json.contains("Card 4242"))
        assertFalse(json.contains("Balance 1000"))
        assertTrue(json.contains("Visible"))
        for (masked in listOf(exact, containing)) {
            val wireframe = find(root, masked)
            assertEquals("placeholder", wireframe.getString("type"))
            assertFalse(wireframe.has("text"))
            assertFalse(wireframe.has("childWireframes"))
        }
        assertEquals("text", find(root, other).getString("type"))
    }

    @Test
    fun `a FLAG_SECURE window is one placeholder the size of the window`() {
        add(TextView(activity).apply { text = "Account 1234" })

        val root = build(secure = true)

        assertEquals("placeholder", root.getString("type"))
        assertEquals(RRWireframe.FIRST_ID, root.getInt("id"))
        assertFalse(root.has("childWireframes"))
        assertFalse(root.toString().contains("Account 1234"))
        assertNotEquals(0, root.getInt("width"))
        assertNotEquals(0, root.getInt("height"))
    }
}
