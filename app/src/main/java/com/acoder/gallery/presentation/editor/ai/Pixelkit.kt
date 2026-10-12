package com.acoder.gallery.presentation.editor.ai


import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Platform-independent building blocks used by the photo tools (denoise, deblur, retouch, lens blur,
 * object move, tone/detail adjustments). Nothing here touches Android, so it is unit-tested on the JVM.
 */

object PixelKit {

    fun red(c: Int) = (c shr 16) and 255
    fun green(c: Int) = (c shr 8) and 255
    fun blue(c: Int) = c and 255
    fun alpha(c: Int) = c ushr 24

    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Float 0..1 channel -> 0..255 int, clamped and rounded. */
    fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 == e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Rec.601 luma, 0..1. */
    fun luma(c: Int): Float = (0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)) / 255f

    // ───────────── blur ─────────────

    /** In-place separable box blur with edge replication. [r] is the radius in pixels. */
    fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1 || w < 1 || h < 1) return
        val line = FloatArray(max(w, h))
        val div = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (x in -r..r) sum += a[o + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                line[x] = sum / div
                sum += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)]
            }
            System.arraycopy(line, 0, a, o, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += a[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                line[y] = sum / div
                sum += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = line[y]
        }
    }

    /** Box widths whose repeated application approximates a Gaussian of [sigma] (Kovesi). */
    fun boxSizesForGauss(sigma: Float, passes: Int = 3): IntArray {
        val ideal = sqrt(12f * sigma * sigma / passes + 1f)
        var wl = floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        wl = max(wl, 1)
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - passes * wl * wl - 4f * passes * wl - 3f * passes) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt().coerceIn(0, passes)
        return IntArray(passes) { if (it < m) wl else wu }
    }

    /** In-place Gaussian blur approximated by three box blurs. Total reach is about 3 * sigma. */
    fun gaussBlur(a: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.35f) return
        for (size in boxSizesForGauss(sigma)) boxBlur(a, w, h, (size - 1) / 2)
    }

    /** Pixels a Gaussian of [sigma] can reach, for choosing band overlap. */
    fun gaussReach(sigma: Float): Int = boxSizesForGauss(sigma).sumOf { (it - 1) / 2 } + 1

    // ───────────── edge-preserving smoothing ─────────────

    /**
     * Guided filter (He et al.): smooths [p] while following the edges of [guide]. Pass the same array
     * for both to get an edge-preserving smoother. [eps] is the edge threshold (variance units).
     * Linear in the number of pixels whatever [r] is. Output reach is about 2 * r.
     */
    fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = guide.copyOf().also { boxBlur(it, w, h, r) }
        val meanP = if (guide === p) meanI else p.copyOf().also { boxBlur(it, w, h, r) }
        val corrIp = FloatArray(n) { guide[it] * p[it] }.also { boxBlur(it, w, h, r) }
        val corrII = if (guide === p) corrIp else FloatArray(n) { guide[it] * guide[it] }.also { boxBlur(it, w, h, r) }
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrII[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            val ai = cov / (varI + eps)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }
        boxBlur(a, w, h, r)
        boxBlur(b, w, h, r)
        return FloatArray(n) { a[it] * guide[it] + b[it] }
    }

    // ───────────── colour planes ─────────────

    /** Splits ARGB pixels into R, G, B float planes (0..1). */
    fun splitRgb(px: IntArray): Array<FloatArray> {
        val n = px.size
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = red(c) / 255f
            g[i] = green(c) / 255f
            b[i] = blue(c) / 255f
        }
        return arrayOf(r, g, b)
    }

    /** Local min and max over a (2r+1) square, separable. */
    fun localMinMax(a: FloatArray, w: Int, h: Int, r: Int): Pair<FloatArray, FloatArray> {
        fun pass(src: FloatArray, horizontal: Boolean, isMax: Boolean): FloatArray {
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var best = src[y * w + x]
                for (d in -r..r) {
                    val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                    val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                    val v = src[yy * w + xx]
                    if (if (isMax) v > best else v < best) best = v
                }
                out[y * w + x] = best
            }
            return out
        }
        val mn = pass(pass(a, true, false), false, false)
        val mx = pass(pass(a, true, true), false, true)
        return mn to mx
    }
}

