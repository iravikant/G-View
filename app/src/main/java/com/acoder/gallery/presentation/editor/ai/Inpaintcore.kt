package com.acoder.gallery.presentation.editor.ai

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Platform-independent part of the Magic Eraser. Nothing in this file touches Android, Compose or
 * the inference runtime, so it can be unit-tested on the JVM (see test/InpaintCoreTest.kt).
 *
 * Flow (see MagicEraserPipeline):
 *   strokes -> binary mask -> grown mask -> chunks -> per chunk: context window -> engine -> feathered
 *   write-back. Pixels outside the grown mask (plus a few feather pixels) are never modified.
 */

/** Half-open pixel rectangle: x0 <= x < x1, y0 <= y < y1. */
data class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    fun contains(x: Int, y: Int) = x >= x0 && x < x1 && y >= y0 && y < y1
    fun contains(o: Region) = o.x0 >= x0 && o.y0 >= y0 && o.x1 <= x1 && o.y1 <= y1
}

/** Read / write access to the photo being edited, in ARGB_8888 ints. */
interface PixelSurface {
    val width: Int
    val height: Int
    fun read(r: Region): IntArray
    fun write(r: Region, pixels: IntArray)
}

/**
 * An inpainting model. [pixels] is a width*height ARGB window; [known] marks pixels that are real
 * (true) or must be reconstructed (false). Returns a window of the same size.
 */
interface InpaintEngine {
    fun inpaint(pixels: IntArray, known: BooleanArray, width: Int, height: Int): IntArray
}

data class EraserParams(
    /** How far the user's mask is grown to swallow edges, halos and thin shadows (px). */
    val dilatePx: Int,
    /** Soft transition just outside the grown mask (px). */
    val featherPx: Int,
    /** Context kept around each chunk (px). */
    val contextPad: Int = 160,
    /** The model works at 512 px; windows are at least this big when the image allows it. */
    val minWindow: Int = 512,
    /** Smallest chunk edge (px). Larger masks are processed as several chunks. */
    val minChunk: Int = 320,
    val maxChunksPerAxis: Int = 5
)

fun defaultParams(width: Int, height: Int, brushPx: Float): EraserParams {
    val shortSide = min(width, height)
    return EraserParams(
        dilatePx = (brushPx * 0.15f).roundToInt().coerceIn(3, 16),
        featherPx = (shortSide / 1000f * 3f).roundToInt().coerceIn(2, 6)
    )
}

data class EraseStats(val chunks: Int, val holePixels: Int)

// ───────────────────────── Mask geometry ─────────────────────────

