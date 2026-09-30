package com.couchmode.app.daemon

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The daemon has to be started once after every reboot through the Retroid's Settings -> "Run script
 * as Root". This puts the start script where that screen's file picker can find it, under a brand-new
 * name every time: the root menu can otherwise run a stale same-named copy (see CLAUDE.md).
 */
object StartScript {
    private const val ASSET = "couchmode-start.sh"

    /** Saves the script to the Download folder. Returns the file name, or null if it couldn't be saved. */
    fun saveToDownloads(context: Context): String? {
        // MediaStore.Downloads needs Android 10 (API 29). The Retroid handhelds run newer versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "couchmode-start-$stamp.sh"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-sh")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: return null
            stream.use { it.write(bytes) }
            name
        } catch (e: Exception) {
            null
        }
    }
}
