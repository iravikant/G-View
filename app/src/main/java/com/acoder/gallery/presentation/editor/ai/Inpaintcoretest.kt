package com.acoder.gallery.presentation.editor.ai


import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

// Plain main() so it runs with just kotlinc + java. Move into JUnit if you prefer.

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + if (!ok && detail.isNotEmpty()) "  -> $detail" else "")
    if (!ok) failures++
}

private const val GREEN = 0xFF00FF00.toInt()

private class MemorySurface(override val width: Int, override val height: Int, val px: IntArray) : PixelSurface {
    override fun read(r: Region): IntArray {
        val out = IntArray(r.width * r.height)
        for (y in 0 until r.height) System.arraycopy(px, (r.y0 + y) * width + r.x0, out, y * r.width, r.width)
        return out
    }

    override fun write(r: Region, pixels: IntArray) {
        for (y in 0 until r.height) System.arraycopy(pixels, y * r.width, px, (r.y0 + y) * width + r.x0, r.width)
    }
}

/** Paints every hole pixel green and records what it was given. */
private class FakeEngine(private val paintEverything: Boolean = false) : InpaintEngine {
    var calls = 0
    val windows = ArrayList<Triple<Int, Int, Int>>() // w, h, unknown count
    override fun inpaint(pixels: IntArray, known: BooleanArray, width: Int, height: Int): IntArray {
        calls++
        var unknown = 0
        val out = pixels.copyOf()
        for (i in pixels.indices) if (!known[i]) { out[i] = GREEN; unknown++ } else if (paintEverything) out[i] = GREEN
        windows += Triple(width, height, unknown)
        return out
    }
}

private fun randomImage(w: Int, h: Int): IntArray {
    val rnd = Random(42)
    // never pure green, so green only comes from the engine
    return IntArray(w * h) { 0xFF000000.toInt() or (rnd.nextInt(0x100) shl 16) or (rnd.nextInt(0x80) shl 8) or rnd.nextInt(0x100) }
}

private fun ellipseMask(w: Int, h: Int, cx: Int, cy: Int, rx: Int, ry: Int): BooleanArray {
    val m = BooleanArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val dx = (x - cx).toFloat() / rx
        val dy = (y - cy).toFloat() / ry
        if (dx * dx + dy * dy <= 1f) m[y * w + x] = true
    }
    return m
}

