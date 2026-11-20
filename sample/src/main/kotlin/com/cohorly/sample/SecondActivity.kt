package com.cohorly.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SecondActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SampleApplication).cohorly.track("\$screen", mapOf("\$screen_name" to "SecondActivity"))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        layout.addView(TextView(this).apply { setText(R.string.second_title) })
        layout.addView(
            Button(this).apply {
                setText(R.string.back)
                setOnClickListener { finish() }
            },
        )
        setContentView(layout)
        applySystemBarInsets(layout)
    }
}
