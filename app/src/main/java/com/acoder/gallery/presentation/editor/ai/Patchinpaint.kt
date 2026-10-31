package com.acoder.gallery.presentation.editor.ai

import android.graphics.Bitmap
import com.acoder.gallery.presentation.editor.resampleHighQuality
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Object removal by patch synthesis ("image completion", Wexler et al. with PatchMatch, Barnes et al.).
 *
 * The hole is filled from coarse to fine. At every size, each 7x7 patch that touches the hole is matched with the
 * most similar patch from the untouched part of the SAME photo (PatchMatch), and the hole is rebuilt from the
 * overlapping best matches. Repeating this refines textures (grass, sand, brick) and keeps straight edges going.
 * No model file is needed.
 *
 * It works on a copy of the area around the hole (at most [WORK] px) and only the hole pixels are written back
 * to the real photo, so the rest of the photo is never touched.
 */
internal object PatchInpaint {

    private const val R = 3              // patch radius: 7 x 7 patches
    private const val WORK = 640         // longest side of the working copy

    /**
     * @param mask   255 where the user marked what to remove
     * @param growPx how far the mask is grown, to also catch shadows and soft edges of the object
     * @param avoidMask optional boolean array marking pixels (e.g. subjects/people/animals) to NOT sample from
     * @param check  called regularly; throw from it to cancel
     */
    fun erase(
        img: Bitmap,
        mask: ByteArray,
        growPx: Int,
        avoidMask: BooleanArray? = null,
        progress: (Float) -> Unit,
        check: () -> Unit
    ): Bitmap {
        val w = img.width
        val h = img.height
        val hole = grow(BooleanArray(w * h) { (mask[it].toInt() and 0xFF) > 127 }, w, h, growPx)

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (hole[row + x]) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return img

        // area around the hole: plenty of surroundings so there is good material to copy from
        val margin = max(64, max(maxX - minX, maxY - minY))
        val x0 = max(0, minX - margin)
        val y0 = max(0, minY - margin)
        val cw = min(w, maxX + margin + 1) - x0
        val ch = min(h, maxY + margin + 1) - y0

        val crop = Bitmap.createBitmap(img, x0, y0, cw, ch)
        val scale = min(1f, WORK.toFloat() / max(cw, ch))
        val sw = max(24, (cw * scale).roundToInt())
        val sh = max(24, (ch * scale).roundToInt())
        val small = if (sw == cw && sh == ch) crop else resampleHighQuality(crop, sw, sh)
        val smallPx = IntArray(sw * sh)
        small.getPixels(smallPx, 0, sw, 0, 0, sw, sh)

        // hole at working size: every real hole pixel marks its working-size pixel
        var smallHole = BooleanArray(sw * sh)
        for (v in 0 until ch) {
            val sy = min(sh - 1, (v.toLong() * sh / ch).toInt())
            val row = (y0 + v) * w + x0
            for (u in 0 until cw) if (hole[row + u]) smallHole[sy * sw + min(sw - 1, (u.toLong() * sw / cw).toInt())] = true
        }
        smallHole = grow(smallHole, sw, sh, 1)

        // avoidMask at working size (excluding object/people/animal pixels from source candidate patches)
        var smallAvoid: BooleanArray? = null
        if (avoidMask != null) {
            smallAvoid = BooleanArray(sw * sh)
            for (v in 0 until ch) {
                val sy = min(sh - 1, (v.toLong() * sh / ch).toInt())
                val row = (y0 + v) * w + x0
                for (u in 0 until cw) {
                    val idx = row + u
                    if (idx in avoidMask.indices && avoidMask[idx]) {
                        smallAvoid[sy * sw + min(sw - 1, (u.toLong() * sw / cw).toInt())] = true
                    }
                }
            }
            smallAvoid = grow(smallAvoid, sw, sh, 2)
        }

        val filled = fill(smallPx, smallHole, smallAvoid, sw, sh, progress, check)

        // back to the real size
        val predSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { it.setPixels(filled, 0, sw, 0, 0, sw, sh) }
        val pred = if (sw == cw && sh == ch) predSmall else Bitmap.createScaledBitmap(predSmall, cw, ch, true)
        val predPx = IntArray(cw * ch)
        pred.getPixels(predPx, 0, cw, 0, 0, cw, ch)

        // write back: hole pixels fully, a 2 px soft edge around them
        val edge = FloatArray(cw * ch)
        for (v in 0 until ch) for (u in 0 until cw) if (hole[(y0 + v) * w + x0 + u]) edge[v * cw + u] = 1f
        val soft = blur(edge, cw, ch, 2)

        val out = img.copy(Bitmap.Config.ARGB_8888, true)
        val orig = IntArray(cw * ch)
        out.getPixels(orig, 0, cw, x0, y0, cw, ch)
        for (k in orig.indices) {
            val a = if (edge[k] > 0.5f) 1f else (soft[k] * 1.5f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val o = orig[k]
            val p = predPx[k]
            val r = (((o shr 16) and 255) * (1f - a) + ((p shr 16) and 255) * a).roundToInt()
            val g = (((o shr 8) and 255) * (1f - a) + ((p shr 8) and 255) * a).roundToInt()
            val b = ((o and 255) * (1f - a) + (p and 255) * a).roundToInt()
            orig[k] = (o and (255 shl 24)) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(orig, 0, cw, x0, y0, cw, ch)
        return out
    }

    // ---------------------------------------------------------------------
    // One size of the pyramid
    // ---------------------------------------------------------------------

    private class Lvl(val w: Int, val h: Int, val px: IntArray, val hole: BooleanArray, val avoid: BooleanArray? = null) {
        val n = w * h
        /** centre of a patch with no hole pixel and no avoid pixel in it: can be copied from */
        val valid = BooleanArray(n)
        val cands = IntArray(n)
        var nc = 0
        /** centre of a patch that touches the hole: has to be rebuilt */
        val isTarget = BooleanArray(n)
        val targets = IntArray(n)
        var nt = 0
        /** best match for each target: where it comes from, and how different the two patches are */
        val nx = IntArray(n)
        val ny = IntArray(n)
        val cost = IntArray(n)

        init {
            val iw = w + 1
            val integHole = IntArray(iw * (h + 1))
            val integAvoid = IntArray(iw * (h + 1))
            for (y in 0 until h) {
                var rowHole = 0
                var rowAvoid = 0
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (hole[idx]) rowHole++
                    if ((avoid != null && avoid[idx]) || hole[idx]) rowAvoid++
                    integHole[(y + 1) * iw + x + 1] = integHole[y * iw + x + 1] + rowHole
                    integAvoid[(y + 1) * iw + x + 1] = integAvoid[y * iw + x + 1] + rowAvoid
                }
            }
            for (y in R until h - R) for (x in R until w - R) {
                val a = y - R; val b = y + R + 1; val c = x - R; val d = x + R + 1
                val insideHole = integHole[b * iw + d] - integHole[a * iw + d] - integHole[b * iw + c] + integHole[a * iw + c]
                val insideAvoid = integAvoid[b * iw + d] - integAvoid[a * iw + d] - integAvoid[b * iw + c] + integAvoid[a * iw + c]
                val i = y * w + x
                if (insideAvoid == 0) {
                    valid[i] = true
                    cands[nc++] = i
                }
                if (insideHole > 0) {
                    isTarget[i] = true
                    targets[nt++] = i
                }
            }
            // Fallback: If avoiding subject colors left no candidate patches, allow any non-hole patch
            if (nc == 0) {
                for (y in R until h - R) for (x in R until w - R) {
                    val a = y - R; val b = y + R + 1; val c = x - R; val d = x + R + 1
                    val insideHole = integHole[b * iw + d] - integHole[a * iw + d] - integHole[b * iw + c] + integHole[a * iw + c]
                    val i = y * w + x
                    if (insideHole == 0 && !valid[i]) {
                        valid[i] = true
                        cands[nc++] = i
                    }
                }
            }
        }
    }

    private fun usable(l: Lvl, x: Int, y: Int): Boolean =
        x >= R && y >= R && x < l.w - R && y < l.h - R && l.valid[y * l.w + x]

    /** Half-size copy. A block that touches the hole is a hole. */
    private fun down(l: Lvl): Lvl {
        val w2 = l.w / 2
        val h2 = l.h / 2
        val px = IntArray(w2 * h2)
        val hole = BooleanArray(w2 * h2)
        val avoid = if (l.avoid != null) BooleanArray(w2 * h2) else null
        for (y in 0 until h2) for (x in 0 until w2) {
            var r = 0; var g = 0; var b = 0; var cnt = 0
            var anyHole = false
            var anyAvoid = false
            for (dy in 0..1) for (dx in 0..1) {
                val i = (2 * y + dy) * l.w + 2 * x + dx
                if (l.hole[i]) anyHole = true
                if (l.avoid != null && l.avoid[i]) anyAvoid = true
                if (!l.hole[i]) {
                    val p = l.px[i]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; cnt++
                }
            }
            hole[y * w2 + x] = anyHole
            if (avoid != null) avoid[y * w2 + x] = anyAvoid
            px[y * w2 + x] = if (cnt > 0) (255 shl 24) or ((r / cnt) shl 16) or ((g / cnt) shl 8) or (b / cnt) else 0
        }
        return Lvl(w2, h2, px, hole, avoid)
    }

    private fun holeSize(l: Lvl): Int {
        var minX = l.w; var minY = l.h; var maxX = -1; var maxY = -1
        for (y in 0 until l.h) for (x in 0 until l.w) if (l.hole[y * l.w + x]) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return if (maxX < 0) 0 else max(maxX - minX, maxY - minY) + 1
    }

    // ---------------------------------------------------------------------
    // The whole fill
    // ---------------------------------------------------------------------

    private fun fill(
        px: IntArray, hole: BooleanArray, avoid: BooleanArray?, w: Int, h: Int,
        progress: (Float) -> Unit, check: () -> Unit
    ): IntArray {
        // size pyramid: halve until the hole is small, but keep every level big enough for a patch
        val levels = ArrayList<Lvl>()
        var cur = Lvl(w, h, px.clone(), hole, avoid)
        levels += cur
        while (levels.size < 5 && holeSize(cur) > 22 && min(cur.w, cur.h) / 2 >= 32) {
            cur = down(cur)
            levels += cur
        }

        val rnd = Random(12345)
        val total = levels.indices.sumOf { iterationsFor(it, levels.size) }
        var done = 0

        for (li in levels.indices.reversed()) {
            val l = levels[li]
            check()

            if (li == levels.lastIndex) {
                diffuse(l)                               // a rough first guess for the hole
            } else {
                val coarse = levels[li + 1]
                for (i in 0 until l.n) if (l.hole[i]) {
                    val cx = min(coarse.w - 1, (i % l.w) / 2)
                    val cy = min(coarse.h - 1, (i / l.w) / 2)
                    l.px[i] = coarse.px[cy * coarse.w + cx]
                }
            }

            if (l.nc == 0) {                              // nothing to copy from at this size
                if (li != levels.lastIndex) continue
                continue
            }

            // starting guess for the matches
            for (t in 0 until l.nt) {
                val p = l.targets[t]
                var qx = -1
                var qy = -1
                if (li != levels.lastIndex) {
                    val coarse = levels[li + 1]
                    val cx = (p % l.w) / 2
                    val cy = (p / l.w) / 2
                    if (cx < coarse.w && cy < coarse.h && coarse.isTarget[cy * coarse.w + cx]) {
                        val ci = cy * coarse.w + cx
                        qx = coarse.nx[ci] * 2 + (p % l.w) % 2
                        qy = coarse.ny[ci] * 2 + (p / l.w) % 2
                    }
                }
                if (!usable(l, qx, qy)) {
                    val c = l.cands[rnd.nextInt(l.nc)]
                    qx = c % l.w
                    qy = c / l.w
                }
                l.nx[p] = qx
                l.ny[p] = qy
                l.cost[p] = cost(l, p % l.w, p / l.w, qx, qy, Int.MAX_VALUE)
            }

            val acc = Accumulator(l.n)
            val iters = iterationsFor(li, levels.size)
            for (it in 0 until iters) {
                sweep(l, forward = it % 2 == 0, rnd = rnd, check = check)
                vote(l, acc)
                done++
                progress(0.05f + 0.9f * done / total)
            }
        }
        return levels[0].px
    }

    private fun iterationsFor(level: Int, count: Int): Int = if (level == 0) 3 else if (level == count - 1) 6 else 4

    /** First guess: the hole is filled from its edge inwards with the average of the non-avoid background pixels around it. */
    private fun diffuse(l: Lvl) {
        val known = BooleanArray(l.n) { !l.hole[it] && (l.avoid == null || !l.avoid[it]) }
        if (known.none { it }) {
            for (i in 0 until l.n) known[i] = !l.hole[i]
        }
        val queue = IntArray(l.n)
        var head = 0
        var tail = 0
        val queued = BooleanArray(l.n)
        for (i in 0 until l.n) {
            if (!l.hole[i]) continue
            val x = i % l.w
            val y = i / l.w
            if ((x > 0 && known[i - 1]) || (x < l.w - 1 && known[i + 1]) || (y > 0 && known[i - l.w]) || (y < l.h - 1 && known[i + l.w])) {
                queue[tail++] = i; queued[i] = true
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % l.w
            val y = i / l.w
            var r = 0; var g = 0; var b = 0; var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= l.w || ny >= l.h) continue
                val j = ny * l.w + nx
                if (known[j]) {
                    val p = l.px[j]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
                }
            }
            if (c > 0) l.px[i] = (255 shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
            known[i] = true
            for (k in 0 until 4) {
                val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= l.w || ny >= l.h) continue
                val j = ny * l.w + nx
                if (l.hole[j] && !queued[j]) { queued[j] = true; queue[tail++] = j }
            }
        }
    }

