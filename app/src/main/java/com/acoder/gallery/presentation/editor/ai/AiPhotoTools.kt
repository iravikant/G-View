package com.acoder.gallery.presentation.editor.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.acoder.gallery.presentation.editor.resampleHighQuality
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** How a model file wants its input pixels normalized. */
enum class Norm {
    UNIT,       // 0..1
    RAW255,     // 0..255
    IMAGENET,   // (0..1 - mean) / std
    SYMMETRIC   // -1..1
}

enum class AiTool(val label: String, val asset: String, val norm: Norm = Norm.UNIT) {
    ENHANCE("AI enhance", "ai/zero_dce_pp.tflite"),
    DENOISE("Denoise", "ai/nafnet_denoise.tflite"),
    UNBLUR("Unblur", "ai/nafnet_deblur.tflite"),
    UPSCALE("Upscale", "ai/realesrgan_x2.tflite"),
    PORTRAIT("Portrait blur", "ai/selfie_segmenter.tflite"),
    BACKGROUND("Remove background", "ai/u2netp.tflite", Norm.IMAGENET)
}

/**
 * Public entry point of the AI tools.
 *
 *  - Remove background / Portrait blur use a real segmentation network (Google ML Kit, or a .tflite file you add),
 *    then clean the result up (stray blobs, holes) and snap its edge to the photo with a guided filter.
 *  - Enhance / Denoise / Unblur / Upscale use a .tflite model when its file is in assets/ai, otherwise a
 *    good classical algorithm ("basic" quality).
 *
 * After a run, [lastEngine] says which engine did the work.
 */
object AiPhotoTools {

    private const val TAG = "AiPhotoTools"

    @Volatile
    var lastEngine: String = ""
        internal set

    /** Every tool always has something to run with, so there is nothing to check any more. */
    fun isAvailable(context: Context, tool: AiTool): Boolean = true

    internal fun hasAsset(context: Context, asset: String): Boolean =
        try { context.assets.open(asset).use { }; true } catch (_: Exception) { false }

    suspend fun run(context: Context, tool: AiTool, src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        currentCoroutineContext().ensureActive()
        return when (tool) {
            AiTool.BACKGROUND, AiTool.PORTRAIT -> Cutout.run(context, tool, src, onProgress)
            else -> runImageTool(context, tool, src, onProgress)
        }
    }

    private suspend fun runImageTool(context: Context, tool: AiTool, src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        if (hasAsset(context, tool.asset)) {
            try {
                val result = Neural.runTool(context, tool, src, onProgress)
                lastEngine = "model file"
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Model for ${tool.label} failed, using the basic version", e)
            }
        }
        lastEngine = "basic"
        return when (tool) {
            AiTool.ENHANCE -> Classic.enhance(src, onProgress)
            AiTool.DENOISE -> Classic.denoise(src, onProgress)
            AiTool.UNBLUR -> Classic.unblur(src, onProgress)
            AiTool.UPSCALE -> Classic.upscale(src, onProgress)
            else -> throw IllegalStateException("Unsupported tool")
        }
    }
}

// =========================================================================
// Neural image-to-image models (.tflite files in assets/ai)
// =========================================================================

internal object Neural {

    suspend fun runTool(context: Context, tool: AiTool, src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val interp = load(context, tool.asset)
        try {
            return when (tool) {
                AiTool.ENHANCE -> enhance(interp, tool, src, onProgress)
                AiTool.UPSCALE -> tiled(interp, tool, src, 4096, onProgress)
                AiTool.DENOISE, AiTool.UNBLUR -> tiled(interp, tool, src, 8192, onProgress)
                else -> throw IllegalArgumentException("Not an image-to-image tool")
            }
        } finally {
            interp.close()
        }
    }

    // ---------------------------------------------------------------------
    // Model loading + shapes
    // ---------------------------------------------------------------------

    fun load(context: Context, asset: String): Interpreter {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        val opts = Interpreter.Options().apply {
            setNumThreads(max(2, Runtime.getRuntime().availableProcessors() - 1))
        }
        return Interpreter(buf, opts)
    }

    private class Io(val inH: Int, val inW: Int, val outH: Int, val outW: Int, val outC: Int)

    /**
     * Allocates the model. A model with a fixed input size keeps it; a model with a flexible size gets
     * [wantH] x [wantW].
     */
    private fun prepare(interp: Interpreter, wantH: Int, wantW: Int): Io {
        val t = interp.getInputTensor(0)
        val shape = t.shape()
        if (shape.size != 4) throw IllegalStateException("This model does not take an image as input.")
        if (shape[3] != 3) {
            throw IllegalStateException(
                "This model uses channels-first (NCHW) images. Convert it with onnx2tf so it is channels-last."
            )
        }
        var h = shape[1]
        var w = shape[2]
        if (t.shapeSignature().any { it < 0 }) {
            h = wantH; w = wantW
            interp.resizeInput(0, intArrayOf(1, h, w, 3))
        }
        interp.allocateTensors()
        val os = interp.getOutputTensor(0).shape()
        if (os.size != 4) throw IllegalStateException("This model does not return an image.")
        return Io(h, w, os[1], os[2], os[3])
    }