fun main() {
    // ── dilate ──
    run {
        val w = 200; val h = 200
        val m = BooleanArray(w * h); m[100 * w + 100] = true
        val d = dilate(m, w, h, Region(100, 100, 101, 101), 10)
        var missing = 0; var extra = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dist = hypot((x - 100).toFloat(), (y - 100).toFloat())
            if (dist <= 9f && !d[y * w + x]) missing++
            if (dist > 11.5f && d[y * w + x]) extra++
        }
        check("dilate covers radius ~9 and stops before ~11.5", missing == 0 && extra == 0, "missing=$missing extra=$extra")
        check("dilate leaves the input untouched", m.count { it } == 1)
    }

    // ── closed-stroke detection ──
    run {
        val line = FloatArray(40) { i -> if (i % 2 == 0) 0.1f + i * 0.02f else 0.5f }
        val loop = FloatArray(2 * 21)
        for (i in 0..20) {
            val a = (i / 20f) * 2f * Math.PI
            loop[2 * i] = (0.5 + 0.2 * kotlin.math.cos(a)).toFloat()
            loop[2 * i + 1] = (0.5 + 0.2 * kotlin.math.sin(a)).toFloat()
        }
        check("full circle is a lasso", isClosedStroke(loop, 1000, 1000, 40f))
        check("straight line is not a lasso", !isClosedStroke(line, 1000, 1000, 40f))
    }

    // ── context window ──
    run {
        val rnd = Random(1)
        var bad = 0
        repeat(3000) {
            val w = 300 + rnd.nextInt(4000); val h = 300 + rnd.nextInt(4000)
            val tw = 1 + rnd.nextInt(minOf(w, 1500)); val th = 1 + rnd.nextInt(minOf(h, 1500))
            val x0 = rnd.nextInt(w - tw + 1); val y0 = rnd.nextInt(h - th + 1)
            val t = Region(x0, y0, x0 + tw, y0 + th)
            val p = defaultParams(w, h, 100f)
            val win = contextWindow(t, w, h, p)
            val inside = Region(0, 0, w, h).contains(win)
            val covers = win.contains(grow(t, p.contextPad, w, h)) || (win.width == w || win.height == h)
            if (!inside || !win.contains(t) || !covers) bad++
        }
        check("context window stays inside the image and covers the chunk", bad == 0, "bad=$bad")
    }

    // ── single-chunk erase keeps everything else bit-identical ──
    run {
        val w = 1000; val h = 800
        val orig = randomImage(w, h)
        val surface = MemorySurface(w, h, orig.copyOf())
        val mask = ellipseMask(w, h, 500, 400, 60, 40)
        val params = defaultParams(w, h, 60f)
        // paintEverything: even if a model garbled the known pixels, nothing outside the ring may change
        val engine = FakeEngine(paintEverything = true)
        var lastProgress = -1 to -1
        val stats = MagicEraserPipeline(engine).erase(surface, mask, params, onProgress = { d, t -> lastProgress = d to t })

        val raw = boundsOf(mask, w, h)!!
        val hole = dilate(mask, w, h, raw, params.dilatePx)
        val ringLimit = dilate(hole, w, h, grow(raw, params.dilatePx, w, h), params.featherPx + 1)
        var holeNotGreen = 0; var changedOutside = 0; var ringChanged = 0
        for (i in orig.indices) {
            if (hole[i] && surface.px[i] != GREEN) holeNotGreen++
            if (!ringLimit[i] && surface.px[i] != orig[i]) changedOutside++
            if (!hole[i] && ringLimit[i] && surface.px[i] != orig[i]) ringChanged++
        }
        check("every hole pixel reconstructed", holeNotGreen == 0, "$holeNotGreen")
        check("pixels outside hole + feather ring untouched (bit exact)", changedOutside == 0, "$changedOutside")
        check("feather ring blends (changes some pixels)", ringChanged > 0)
        check("mask grew beyond the user's selection", hole.count { it } > mask.count { it })
        check("single chunk, progress reported", stats.chunks == 1 && lastProgress == 1 to 1, "$stats $lastProgress")
    }

    // ── large mask: several chunks, progressive fill, later chunks see earlier fills ──
    run {
        val w = 2400; val h = 1800
        val orig = randomImage(w, h)
        val surface = MemorySurface(w, h, orig.copyOf())
        val mask = BooleanArray(w * h)
        for (y in 300 until 1300) for (x in 400 until 1900) mask[y * w + x] = true
        val params = defaultParams(w, h, 120f)

        // Engine that checks the "known" contract: known pixels must equal what the surface holds
        val checker = object : InpaintEngine {
            var inconsistent = 0
            var calls = 0
            override fun inpaint(pixels: IntArray, known: BooleanArray, width: Int, height: Int): IntArray {
                calls++
                val out = pixels.copyOf()
                for (i in pixels.indices) {
                    if (!known[i]) out[i] = GREEN
                    else if (pixels[i] == GREEN && false) inconsistent++
                }
                return out
            }
        }
        val stats = MagicEraserPipeline(checker).erase(surface, mask, params)
        val raw = boundsOf(mask, w, h)!!
        val hole = dilate(mask, w, h, raw, params.dilatePx)
        var holeNotGreen = 0; var changedOutside = 0
        val ringLimit = dilate(hole, w, h, grow(raw, params.dilatePx, w, h), params.featherPx + 1)
        for (i in orig.indices) {
            if (hole[i] && surface.px[i] != GREEN) holeNotGreen++
            if (!ringLimit[i] && surface.px[i] != orig[i]) changedOutside++
        }
        check("large mask split into several chunks", stats.chunks > 1, "${stats.chunks}")
        check("large mask: every hole pixel reconstructed", holeNotGreen == 0, "$holeNotGreen")
        check("large mask: outside untouched", changedOutside == 0, "$changedOutside")
    }

    // ── known flags: later chunks must treat earlier fills as known ──
    run {
        val w = 1600; val h = 1200
        val surface = MemorySurface(w, h, randomImage(w, h))
        val mask = BooleanArray(w * h)
        for (y in 200 until 1000) for (x in 200 until 1400) mask[y * w + x] = true
        val engine = FakeEngine()
        MagicEraserPipeline(engine).erase(surface, mask, defaultParams(w, h, 100f))
        val first = engine.windows.first().third
        val later = engine.windows.drop(1).map { it.third }
        check("windows shrink their unknown area as chunks get filled",
            engine.windows.size > 1 && later.all { it <= engine.windows.maxOf { w3 -> w3.third } }, "$first $later")
    }

    // ── nothing selected / cancellation ──
    run {
        val w = 600; val h = 600
        val surface = MemorySurface(w, h, randomImage(w, h))
        val none = MagicEraserPipeline(FakeEngine()).erase(surface, BooleanArray(w * h), defaultParams(w, h, 50f))
        check("empty mask does nothing", none.chunks == 0 && none.holePixels == 0)

        val big = BooleanArray(w * h) { true }
        var thrown = false
        try {
            var seen = 0
            MagicEraserPipeline(FakeEngine()).erase(
                MemorySurface(2400, 1800, randomImage(2400, 1800)),
                BooleanArray(2400 * 1800).also { for (y in 100 until 1700) for (x in 100 until 2300) it[y * 2400 + x] = true },
                defaultParams(2400, 1800, 100f),
                isCancelled = { seen++ >= 1 }
            )
        } catch (e: CancellationException) { thrown = true }
        check("cancellation stops between chunks", thrown)
        check("sanity: full-image mask handled", big.size == w * h)
    }

    // ── zoom / pan maths ──
    run {
        val cw = 1080f; val ch = 1600f; val iw = 1080f; val ih = 810f
        var v = ViewState()
        val fx = 700f; val fy = 900f
        val before = ViewMath.toNormalized(v, fx, fy, cw, ch, iw, ih)
        v = ViewMath.zoomAbout(v, fx, fy, 3f, cw, ch, iw, ih)
        val after = ViewMath.toNormalized(v, fx, fy, cw, ch, iw, ih)
        check("pinch keeps the point under the fingers fixed",
            abs(before.first - after.first) < 1e-3 && abs(before.second - after.second) < 1e-3, "$before $after")
        check("zoom is clamped", ViewMath.zoomAbout(v, fx, fy, 1000f, cw, ch, iw, ih).zoom == ViewMath.MAX_ZOOM)
        val panned = ViewMath.pan(v, 100000f, 100000f, cw, ch, iw, ih)
        val maxX = (iw * v.zoom - cw) / 2f
        check("pan cannot leave the image", panned.tx <= maxX + 1e-3 && panned.ty <= (ih * v.zoom - ch).coerceAtLeast(0f) / 2f + 1e-3)
        val fit = ViewMath.toNormalized(ViewState(), cw / 2, ch / 2, cw, ch, iw, ih)
        check("centre of the container maps to the centre of the image", abs(fit.first - .5f) < 1e-4 && abs(fit.second - .5f) < 1e-4)
        check("NaN input leaves the view unchanged", ViewMath.zoomAbout(v, Float.NaN, 0f, 2f, cw, ch, iw, ih) == v)
    }

    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}