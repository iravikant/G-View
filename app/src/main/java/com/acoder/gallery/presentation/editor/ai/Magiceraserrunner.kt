package com.acoder.gallery.presentation.editor.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** [PixelSurface] over a mutable ARGB_8888 bitmap. */
class BitmapSurface(private val bmp: Bitmap) : PixelSurface {
    override val width: Int get() = bmp.width
    override val height: Int get() = bmp.height

    override fun read(r: Region): IntArray {
        val out = IntArray(r.width * r.height)
        bmp.getPixels(out, 0, r.width, r.x0, r.y0, r.width, r.height)
        return out
    }

    override fun write(r: Region, pixels: IntArray) {
        bmp.setPixels(pixels, 0, r.width, r.x0, r.y0, r.width, r.height)
    }
}

object MagicEraserRunner {

    /**
     * Erases the area covered by [strokes] from [source] and returns a NEW bitmap; [source] is never
     * modified, so the caller can keep it for undo.
     *
     * [strokes]: each stroke is x0,y0,x1,y1... normalised (0..1) to the image.
     * [brushFraction]: brush width as a fraction of the image's shorter side.
     * Runs on a background dispatcher and stops between chunks when the coroutine is cancelled.
     */
    suspend fun erase(
        source: Bitmap,
        strokes: List<FloatArray>,
        brushFraction: Float,
        engine: InpaintEngine,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = source.width
        val h = source.height
        val brushPx = brushFraction * min(w, h)

        val mask = rasterizeMask(strokes, w, h, brushPx)
        val out = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            null
        } ?: throw IllegalStateException("Not enough memory to edit a photo this large")

        try {
            val stats = MagicEraserPipeline(engine).erase(
                surface = BitmapSurface(out),
                rawMask = mask,
                params = defaultParams(w, h, brushPx),
                isCancelled = { !isActive },
                onProgress = onProgress
            )
            if (stats.holePixels == 0) throw IllegalArgumentException("Nothing is selected")
            out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }

    /**
     * Rasterises the strokes at the bitmap's own resolution (so the mask is in the image's coordinate
     * system, not the preview's). Open strokes select the painted band; closed lassos select their
     * inside too. Drawn band by band so a large photo never needs a full-size mask bitmap.
     */
    fun rasterizeMask(strokes: List<FloatArray>, w: Int, h: Int, brushPx: Float): BooleanArray {
        val mask = BooleanArray(w * h)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = brushPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val items = strokes.filter { it.size >= 4 }.map { pts ->
            val path = Path()
            path.moveTo(pts[0] * w, pts[1] * h)
            for (i in 1 until pts.size / 2) path.lineTo(pts[2 * i] * w, pts[2 * i + 1] * h)
            path to isClosedStroke(pts, w, h, brushPx)
        }
        if (items.isEmpty()) return mask

        val band = min(h, 256)
        val bmp = Bitmap.createBitmap(w, band, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val row = IntArray(w * band)
        try {
            var y0 = 0
            while (y0 < h) {
                val rows = min(band, h - y0)
                bmp.eraseColor(0)
                canvas.save()
                canvas.translate(0f, -y0.toFloat())
                for ((path, closed) in items) {
                    canvas.drawPath(path, line)
                    if (closed) canvas.drawPath(path, fill)
                }
                canvas.restore()
                bmp.getPixels(row, 0, w, 0, 0, w, rows)
                for (y in 0 until rows) {
                    val o = y * w
                    val dst = (y0 + y) * w
                    for (x in 0 until w) if ((row[o + x] ushr 24) >= 128) mask[dst + x] = true
                }
                y0 += rows
            }
        } finally {
            bmp.recycle()
        }
        return mask
    }
}