    fun scaleTo(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val s = maxSide.toFloat() / m
        return resampleHighQuality(
            src,
            (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1)
        )
    }

    fun floatBuffer(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

    // ---------------------------------------------------------------------
    // Denoise / Unblur / Upscale: image in, image out, tiled
    // ---------------------------------------------------------------------

    suspend fun tiled(
        interp: Interpreter,
        tool: AiTool,
        src: Bitmap,
        maxOutSide: Int,
        onProgress: (Float) -> Unit
    ): Bitmap {
        val io = prepare(interp, 256, 256)
        if (io.outC != 3) throw IllegalStateException("Unexpected model output.")
        val scale = max(1, (io.outH.toFloat() / io.inH).roundToInt())

        // Keep the result at a size the phone can hold
        val bmp = scaleTo(src, maxOutSide / scale)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val outW = w * scale
        val outH = h * scale
        val out = IntArray(outW * outH)

        val tw = io.inW
        val th = io.inH
        val ovx = min(16, tw / 4)
        val ovy = min(16, th / 4)
        val stepX = tw - 2 * ovx
        val stepY = th - 2 * ovy
        val tilesX = ceil(w / stepX.toFloat()).toInt()
        val tilesY = ceil(h / stepY.toFloat()).toInt()

        val inBuf = floatBuffer(tw * th * 3)
        val outBuf = floatBuffer(io.outH * io.outW * 3)
        val inArr = FloatArray(tw * th * 3)
        val outArr = FloatArray(io.outH * io.outW * 3)
        val inK = if ((tool.norm == Norm.RAW255)) 1f else 1f / 255f
        var outK = 255f
        var checkedRange = false
        var done = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                currentCoroutineContext().ensureActive()
                val ox = tx * stepX
                val oy = ty * stepY

                // fill the tile (edges are repeated)
                var i = 0
                for (v in 0 until th) {
                    val sy = (oy - ovy + v).coerceIn(0, h - 1)
                    for (u in 0 until tw) {
                        val sx = (ox - ovx + u).coerceIn(0, w - 1)
                        val p = px[sy * w + sx]
                        inArr[i++] = ((p shr 16) and 255) * inK
                        inArr[i++] = ((p shr 8) and 255) * inK
                        inArr[i++] = (p and 255) * inK
                    }
                }
                inBuf.rewind(); inBuf.asFloatBuffer().put(inArr)
                outBuf.rewind()
                interp.run(inBuf, outBuf)
                outBuf.rewind(); outBuf.asFloatBuffer().get(outArr)

                if (!checkedRange) {
                    // Some models answer 0..1, some 0..255
                    var mx = 0f
                    for (k in outArr.indices step 7) if (outArr[k] > mx) mx = outArr[k]
                    outK = if (mx > 2f) 1f else 255f
                    checkedRange = true
                }

                // copy the middle of the tile (the overlap is thrown away)
                val keepW = min(stepX, w - ox)
                val keepH = min(stepY, h - oy)
                for (v in 0 until keepH * scale) {
                    val srcRow = (ovy * scale + v) * io.outW
                    val dstRow = (oy * scale + v) * outW + ox * scale
                    for (u in 0 until keepW * scale) {
                        val o = (srcRow + ovx * scale + u) * 3
                        val r = (outArr[o] * outK).roundToInt().coerceIn(0, 255)
                        val g = (outArr[o + 1] * outK).roundToInt().coerceIn(0, 255)
                        val b = (outArr[o + 2] * outK).roundToInt().coerceIn(0, 255)
                        out[dstRow + u] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                done++
                onProgress(done / (tilesX * tilesY).toFloat())
            }
        }
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, outW, 0, 0, outW, outH) }
    }

    // ---------------------------------------------------------------------
    // AI enhance: the model brightens a small copy; the brightening is then applied to the full-size photo
    // as a smooth per-pixel gain, so no detail is lost and there are no tile seams.
    // ---------------------------------------------------------------------

    suspend fun enhance(interp: Interpreter, tool: AiTool, src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val small = scaleTo(src, 1024)
        val io = prepare(
            interp,
            (small.height / 4 * 4).coerceAtLeast(4),
            (small.width / 4 * 4).coerceAtLeast(4)
        )
        if (io.outC != 3) throw IllegalStateException("Unexpected model output.")
        onProgress(0.1f)

        val inBmp = if (small.width == io.inW && small.height == io.inH) small
        else Bitmap.createScaledBitmap(small, io.inW, io.inH, true)
        val inPx = IntArray(io.inW * io.inH)
        inBmp.getPixels(inPx, 0, io.inW, 0, 0, io.inW, io.inH)

        val inK = if ((tool.norm == Norm.RAW255)) 1f else 1f / 255f
        val inArr = FloatArray(io.inW * io.inH * 3)
        var i = 0
        for (p in inPx) {
            inArr[i++] = ((p shr 16) and 255) * inK
            inArr[i++] = ((p shr 8) and 255) * inK
            inArr[i++] = (p and 255) * inK
        }
        val inBuf = floatBuffer(inArr.size)
        val outArr = FloatArray(io.outH * io.outW * 3)
        val outBuf = floatBuffer(outArr.size)
        inBuf.asFloatBuffer().put(inArr)
        currentCoroutineContext().ensureActive()
        interp.run(inBuf, outBuf)
        outBuf.rewind(); outBuf.asFloatBuffer().get(outArr)
        onProgress(0.5f)

        var mx = 0f
        for (k in outArr.indices step 7) if (outArr[k] > mx) mx = outArr[k]
        val outK = if (mx > 2f) 1f else 255f

        // gain = enhanced / original, measured on the small copy
        val ew = io.outW
        val eh = io.outH
        val ref = if (ew == io.inW && eh == io.inH) inBmp else Bitmap.createScaledBitmap(inBmp, ew, eh, true)
        val refPx = IntArray(ew * eh)
        ref.getPixels(refPx, 0, ew, 0, 0, ew, eh)
        val gR = FloatArray(ew * eh); val gG = FloatArray(ew * eh); val gB = FloatArray(ew * eh)
        for (k in 0 until ew * eh) {
            val p = refPx[k]
            val er = outArr[k * 3] * outK
            val eg = outArr[k * 3 + 1] * outK
            val eb = outArr[k * 3 + 2] * outK
            gR[k] = ((er + 1f) / (((p shr 16) and 255) + 1f)).coerceIn(0.3f, 6f)
            gG[k] = ((eg + 1f) / (((p shr 8) and 255) + 1f)).coerceIn(0.3f, 6f)
            gB[k] = ((eb + 1f) / ((p and 255) + 1f)).coerceIn(0.3f, 6f)
        }

        // apply the gain (bilinear) to the full-size photo
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val x0s = IntArray(w); val x1s = IntArray(w); val txs = FloatArray(w)
        for (x in 0 until w) {
            val fx = if (w > 1) x * (ew - 1f) / (w - 1f) else 0f
            x0s[x] = fx.toInt().coerceIn(0, ew - 1)
            x1s[x] = min(x0s[x] + 1, ew - 1)
            txs[x] = fx - x0s[x]
        }
        for (y in 0 until h) {
            if (y % 64 == 0) currentCoroutineContext().ensureActive()
            val fy = if (h > 1) y * (eh - 1f) / (h - 1f) else 0f
            val y0 = fy.toInt().coerceIn(0, eh - 1)
            val y1 = min(y0 + 1, eh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val a = y0 * ew + x0s[x]; val b = y0 * ew + x1s[x]
                val c = y1 * ew + x0s[x]; val d = y1 * ew + x1s[x]
                val tx = txs[x]
                val top = 1f - ty
                val wa = (1f - tx) * top; val wb = tx * top; val wc = (1f - tx) * ty; val wd = tx * ty
                val gr = gR[a] * wa + gR[b] * wb + gR[c] * wc + gR[d] * wd
                val gg = gG[a] * wa + gG[b] * wb + gG[c] * wc + gG[d] * wd
                val gb = gB[a] * wa + gB[b] * wb + gB[c] * wc + gB[d] * wd
                val p = px[y * w + x]
                val r = ((((p shr 16) and 255) * gr).roundToInt()).coerceIn(0, 255)
                val g = ((((p shr 8) and 255) * gg).roundToInt()).coerceIn(0, 255)
                val bl = (((p and 255) * gb).roundToInt()).coerceIn(0, 255)
                px[y * w + x] = (p and (255 shl 24)) or (r shl 16) or (g shl 8) or bl
            }
        }
        onProgress(1f)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

}

