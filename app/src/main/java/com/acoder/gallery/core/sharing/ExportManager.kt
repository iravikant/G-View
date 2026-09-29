package com.acoder.gallery.core.sharing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

object ExportManager {

    /**
     * Saves [file] as a shareable copy and returns a content Uri for it, or null on failure.
     * Tries MediaStore.Downloads first (API 29+); some OEM builds (notably ColorOS/MIUI) silently
     * refuse writes into a custom Download subfolder, so this always verifies real bytes were
     * copied rather than trusting a successful insert(), and falls back to the app's own cache
     * directory shared via FileProvider if the MediaStore path doesn't pan out.
     */
    fun saveToDownloads(context: Context, file: File, mime: String, name: String): Uri? {
        if (!file.exists() || file.length() == 0L) return null

        if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(context, file, mime, name)?.let { return it }
        }
        return saveToAppCache(context, file, name)
    }

    private fun saveViaMediaStore(context: Context, file: File, mime: String, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Gallery")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val bytesCopied = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            if (bytesCopied == null || bytesCopied <= 0L) {
                // openOutputStream returned null, or nothing was actually written — treat as failure
                // instead of silently marking a zero-byte row as finished.
                resolver.delete(uri, null, null)
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveToAppCache(context: Context, file: File, name: String): Uri? = try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(dir, name)
        file.copyTo(out, overwrite = true)
        if (out.length() > 0L) MediaShareManager.fileUri(context, out) else null
    } catch (e: Exception) {
        null
    }
}
