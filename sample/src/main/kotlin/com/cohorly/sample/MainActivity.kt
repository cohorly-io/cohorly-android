package com.cohorly.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById<View>(R.id.content_root))
        val cohorly = (application as SampleApplication).cohorly
        cohorly.track("\$screen", mapOf("\$screen_name" to "MainActivity"))

        findViewById<Button>(R.id.open_dialog).setOnClickListener {
            cohorly.track("Dialog Opened")
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title)
                .setMessage(R.string.dialog_message)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }
        findViewById<Button>(R.id.open_second).setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))
        }
        // VEL-2151: an uncaught exception on the main thread, so the error buffer goes with the crash.
        findViewById<Button>(R.id.crash).setOnClickListener {
            throw RuntimeException("Cohorly sample crash")
        }
        findViewById<Button>(R.id.open_compose).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }
    }
}