/**
 * Runs a local pixel operation over a big image in horizontal bands so only a few megapixels are in
 * memory at once. Each band is processed together with [overlap] extra rows above and below, so the
 * result matches processing the whole image as long as the operation's reach is <= [overlap].
 */
object Bands {

    fun interface Op {
        /** [win] is [w] x [rows]; [winY0] is the image row of the window's first row. Returns the same size. */
        fun run(win: IntArray, w: Int, rows: Int, winY0: Int): IntArray
    }

    fun run(
        width: Int,
        height: Int,
        overlap: Int,
        targetPixels: Int = 1_500_000,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
        read: (y0: Int, rows: Int) -> IntArray,
        write: (y0: Int, rows: Int, px: IntArray) -> Unit,
        op: Op
    ) {
        require(width > 0 && height > 0) { "empty image" }
        val bandRows = max(max(1, targetPixels / width), 2 * overlap + 1).coerceAtMost(height)
        var y = 0
        while (y < height) {
            if (!shouldContinue()) throw kotlin.coroutines.cancellation.CancellationException("cancelled")
            val y1 = min(height, y + bandRows)
            val ry0 = max(0, y - overlap)
            val ry1 = min(height, y1 + overlap)
            val rows = ry1 - ry0
            val result = op.run(read(ry0, rows), width, rows, ry0)
            require(result.size == width * rows) { "operation returned a window of the wrong size" }
            val interior = IntArray(width * (y1 - y))
            System.arraycopy(result, (y - ry0) * width, interior, 0, interior.size)
            write(y, y1 - y, interior)
            y = y1
            onProgress(y.toFloat() / height)
        }
    }

    /** Convenience for in-memory images. */
    fun runInMemory(src: IntArray, width: Int, height: Int, overlap: Int, targetPixels: Int = 1_500_000, op: Op): IntArray {
        val out = IntArray(src.size)
        run(
            width, height, overlap, targetPixels,
            read = { y0, rows -> src.copyOfRange(y0 * width, (y0 + rows) * width) },
            write = { y0, rows, px -> System.arraycopy(px, 0, out, y0 * width, rows * width) },
            op = op
        )
        return out
    }
}

/** Area-average downscale computed from rows, so the full-size image never has to be in memory. */
object Downscale {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun run(
        width: Int,
        height: Int,
        maxSide: Int,
        shouldContinue: () -> Boolean = { true },
        read: (y0: Int, rows: Int) -> IntArray
    ): Result {
        val scale = min(1f, maxSide.toFloat() / max(width, height))
        val lw = max(1, (width * scale).roundToInt())
        val lh = max(1, (height * scale).roundToInt())
        val sum = FloatArray(lw * lh * 3)
        val cnt = FloatArray(lw * lh)
        val chunk = max(1, 1_000_000 / width)
        var y = 0
        while (y < height) {
            if (!shouldContinue()) throw kotlin.coroutines.cancellation.CancellationException("cancelled")
            val rows = min(chunk, height - y)
            val px = read(y, rows)
            for (yy in 0 until rows) {
                val ly = ((y + yy).toLong() * lh / height).toInt().coerceAtMost(lh - 1)
                for (x in 0 until width) {
                    val lx = (x.toLong() * lw / width).toInt().coerceAtMost(lw - 1)
                    val c = px[yy * width + x]
                    val o = ly * lw + lx
                    sum[o * 3] += PixelKit.red(c)
                    sum[o * 3 + 1] += PixelKit.green(c)
                    sum[o * 3 + 2] += PixelKit.blue(c)
                    cnt[o] += 1f
                }
            }
            y += rows
        }
        val out = IntArray(lw * lh) { i ->
            val c = max(cnt[i], 1f)
            PixelKit.argb(255, (sum[i * 3] / c).roundToInt(), (sum[i * 3 + 1] / c).roundToInt(), (sum[i * 3 + 2] / c).roundToInt())
        }
        return Result(out, lw, lh)
    }

    fun ofArray(src: IntArray, width: Int, height: Int, maxSide: Int): Result =
        run(width, height, maxSide) { y0, rows -> src.copyOfRange(y0 * width, (y0 + rows) * width) }
}