package com.cohorly.android.crash

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Startup hook for crash reporting, registered by the library manifest
 * (`initOrder` 1000). `onCreate` runs after `Application.attachBaseContext`
 * and before `Application.onCreate` and lower-order providers, so the handler
 * is in place before app code can crash. A ContentProvider rather than
 * androidx.startup: no dependency, and apps that remove androidx.startup's
 * `InitializationProvider` do not remove this one (ADR-0020).
 */
class CohorlyCrashProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            context?.let { CohorlyCrashReporter.start(it) }
        } catch (_: Throwable) {
            // A bug of ours must never take the app down at startup.
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0
}
