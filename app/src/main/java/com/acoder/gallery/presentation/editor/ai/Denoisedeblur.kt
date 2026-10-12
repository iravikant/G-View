package com.acoder.gallery.presentation.editor.ai


import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Noise reduction: edge-preserving smoothing of luminance plus stronger smoothing of colour noise.
 *
 * Luma: a guided filter following its own edges (flat areas are averaged, edges are kept).
 * Chroma: a wider guided filter that follows the cleaned luma, which removes blotchy colour noise
 * without bleeding colours across edges.
 * Strength 0 returns the input unchanged.
 */
object Denoiser {

    private fun lumaRadius(s: Float) = 2 + (2f * s).roundToInt()
    private fun chromaRadius(s: Float) = 5 + (4f * s).roundToInt()

    /** Rows of context an operation needs around a band. */
    fun overlap(strength: Float): Int = 2 * chromaRadius(strength) + 2 * lumaRadius(strength) + 4

    fun process(win: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return win.copyOf()
        val n = w * h

        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val c = win[i]
            val r = PixelKit.red(c) / 255f
            val g = PixelKit.green(c) / 255f
            val b = PixelKit.blue(c) / 255f
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            y[i] = l
            cb[i] = (b - l) * 0.564f
            cr[i] = (r - l) * 0.713f
        }

        val epsY = sq(0.03f + 0.10f * s)
        val yFiltered = PixelKit.guidedFilter(y, y, w, h, lumaRadius(s), epsY)

        val epsC = sq(0.04f + 0.12f * s)
        val rc = chromaRadius(s)
        val cbFiltered = PixelKit.guidedFilter(yFiltered, cb, w, h, rc, epsC)
        val crFiltered = PixelKit.guidedFilter(yFiltered, cr, w, h, rc, epsC)

        val lumaMix = s
        val chromaMix = min(1f, s * 1.3f)
        val out = IntArray(n)
        for (i in 0 until n) {
            val l = PixelKit.lerp(y[i], yFiltered[i], lumaMix)
            val cbv = PixelKit.lerp(cb[i], cbFiltered[i], chromaMix)
            val crv = PixelKit.lerp(cr[i], crFiltered[i], chromaMix)
            val r = l + crv / 0.713f
            val b = l + cbv / 0.564f
            val g = (l - 0.299f * r - 0.114f * b) / 0.587f
            out[i] = PixelKit.argb(
                PixelKit.alpha(win[i]), PixelKit.to255(r), PixelKit.to255(g), PixelKit.to255(b)
            )
        }
        return out
    }

    private fun sq(v: Float) = v * v
}

/**
 * Deblurring by iterative back-projection (a damped van Cittert deconvolution) assuming a Gaussian blur,
 * on luminance only: estimate += beta * (observed - blur(estimate)). Each pass re-injects the detail the
 * blur removed. The result is clamped to the local range of the original, which stops ringing halos.
 *
 * It recovers mild softness (slight defocus, small camera shake). It cannot rebuild detail that is gone,
 * the finest frequencies are lost for good, and strong motion blur is outside a Gaussian model. Noise is
 * amplified along with detail, so denoise first if the photo is noisy. Strength 0 returns the input.
 */
object Deblurrer {

    fun sigma(strength: Float) = 0.7f + 1.5f * strength.coerceIn(0f, 1f)
    fun iterations(strength: Float) = 3 + (3f * strength.coerceIn(0f, 1f)).roundToInt()
    private const val BETA = 1.2f
    private fun clampRadius(sigma: Float) = kotlin.math.ceil(1.5f * sigma).toInt() + 1

    /** Rows of context an operation needs around a band. */
    fun overlap(strength: Float): Int {
        val sig = sigma(strength)
        return iterations(strength) * PixelKit.gaussReach(sig) + clampRadius(sig) + 8
    }

    fun process(win: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return win.copyOf()
        val n = w * h
        val sig = sigma(s)

        val y = FloatArray(n) { PixelKit.luma(win[it]) }
        // The window must be wide enough for a blurred edge to reach its true plateau values.
        val (mn, mx) = PixelKit.localMinMax(y, w, h, clampRadius(sig))
        val margin = 0.02f

        val est = y.copyOf()
        val tmp = FloatArray(n)
        repeat(iterations(s)) {
            System.arraycopy(est, 0, tmp, 0, n)
            PixelKit.gaussBlur(tmp, w, h, sig)
            for (i in 0 until n) est[i] = (est[i] + BETA * (y[i] - tmp[i])).coerceIn(mn[i] - margin, mx[i] + margin)
        }

        val mix = min(1f, s * 4f)
        val out = IntArray(n)
        for (i in 0 until n) {
            val delta = (est[i].coerceIn(0f, 1f) - y[i]) * mix
            val c = win[i]
            out[i] = PixelKit.argb(
                PixelKit.alpha(c),
                PixelKit.to255(PixelKit.red(c) / 255f + delta),
                PixelKit.to255(PixelKit.green(c) / 255f + delta),
                PixelKit.to255(PixelKit.blue(c) / 255f + delta)
            )
        }
        return out
    }
}