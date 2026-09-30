package com.acoder.gallery.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.PdfPageSize
import com.acoder.gallery.domain.model.PdfQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class PdfCreator @Inject constructor(@ApplicationContext private val ctx: Context) {

    suspend fun create(
        items: List<MediaItem>,
        size: PdfPageSize,
        quality: PdfQuality,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "Gallery_${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        var pagesAdded = 0
        try {
            items.forEachIndexed { i, item ->
                ensureActive()
                val bmp = try {
                    decode(item.uri, quality.maxDim)
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    var pw: Int
                    var ph: Int
                    when (size) {
                        PdfPageSize.A4 -> { pw = 595; ph = 842 }
                        PdfPageSize.LETTER -> { pw = 612; ph = 792 }
                        PdfPageSize.ORIGINAL -> { pw = bmp.width; ph = bmp.height }
                    }
                    if (size != PdfPageSize.ORIGINAL && bmp.width > bmp.height) {
                        val t = pw
                        pw = ph
                        ph = t
                    } // landscape page for wide photos
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pagesAdded + 1).create())
                    val scale = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    val l = (pw - w) / 2f
                    val t = (ph - h) / 2f
                    page.canvas.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    bmp.recycle()
                    pagesAdded++
                }
                onProgress(i + 1)
            }
            if (pagesAdded == 0) throw IllegalStateException("None of the selected photos could be read")
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        if (file.length() == 0L) throw IllegalStateException("The generated PDF came out empty")
        file
    }

    private fun decode(uri: Uri, maxDim: Int): Bitmap? {
        val resolver = ctx.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        val s = maxDim.toFloat() / longest
                        d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                    }
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {
                decodeFallback(uri, maxDim)
            }
        } else {
            decodeFallback(uri, maxDim)
        }
    }

    private fun decodeFallback(uri: Uri, maxDim: Int): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        val sample = max(1, longest / maxDim)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