/** Tight bounding box of the true pixels of [mask] (optionally only inside [within]), or null. */
fun boundsOf(mask: BooleanArray, w: Int, h: Int, within: Region? = null): Region? {
    val area = within ?: Region(0, 0, w, h)
    var x0 = Int.MAX_VALUE
    var y0 = Int.MAX_VALUE
    var x1 = -1
    var y1 = -1
    for (y in area.y0 until area.y1) {
        val row = y * w
        for (x in area.x0 until area.x1) {
            if (mask[row + x]) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
    }
    return if (x1 < 0) null else Region(x0, y0, x1 + 1, y1 + 1)
}

fun grow(r: Region, by: Int, w: Int, h: Int) =
    Region(max(0, r.x0 - by), max(0, r.y0 - by), min(w, r.x1 + by), min(h, r.y1 + by))

/**
 * Grows [mask] by about [radius] pixels (round, not square) using a 3-4 chamfer distance transform.
 * Returns a new array; [mask] is untouched. [bounds] must contain every true pixel of [mask].
 */
fun dilate(mask: BooleanArray, w: Int, h: Int, bounds: Region, radius: Int): BooleanArray {
    val out = mask.copyOf()
    if (radius <= 0) return out
    val rad = radius.coerceAtMost(40)               // 3 * 40 still fits in a byte
    val r = grow(bounds, rad, w, h)
    val rw = r.width
    val rh = r.height
    val far = 127
    val d = ByteArray(rw * rh)
    for (y in 0 until rh) {
        val src = (r.y0 + y) * w + r.x0
        for (x in 0 until rw) d[y * rw + x] = if (mask[src + x]) 0 else far.toByte()
    }
    for (y in 0 until rh) {
        for (x in 0 until rw) {
            val i = y * rw + x
            var v = d[i].toInt()
            if (v == 0) continue
            if (x > 0) v = min(v, d[i - 1] + 3)
            if (y > 0) {
                v = min(v, d[i - rw] + 3)
                if (x > 0) v = min(v, d[i - rw - 1] + 4)
                if (x < rw - 1) v = min(v, d[i - rw + 1] + 4)
            }
            d[i] = min(v, far).toByte()
        }
    }
    for (y in rh - 1 downTo 0) {
        for (x in rw - 1 downTo 0) {
            val i = y * rw + x
            var v = d[i].toInt()
            if (v == 0) continue
            if (x < rw - 1) v = min(v, d[i + 1] + 3)
            if (y < rh - 1) {
                v = min(v, d[i + rw] + 3)
                if (x < rw - 1) v = min(v, d[i + rw + 1] + 4)
                if (x > 0) v = min(v, d[i + rw - 1] + 4)
            }
            d[i] = min(v, far).toByte()
        }
    }
    val limit = rad * 3
    for (y in 0 until rh) {
        val dst = (r.y0 + y) * w + r.x0
        for (x in 0 until rw) if (d[y * rw + x] <= limit) out[dst + x] = true
    }
    return out
}

/**
 * A stroke is a closed lasso (its inside is selected too) only when it ends near where it began.
 * Open brush strokes select just the painted band. [pts] is x0,y0,x1,y1... normalised to the image.
 */
fun isClosedStroke(pts: FloatArray, w: Int, h: Int, brushPx: Float): Boolean {
    val n = pts.size / 2
    if (n < 6) return false
    val dx = (pts[0] - pts[2 * (n - 1)]) * w
    val dy = (pts[1] - pts[2 * (n - 1) + 1]) * h
    val gap = sqrt(dx * dx + dy * dy)
    // total drawn length must be well above the gap, otherwise it is just a short squiggle
    var len = 0f
    for (i in 1 until n) {
        val sx = (pts[2 * i] - pts[2 * i - 2]) * w
        val sy = (pts[2 * i + 1] - pts[2 * i - 1]) * h
        len += sqrt(sx * sx + sy * sy)
    }
    return gap <= max(brushPx * 1.5f, 0.04f * min(w, h)) && len > gap * 3f
}

// ───────────────────────── Chunking ─────────────────────────

/** [cell] owns the mask pixels inside it; [tight] is the bounding box of those pixels. */
class Chunk(val cell: Region, val tight: Region)

/**
 * Splits a large mask into a grid of chunks that are inpainted one after another. The model sees a
 * 512 px view, so big holes are filled progressively with fresh context, as its authors recommend.
 */
fun planChunks(mask: BooleanArray, w: Int, h: Int, bounds: Region, p: EraserParams): List<Chunk> {
    val longest = max(bounds.width, bounds.height)
    val cs = max(p.minChunk, ceil(longest / p.maxChunksPerAxis.toFloat()).toInt())
    val out = ArrayList<Chunk>()
    var cy = bounds.y0
    while (cy < bounds.y1) {
        var cx = bounds.x0
        while (cx < bounds.x1) {
            val cell = Region(cx, cy, min(cx + cs, bounds.x1), min(cy + cs, bounds.y1))
            boundsOf(mask, w, h, cell)?.let { out += Chunk(cell, it) }
            cx += cs
        }
        cy += cs
    }
    return out
}

/** Square-ish window around [t] with [EraserParams.contextPad] of context, kept inside the image. */
fun contextWindow(t: Region, w: Int, h: Int, p: EraserParams): Region {
    val side = max(max(t.width, t.height) + 2 * p.contextPad, p.minWindow)
    val cw = min(side, w)
    val ch = min(side, h)
    val x0 = ((t.x0 + t.x1) / 2 - cw / 2).coerceIn(0, w - cw)
    val y0 = ((t.y0 + t.y1) / 2 - ch / 2).coerceIn(0, h - ch)
    return Region(x0, y0, x0 + cw, y0 + ch)
}

/** In-place box blur of a [w] x [h] float image (edges clamped) using running sums. */
fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
    if (r < 1) return
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

// ───────────────────────── Pipeline ─────────────────────────

class MagicEraserPipeline(private val engine: InpaintEngine) {

    /**
     * Erases the true pixels of [rawMask] (width*height of [surface]) and writes the reconstruction
     * into [surface]. Pixels outside the grown mask and its feather ring stay bit-identical.
     * Throws [CancellationException] if [isCancelled] turns true between chunks.
     */
    fun erase(
        surface: PixelSurface,
        rawMask: BooleanArray,
        params: EraserParams,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): EraseStats {
        val w = surface.width
        val h = surface.height
        require(rawMask.size == w * h) { "mask size does not match the image" }

        val raw = boundsOf(rawMask, w, h) ?: return EraseStats(0, 0)
        val hole = dilate(rawMask, w, h, raw, params.dilatePx)
        val grown = grow(raw, params.dilatePx, w, h)
        val chunks = planChunks(hole, w, h, grown, params)
        var holePixels = 0
        for (y in grown.y0 until grown.y1) for (x in grown.x0 until grown.x1) if (hole[y * w + x]) holePixels++

        onProgress(0, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            if (isCancelled()) throw CancellationException("Erase cancelled")
            processChunk(surface, hole, chunk, params)
            onProgress(index + 1, chunks.size)
        }
        return EraseStats(chunks.size, holePixels)
    }

    private fun processChunk(surface: PixelSurface, hole: BooleanArray, chunk: Chunk, p: EraserParams) {
        val w = surface.width
        val h = surface.height
        val win = contextWindow(chunk.tight, w, h, p)
        val ww = win.width
        val wh = win.height

        val px = surface.read(win)
        val known = BooleanArray(ww * wh)
        for (y in 0 until wh) {
            val row = (win.y0 + y) * w + win.x0
            for (x in 0 until ww) known[y * ww + x] = !hole[row + x]
        }

        val result = engine.inpaint(px, known, ww, wh)
        require(result.size == px.size) { "engine returned a window of the wrong size" }

        // Feathered write-back limited to this chunk's hole pixels plus a thin ring around them.
        val f = p.featherPx
        val rr = grow(chunk.tight, f, w, h)
        check(win.contains(rr)) { "context window does not cover the chunk" }
        val rw = rr.width
        val rh = rr.height
        val soft = FloatArray(rw * rh)
        val cell = chunk.cell
        for (y in 0 until rh) {
            val gy = rr.y0 + y
            for (x in 0 until rw) {
                val gx = rr.x0 + x
                if (cell.contains(gx, gy) && hole[gy * w + gx]) soft[y * rw + x] = 1f
            }
        }
        boxBlur(soft, rw, rh, f)

        val outPx = IntArray(rw * rh)
        for (y in 0 until rh) {
            val gy = rr.y0 + y
            for (x in 0 until rw) {
                val gx = rr.x0 + x
                val wi = (gy - win.y0) * ww + (gx - win.x0)
                val isHole = hole[gy * w + gx]
                val own = isHole && cell.contains(gx, gy)
                val alpha = when {
                    own -> 1f
                    isHole -> 0f                              // belongs to a later chunk
                    else -> min(1f, soft[y * rw + x] * 2f)    // feather ring outside the hole
                }
                val o = px[wi]
                outPx[y * rw + x] = if (alpha <= 0f) o else if (alpha >= 1f) {
                    (o and 0xFF000000.toInt()) or (result[wi] and 0x00FFFFFF)
                } else {
                    val u = result[wi]
                    val r = lerp((o shr 16) and 255, (u shr 16) and 255, alpha)
                    val g = lerp((o shr 8) and 255, (u shr 8) and 255, alpha)
                    val b = lerp(o and 255, u and 255, alpha)
                    (o and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        surface.write(rr, outPx)

        // These pixels are now real content for the following chunks.
        for (y in cell.y0 until cell.y1) for (x in cell.x0 until cell.x1) hole[y * w + x] = false
    }

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
}

// ───────────────────────── Zoom / pan maths ─────────────────────────

/**
 * Zoom and pan of the preview. The image (iw x ih px at zoom 1) is centred in a container
 * (cw x ch px); the layer is scaled about its centre and then translated by (tx, ty).
 */
data class ViewState(val zoom: Float = 1f, val tx: Float = 0f, val ty: Float = 0f)

object ViewMath {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f

    fun clamp(v: ViewState, cw: Float, ch: Float, iw: Float, ih: Float): ViewState {
        val z = v.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxX = max(0f, (iw * z - cw) / 2f)
        val maxY = max(0f, (ih * z - ch) / 2f)
        return ViewState(z, v.tx.coerceIn(-maxX, maxX), v.ty.coerceIn(-maxY, maxY))
    }

    /** Multiplies the zoom by [factor] keeping the image point under (fx, fy) fixed. */
    fun zoomAbout(v: ViewState, fx: Float, fy: Float, factor: Float, cw: Float, ch: Float, iw: Float, ih: Float): ViewState {
        if (!factor.isFinite() || factor <= 0f || !fx.isFinite() || !fy.isFinite()) return v
        val z2 = (v.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val qx = (fx - cw / 2f - v.tx) / v.zoom
        val qy = (fy - ch / 2f - v.ty) / v.zoom
        return clamp(ViewState(z2, fx - cw / 2f - qx * z2, fy - ch / 2f - qy * z2), cw, ch, iw, ih)
    }

    fun pan(v: ViewState, dx: Float, dy: Float, cw: Float, ch: Float, iw: Float, ih: Float): ViewState {
        if (!dx.isFinite() || !dy.isFinite()) return v
        return clamp(v.copy(tx = v.tx + dx, ty = v.ty + dy), cw, ch, iw, ih)
    }

    /** Container point -> position in the image, normalised to 0..1 and clamped to the image. */
    fun toNormalized(v: ViewState, px: Float, py: Float, cw: Float, ch: Float, iw: Float, ih: Float): Pair<Float, Float> {
        val qx = (px - cw / 2f - v.tx) / v.zoom + iw / 2f
        val qy = (py - ch / 2f - v.ty) / v.zoom + ih / 2f
        return (qx / iw).coerceIn(0f, 1f) to (qy / ih).coerceIn(0f, 1f)
    }
}

/** Convenience for tests and callers: absolute difference of two ARGB ints, per channel max. */
fun channelDiff(a: Int, b: Int): Int = max(
    abs(((a shr 16) and 255) - ((b shr 16) and 255)),
    max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))
)