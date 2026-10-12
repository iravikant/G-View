package com.acoder.gallery.core.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =========================================================================
// Model
// =========================================================================

/** How the photos are arranged. */
enum class CollageLayout(val label: String) {
    /** Dynamic: balanced split-tree layout that adapts to the photos. Use shuffle for new variations. */
    SMART("Smart"),

    /** Moodboard: tightly packed tiles of very different sizes, like a designer's board. */
    MOODBOARD("Moodboard"),

    /** Overlapping, slightly tilted panels layered on top of each other. */
    LAYERED("Layered"),

    /** Tilted prints pinned or taped on a board, scrapbook style. */
    SCRAPBOOK("Scrapbook"),

    /** Equal square tiles. An incomplete last row is centered. */
    GRID("Grid"),

    /** One large featured photo on top, the rest in a grid below. */
    FEATURE("Feature"),

    /** Justified rows that respect each photo's aspect ratio. */
    MOSAIC("Mosaic"),

    /** Pinterest-style columns with balanced heights. */
    MASONRY("Masonry"),

    /** Modern bento-box blocks with tiles of different sizes. */
    BENTO("Bento"),

    /** One tall feature photo on the left, the rest stacked on the right. */
    MAGAZINE("Magazine"),

    /** Columns that alternate between tall and short tiles. */
    STAGGER("Stagger"),

    /** Grid of arch-shaped (rounded top) tiles. */
    ARCH("Arch"),

    /** Photos stacked vertically, like a story strip. */
    STORY("Story")
}

/** Outline a photo is cropped to. */
enum class TileShape { RECT, ARCH }

/** One tile of a layout thumbnail: [rect] is [x, y, w, h] in a unit square. */
class ThumbTile(val rect: FloatArray, val shape: TileShape, val angle: Float = 0f)

/** How each photo is presented. The background is chosen separately. */
enum class CollageFrame(val label: String) {
    /** Bright, airy, rounded. */
    CLEAN("Clean"),

    /** Pastel background, big corners and a soft glow of shadow. */
    SOFT("Soft"),

    /** Deep black with a hairline edge. */
    NOIR("Noir"),

    /** Museum style: off-white mats on a warm wall. */
    GALLERY("Gallery"),

    /** White cards with real elevation. */
    FLOAT("Float"),

    /** Frosted glass cards over an aurora gradient. */
    GLASS("Glass"),

    /** Warm paper with a thin printed frame. */
    EDITORIAL("Editorial"),

    /** Bold gradient with thick white borders, like die-cut stickers. */
    STICKER("Sticker"),

    /** Instant-film prints, slightly tilted. */
    INSTANT("Instant"),

    /** Light gray moodboard look: tight white tiles with a hairline shadow. */
    MINIMAL("Minimal"),

    /** Kraft paper board with tilted prints, tape and pins. */
    SCRAPBOOK("Scrapbook"),

    /** Ripped white paper edges on an aged paper background. */
    TORN("Torn")
}

/** Procedural paper texture drawn over the background. */
enum class CollageTexture(val label: String) {
    NONE("None"),
    PAPER("Paper"),
    KRAFT("Kraft"),
    LINEN("Linen"),
    GRAIN("Grain")
}

/** Tape strips and pins attached to the tiles. */
enum class CollageDecor(val label: String) {
    NONE("None"),
    TAPE("Tape"),
    PINS("Pins"),
    MIXED("Mixed")
}

/** Canvas shape for freeform layouts. [ratio] is height / width; null = decided by the layout. */
enum class CollageCanvas(val label: String, val ratio: Float?) {
    AUTO("Auto", null),
    SQUARE("1:1", 1f),
    PORTRAIT("4:5", 1.25f),
    STORY("9:16", 16f / 9f),
    LANDSCAPE("3:2", 2f / 3f)
}

/** Built-in Android system font families (no font files needed). */
enum class CollageFont(val label: String, val family: String) {
    MEDIUM("Modern", "sans-serif-medium"),
    REGULAR("Clean", "sans-serif"),
    LIGHT("Light", "sans-serif-light"),
    THIN("Thin", "sans-serif-thin"),
    BLACK("Heavy", "sans-serif-black"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
    SCRIPT("Script", "cursive"),
    CASUAL("Casual", "casual"),
    SMALL_CAPS("Small caps", "sans-serif-smallcaps")
}

/** One color = solid background. Two or more colors = gradient at [angle] degrees (0 = left to right, 90 = top to bottom). */
data class CollageBackground(val colors: List<Int>, val angle: Float = 135f) {
    init {
        require(colors.isNotEmpty()) { "A background needs at least one color" }
    }
}

/** Text overlay. Position is the text center as a fraction of the canvas (0..1). */
data class CollageText(
    val text: String = "",
    val font: CollageFont = CollageFont.MEDIUM,
    /** Font size as a fraction of the canvas width. */
    val sizeFraction: Float = 0.06f,
    /** null = automatic (dark or light, whichever contrasts with the background). */
    val color: Int? = null,
    val x: Float = 0.5f,
    val y: Float = 0.92f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val uppercase: Boolean = false,
    val shadow: Boolean = false,
    /** Soft rounded plate behind the text, useful when it sits on top of photos. */
    val backdrop: Boolean = false,
    /** Paper-tape label with ragged ends behind the text (dark ink by default). */
    val tape: Boolean = false,
    /** In em units. */
    val letterSpacing: Float = 0.04f,
    /** Degrees. */
    val rotation: Float = 0f
)

data class CollageConfig(
    val layout: CollageLayout = CollageLayout.GRID,
    val frame: CollageFrame = CollageFrame.CLEAN,
    /** Output width in px. Height is calculated from the layout. */
    val width: Int = 1080,
    /** Used by GRID, MASONRY, STAGGER and ARCH. */
    val columns: Int = 2,
    /** Gap between tiles, px at 1080 width. null = the frame's default. */
    val spacing: Int? = null,
    /** Corner radius, px at 1080 width. null = the frame's default. */
    val corner: Int? = null,
    /** Outer margin, px at 1080 width. null = the frame's default. */
    val padding: Int? = null,
    /** null = the frame's default background. */
    val background: CollageBackground? = null,
    /** Adds an empty band under the collage, handy for text. */
    val footer: Boolean = false,
    val text: CollageText? = null,
    /** Photo order: slot i shows uris[order[i]]. null = original order. */
    val order: List<Int>? = null,
    /** Per-photo zoom / pan (keyed by uri index). Missing = fitted and centered. */
    val transforms: Map<Int, PhotoTransform> = emptyMap(),
    /** Variation number for dynamic layouts (SMART). Same seed = same arrangement. */
    val seed: Int = 0,
    /** Canvas shape for SMART, MOODBOARD and LAYERED. */
    val canvas: CollageCanvas = CollageCanvas.AUTO,
    /** null = the frame's default texture. */
    val texture: CollageTexture? = null,
    /** null = automatic (Scrapbook gets tape and pins, everything else none). */
    val decor: CollageDecor? = null,
    val quality: Int = 94
)

/** How a photo sits inside its tile. zoom >= 1 (1 = cover-fit); pan -1..1 (0 = centered). */
data class PhotoTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

/** Geometry of one rendered tile in preview-bitmap pixels, used by the UI for hit-testing and gestures. */
class TileInfo(
    val sourceIndex: Int,
    val card: RectF,
    val photo: RectF,
    /** Size of the cover-fitted photo at zoom 1. Overflow at zoom z = img * z - photo size. */
    val imgW: Float,
    val imgH: Float,
    val photoCorner: Float,
    /** Tile rotation in degrees (Instant frame). */
    val angle: Float,
    val shape: TileShape = TileShape.RECT
)

class PreviewResult(val bitmap: Bitmap, val tiles: List<TileInfo>)

// =========================================================================
// Generator
// =========================================================================

object CollageGenerator {

    private const val TAG = "CollageGenerator"

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Backward compatible entry point: plain rounded grid. */
    fun generate(
        context: Context,
        uris: List<Uri>,
        columns: Int = 2,
        spacing: Int = 8,
        corner: Int = 20
    ): File = generate(
        context,
        uris,
        CollageConfig(columns = columns, spacing = spacing, corner = corner)
    )