// =========================================================================
// Subject cut-out: Remove background + Portrait blur
// =========================================================================

private object Cutout {

    private const val TAG = "Cutout"

    /** Working size of the mask. Edges are refined here and then scaled up to the real photo. */
    private const val WORK = 1280

    private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    suspend fun run(context: Context, tool: AiTool, src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val work = Neural.scaleTo(src, WORK)
        onProgress(0.05f)

        val people: suspend () -> FloatArray = { MlKit.selfie(work) }
        val subject: suspend () -> FloatArray = { MlKit.subject(work) }
        val file: suspend () -> FloatArray = { tfliteMask(context, tool, work) }

        // Best engine first. People photos: the people model is the most accurate. Everything else: the subject model.
        val engines: List<Pair<String, suspend () -> FloatArray>> =
            if (tool == AiTool.PORTRAIT) listOf("ML Kit people" to people, "ML Kit subject" to subject, "model file" to file)
            else listOf("ML Kit subject" to subject, "model file" to file, "ML Kit people" to people)

        var mask: FloatArray? = null
        var used = ""
        val problems = ArrayList<String>()
        for ((name, engine) in engines) {
            currentCoroutineContext().ensureActive()
            try {
                mask = engine()
                used = name
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "$name failed", e)
                problems += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (mask == null) {
            throw IllegalStateException(
                "No cut-out engine worked. Add the ML Kit segmentation libraries (see the setup notes). " +
                        problems.joinToString("; ")
            )
        }
        onProgress(0.35f)

        val refined = Refiner.refine(work, mask)
        onProgress(0.6f)

        val result = if (tool == AiTool.PORTRAIT) portrait(src, work, refined, onProgress) else cutOut(src, work, refined)
        AiPhotoTools.lastEngine = used
        onProgress(1f)
        return result
    }

    // ---------------------------------------------------------------------
    // Using the mask
    // ---------------------------------------------------------------------

    /** The subject keeps its pixels; everything else becomes transparent. */
    private fun cutOut(src: Bitmap, work: Bitmap, m: FloatArray): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val rs = Resizer(work.width, work.height, w, h)
        for (y in 0 until h) {
            rs.row(y)
            for (x in 0 until w) {
                val a = rs.sample(m, x)
                val alpha = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
                val i = y * w + x
                px[i] = if (alpha < 3) 0 else (alpha shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(true)
            it.setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** Sharp subject; the background is blurred more and more the further it is from the subject. */
    private fun portrait(src: Bitmap, work: Bitmap, m: FloatArray, onProgress: (Float) -> Unit): Bitmap {
        val ww = work.width
        val wh = work.height
        val n = ww * wh
        val wpx = IntArray(n)
        work.getPixels(wpx, 0, ww, 0, 0, ww, wh)
        val short = min(ww, wh)

        val mid = blurPixels(wpx, ww, wh, max(5, short / 110))
        val strong = blurPixels(wpx, ww, wh, max(10, short / 45))
        onProgress(0.75f)

        val dist = distanceFromSubject(m, ww, wh)
        val reach = 0.22f * short
        val bg = IntArray(n)
        for (i in 0 until n) bg[i] = mixColor(mid[i], strong[i], (dist[i] / reach).coerceIn(0f, 1f))

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val rs = Resizer(ww, wh, w, h)
        for (y in 0 until h) {
            rs.row(y)
            for (x in 0 until w) {
                val keep = rs.sample(m, x)                 // how much of the original photo to keep
                if (keep >= 0.995f) continue
                val i = y * w + x
                px[i] = mixColor(rs.sampleColor(bg, x), px[i], keep)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun blurPixels(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val n = w * h
        var rc = FloatArray(n) { ((px[it] shr 16) and 255).toFloat() }
        var gc = FloatArray(n) { ((px[it] shr 8) and 255).toFloat() }
        var bc = FloatArray(n) { (px[it] and 255).toFloat() }
        repeat(3) {                       // three box blurs look like a gaussian blur
            rc = box(rc, w, h, r); gc = box(gc, w, h, r); bc = box(bc, w, h, r)
        }
        return IntArray(n) {
            (255 shl 24) or
                    (rc[it].roundToInt().coerceIn(0, 255) shl 16) or
                    (gc[it].roundToInt().coerceIn(0, 255) shl 8) or
                    bc[it].roundToInt().coerceIn(0, 255)
        }
    }

    /** Distance (in pixels) from every pixel to the nearest subject pixel. */
    private fun distanceFromSubject(m: FloatArray, w: Int, h: Int): FloatArray {
        val d = FloatArray(w * h) { if (m[it] >= 0.5f) 0f else 1e9f }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (v == 0f) continue
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) {
                v = min(v, d[i - w] + 1f)
                if (x > 0) v = min(v, d[i - w - 1] + 1.414f)
                if (x < w - 1) v = min(v, d[i - w + 1] + 1.414f)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (v == 0f) continue
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1f)
                if (x < w - 1) v = min(v, d[i + w + 1] + 1.414f)
                if (x > 0) v = min(v, d[i + w - 1] + 1.414f)
            }
            d[i] = v
        }
        return d
    }

    // ---------------------------------------------------------------------
    // A .tflite segmentation model from assets (optional)
    // ---------------------------------------------------------------------

    private fun normalized(v: Int, channel: Int, norm: Norm): Float = when (norm) {
        Norm.UNIT -> v / 255f
        Norm.RAW255 -> v.toFloat()
        Norm.IMAGENET -> (v / 255f - IMAGENET_MEAN[channel]) / IMAGENET_STD[channel]
        Norm.SYMMETRIC -> (v - 127.5f) / 127.5f
    }

    private suspend fun tfliteMask(context: Context, tool: AiTool, work: Bitmap): FloatArray {
        if (!AiPhotoTools.hasAsset(context, tool.asset)) throw IllegalStateException("no ${tool.asset} in assets")
        val interp = Neural.load(context, tool.asset)
        try {
            val t = interp.getInputTensor(0)
            val shape = t.shape()
            if (shape.size != 4 || shape[3] != 3) throw IllegalStateException("not an image segmentation model")
            if (t.shapeSignature().any { it < 0 }) interp.resizeInput(0, intArrayOf(1, 320, 320, 3))
            interp.allocateTensors()
            val inShape = interp.getInputTensor(0).shape()
            val inH = inShape[1]
            val inW = inShape[2]

            // Some models (U2-Net) have several outputs; the first one is the final result.
            val outCount = interp.outputTensorCount
            val outShapes = (0 until outCount).map { interp.getOutputTensor(it).shape() }
            val os = outShapes[0]
            if (os.size < 3) throw IllegalStateException("unexpected model output")
            val oh = os[1]
            val ow = os[2]
            val oc = if (os.size == 4) os[3] else 1

            val inBmp = Bitmap.createScaledBitmap(work, inW, inH, true)
            val inPx = IntArray(inW * inH)
            inBmp.getPixels(inPx, 0, inW, 0, 0, inW, inH)
            val inArr = FloatArray(inW * inH * 3)
            var i = 0
            for (p in inPx) {
                inArr[i++] = normalized((p shr 16) and 255, 0, tool.norm)
                inArr[i++] = normalized((p shr 8) and 255, 1, tool.norm)
                inArr[i++] = normalized(p and 255, 2, tool.norm)
            }
            val inBuf = Neural.floatBuffer(inArr.size)
            inBuf.asFloatBuffer().put(inArr)
            val outBufs = HashMap<Int, Any>()
            for (k in 0 until outCount) outBufs[k] = Neural.floatBuffer(outShapes[k].fold(1) { a, b -> a * b })
            currentCoroutineContext().ensureActive()
            interp.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), outBufs)

            val outArr = FloatArray(oh * ow * oc)
            (outBufs[0] as ByteBuffer).let { it.rewind(); it.asFloatBuffer().get(outArr) }

            val prob = FloatArray(ow * oh) { k -> if (oc == 1) outArr[k] else 1f - outArr[k * oc] }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in prob) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (lo < -0.05f || hi > 1.05f) {                       // raw scores -> 0..1
                for (k in prob.indices) prob[k] = 1f / (1f + exp(-prob[k]))
                lo = 0f; hi = 1f
            }
            if (tool.norm == Norm.IMAGENET && hi - lo > 1e-4f) {   // U2-Net style: stretch to the full range
                for (k in prob.indices) prob[k] = ((prob[k] - lo) / (hi - lo)).coerceIn(0f, 1f)
            }
            return resizeMask(prob, ow, oh, work.width, work.height)
        } finally {
            interp.close()
        }
    }
}

// =========================================================================
// Google ML Kit segmentation (no model file to manage)
// =========================================================================

private object MlKit {

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Any subject (people, pets, objects). Needs Google Play services; the first run may download the model. */
    suspend fun subject(work: Bitmap): FloatArray {
        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val result = segmenter.process(InputImage.fromBitmap(work, 0)).await()
            val buf = result.foregroundConfidenceMask ?: throw IllegalStateException("ML Kit returned no mask")
            val n = work.width * work.height
            if (buf.capacity() < n) throw IllegalStateException("ML Kit mask has an unexpected size")
            val out = FloatArray(n)
            buf.rewind()
            buf.get(out)
            return out
        } finally {
            segmenter.close()
        }
    }

