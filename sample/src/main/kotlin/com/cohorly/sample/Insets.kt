package com.cohorly.sample

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * With targetSdk 35+ the app draws edge to edge: the content starts at the
 * top of the screen, under the status bar and the action bar. Pads [view]
 * by the insets the window reports (system bars and display cutout), on top
 * of its own padding, so the content starts below the bars.
 */
fun applySystemBarInsets(view: View) {
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val bottom = view.paddingBottom
    view.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                left + insets.systemWindowInsetLeft,
                top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight,
                bottom + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
    view.requestApplyInsets()
}