    /** Full-quality export to a JPEG file. Heavy work: call from Dispatchers.IO. */
    fun generate(context: Context, uris: List<Uri>, config: CollageConfig): File {
        val bitmap = render(context, uris, config)
        try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "Collage_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, config.quality.coerceIn(60, 100), it)
            }
            if (file.length() == 0L) throw IllegalStateException("The generated collage came out empty")
            return file
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Low-resolution render for the live preview. The text overlay is NOT drawn here:
     * the UI draws it on top with [drawTextOverlay] so it can be dragged smoothly.
     * Also returns the tile geometry so the UI can hit-test and pan photos.
     * The caller owns the returned bitmap. Heavy work: call from Dispatchers.IO.
     *
     * [shouldContinue] is polled between photos; return false to abort early.
     */
    fun renderPreview(
        context: Context,
        uris: List<Uri>,
        config: CollageConfig,
        previewWidth: Int = 600,
        shouldContinue: () -> Boolean = { true }
    ): PreviewResult {
        val (bmp, tiles) = renderWithTiles(
            context, uris, config.copy(width = previewWidth, text = null), shouldContinue
        )
        return PreviewResult(bmp, tiles)
    }

    // ---------------------------------------------------------------------
    // Public helpers for the UI
    // ---------------------------------------------------------------------

    /** Layouts that look good for [n] photos (the editor only offers these). */
    fun layoutsFor(n: Int): List<CollageLayout> = when {
        n <= 1 -> listOf(CollageLayout.GRID)
        n == 2 -> listOf(
            CollageLayout.MOSAIC, CollageLayout.GRID, CollageLayout.MAGAZINE,
            CollageLayout.FEATURE, CollageLayout.STORY, CollageLayout.ARCH, CollageLayout.SCRAPBOOK
        )
        else -> buildList {
            add(CollageLayout.SMART)
            add(CollageLayout.MOODBOARD)
            if (n <= 16) add(CollageLayout.LAYERED)
            if (n <= 10) add(CollageLayout.SCRAPBOOK)
            add(CollageLayout.BENTO)
            add(CollageLayout.MOSAIC)
            add(CollageLayout.MASONRY)
            add(CollageLayout.GRID)
            add(CollageLayout.FEATURE)
            if (n >= 4) add(CollageLayout.STAGGER)
            add(CollageLayout.ARCH)
            // Magazine and Story get far too tall with many photos
            if (n <= 6) {
                add(CollageLayout.MAGAZINE)
                add(CollageLayout.STORY)
            }
        }
    }

    /** The layout that suits [n] photos best. */
    fun defaultLayout(n: Int): CollageLayout = when {
        n <= 1 -> CollageLayout.GRID
        n == 2 -> CollageLayout.MOSAIC
        n <= 9 -> CollageLayout.SMART
        else -> CollageLayout.MOODBOARD
    }

    /** Sensible column count for column based layouts with [n] photos. */
    fun defaultColumns(n: Int): Int = when {
        n <= 4 -> 2
        n <= 9 -> 3
        else -> 4
    }

    /** Layouts that react to the column count. */
    fun usesColumns(layout: CollageLayout): Boolean = when (layout) {
        CollageLayout.GRID, CollageLayout.MASONRY, CollageLayout.STAGGER, CollageLayout.ARCH -> true
        else -> false
    }

    /** Layouts that offer a "shuffle" (different arrangement per seed). */
    fun usesSeed(layout: CollageLayout): Boolean = when (layout) {
        CollageLayout.SMART, CollageLayout.MOODBOARD, CollageLayout.LAYERED,
        CollageLayout.SCRAPBOOK, CollageLayout.BENTO, CollageLayout.MAGAZINE -> true
        else -> false
    }

    /** Layouts whose canvas shape (1:1, 4:5, 9:16, 3:2) can be chosen. */
    fun usesCanvas(layout: CollageLayout): Boolean =
        layout == CollageLayout.SMART || layout == CollageLayout.MOODBOARD || layout == CollageLayout.LAYERED

    private fun isOverlapping(layout: CollageLayout): Boolean =
        layout == CollageLayout.LAYERED || layout == CollageLayout.SCRAPBOOK

    /**
     * Schematic tiles of [layout] for [n] photos, fitted into a unit square.
     * Used for the layout thumbnails so they show the real arrangement.
     */
    fun thumbRects(layout: CollageLayout, n: Int, columns: Int): List<ThumbTile> {
        val count = n.coerceIn(1, 9)
        val fake = List(count) { Source(Uri.EMPTY, it, 1000, 1000, ExifInterface.ORIENTATION_NORMAL) }
        val res = buildLayout(layout, columns.coerceAtMost(count), fake, 1f, 0.05f, 0, CollageCanvas.AUTO)
        val k = 1f / max(1f, res.contentHeight)
        val ox = (1f - k) / 2f
        val oy = (1f - res.contentHeight * k) / 2f
        return res.slots.mapIndexed { i, r ->
            ThumbTile(
                floatArrayOf(r.left * k + ox, r.top * k + oy, r.width() * k, r.height() * k),
                res.shapes?.getOrNull(i) ?: TileShape.RECT,
                res.angles?.getOrNull(i) ?: 0f
            )
        }
    }

    fun defaultBackground(frame: CollageFrame): CollageBackground = when (frame) {
        CollageFrame.CLEAN -> CollageBackground(listOf(Color.WHITE))
        CollageFrame.SOFT -> CollageBackground(listOf(0xFFFFF1EB.toInt(), 0xFFF1E8FF.toInt()), 135f)
        CollageFrame.NOIR -> CollageBackground(listOf(0xFF0A0A0C.toInt(), 0xFF1B1C21.toInt()), 90f)
        CollageFrame.GALLERY -> CollageBackground(listOf(0xFFECE8E1.toInt()))
        CollageFrame.FLOAT -> CollageBackground(listOf(0xFFF5F6F8.toInt(), 0xFFE1E5EB.toInt()), 90f)
        CollageFrame.GLASS -> CollageBackground(
            listOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF3AAFA9.toInt()), 135f
        )
        CollageFrame.EDITORIAL -> CollageBackground(listOf(0xFFF6F2EA.toInt()))
        CollageFrame.STICKER -> CollageBackground(listOf(0xFFFFD86F.toInt(), 0xFFFC6262.toInt()), 135f)
        CollageFrame.INSTANT -> CollageBackground(listOf(0xFFF1EBE0.toInt(), 0xFFDDD2BF.toInt()), 45f)
        CollageFrame.MINIMAL -> CollageBackground(listOf(0xFFE3E4E6.toInt()))
        CollageFrame.SCRAPBOOK -> CollageBackground(listOf(0xFFE6D3B8.toInt(), 0xFFD8BF9C.toInt()), 135f)
        CollageFrame.TORN -> CollageBackground(listOf(0xFFE7DDCB.toInt(), 0xFFD7C9B1.toInt()), 135f)
    }

    /** Paper texture a frame comes with. */
    fun defaultTexture(frame: CollageFrame): CollageTexture = when (frame) {
        CollageFrame.SCRAPBOOK -> CollageTexture.KRAFT
        CollageFrame.TORN -> CollageTexture.PAPER
        else -> CollageTexture.NONE
    }

    fun defaultSpacing(frame: CollageFrame): Int = when (frame) {
        CollageFrame.CLEAN -> 12
        CollageFrame.SOFT -> 18
        CollageFrame.NOIR -> 10
        CollageFrame.GALLERY -> 28
        CollageFrame.FLOAT -> 22
        CollageFrame.GLASS -> 16
        CollageFrame.EDITORIAL -> 14
        CollageFrame.STICKER -> 20
        CollageFrame.INSTANT -> 32
        CollageFrame.MINIMAL -> 6
        CollageFrame.SCRAPBOOK -> 14
        CollageFrame.TORN -> 8
    }

    fun defaultCorner(frame: CollageFrame): Int = when (frame) {
        CollageFrame.CLEAN -> 24
        CollageFrame.SOFT -> 40
        CollageFrame.NOIR -> 16
        CollageFrame.GALLERY -> 0
        CollageFrame.FLOAT -> 28
        CollageFrame.GLASS -> 30
        CollageFrame.EDITORIAL -> 0
        CollageFrame.STICKER -> 30
        CollageFrame.INSTANT -> 6
        CollageFrame.MINIMAL -> 4
        CollageFrame.SCRAPBOOK -> 4
        CollageFrame.TORN -> 0
    }