    /** People only. The model is inside the app, so it works offline and needs no download. */
    suspend fun selfie(work: Bitmap): FloatArray {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        try {
            val mask = segmenter.process(InputImage.fromBitmap(work, 0)).await()
            val n = mask.width * mask.height
            val values = floatsFrom(mask.buffer, n)
            return if (mask.width == work.width && mask.height == work.height) values
            else resizeMask(values, mask.width, mask.height, work.width, work.height)
        } finally {
            segmenter.close()
        }
    }

    /** Reads [n] floats; tries the phone's byte order first and the other one if the numbers look wrong. */
    private fun floatsFrom(buf: ByteBuffer, n: Int): FloatArray {
        if (buf.capacity() < n * 4) throw IllegalStateException("ML Kit mask has an unexpected size")
        fun read(order: ByteOrder): FloatArray {
            val b = buf.duplicate().order(order)
            b.rewind()
            return FloatArray(n).also { b.asFloatBuffer().get(it) }
        }
        fun valid(a: FloatArray): Boolean {
            var k = 0
            while (k < a.size) {
                val v = a[k]
                if (v.isNaN() || v < -0.1f || v > 1.1f) return false
                k += max(1, a.size / 64)
            }
            return true
        }
        val native = read(ByteOrder.nativeOrder())
        if (valid(native)) return native
        val other = read(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        return if (valid(other)) other else native
    }
}

// =========================================================================
// Mask clean-up: this is what makes the edge professional
// =========================================================================

private object Refiner {

