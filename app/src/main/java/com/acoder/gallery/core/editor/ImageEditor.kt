package com.acoder.gallery.core.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

class ImageEditor(private val original: Bitmap) {

    /** [crop] is normalised (0..1) and expressed in the rotated/flipped image space, so it is resolution independent. */
    data class State(
        val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f,
        val sharpness: Float = 0f, val definition: Float = 0f, val shadows: Float = 0f,
        val highlights: Float = 0f, val blackPoint: Float = 0f, val exposure: Float = 0f,
        val temperature: Float = 0f, val rotation: Int = 0, val flipX: Boolean = false,
        val flipY: Boolean = false, val crop: RectF? = null
    )

    fun render(s: State): Bitmap {
        val contrast = (s.contrast + s.sharpness * 0.25f).coerceIn(0.1f, 2.5f)
        val sat = (s.saturation + s.definition * 0.15f).coerceIn(0f, 2.5f)
        val offset = s.exposure * 80f + s.brightness * 255f + s.shadows * 25f - s.highlights * 25f - s.blackPoint * 50f
        val warm = s.temperature * 20f

        val matrix = ColorMatrix().apply { setSaturation(sat) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset + warm,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset - warm,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val base = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(base).drawBitmap(original, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })

        val geometry = Matrix().apply {
            postRotate(s.rotation.toFloat())
            postScale(if (s.flipX) -1f else 1f, if (s.flipY) -1f else 1f)
        }
        val transformed = Bitmap.createBitmap(base, 0, 0, base.width, base.height, geometry, true)
        if (transformed !== base) base.recycle()

        val crop = s.crop ?: return transformed
        val w = transformed.width
        val h = transformed.height
        val left = (crop.left * w).toInt().coerceIn(0, w - 1)
        val top = (crop.top * h).toInt().coerceIn(0, h - 1)
        val cw = ((crop.right - crop.left) * w).toInt().coerceIn(1, w - left)
        val ch = ((crop.bottom - crop.top) * h).toInt().coerceIn(1, h - top)
        val cropped = Bitmap.createBitmap(transformed, left, top, cw, ch)
        if (cropped !== transformed) transformed.recycle()
        return cropped
    }
}