    /** These frames ignore the corner setting. */
    fun hasFixedCorner(frame: CollageFrame): Boolean =
        frame == CollageFrame.INSTANT || frame == CollageFrame.GALLERY || frame == CollageFrame.EDITORIAL ||
                frame == CollageFrame.SCRAPBOOK || frame == CollageFrame.TORN

    /** Dark or light ink, whichever contrasts with [bg]. */
    fun inkFor(bg: CollageBackground): Int {
        val lum = bg.colors.map { luminance(it) }.average()
        return if (lum > 0.6) 0xFF1C1C1E.toInt() else Color.WHITE
    }

    /**
     * Draws [t] onto [canvas] of the given size. Used by the exporter and by the preview
     * screen, so what you see is what you get. [autoColor] is used when t.color is null.
     */
    fun drawTextOverlay(canvas: Canvas, t: CollageText, autoColor: Int, width: Float, height: Float) {
        if (t.text.isBlank()) return

        val size = t.sizeFraction * width
        val style = when {
            t.bold && t.italic -> Typeface.BOLD_ITALIC
            t.bold -> Typeface.BOLD
            t.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color ?: if (t.tape) 0xFF2B2623.toInt() else autoColor
            textAlign = Paint.Align.CENTER
            textSize = size
            letterSpacing = t.letterSpacing
            typeface = Typeface.create(t.font.family, style)
        }

        val raw = if (t.uppercase) t.text.uppercase() else t.text
        val lines = raw.split('\n')
        val lineH = size * 1.22f
        val blockH = lineH * lines.size
        val cx = t.x * width
        val cy = t.y * height

        canvas.save()
        canvas.rotate(t.rotation, cx, cy)

        if (t.tape) {
            val maxW = lines.maxOf { paint.measureText(it) }
            val l = cx - maxW / 2f - size * 0.7f
            val r = cx + maxW / 2f + size * 0.7f
            val tp = cy - blockH / 2f - size * 0.3f
            val bt = cy + blockH / 2f + size * 0.3f
            val tooth = size * 0.2f
            val amp = size * 0.08f
            val path = Path()
            path.moveTo(l, tp)
            path.lineTo(r, tp)
            var y = tp
            var out = true
            while (y < bt) {
                y = min(y + tooth, bt)
                path.lineTo(if (out) r + amp else r, y)
                out = !out
            }
            path.lineTo(l, bt)
            y = bt
            out = true
            while (y > tp) {
                y = max(y - tooth, tp)
                path.lineTo(if (out) l - amp else l, y)
                out = !out
            }
            path.close()
            val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xF2EADCC4.toInt()
                setShadowLayer(size * 0.12f, 0f, size * 0.05f, 0x44000000)
            }
            canvas.drawPath(path, tapePaint)
        }