    /**
     * 1. Drop stray blobs (a picture frame on the wall) and keep the main subject(s).
     * 2. Fill small holes inside the subject (gaps in hair, between fingers and a bangle).
     * 3. Guided filter: the soft edge of the mask snaps to the real edge of the photo.
     * 4. A final contrast curve makes the edge crisp but still anti-aliased.
     */
    fun refine(work: Bitmap, mask: FloatArray): FloatArray {
        val w = work.width
        val h = work.height
        val n = w * h
        val m = FloatArray(n) { mask[it].coerceIn(0f, 1f) }
        cleanup(m, w, h)

        val px = IntArray(n)
        work.getPixels(px, 0, w, 0, 0, w, h)
        val guide = FloatArray(n) {
            val p = px[it]
            (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f
        }
        val r = (min(w, h) / 100).coerceIn(4, 14)
        val q = guided(guide, m, w, h, r, 2e-4f)
        for (i in 0 until n) {
            val s = ((q[i] - 0.3f) / 0.45f).coerceIn(0f, 1f)
            q[i] = s * s * (3f - 2f * s)
        }
        return q
    }

    private class Comps(val labels: IntArray, val area: IntArray, val touchesBorder: BooleanArray, val count: Int)

    /** Connected regions (4-neighbour) of the pixels whose flag equals [want]. */
    private fun components(flag: BooleanArray, want: Boolean, w: Int, h: Int): Comps {
        val n = w * h
        val labels = IntArray(n)
        val queue = IntArray(n)
        val areas = ArrayList<Int>().apply { add(0) }
        val borders = ArrayList<Boolean>().apply { add(false) }
        var next = 0
        for (start in 0 until n) {
            if (labels[start] != 0 || flag[start] != want) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = next
            var area = 0
            var border = false
            while (head < tail) {
                val p = queue[head++]
                area++
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0) { val q = p - 1; if (labels[q] == 0 && flag[q] == want) { labels[q] = next; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (labels[q] == 0 && flag[q] == want) { labels[q] = next; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (labels[q] == 0 && flag[q] == want) { labels[q] = next; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (labels[q] == 0 && flag[q] == want) { labels[q] = next; queue[tail++] = q } }
            }
            areas.add(area)
            borders.add(border)
        }
        return Comps(labels, areas.toIntArray(), borders.toBooleanArray(), next)
    }

    private fun cleanup(m: FloatArray, w: Int, h: Int) {
        val n = w * h
        val fg = components(BooleanArray(n) { m[it] > 0.5f }, true, w, h)
        var biggest = 0
        for (k in 1..fg.count) if (fg.area[k] > biggest) biggest = fg.area[k]
        if (biggest < n * 0.002f) throw IllegalStateException("No subject was found in this photo.")

        // keep the main subject(s): anything at least 15% as big as the biggest one
        val keepLabel = BooleanArray(fg.count + 1) { it > 0 && fg.area[it] >= biggest * 0.15f }
        val keepF = FloatArray(n) { if (fg.labels[it] != 0 && keepLabel[fg.labels[it]]) 1f else 0f }
        // grow the kept area a little, so the soft edge of the subject is not cut off
        val grown = box(keepF, w, h, max(6, min(w, h) / 80))
        for (i in 0 until n) if (grown[i] < 0.001f) m[i] = 0f

        // fill small holes that are completely inside the subject
        val bg = components(BooleanArray(n) { m[it] <= 0.5f }, true, w, h)
        val maxHole = n * 0.004f
        for (i in 0 until n) {
            val l = bg.labels[i]
            if (l != 0 && !bg.touchesBorder[l] && bg.area[l] < maxHole) m[i] = 1f
        }
    }

    /** Edge-preserving smoothing (He et al., "Guided Image Filtering"): [guide] is the photo, [p] the mask. */
    private fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, r)
        val meanP = box(p, w, h, r)
        val corrIp = box(FloatArray(n) { guide[it] * p[it] }, w, h, r)
        val corrI = box(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, r)
        val meanB = box(b, w, h, r)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }
}

// =========================================================================
// Shared helpers
// =========================================================================

/** Average of the (2r+1) x (2r+1) square around every pixel. Runs in time independent of r. */
private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h)
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val o = y * w
        var sum = 0f
        var cnt = 0
        for (x in 0..min(r, w - 1)) { sum += src[o + x]; cnt++ }
        for (x in 0 until w) {
            tmp[o + x] = sum / cnt
            val add = x + r + 1
            val rem = x - r
            if (add < w) { sum += src[o + add]; cnt++ }
            if (rem >= 0) { sum -= src[o + rem]; cnt-- }
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

private fun mixColor(c0: Int, c1: Int, t: Float): Int {
    val u = 1f - t
    val r = (((c0 shr 16) and 255) * u + ((c1 shr 16) and 255) * t).roundToInt()
    val g = (((c0 shr 8) and 255) * u + ((c1 shr 8) and 255) * t).roundToInt()
    val b = ((c0 and 255) * u + (c1 and 255) * t).roundToInt()
    return (c1 and (255 shl 24)) or (r shl 16) or (g shl 8) or b
}

/** Bilinear lookups of a small image while walking over a bigger one, row by row. */
private class Resizer(private val sw: Int, private val sh: Int, dw: Int, private val dh: Int) {
    private val x0 = IntArray(dw)
    private val x1 = IntArray(dw)
    private val tx = FloatArray(dw)
    private var y0 = 0
    private var y1 = 0
    private var ty = 0f

    init {
        for (x in 0 until dw) {
            val f = if (dw > 1) x * (sw - 1f) / (dw - 1f) else 0f
            x0[x] = f.toInt().coerceIn(0, sw - 1)
            x1[x] = min(x0[x] + 1, sw - 1)
            tx[x] = f - x0[x]
        }
    }

    fun row(y: Int) {
        val f = if (dh > 1) y * (sh - 1f) / (dh - 1f) else 0f
        y0 = f.toInt().coerceIn(0, sh - 1)
        y1 = min(y0 + 1, sh - 1)
        ty = f - y0
    }

    fun sample(a: FloatArray, x: Int): Float {
        val t = tx[x]
        val top = a[y0 * sw + x0[x]] * (1f - t) + a[y0 * sw + x1[x]] * t
        val bot = a[y1 * sw + x0[x]] * (1f - t) + a[y1 * sw + x1[x]] * t
        return top * (1f - ty) + bot * ty
    }

    fun sampleColor(a: IntArray, x: Int): Int {
        val t = tx[x]
        val c00 = a[y0 * sw + x0[x]]; val c01 = a[y0 * sw + x1[x]]
        val c10 = a[y1 * sw + x0[x]]; val c11 = a[y1 * sw + x1[x]]
        fun ch(shift: Int): Int {
            val top = ((c00 shr shift) and 255) * (1f - t) + ((c01 shr shift) and 255) * t
            val bot = ((c10 shr shift) and 255) * (1f - t) + ((c11 shr shift) and 255) * t
            return (top * (1f - ty) + bot * ty).roundToInt().coerceIn(0, 255)
        }
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

private fun resizeMask(a: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    if (sw == dw && sh == dh) return a
    val out = FloatArray(dw * dh)
    val rs = Resizer(sw, sh, dw, dh)
    for (y in 0 until dh) {
        rs.row(y)
        for (x in 0 until dw) out[y * dw + x] = rs.sample(a, x)
    }
    return out
}

// =========================================================================
// Basic (no model file) versions of the image tools
// =========================================================================

private object Classic {

    /** Auto levels + a lift for dark photos + a gentle contrast curve + vibrance. Keeps hue, protects highlights. */
    suspend fun enhance(src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        onProgress(0.1f)

        val hist = IntArray(256)
        for (p in px) hist[((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8)]++
        fun percentile(f: Float): Int {
            val target = (n * f).toInt()
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        var lo = percentile(0.005f).toFloat()
        var hi = percentile(0.995f).toFloat()
        if (hi - lo < 40f) { lo = max(0f, lo - 20f); hi = min(255f, hi + 20f) }

        var mean = 0.0
        for (i in 0..255) mean += hist[i] * ((i - lo) / (hi - lo)).coerceIn(0f, 1f)
        mean /= n
        // dark photos get lifted, bright ones are left alone
        val gamma = if (mean in 0.03..0.97) (ln(0.46) / ln(mean)).toFloat().coerceIn(0.55f, 1.1f) else 1f

        val lut = FloatArray(256) { i ->
            var v = ((i - lo) / (hi - lo)).coerceIn(0f, 1f).pow(gamma)
            val s = v * v * (3f - 2f * v)
            v = v * 0.85f + s * 0.15f                       // gentle S-curve
            v * 255f
        }

        for (y in 0 until h) {
            if (y % 64 == 0) currentCoroutineContext().ensureActive()
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                var r = ((p shr 16) and 255).toFloat()
                var g = ((p shr 8) and 255).toFloat()
                var b = (p and 255).toFloat()
                val luma = (0.299f * r + 0.587f * g + 0.114f * b)
                val yi = luma.toInt().coerceIn(0, 255)
                val scale = if (yi > 0) (lut[yi] / luma).coerceIn(0.5f, 3.5f) else 1f
                r *= scale; g *= scale; b *= scale
                val peak = max(r, max(g, b))
                if (peak > 255f) { val k = 255f / peak; r *= k; g *= k; b *= k }   // keep the hue, no colour clipping
                // vibrance: dull colours gain more than vivid ones
                val mx = max(r, max(g, b))
                val sat = if (mx > 0f) (mx - min(r, min(g, b))) / mx else 0f
                val boost = 1f + 0.28f * (1f - sat)
                val l2 = 0.299f * r + 0.587f * g + 0.114f * b
                r = l2 + (r - l2) * boost
                g = l2 + (g - l2) * boost
                b = l2 + (b - l2) * boost
                px[i] = (p and (255 shl 24)) or
                        (r.roundToInt().coerceIn(0, 255) shl 16) or
                        (g.roundToInt().coerceIn(0, 255) shl 8) or
                        b.roundToInt().coerceIn(0, 255)
            }
            if (y % 32 == 0) onProgress(0.1f + 0.9f * y / h)
        }
        onProgress(1f)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Bilateral filter: smooths grain but keeps edges, because similar-coloured neighbours count more. */
    suspend fun denoise(src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.clone()

        val rad = 2
        val sigmaSpace = 1.6f
        val sigmaColor = 22f
        val side = 2 * rad + 1
        val spatial = FloatArray(side * side)
        var k = 0
        for (dy in -rad..rad) for (dx in -rad..rad) {
            spatial[k++] = exp(-(dx * dx + dy * dy) / (2f * sigmaSpace * sigmaSpace))
        }
        val colorW = FloatArray(256) { d -> exp(-(d * d) / (2f * sigmaColor * sigmaColor)) }

        for (y in rad until h - rad) {
            if (y % 32 == 0) {
                currentCoroutineContext().ensureActive()
                onProgress(y / h.toFloat())
            }
            for (x in rad until w - rad) {
                val c = px[y * w + x]
                val cr = (c shr 16) and 255
                val cg = (c shr 8) and 255
                val cb = c and 255
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                var j = 0
                for (dy in -rad..rad) {
                    val row = (y + dy) * w + x
                    for (dx in -rad..rad) {
                        val q = px[row + dx]
                        val qr = (q shr 16) and 255
                        val qg = (q shr 8) and 255
                        val qb = q and 255
                        val d = (abs(qr - cr) + abs(qg - cg) + abs(qb - cb)) / 3
                        val wt = spatial[j++] * colorW[d]
                        sr += wt * qr; sg += wt * qg; sb += wt * qb; sw += wt
                    }
                }
                out[y * w + x] = (c and (255 shl 24)) or
                        ((sr / sw).roundToInt().coerceIn(0, 255) shl 16) or
                        ((sg / sw).roundToInt().coerceIn(0, 255) shl 8) or
                        (sb / sw).roundToInt().coerceIn(0, 255)
            }
        }
        onProgress(1f)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    /** Unsharp mask on brightness only (no colour fringes), with a threshold so grain is not boosted. */
    suspend fun unblur(src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        sharpen(px, w, h, amount = 1.1f, threshold = 2.5f, onProgress = onProgress)
        onProgress(1f)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Smooth 2x enlargement followed by a light sharpening pass. Capped at 4096 px on the long side. */
    suspend fun upscale(src: Bitmap, onProgress: (Float) -> Unit): Bitmap {
        val longSide = max(src.width, src.height)
        val f = min(2f, 4096f / longSide)
        if (f < 1.05f) throw IllegalStateException("This photo is already at the largest size (4096 px).")
        val tw = (src.width * f).roundToInt()
        val th = (src.height * f).roundToInt()
        onProgress(0.2f)
        val big = Bitmap.createScaledBitmap(src, tw, th, true)
        val px = IntArray(tw * th)
        big.getPixels(px, 0, tw, 0, 0, tw, th)
        sharpen(px, tw, th, amount = 0.7f, threshold = 1.5f) { onProgress(0.3f + 0.7f * it) }
        onProgress(1f)
        return Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, tw, 0, 0, tw, th) }
    }

    /** Works in horizontal bands so a 12-megapixel photo does not need hundreds of MB of temporary memory. */
    private suspend fun sharpen(
        px: IntArray, w: Int, h: Int,
        amount: Float, threshold: Float,
        onProgress: (Float) -> Unit
    ) {
        val band = 512
        val pad = 4
        var y0 = 0
        while (y0 < h) {
            currentCoroutineContext().ensureActive()
            val y1 = min(h, y0 + band)
            val s = max(0, y0 - pad)
            val e = min(h, y1 + pad)
            val bh = e - s
            val luma = FloatArray(w * bh) {
                val p = px[s * w + it]
                0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
            }
            var blur = luma
            repeat(3) { blur = box(blur, w, bh, 1) }
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val li = (y - s) * w + x
                    val d = luma[li] - blur[li]
                    val ad = abs(d)
                    if (ad <= threshold) continue
                    val add = (ad - threshold) * (if (d < 0f) -1f else 1f) * amount
                    val i = y * w + x
                    val p = px[i]
                    px[i] = (p and (255 shl 24)) or
                            ((((p shr 16) and 255) + add).roundToInt().coerceIn(0, 255) shl 16) or
                            ((((p shr 8) and 255) + add).roundToInt().coerceIn(0, 255) shl 8) or
                            ((p and 255) + add).roundToInt().coerceIn(0, 255)
                }
            }
            y0 = y1
            onProgress(y0 / h.toFloat())
        }
    }
}