    /** Sum of squared colour differences between the patch around (px,py) and the patch around (qx,qy). */
    private fun cost(l: Lvl, px: Int, py: Int, qx: Int, qy: Int, limit: Int): Int {
        var sum = 0
        for (dy in -R..R) {
            val ra = (py + dy) * l.w + px
            val rb = (qy + dy) * l.w + qx
            for (dx in -R..R) {
                val a = l.px[ra + dx]
                val b = l.px[rb + dx]
                val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
                val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
                val db = (a and 255) - (b and 255)
                sum += dr * dr + dg * dg + db * db
            }
            if (sum >= limit) return sum
        }
        return sum
    }

    /** PatchMatch: neighbours share good matches (propagation), and random jumps find new ones. */
    private fun sweep(l: Lvl, forward: Boolean, rnd: Random, check: () -> Unit) {
        val w = l.w
        val dir = if (forward) 1 else -1
        var t = if (forward) 0 else l.nt - 1
        var count = 0
        val maxRad = max(l.w, l.h)
        while (t in 0 until l.nt) {
            if ((count++ and 2047) == 0) check()
            val p = l.targets[t]
            t += dir
            val px = p % w
            val py = p / w
            var bx = l.nx[p]
            var by = l.ny[p]
            var best = l.cost[p]

            // the neighbour that was handled just before: its match, shifted by one, is a good guess
            val hn = px - dir
            if (hn >= R && hn < w - R) {
                val pn = py * w + hn
                if (l.isTarget[pn]) {
                    val cx = l.nx[pn] + dir
                    val cy = l.ny[pn]
                    if (usable(l, cx, cy)) {
                        val c = cost(l, px, py, cx, cy, best)
                        if (c < best) { best = c; bx = cx; by = cy }
                    }
                }
            }
            val vn = py - dir
            if (vn >= R && vn < l.h - R) {
                val pn = vn * w + px
                if (l.isTarget[pn]) {
                    val cx = l.nx[pn]
                    val cy = l.ny[pn] + dir
                    if (usable(l, cx, cy)) {
                        val c = cost(l, px, py, cx, cy, best)
                        if (c < best) { best = c; bx = cx; by = cy }
                    }
                }
            }

            // random tries around the best match, in smaller and smaller circles
            var rad = maxRad
            while (rad >= 1) {
                val cx = bx + rnd.nextInt(2 * rad + 1) - rad
                val cy = by + rnd.nextInt(2 * rad + 1) - rad
                if (usable(l, cx, cy)) {
                    val c = cost(l, px, py, cx, cy, best)
                    if (c < best) { best = c; bx = cx; by = cy }
                }
                rad = rad shr 1
            }
            l.nx[p] = bx
            l.ny[p] = by
            l.cost[p] = best
        }
    }