        if (t.backdrop) {
            val maxW = lines.maxOf { paint.measureText(it) }
            val padX = size * 0.5f
            val padY = size * 0.25f
            val r = RectF(cx - maxW / 2f - padX, cy - blockH / 2f - padY, cx + maxW / 2f + padX, cy + blockH / 2f + padY)
            val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (luminance(paint.color) > 0.6f) 0x99000000.toInt() else 0xCCFFFFFF.toInt()
            }
            val radius = min(r.height() / 2f, size * 0.7f)
            canvas.drawRoundRect(r, radius, radius, plate)
        }

        if (t.shadow) paint.setShadowLayer(size * 0.14f, 0f, size * 0.05f, 0x99000000.toInt())

        val fm = paint.fontMetrics
        var baseline = cy - blockH / 2f + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        for (line in lines) {
            canvas.drawText(line, cx, baseline, paint)
            baseline += lineH
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    /**
     * Draws one photo into its tile with [t] applied. The UI uses this for the live overlay
     * while pinching / dragging, so the result matches the final render. The canvas must
     * already be scaled to preview-bitmap pixels.
     */
    fun drawTilePhoto(canvas: Canvas, bmp: Bitmap, tile: TileInfo, t: PhotoTransform, paint: Paint) {
        val dst = tile.photo
        val zoom = if (t.zoom.isFinite()) t.zoom.coerceIn(1f, 8f) else 1f
        val panX = if (t.panX.isFinite()) t.panX.coerceIn(-1f, 1f) else 0f
        val panY = if (t.panY.isFinite()) t.panY.coerceIn(-1f, 1f) else 0f

        val scale = max(dst.width() / bmp.width, dst.height() / bmp.height) * zoom
        val overflowX = max(bmp.width * scale - dst.width(), 0f)
        val overflowY = max(bmp.height * scale - dst.height(), 0f)

        canvas.save()
        if (tile.angle != 0f) canvas.rotate(tile.angle, tile.card.centerX(), tile.card.centerY())
        // Clip to the tile outline, then draw the whole bitmap through an exact transform.
        // No shader is involved, so it can never smear edge pixels into streaks.
        val clip = shapePath(tile.shape, dst, tile.photoCorner)
        canvas.clipPath(clip)
        canvas.translate(
            dst.left - overflowX * (0.5f + 0.5f * panX),
            dst.top - overflowY * (0.5f + 0.5f * panY)
        )
        canvas.scale(scale, scale)
        paint.shader = null
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
    }

    /** Decodes one photo (EXIF rotated) so its longest side is about [maxSide] px, for the live overlay. */
    fun loadPhoto(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? {
        val src = readSource(context, uri, 0) ?: return null
        val a = src.aspect
        val w = if (a >= 1f) maxSide else (maxSide * a).toInt().coerceAtLeast(1)
        val h = if (a >= 1f) (maxSide / a).toInt().coerceAtLeast(1) else maxSide
        return try {
            decode(context, src, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $uri", e)
            null
        }
    }

    private fun render(
        context: Context,
        uris: List<Uri>,
        config: CollageConfig,
        shouldContinue: () -> Boolean = { true }
    ): Bitmap = renderWithTiles(context, uris, config, shouldContinue).first

    private fun renderWithTiles(
        context: Context,
        uris: List<Uri>,
        config: CollageConfig,
        shouldContinue: () -> Boolean = { true }
    ): Pair<Bitmap, List<TileInfo>> {
        require(config.width in 480..4096) { "width must be between 480 and 4096" }

        val s = config.width / 1080f
        val overlapping = isOverlapping(config.layout)
        val spec = specFor(config, s, overlapping)

        val all = uris.mapIndexed { i, u -> readSource(context, u, i) }
        val requested = config.order
            ?.takeIf { o -> o.size == uris.size && o.toSet().size == o.size && o.all { it in uris.indices } }
            ?: uris.indices.toList()
        val sources = requested.mapNotNull { all[it] }
        if (sources.isEmpty()) throw IllegalStateException("None of the selected photos could be read")

        val width = config.width.toFloat()
        val contentW = width - spec.padding * 2f
        val layout = buildLayout(config.layout, config.columns, sources, contentW, spec.gap, config.seed, config.canvas)

        val footerH = if (config.footer) 130f * s else 0f
        val totalH = ceil(spec.padding * 2f + layout.contentHeight + footerH).toInt()
        if (totalH > width * 11f) {
            throw IllegalStateException("Too many photos for this layout. Try fewer photos or another layout.")
        }

        val bg = config.background ?: defaultBackground(config.frame)
        val ink = inkFor(bg)
        val tiles = ArrayList<TileInfo>()

        val out = Bitmap.createBitmap(config.width, totalH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            drawBackground(canvas, bg, width, totalH.toFloat())
            drawTexture(canvas, config.texture ?: defaultTexture(config.frame), width, totalH.toFloat(), s)

            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var drawn = 0
            val decor = config.decor
                ?: if (config.layout == CollageLayout.SCRAPBOOK || config.frame == CollageFrame.SCRAPBOOK) CollageDecor.MIXED
                else CollageDecor.NONE

            layout.slots.forEachIndexed { i, slot ->
                if (!shouldContinue()) throw CancellationException("Collage render cancelled")

                val src = sources[i]
                val card = RectF(slot).apply { offset(spec.padding, spec.padding) }
                val shape = layout.shapes?.getOrNull(i) ?: TileShape.RECT
                val photo = photoRect(config.frame, card, s, shape, overlapping)

                // Tile geometry for the editor
                val photoAspect = photo.width() / photo.height()
                val wider = src.aspect > photoAspect
                val imgW = if (wider) photo.height() * src.aspect else photo.width()
                val imgH = if (wider) photo.height() else photo.width() / src.aspect
                val angle = layout.angles?.getOrNull(i) ?: when (config.frame) {
                    CollageFrame.INSTANT -> INSTANT_ANGLES[i % INSTANT_ANGLES.size]
                    CollageFrame.SCRAPBOOK -> INSTANT_ANGLES[i % INSTANT_ANGLES.size] * 0.6f
                    else -> 0f
                }
                tiles += TileInfo(src.index, RectF(card), RectF(photo), imgW, imgH, spec.photoCorner, angle, shape)

                val bmp = try {
                    decode(
                        context,
                        src,
                        ceil(photo.width()).toInt().coerceAtLeast(1),
                        ceil(photo.height()).toInt().coerceAtLeast(1)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not decode ${src.uri}, skipping", e)
                    null
                } ?: return@forEachIndexed

                val xf = config.transforms[src.index] ?: PhotoTransform()
                drawTile(canvas, config.frame, spec, card, photo, bmp, angle, s, ink, photoPaint, shape, xf, i, config.seed)
                if (decor != CollageDecor.NONE) drawDecor(canvas, decor, card, angle, s, config.seed, i)
                bmp.recycle()
                drawn++
            }

            if (drawn == 0) throw IllegalStateException("None of the selected photos could be read")

            if (config.frame == CollageFrame.EDITORIAL) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * s
                    color = ink
                }
                val inset = 26f * s
                canvas.drawRect(inset, inset, width - inset, totalH - inset, border)
            }

            val text = config.text
            if (text != null && text.text.isNotBlank()) {
                drawTextOverlay(canvas, text, ink, width, totalH.toFloat())
            }

            return out to tiles
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }

    private fun drawTile(
        canvas: Canvas,
        frame: CollageFrame,
        spec: Spec,
        card: RectF,
        photo: RectF,
        bmp: Bitmap,
        angle: Float,
        s: Float,
        ink: Int,
        photoPaint: Paint,
        shape: TileShape = TileShape.RECT,
        xf: PhotoTransform = PhotoTransform(),
        tileIndex: Int = 0,
        seed: Int = 0
    ) {
        canvas.save()
        if (angle != 0f) canvas.rotate(angle, card.centerX(), card.centerY())

        // Card, mat, glass or print base behind the photo (also carries the shadow)
        if (spec.paperColor != null) {
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.paperColor
                if (spec.shadow) setShadowLayer(spec.shadowRadius, 0f, spec.shadowRadius * 0.4f, spec.shadowColor)
            }
            val paperPath = if (frame == CollageFrame.TORN && shape == TileShape.RECT) {
                tornPath(card, s, Random(tileIndex * 7919L + seed * 31L + 11L))
            } else {
                shapePath(shape, card, spec.paperCorner)
            }
            canvas.drawPath(paperPath, paper)
        }

        drawCover(canvas, bmp, photo, spec.photoCorner, photoPaint, xf.panX, xf.panY, xf.zoom, shape)

        when (frame) {
            CollageFrame.NOIR -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * s
                    color = (ink and 0x00FFFFFF) or (0x30 shl 24)
                }
                val r = RectF(photo).apply { inset(s, s) }
                canvas.drawPath(shapePath(shape, r, spec.photoCorner), stroke)
            }
            CollageFrame.GALLERY -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.4f * s
                    color = 0x24000000
                }
                canvas.drawPath(shapePath(shape, photo, 0f), stroke)
            }
            CollageFrame.GLASS -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f * s
                    color = 0x88FFFFFF.toInt()
                }
                val r = RectF(card).apply { inset(0.8f * s, 0.8f * s) }
                canvas.drawPath(shapePath(shape, r, spec.paperCorner), stroke)
            }
            else -> Unit
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Style specification
    // ---------------------------------------------------------------------

    private val INSTANT_ANGLES = floatArrayOf(-2.4f, 1.6f, -1.1f, 2.3f, -1.8f, 1.2f)

    private class Spec(
        val padding: Float,
        val gap: Float,
        val photoCorner: Float,
        val paperCorner: Float,
        val paperColor: Int?,
        val shadow: Boolean,
        val shadowRadius: Float,
        val shadowColor: Int
    )

    private fun specFor(cfg: CollageConfig, s: Float, overlapping: Boolean): Spec {
        val frame = cfg.frame
        val gap = (cfg.spacing ?: defaultSpacing(frame)) * s
        val corner = (if (hasFixedCorner(frame)) defaultCorner(frame) else (cfg.corner ?: defaultCorner(frame))) * s
        val pad = cfg.padding?.let { it * s }

        val base = when (frame) {
            CollageFrame.CLEAN, CollageFrame.NOIR -> Spec(
                padding = pad ?: max(gap * 2f, 28f * s),
                gap = gap,
                photoCorner = corner,
                paperCorner = 0f,
                paperColor = null,
                shadow = false,
                shadowRadius = 0f,
                shadowColor = 0
            )

            // The card sits exactly behind the photo, so it only contributes the soft shadow.
            CollageFrame.SOFT -> Spec(
                padding = pad ?: 40f * s,
                gap = gap,
                photoCorner = corner,
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 22f * s,
                shadowColor = 0x2E000000
            )

            CollageFrame.GALLERY -> Spec(
                padding = pad ?: 56f * s,
                gap = gap,
                photoCorner = 0f,
                paperCorner = 2f * s,
                paperColor = 0xFFFBFAF7.toInt(),
                shadow = true,
                shadowRadius = 10f * s,
                shadowColor = 0x2E000000
            )

            CollageFrame.FLOAT -> Spec(
                padding = pad ?: 44f * s,
                gap = gap,
                photoCorner = max(corner - 6f * s, 0f),
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 24f * s,
                shadowColor = 0x38000000
            )

            CollageFrame.GLASS -> Spec(
                padding = pad ?: 40f * s,
                gap = gap,
                photoCorner = max(corner - 8f * s, 0f),
                paperCorner = corner,
                paperColor = 0x33FFFFFF,
                shadow = false,
                shadowRadius = 0f,
                shadowColor = 0
            )

            CollageFrame.EDITORIAL -> Spec(
                padding = pad ?: 84f * s,
                gap = gap,
                photoCorner = 0f,
                paperCorner = 0f,
                paperColor = null,
                shadow = false,
                shadowRadius = 0f,
                shadowColor = 0
            )

            CollageFrame.STICKER -> Spec(
                padding = pad ?: 40f * s,
                gap = gap,
                photoCorner = max(corner - 9f * s, 0f),
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 12f * s,
                shadowColor = 0x4D000000
            )

            CollageFrame.INSTANT -> Spec(
                padding = pad ?: 48f * s,
                gap = max(gap, 16f * s),
                photoCorner = 2f * s,
                paperCorner = corner,
                paperColor = 0xFFFDFCF8.toInt(),
                shadow = true,
                shadowRadius = 14f * s,
                shadowColor = 0x50000000
            )

            CollageFrame.MINIMAL -> Spec(
                padding = pad ?: 28f * s,
                gap = gap,
                photoCorner = corner,
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 5f * s,
                shadowColor = 0x26000000
            )

            CollageFrame.SCRAPBOOK -> Spec(
                padding = pad ?: 52f * s,
                gap = max(gap, 14f * s),
                photoCorner = 2f * s,
                paperCorner = corner,
                paperColor = 0xFFFDFCF8.toInt(),
                shadow = true,
                shadowRadius = 12f * s,
                shadowColor = 0x55000000
            )

            CollageFrame.TORN -> Spec(
                padding = pad ?: 36f * s,
                gap = gap,
                photoCorner = 0f,
                paperCorner = 0f,
                paperColor = 0xFFFBFAF6.toInt(),
                shadow = true,
                shadowRadius = 9f * s,
                shadowColor = 0x50000000
            )
        }

        // Overlapping layouts need a paper edge and shadow, or the tiles would melt into each other.
        return if (overlapping && base.paperColor == null) {
            Spec(base.padding, base.gap, base.photoCorner, base.photoCorner, Color.WHITE, true, 10f * s, 0x40000000)
        } else base
    }

    /** Where the photo sits inside its card (mat, print border, torn edge). */
    private fun photoRect(frame: CollageFrame, card: RectF, s: Float, shape: TileShape, overlapping: Boolean): RectF {
        if (shape == TileShape.RECT) return rectPhotoRect(frame, card, s, overlapping)
        // Arch tiles: a slim constant border for every frame that has one
        val margin = when (frame) {
            CollageFrame.FLOAT -> 6f * s
            CollageFrame.GLASS, CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> 8f * s
            CollageFrame.STICKER -> 9f * s
            CollageFrame.GALLERY -> 10f * s
            CollageFrame.TORN -> 12f * s
            CollageFrame.MINIMAL -> 0f
            else -> if (overlapping) 6f * s else 0f
        }
        return RectF(card).apply { inset(margin, margin) }
    }

    private fun rectPhotoRect(frame: CollageFrame, card: RectF, s: Float, overlapping: Boolean): RectF = when (frame) {
        CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> {
            val b = min(card.width(), card.height()) * 0.06f
            RectF(card.left + b, card.top + b, card.right - b, card.bottom - b * 3.4f)
        }
        CollageFrame.GALLERY -> {
            val b = min(card.width(), card.height()) * 0.075f
            RectF(card.left + b, card.top + b, card.right - b, card.bottom - b * 1.5f)
        }
        CollageFrame.FLOAT -> RectF(card).apply { inset(6f * s, 6f * s) }
        CollageFrame.GLASS -> RectF(card).apply { inset(8f * s, 8f * s) }
        CollageFrame.STICKER -> RectF(card).apply { inset(9f * s, 9f * s) }
        CollageFrame.TORN -> RectF(card).apply { inset(13f * s, 13f * s) }
        CollageFrame.MINIMAL -> if (overlapping) RectF(card).apply { inset(4f * s, 4f * s) } else RectF(card)
        else -> if (overlapping) RectF(card).apply { inset(6f * s, 6f * s) } else RectF(card)
    }

    // ---------------------------------------------------------------------
    // Layouts (all coordinates relative to the content area)
    // ---------------------------------------------------------------------

    private class LayoutResult(
        val slots: List<RectF>,
        val contentHeight: Float,
        /** null = every tile is a rounded rectangle. */
        val shapes: List<TileShape>? = null,
        /** Per-tile rotation in degrees; null = decided by the frame. */
        val angles: List<Float>? = null
    )

    private fun buildLayout(
        requested: CollageLayout,
        columns: Int,
        sources: List<Source>,
        contentW: Float,
        gap: Float,
        seed: Int,
        canvas: CollageCanvas
    ): LayoutResult {
        val n = sources.size
        val layout = if (requested in layoutsFor(n)) requested else defaultLayout(n)

        if (n == 1) {
            val h = contentW / sources[0].aspect.coerceIn(0.75f, 1.5f)
            return LayoutResult(listOf(RectF(0f, 0f, contentW, h)), h)
        }

        val ratio = canvas.ratio ?: autoRatio(n)
        return when (layout) {
            CollageLayout.SMART -> smartSlots(sources, contentW, gap, seed, ratio)
            CollageLayout.MOODBOARD -> moodboardSlots(sources, contentW, gap, seed, ratio, overlap = false)
            CollageLayout.LAYERED -> moodboardSlots(sources, contentW, gap, seed, ratio, overlap = true)
            CollageLayout.SCRAPBOOK -> scrapbookSlots(n, contentW, seed)

            CollageLayout.GRID -> {
                val (slots, h) = gridSlots(n, columns, contentW, gap, 0f, 1f)
                LayoutResult(slots, h)
            }

            CollageLayout.FEATURE -> {
                val heroH = contentW * 0.68f
                val m = n - 1
                val cols = when {
                    m <= 3 -> m
                    m == 4 -> 2
                    else -> 3
                }
                val r = if (cols <= 2) 0.72f else 1f
                val (rest, h) = gridSlots(m, cols, contentW, gap, heroH + gap, r)
                LayoutResult(listOf(RectF(0f, 0f, contentW, heroH)) + rest, heroH + gap + h)
            }

            CollageLayout.MOSAIC -> mosaicSlots(sources, contentW, gap)
            CollageLayout.MASONRY -> masonrySlots(sources, columns, contentW, gap)
            CollageLayout.BENTO -> bentoSlots(n, contentW, gap, seed)
            CollageLayout.MAGAZINE -> magazineSlots(n, contentW, gap, seed)
            CollageLayout.STAGGER -> staggerSlots(n, columns, contentW, gap)

            CollageLayout.ARCH -> {
                val (slots, h) = gridSlots(n, columns, contentW, gap, 0f, 1.32f)
                LayoutResult(slots, h, List(n) { TileShape.ARCH })
            }

            CollageLayout.STORY -> storySlots(sources, contentW, gap)
        }
    }

    private fun autoRatio(n: Int): Float = when {
        n <= 3 -> 1.0f
        n <= 5 -> 1.2f
        n <= 8 -> 1.35f
        n <= 12 -> 1.5f
        else -> 1.8f
    }

    private fun gridSlots(
        n: Int,
        columns: Int,
        contentW: Float,
        gap: Float,
        top: Float,
        ratio: Float
    ): Pair<List<RectF>, Float> {
        val cols = columns.coerceIn(1, max(n, 1))
        val cellW = (contentW - gap * (cols - 1)) / cols
        val cellH = cellW * ratio
        val rows = ceil(n / cols.toDouble()).toInt()

        val slots = ArrayList<RectF>(n)
        for (i in 0 until n) {
            val row = i / cols
            val col = i % cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            val rowW = inRow * cellW + gap * (inRow - 1)
            val x = (contentW - rowW) / 2f + col * (cellW + gap)
            val y = top + row * (cellH + gap)
            slots += RectF(x, y, x + cellW, y + cellH)
        }
        return slots to (rows * cellH + gap * (rows - 1))
    }

    /**
     * Dynamic layout: the canvas is split again and again (a binary space partition) until every
     * photo has a tile. Many random candidates are tried and the one whose tiles best match the
     * photos' own shapes wins, so landscape photos get wide tiles and portraits get tall ones.
     * The same [seed] always gives the same result; a new seed gives a new arrangement.
     */
    private fun smartSlots(sources: List<Source>, contentW: Float, gap: Float, seed: Int, ratio: Float): LayoutResult {
        val n = sources.size
        val h = contentW * ratio
        val asp = sources.map { it.aspect.coerceIn(0.5f, 2f) }
        val limit = ln(2.2f)

        var best: List<RectF>? = null
        var bestScore = Float.MAX_VALUE
        for (attempt in 0 until 40) {
            val rng = Random(seed.toLong() * 1_000_003L + attempt)
            val out = arrayOfNulls<RectF>(n)
            partition((0 until n).toList(), RectF(0f, 0f, contentW, h), gap, rng, out)

            var score = 0f
            for (i in 0 until n) {
                val r = out[i]!!
                val tileAspect = (r.width() / r.height()).coerceAtLeast(0.01f)
                score += abs(ln(tileAspect / asp[i]))
                val extreme = abs(ln(tileAspect))
                if (extreme > limit) score += (extreme - limit) * 3f
            }
            score /= n
            if (score < bestScore) {
                bestScore = score
                best = out.map { it!! }
            }
        }
        return LayoutResult(best!!, h)
    }

    private fun partition(items: List<Int>, r: RectF, gap: Float, rng: Random, out: Array<RectF?>) {
        val n = items.size
        if (n == 1) {
            out[items[0]] = RectF(r)
            return
        }
        val k = when (n) {
            2 -> 1
            3 -> if (rng.nextBoolean()) 1 else 2
            else -> (n / 2 + rng.nextInt(3) - 1).coerceIn(1, n - 1)
        }
        val first = items.subList(0, k)
        val second = items.subList(k, n)

        // A lone photo may become a larger "hero" tile
        fun weight(size: Int): Float = if (size == 1) 1f + rng.nextFloat() * 1.2f else size.toFloat()
        val wa = weight(first.size)
        val wb = weight(second.size)
        val frac = wa / (wa + wb)

        val wide = r.width() >= r.height()
        val sideBySide = if (rng.nextFloat() < 0.15f) !wide else wide

        if (sideBySide) {
            val avail = (r.width() - gap).coerceAtLeast(2f)
            val wFirst = avail * frac
            partition(first, RectF(r.left, r.top, r.left + wFirst, r.bottom), gap, rng, out)
            partition(second, RectF(r.left + wFirst + gap, r.top, r.right, r.bottom), gap, rng, out)
        } else {
            val avail = (r.height() - gap).coerceAtLeast(2f)
            val hFirst = avail * frac
            partition(first, RectF(r.left, r.top, r.right, r.top + hFirst), gap, rng, out)
            partition(second, RectF(r.left, r.top + hFirst + gap, r.right, r.bottom), gap, rng, out)
        }
    }

    /** Columns whose tiles alternate between tall and short, offset from column to column. */
    private fun staggerSlots(n: Int, columns: Int, contentW: Float, gap: Float): LayoutResult {
        val cols = columns.coerceIn(2, 4).coerceAtMost(max(2, n / 2))
        val colW = (contentW - gap * (cols - 1)) / cols

        val items = List(cols) { ArrayList<Int>() }
        for (i in 0 until n) items[i % cols] += i

        val tall = colW * 1.35f
        val short = colW * 0.9f
        val natural = items.mapIndexed { c, list ->
            list.indices.map { j -> if ((j + c) % 2 == 0) tall else short }
        }
        val target = natural.indices.maxOf { natural[it].sum() + gap * (items[it].size - 1) }

        val slots = arrayOfNulls<RectF>(n)
        for (c in 0 until cols) {
            if (items[c].isEmpty()) continue
            val avail = target - gap * (items[c].size - 1)
            val factor = avail / natural[c].sum()
            val x = c * (colW + gap)
            var y = 0f
            items[c].forEachIndexed { j, idx ->
                val th = natural[c][j] * factor
                slots[idx] = RectF(x, y, x + colW, y + th)
                y += th + gap
            }
        }
        return LayoutResult(slots.map { it!! }, target)
    }

    /** Justified rows: every full row spans the whole width exactly. */
    private fun mosaicSlots(sources: List<Source>, contentW: Float, gap: Float): LayoutResult {
        val n = sources.size
        val asp = sources.map { it.aspect.coerceIn(0.6f, 2.0f) }
        val targetH = contentW * 0.34f

        val slots = ArrayList<RectF>(n)
        var y = 0f
        var i = 0
        while (i < n) {
            var j = i
            var sum = 0f
            while (j < n) {
                sum += asp[j]
                j++
                if (sum * targetH + gap * (j - i - 1) >= contentW) break
            }
            val count = j - i
            val filled = sum * targetH + gap * (count - 1) >= contentW
            var rowH = (contentW - gap * (count - 1)) / sum
            if (!filled) rowH = min(rowH, targetH * 1.15f)

            val rowW = sum * rowH + gap * (count - 1)
            var x = (contentW - rowW) / 2f
            for (k in i until j) {
                val w = asp[k] * rowH
                slots += RectF(x, y, x + w, y + rowH)
                x += w + gap
            }
            y += rowH + gap
            i = j
        }
        return LayoutResult(slots, y - gap)
    }

    /** Columns filled shortest-first, then stretched so every column ends at the same height. */
    private fun masonrySlots(sources: List<Source>, columns: Int, contentW: Float, gap: Float): LayoutResult {
        val n = sources.size
        val cols = columns.coerceIn(2, 4).coerceAtMost(n)
        val colW = (contentW - gap * (cols - 1)) / cols

        val items = List(cols) { ArrayList<Int>() }
        val colSum = FloatArray(cols)
        val hs = FloatArray(n)
        for (i in 0 until n) {
            hs[i] = colW / sources[i].aspect.coerceIn(0.6f, 1.8f)
            var c = 0
            for (k in 1 until cols) if (colSum[k] < colSum[c]) c = k
            items[c] += i
            colSum[c] += hs[i]
        }

        val target = (0 until cols).maxOf { colSum[it] + gap * (items[it].size - 1) }
        val slots = arrayOfNulls<RectF>(n)
        for (c in 0 until cols) {
            if (items[c].isEmpty()) continue
            val avail = target - gap * (items[c].size - 1)
            val factor = avail / colSum[c]
            val x = c * (colW + gap)
            var y = 0f
            for (i in items[c]) {
                val h = hs[i] * factor
                slots[i] = RectF(x, y, x + colW, y + h)
                y += h + gap
            }
        }
        return LayoutResult(slots.map { it!! }, target)
    }

    /** Blocks on a 4-column unit grid: cell = [col, row, colSpan, rowSpan]. Every tile is at most 2:1. */
    private val BENTO_BLOCKS: Map<Int, Pair<Int, List<IntArray>>> = mapOf(
        1 to (2 to listOf(intArrayOf(0, 0, 4, 2))),
        2 to (2 to listOf(intArrayOf(0, 0, 2, 2), intArrayOf(2, 0, 2, 2))),
        3 to (2 to listOf(intArrayOf(0, 0, 2, 2), intArrayOf(2, 0, 2, 1), intArrayOf(2, 1, 2, 1))),
        4 to (2 to listOf(intArrayOf(0, 0, 2, 2), intArrayOf(2, 0, 2, 1), intArrayOf(2, 1, 1, 1), intArrayOf(3, 1, 1, 1))),
        5 to (2 to listOf(
            intArrayOf(0, 0, 2, 2), intArrayOf(2, 0, 1, 1), intArrayOf(3, 0, 1, 1),
            intArrayOf(2, 1, 1, 1), intArrayOf(3, 1, 1, 1)
        )),
        6 to (3 to listOf(
            intArrayOf(0, 0, 2, 2), intArrayOf(2, 0, 2, 1), intArrayOf(2, 1, 1, 1),
            intArrayOf(3, 1, 1, 2), intArrayOf(0, 2, 2, 1), intArrayOf(2, 2, 1, 1)
        ))
    )

    private fun bentoSlots(n: Int, contentW: Float, gap: Float, seed: Int): LayoutResult {
        val unit = (contentW - gap * 3f) / 4f

        val sizes = ArrayList<Int>()
        var rem = n
        while (rem > 0) {
            val take = when {
                rem >= 8 -> 6
                rem == 7 -> 4
                else -> rem
            }
            sizes += take
            rem -= take
        }

        val slots = ArrayList<RectF>(n)
        var y = 0f
        var block = 0
        for (size in sizes) {
            val (rows, cells) = BENTO_BLOCKS.getValue(size)
            // Each shuffle mirrors / flips the blocks, so the same photos get new positions
            val mirror = ((seed + block) and 1) == 1
            val flip = (((seed + block) shr 1) and 1) == 1
            for (cell in cells) {
                val col = if (mirror) 4 - cell[0] - cell[2] else cell[0]
                val row = if (flip) rows - cell[1] - cell[3] else cell[1]
                val x = col * (unit + gap)
                val top = y + row * (unit + gap)
                val w = cell[2] * unit + (cell[2] - 1) * gap
                val h = cell[3] * unit + (cell[3] - 1) * gap
                slots += RectF(x, top, x + w, top + h)
            }
            y += rows * (unit + gap)
            block++
        }
        return LayoutResult(slots, y - gap)
    }

    private fun magazineSlots(n: Int, contentW: Float, gap: Float, seed: Int): LayoutResult {
        val m = n - 1
        val leftW = (contentW - gap) * 0.58f
        val rightW = contentW - gap - leftW
        val minTileH = rightW * 0.75f
        val h = max(contentW * 1.1f, m * minTileH + gap * (m - 1))
        val tileH = (h - gap * (m - 1)) / m

        val slots = ArrayList<RectF>(n)
        slots += RectF(0f, 0f, leftW, h)
        for (k in 0 until m) {
            val y = k * (tileH + gap)
            slots += RectF(leftW + gap, y, contentW, y + tileH)
        }
        if ((seed and 1) == 1) {
            // Feature photo on the right
            return LayoutResult(slots.map { RectF(contentW - it.right, it.top, contentW - it.left, it.bottom) }, h)
        }
        return LayoutResult(slots, h)
    }

    private fun storySlots(sources: List<Source>, contentW: Float, gap: Float): LayoutResult {
        val slots = ArrayList<RectF>(sources.size)
        var y = 0f
        for (src in sources) {
            val h = contentW / src.aspect.coerceIn(0.8f, 1.8f)
            slots += RectF(0f, y, contentW, y + h)
            y += h + gap
        }
        return LayoutResult(slots, y - gap)
    }

    /**
     * Moodboard: the canvas is cut again and again on a fine grid at random positions, which
     * gives a few big tiles and many small ones, tightly packed. Photos fill the tiles in reading
     * order, and the best of 40 candidates (tiles that suit the photos' shapes) is kept.
     * With [overlap] the tiles grow into each other and tilt slightly, for a layered paper look.
     */
    private fun moodboardSlots(
        sources: List<Source>,
        contentW: Float,
        gap: Float,
        seed: Int,
        ratio: Float,
        overlap: Boolean
    ): LayoutResult {
        val n = sources.size
        val cols = when {
            n <= 12 -> 12
            n <= 24 -> 18
            else -> 24
        }
        val rows = (cols * ratio).roundToInt().coerceAtLeast(6)
        val h = contentW * ratio
        val uw = contentW / cols
        val uh = h / rows
        val asp = sources.map { it.aspect.coerceIn(0.5f, 2f) }
        // Positive = tiles shrink (flat gap), negative = tiles grow (overlap)
        val inset = if (overlap) -(contentW * 0.015f) else gap / 2f
        val limit = ln(2.8f)

        var best: List<RectF>? = null
        var bestScore = Float.MAX_VALUE
        for (attempt in 0 until 40) {
            val rng = Random(seed.toLong() * 1_000_003L + attempt)
            val cells = guillotine(n, cols, rows, uw / uh, rng)
            if (cells.size != n) continue
            cells.sortWith(compareBy<IntArray>({ it[1] }, { it[0] }))

            var score = 0f
            val rects = ArrayList<RectF>(n)
            for (i in 0 until n) {
                val c = cells[i]
                val tw = (c[2] - c[0]) * uw
                val th = (c[3] - c[1]) * uh
                val tileAspect = tw / th
                score += abs(ln(tileAspect / asp[i]))
                val extreme = abs(ln(tileAspect))
                if (extreme > limit) score += (extreme - limit) * 3f

                rects += RectF(
                    c[0] * uw + (if (c[0] > 0) inset else 0f),
                    c[1] * uh + (if (c[1] > 0) inset else 0f),
                    c[2] * uw - (if (c[2] < cols) inset else 0f),
                    c[3] * uh - (if (c[3] < rows) inset else 0f)
                )
            }
            score /= n
            if (score < bestScore) {
                bestScore = score
                best = rects
            }
        }
        val result = best ?: return smartSlots(sources, contentW, gap, seed, ratio)

        val angles = if (overlap) {
            val rng = Random(seed.toLong() * 977L + 5)
            List(n) { (rng.nextFloat() * 2f - 1f) * 1.8f }
        } else null
        return LayoutResult(result, h, null, angles)
    }

    /** Splits a [cols] x [rows] grid into exactly [n] rectangles; each cell is [c0, r0, c1, r1]. */
    private fun guillotine(n: Int, cols: Int, rows: Int, cellAspect: Float, rng: Random): MutableList<IntArray> {
        for (minU in intArrayOf(2, 1)) {
            val rects = mutableListOf(intArrayOf(0, 0, cols, rows))
            var guard = 0
            while (rects.size < n && guard++ < 2000) {
                val cand = rects.indices.filter {
                    val r = rects[it]
                    (r[2] - r[0]) >= 2 * minU || (r[3] - r[1]) >= 2 * minU
                }
                if (cand.isEmpty()) break

                // Bigger rectangles are more likely to be cut
                var total = 0f
                val weights = cand.map { idx ->
                    val r = rects[idx]
                    val wgt = ((r[2] - r[0]) * (r[3] - r[1])).toFloat() * (0.6f + rng.nextFloat())
                    total += wgt
                    wgt
                }
                var pick = rng.nextFloat() * total
                var chosen = cand.last()
                for (k in cand.indices) {
                    pick -= weights[k]
                    if (pick <= 0f) {
                        chosen = cand[k]
                        break
                    }
                }

                val r = rects.removeAt(chosen)
                val w = r[2] - r[0]
                val h = r[3] - r[1]
                val canSide = w >= 2 * minU
                val canStack = h >= 2 * minU
                val sideBySide = if (canSide && canStack) {
                    val wide = w * cellAspect >= h
                    if (rng.nextFloat() < 0.78f) wide else !wide
                } else canSide

                val len = if (sideBySide) w else h
                val f = if (rng.nextFloat() < 0.25f) 0.2f + 0.6f * rng.nextFloat() else 0.32f + 0.36f * rng.nextFloat()
                val pos = (len * f).roundToInt().coerceIn(minU, len - minU)
                if (sideBySide) {
                    rects += intArrayOf(r[0], r[1], r[0] + pos, r[3])
                    rects += intArrayOf(r[0] + pos, r[1], r[2], r[3])
                } else {
                    rects += intArrayOf(r[0], r[1], r[2], r[1] + pos)
                    rects += intArrayOf(r[0], r[1] + pos, r[2], r[3])
                }
            }
            if (rects.size >= n) return rects
        }
        return mutableListOf()
    }

    /**
     * Scrapbook: rows of two prints, then one wide print, and so on. Every print is tilted and
     * overlaps its neighbours a little; the variation changes with the seed.
     */
    private fun scrapbookSlots(n: Int, contentW: Float, seed: Int): LayoutResult {
        val rng = Random(seed.toLong() * 7_919L + 3)
        val slots = ArrayList<RectF>(n)
        val angles = ArrayList<Float>(n)
        fun tilt(): Float = (1.2f + rng.nextFloat() * 3.2f) * (if (rng.nextBoolean()) 1f else -1f)

        var i = 0
        var rowY = 0f
        var bottom = 0f
        var pairRow = true
        while (i < n) {
            if (pairRow && n - i >= 2) {
                val w = contentW * 0.52f
                val h = w * 1.2f
                for (k in 0..1) {
                    val x = if (k == 0) rng.nextFloat() * contentW * 0.03f
                    else contentW - w - rng.nextFloat() * contentW * 0.03f
                    val y = max(rowY + (rng.nextFloat() - 0.5f) * h * 0.08f, 0f)
                    slots += RectF(x, y, x + w, y + h)
                    angles += tilt()
                    bottom = max(bottom, y + h)
                }
                rowY += h * 0.86f
                i += 2
            } else {
                val w = contentW * 0.82f
                val h = w * 0.72f
                val x = ((contentW - w) / 2f + (rng.nextFloat() - 0.5f) * contentW * 0.06f).coerceIn(0f, contentW - w)
                val y = max(rowY + (rng.nextFloat() - 0.5f) * h * 0.06f, 0f)
                slots += RectF(x, y, x + w, y + h)
                angles += tilt()
                bottom = max(bottom, y + h)
                rowY += h * 0.86f
                i += 1
            }
            pairRow = !pairRow
        }
        return LayoutResult(slots, bottom, null, angles)
    }

    // ---------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------

    /** Outline of [shape] fitted into [r]. [corner] is the radius of the square corners. */
    private fun shapePath(shape: TileShape, r: RectF, corner: Float): Path {
        val path = Path()
        when (shape) {
            TileShape.RECT -> path.addRoundRect(r, corner, corner, Path.Direction.CW)
            TileShape.ARCH -> {
                // Fully rounded top, small corners at the bottom
                val top = min(r.width() / 2f, r.height() / 2f)
                val bottom = min(corner, top)
                path.addRoundRect(
                    r,
                    floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
                    Path.Direction.CW
                )
            }
        }
        return path
    }

    private fun drawBackground(canvas: Canvas, bg: CollageBackground, w: Float, h: Float) {
        if (bg.colors.size == 1) {
            canvas.drawColor(bg.colors[0])
            return
        }
        val rad = Math.toRadians(bg.angle.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - dx * half, cy - dy * half,
                cx + dx * half, cy + dy * half,
                bg.colors.toIntArray(), null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, p)
    }

    /**
     * Cover-crop the bitmap into [dst] with anti-aliased edges.
     * [zoom] >= 1 magnifies; [panX]/[panY] in -1..1 choose which part stays visible (0 = centered).
     */
    private fun drawCover(
        canvas: Canvas,
        bmp: Bitmap,
        dst: RectF,
        corner: Float,
        paint: Paint,
        panX: Float = 0f,
        panY: Float = 0f,
        zoom: Float = 1f,
        shape: TileShape = TileShape.RECT
    ) {
        val scale = max(dst.width() / bmp.width, dst.height() / bmp.height) * zoom.coerceIn(1f, 8f)
        val overflowX = bmp.width * scale - dst.width()
        val overflowY = bmp.height * scale - dst.height()
        val dx = dst.left - overflowX * (0.5f + 0.5f * panX.coerceIn(-1f, 1f))
        val dy = dst.top - overflowY * (0.5f + 0.5f * panY.coerceIn(-1f, 1f))

        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        paint.shader = shader
        if (shape == TileShape.RECT) {
            canvas.drawRoundRect(dst, corner, corner, paint)
        } else {
            canvas.drawPath(shapePath(shape, dst, corner), paint)
        }
        paint.shader = null
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** Ripped paper outline around [r]: small random bites along every edge. Same seed = same tear. */
    private fun tornPath(r: RectF, s: Float, rng: Random): Path {
        val step = 7f * s
        val amp = 5.5f * s
        val path = Path()
        var started = false

        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            val horizontal = y0 == y1
            val len = abs(x1 - x0) + abs(y1 - y0)
            val steps = max(2, (len / step).toInt())
            for (k in 0..steps) {
                val t = k.toFloat() / steps
                val bite = if (rng.nextFloat() < 0.08f) 1.8f else 1f
                val edgeFade = if (k == 0 || k == steps) 0.4f else 1f
                val j = (rng.nextFloat() - 0.5f) * 2f * amp * bite * edgeFade
                val px = x0 + (x1 - x0) * t + if (horizontal) 0f else j
                val py = y0 + (y1 - y0) * t + if (horizontal) j else 0f
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else {
                    path.lineTo(px, py)
                }
            }
        }
        edge(r.left, r.top, r.right, r.top)
        edge(r.right, r.top, r.right, r.bottom)
        edge(r.right, r.bottom, r.left, r.bottom)
        edge(r.left, r.bottom, r.left, r.top)
        path.close()
        return path
    }

    /** Washi tape across the top edge of a tile, or a push-pin on it. Drawn rotated with the tile. */
    private fun drawDecor(canvas: Canvas, decor: CollageDecor, card: RectF, angle: Float, s: Float, seed: Int, index: Int) {
        val rng = Random(seed.toLong() * 104_729L + index * 31L + 5L)
        val pin = when (decor) {
            CollageDecor.TAPE -> false
            CollageDecor.PINS -> true
            CollageDecor.MIXED -> index % 2 == 0
            CollageDecor.NONE -> return
        }

        canvas.save()
        if (angle != 0f) canvas.rotate(angle, card.centerX(), card.centerY())
        val cx = card.centerX() + (rng.nextFloat() - 0.5f) * card.width() * 0.25f

        if (!pin) {
            val tw = min(card.width() * 0.34f, 190f * s)
            val th = tw * 0.26f
            val palette = intArrayOf(
                0xCCE8D3B0.toInt(), 0xCCF2BFC8.toInt(), 0xCCBFD3B8.toInt(), 0xCCCFE0EC.toInt(), 0xCCF6E2A8.toInt()
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette[rng.nextInt(palette.size)]
                setShadowLayer(3f * s, 0f, 1.5f * s, 0x33000000)
            }
            val l = cx - tw / 2f
            val r = cx + tw / 2f
            val top = card.top - th / 2f
            val bottom = card.top + th / 2f
            val teeth = 4
            val notch = 2.5f * s
            val path = Path()
            path.moveTo(l, top)
            path.lineTo(r, top)
            for (k in 1..teeth) path.lineTo(r + if (k % 2 == 1) notch else 0f, top + th * k / teeth)
            path.lineTo(l, bottom)
            for (k in 1..teeth) path.lineTo(l - if (k % 2 == 1) notch else 0f, bottom - th * k / teeth)
            path.close()

            canvas.save()
            canvas.rotate((rng.nextFloat() - 0.5f) * 16f, cx, card.top)
            canvas.drawPath(path, paint)
            canvas.restore()
        } else {
            val rad = max(min(card.width() * 0.045f, 22f * s), 8f * s)
            val py = card.top + rad * 1.6f
            val tones = arrayOf(
                intArrayOf(0xFFFFF2B0.toInt(), 0xFFE0B13A.toInt(), 0xFF9C7418.toInt()),
                intArrayOf(0xFFFFB3A8.toInt(), 0xFFD93A2B.toInt(), 0xFF8E1F14.toInt()),
                intArrayOf(0xFFBDE8E0.toInt(), 0xFF2FA394.toInt(), 0xFF176558.toInt())
            )
            val tone = tones[rng.nextInt(tones.size)]
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
            canvas.drawCircle(cx + rad * 0.35f, py + rad * 0.55f, rad, shadow)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx - rad * 0.35f, py - rad * 0.35f, rad * 1.4f,
                    tone, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, py, rad, body)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
            canvas.drawCircle(cx - rad * 0.32f, py - rad * 0.34f, rad * 0.22f, shine)
        }
        canvas.restore()
    }

    /** Procedural paper texture (speckles, fibres, weave) plus a soft vignette. */
    private fun drawTexture(canvas: Canvas, tex: CollageTexture, w: Float, h: Float, s: Float) {
        if (tex == CollageTexture.NONE) return
        val rng = Random(20240611L)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Density is defined per 1080 x 1080 px of design space, so previews look like exports
        val area = (w / s) * (h / s) / (1080f * 1080f)

        fun dots(count: Int, size: Float, color: Int) {
            val pts = FloatArray(count * 2)
            for (i in 0 until count) {
                pts[i * 2] = rng.nextFloat() * w
                pts[i * 2 + 1] = rng.nextFloat() * h
            }
            p.strokeWidth = size
            p.color = color
            canvas.drawPoints(pts, p)
        }

        fun fibers(count: Int, minLen: Float, maxLen: Float, width: Float, color: Int) {
            p.strokeWidth = width
            p.color = color
            for (i in 0 until count) {
                val x = rng.nextFloat() * w
                val y = rng.nextFloat() * h
                val a = rng.nextFloat() * 6.2832f
                val len = minLen + rng.nextFloat() * (maxLen - minLen)
                canvas.drawLine(x, y, x + cos(a) * len, y + sin(a) * len, p)
            }
        }

        when (tex) {
            CollageTexture.PAPER -> {
                dots((9000 * area).toInt(), 1.3f * s, 0x14604020)
                dots((7000 * area).toInt(), 1.6f * s, 0x2EFFFFFF)
                fibers((260 * area).toInt(), 8f * s, 26f * s, 0.9f * s, 0x16705030)
            }
            CollageTexture.KRAFT -> {
                dots((16000 * area).toInt(), 1.5f * s, 0x2A5A3A18)
                dots((9000 * area).toInt(), 1.8f * s, 0x30FFFFFF)
                fibers((520 * area).toInt(), 10f * s, 34f * s, 1.1f * s, 0x2A5A3A18)
                fibers((260 * area).toInt(), 8f * s, 28f * s, 1f * s, 0x33FFFFFF)
            }
            CollageTexture.LINEN -> {
                p.strokeWidth = 1f * s
                p.color = 0x0C000000
                val step = 3f * s
                var y = 0f
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, p)
                    y += step
                }
                var x = 0f
                while (x < w) {
                    canvas.drawLine(x, 0f, x, h, p)
                    x += step
                }
                dots((6000 * area).toInt(), 1.4f * s, 0x1AFFFFFF)
            }
            CollageTexture.GRAIN -> {
                dots((26000 * area).toInt(), 1.1f * s, 0x12000000)
                dots((26000 * area).toInt(), 1.1f * s, 0x14FFFFFF)
            }
            CollageTexture.NONE -> Unit
        }

        val vignette = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, 0x22000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, vignette)
    }

    // ---------------------------------------------------------------------
    // Decoding (EXIF aware, sampled to the size actually needed)
    // ---------------------------------------------------------------------

    private class Source(val uri: Uri, val index: Int, val rawW: Int, val rawH: Int, val orientation: Int) {
        val swapped: Boolean
            get() = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE

        /** Aspect ratio after applying EXIF rotation. */
        val aspect: Float
            get() = if (swapped) rawH.toFloat() / rawW else rawW.toFloat() / rawH
    }

    private fun readSource(context: Context, uri: Uri, index: Int): Source? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(uri) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            Source(uri, index, bounds.outWidth, bounds.outHeight, orientation)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $uri, skipping", e)
            null
        }
    }

    private fun decode(context: Context, src: Source, targetW: Int, targetH: Int): Bitmap? {
        // Target size expressed in the file's raw (unrotated) orientation
        val needW = if (src.swapped) targetH else targetW
        val needH = if (src.swapped) targetW else targetH

        var sample = 1
        while (src.rawW / (sample * 2) >= needW && src.rawH / (sample * 2) >= needH) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(src.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val matrix = orientationMatrix(src.orientation) ?: return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return null
        }
        return m
    }
}