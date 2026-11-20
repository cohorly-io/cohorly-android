package com.cohorly.android.replay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cohorly.android.replay.compose.cohorlyNoCapture

internal const val TYPED = "my typed secret"
internal const val PASSWORD = "hunter2"
internal const val PLAIN = "Hello compose"
internal const val SECRET = "Card 4242"
internal const val MERGED_SECRET = "Card 5555"

/**
 * One of each thing the recorder treats apart: a plain text, a text field
 * with text typed in, a password field, an image with and one without a
 * content description, a no-capture block with a text inside, and a box
 * with no text or image.
 */
@Composable
internal fun SensitiveScreen() {
    val bitmap = remember {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GREEN) }
            .asImageBitmap()
    }
    Column {
        Text(PLAIN, Modifier.testTag("plain"))
        BasicTextField(value = TYPED, onValueChange = {}, modifier = Modifier.testTag("field").width(200.dp))
        BasicTextField(
            value = PASSWORD,
            onValueChange = {},
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.testTag("password").width(200.dp).semantics { password() },
        )
        Image(BitmapPainter(bitmap), contentDescription = "Logo", modifier = Modifier.testTag("described").size(40.dp))
        Image(BitmapPainter(bitmap), contentDescription = null, modifier = Modifier.testTag("undescribed").size(40.dp))
        Box(Modifier.testTag("secret").cohorlyNoCapture()) { Text(SECRET) }
        Box(Modifier.testTag("box").size(50.dp))
        // A merging parent with the modifier turned off must not drop its child's protection.
        Box(Modifier.testTag("merged").clickable {}.cohorlyNoCapture(isEnabled = false)) {
            Text(MERGED_SECRET, Modifier.cohorlyNoCapture())
        }
    }
}

/** The `AndroidComposeView` under [view]. */
internal fun findComposeView(view: View): View {
    if (view.javaClass.name == ComposeDetector.ANDROID_COMPOSE_VIEW) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val found = runCatching { findComposeView(view.getChildAt(i)) }.getOrNull()
            if (found != null) return found
        }
    }
    throw AssertionError("no AndroidComposeView under $view")
}

internal fun SemanticsNodeInteraction.windowBounds(): androidx.compose.ui.geometry.Rect =
    fetchSemanticsNode().boundsInWindow

/** True when one of [rects] contains the whole of [bounds]. */
internal fun List<Rect>.covers(bounds: androidx.compose.ui.geometry.Rect): Boolean = any {
    it.left <= bounds.left && it.top <= bounds.top && it.right >= bounds.right && it.bottom >= bounds.bottom
}

/** True when one of [rects] overlaps [bounds]. */
internal fun List<Rect>.touches(bounds: androidx.compose.ui.geometry.Rect): Boolean = any {
    it.left < bounds.right && bounds.left < it.right && it.top < bounds.bottom && bounds.top < it.bottom
}