    private class Accumulator(n: Int) {
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val wt = FloatArray(n)
        fun clear() { r.fill(0f); g.fill(0f); b.fill(0f); wt.fill(0f) }
    }

    /** Every hole pixel becomes the weighted average of what all patches covering it say it should be. */
    private fun vote(l: Lvl, acc: Accumulator) {
        acc.clear()
        var sum = 0L
        for (t in 0 until l.nt) sum += l.cost[l.targets[t]]
        val mean = sum.toFloat() / max(1, l.nt)
        val inv = 1f / (mean * 0.6f + 1f)               // patches that matched badly count for less
        val w = l.w
        for (t in 0 until l.nt) {
            val p = l.targets[t]
            val px = p % w
            val py = p / w
            val wgt = exp(-l.cost[p] * inv)
            val qx = l.nx[p]
            val qy = l.ny[p]
            for (dy in -R..R) {
                val ty = py + dy
                val sy = qy + dy
                for (dx in -R..R) {
                    val ti = ty * w + px + dx
                    if (!l.hole[ti]) continue
                    val c = l.px[sy * w + qx + dx]
                    acc.r[ti] += wgt * ((c shr 16) and 255)
                    acc.g[ti] += wgt * ((c shr 8) and 255)
                    acc.b[ti] += wgt * (c and 255)
                    acc.wt[ti] += wgt
                }
            }
        }
        for (i in 0 until l.n) {
            if (!l.hole[i] || acc.wt[i] <= 0f) continue
            val k = 1f / acc.wt[i]
            l.px[i] = (255 shl 24) or
                    ((acc.r[i] * k).roundToInt().coerceIn(0, 255) shl 16) or
                    ((acc.g[i] * k).roundToInt().coerceIn(0, 255) shl 8) or
                    (acc.b[i] * k).roundToInt().coerceIn(0, 255)
        }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** Grows a mask by [r] pixels in every direction (square brush), in time independent of r. */
    private fun grow(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return a
        val tmp = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + (if (a[y * w + x]) 1 else 0)
            for (x in 0 until w) tmp[y * w + x] = pre[min(w - 1, x + r) + 1] - pre[max(0, x - r)] > 0
        }
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + (if (tmp[y * w + x]) 1 else 0)
            for (y in 0 until h) out[y * w + x] = pre[min(h - 1, y + r) + 1] - pre[max(0, y - r)] > 0
        }
        return out
    }

    private fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            var cnt = 0
            for (x in 0..min(r, w - 1)) { sum += a[o + x]; cnt++ }
            for (x in 0 until w) {
                tmp[o + x] = sum / cnt
                val add = x + r + 1
                val rem = x - r
                if (add < w) { sum += a[o + add]; cnt++ }
                if (rem >= 0) { sum -= a[o + rem]; cnt-- }
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            var cnt = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; cnt++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / cnt
                val add = y + r + 1
                val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; cnt++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; cnt-- }
            }
        }
        return out
    }
}