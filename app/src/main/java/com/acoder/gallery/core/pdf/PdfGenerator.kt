package com.acoder.gallery.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {
    data class Options(val pageWidth: Int = 595, val pageHeight: Int = 842)

    /**
     * Combines [uris] into a single PDF, one image per page.
     * Throws instead of returning a silently-empty file so the caller can surface a real error
     * (a previous version could hand back a technically-valid but zero-page/zero-byte PDF).
     */
    fun generate(context: Context, uris: List<Uri>, options: Options = Options()): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "Gallery_${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        var pagesAdded = 0
        try {
            uris.forEachIndexed { index, uri ->
                val bmp = try {
                    decodeForPage(context, uri, options.pageWidth, options.pageHeight)
                } catch (e: Exception) {
                    Log.w("PdfGenerator", "Could not decode $uri, skipping", e)
                    null
                }
                if (bmp != null) {
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, index + 1).create())
                    val scale = minOf(options.pageWidth.toFloat() / bmp.width, options.pageHeight.toFloat() / bmp.height) * 0.92f
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    page.canvas.drawBitmap(
                        bmp,
                        null,
                        RectF((options.pageWidth - w) / 2, (options.pageHeight - h) / 2, (options.pageWidth + w) / 2, (options.pageHeight + h) / 2),
                        null
                    )
                    doc.finishPage(page)
                    bmp.recycle()
                    pagesAdded++
                }
            }
            if (pagesAdded == 0) throw IllegalStateException("None of the selected photos could be read")
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        if (file.length() == 0L) throw IllegalStateException("The generated PDF came out empty")
        return file
    }

    private fun decodeForPage(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = kotlin.math.max(1, kotlin.math.min(bounds.outWidth / maxW, bounds.outHeight / maxH))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
