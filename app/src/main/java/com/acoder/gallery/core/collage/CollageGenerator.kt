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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =========================================================================
// Model
// =========================================================================

/** How the photos are arranged. */
enum class CollageLayout(val label: String) {
    /** Equal square tiles. An incomplete last row is centered. */
    GRID("Grid"),

    /** One large featured photo on top, the rest in a grid below. */
    HERO("Hero"),

    /** Justified rows that respect each photo's aspect ratio. */
    MOSAIC("Mosaic"),

    /** Pinterest-style columns with balanced heights. */
    MASONRY("Masonry"),

    /** Modern bento-box blocks with tiles of different sizes. */
    BENTO("Bento"),

    /** One tall feature photo on the left, the rest stacked on the right. */
    MAGAZINE("Magazine"),

    /** Photos stacked vertically, like a story strip. */
    STRIP("Strip"),

    /** Round photos on a ring, with a larger one in the middle when there are enough. */
    CIRCLE("Circle"),

    /** A grid of round photos. */
    DOTS("Dots"),

    /** Hexagon photos packed like a honeycomb, growing outward from the middle. */
    HEXAGON("Hexagon"),

    /** Photos stacked in a pyramid: a narrow top, a wide bottom. */
    TRIANGLE("Triangle")
}

/** Outline a photo is cropped to. */
enum class TileShape { RECT, CIRCLE, HEXAGON }

/** One tile of a layout thumbnail: [rect] is [x, y, w, h] in a unit square. */
class ThumbTile(val rect: FloatArray, val shape: TileShape)

/** How each photo is presented. The background is chosen separately. */
enum class CollageFrame(val label: String) {
    CLEAN("Clean"),
    DARK("Dark"),
    FLOATING("Floating"),
    POLAROID("Polaroid"),
    MATTED("Matted"),
    GLASS("Glass"),
    FILM("Film"),
    EDITORIAL("Editorial")
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
    /** Used by GRID and MASONRY. */
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
    /** Tile rotation in degrees (Polaroid). */
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
    fun layoutsFor(n: Int): List<CollageLayout> {
        if (n <= 1) return listOf(CollageLayout.GRID)
        val out = ArrayList<CollageLayout>()
        if (n == 2) {
            out += listOf(
                CollageLayout.MOSAIC, CollageLayout.GRID, CollageLayout.MAGAZINE,
                CollageLayout.HERO, CollageLayout.STRIP
            )
        } else {
            out += listOf(
                CollageLayout.GRID, CollageLayout.HERO, CollageLayout.MOSAIC,
                CollageLayout.MASONRY, CollageLayout.BENTO
            )
            // Magazine and Strip get far too tall with many photos
            if (n <= 6) out += listOf(CollageLayout.MAGAZINE, CollageLayout.STRIP)
        }
        if (n <= 12) out += CollageLayout.CIRCLE
        out += CollageLayout.DOTS
        if (n <= 19) out += CollageLayout.HEXAGON
        if (n in 3..15) out += CollageLayout.TRIANGLE
        return out
    }

    /** The layout that suits [n] photos best. */
    fun defaultLayout(n: Int): CollageLayout = when {
        n <= 1 -> CollageLayout.GRID
        n == 2 -> CollageLayout.MOSAIC
        n == 4 -> CollageLayout.GRID
        n <= 8 -> CollageLayout.BENTO
        else -> CollageLayout.MOSAIC
    }

    /** Sensible column count for Grid / Masonry with [n] photos. */
    fun defaultColumns(n: Int): Int = when {
        n <= 4 -> 2
        n <= 9 -> 3
        else -> 4
    }

    /**
     * Schematic tiles of [layout] for [n] photos, fitted into a unit square.
     * Used for the layout thumbnails so they show the real arrangement.
     */
    fun thumbRects(layout: CollageLayout, n: Int, columns: Int): List<ThumbTile> {
        val count = n.coerceIn(1, 9)
        val fake = List(count) { Source(Uri.EMPTY, it, 1000, 1000, ExifInterface.ORIENTATION_NORMAL) }
        val res = buildLayout(layout, columns.coerceAtMost(count), fake, 1f, 0.06f)
        val k = 1f / max(1f, res.contentHeight)
        val ox = (1f - k) / 2f
        val oy = (1f - res.contentHeight * k) / 2f
        return res.slots.mapIndexed { i, r ->
            ThumbTile(
                floatArrayOf(r.left * k + ox, r.top * k + oy, r.width() * k, r.height() * k),
                res.shapes?.getOrNull(i) ?: TileShape.RECT
            )
        }
    }

    fun defaultBackground(frame: CollageFrame): CollageBackground = when (frame) {
        CollageFrame.CLEAN, CollageFrame.EDITORIAL -> CollageBackground(listOf(Color.WHITE))
        CollageFrame.DARK -> CollageBackground(listOf(0xFF111214.toInt()))
        CollageFrame.FLOATING -> CollageBackground(listOf(0xFFF5F6F8.toInt(), 0xFFE3E6EB.toInt()), 90f)
        CollageFrame.POLAROID -> CollageBackground(listOf(0xFFF1EBE0.toInt(), 0xFFDDD2BF.toInt()), 45f)
        CollageFrame.MATTED -> CollageBackground(
            listOf(0xFF1E2A5E.toInt(), 0xFF6A3D9A.toInt(), 0xFFE5566D.toInt()), 45f
        )
        CollageFrame.GLASS -> CollageBackground(
            listOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF3AAFA9.toInt()), 135f
        )
        CollageFrame.FILM -> CollageBackground(listOf(0xFFEDEAE4.toInt()))
    }

    fun defaultSpacing(frame: CollageFrame): Int = when (frame) {
        CollageFrame.CLEAN, CollageFrame.DARK -> 12
        CollageFrame.FLOATING, CollageFrame.MATTED -> 20
        CollageFrame.POLAROID -> 32
        CollageFrame.GLASS -> 16
        CollageFrame.FILM -> 18
        CollageFrame.EDITORIAL -> 14
    }

    fun defaultCorner(frame: CollageFrame): Int = when (frame) {
        CollageFrame.CLEAN, CollageFrame.DARK, CollageFrame.FLOATING, CollageFrame.MATTED -> 24
        CollageFrame.GLASS -> 28
        CollageFrame.POLAROID, CollageFrame.FILM -> 6
        CollageFrame.EDITORIAL -> 0
    }

    /** These frames ignore the corner setting. */
    fun hasFixedCorner(frame: CollageFrame): Boolean =
        frame == CollageFrame.POLAROID || frame == CollageFrame.FILM || frame == CollageFrame.EDITORIAL

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
            color = t.color ?: autoColor
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
        // Clip to the rounded tile, then draw the whole bitmap through an exact transform.
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
        val spec = specFor(config, s)

        val all = uris.mapIndexed { i, u -> readSource(context, u, i) }
        val requested = config.order
            ?.takeIf { o -> o.size == uris.size && o.toSet().size == o.size && o.all { it in uris.indices } }
            ?: uris.indices.toList()
        val sources = requested.mapNotNull { all[it] }
        if (sources.isEmpty()) throw IllegalStateException("None of the selected photos could be read")

        val width = config.width.toFloat()
        val contentW = width - spec.padding * 2f
        val layout = buildLayout(config.layout, config.columns, sources, contentW, spec.gap)

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

            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var drawn = 0

            layout.slots.forEachIndexed { i, slot ->
                if (!shouldContinue()) throw CancellationException("Collage render cancelled")

                val src = sources[i]
                val card = RectF(slot).apply { offset(spec.padding, spec.padding) }
                val shape = layout.shapes?.getOrNull(i) ?: TileShape.RECT
                val photo = photoRect(config.frame, card, s, shape)

                // Tile geometry for the editor
                val photoAspect = photo.width() / photo.height()
                val wider = src.aspect > photoAspect
                val imgW = if (wider) photo.height() * src.aspect else photo.width()
                val imgH = if (wider) photo.height() else photo.width() / src.aspect
                val angle = if (config.frame == CollageFrame.POLAROID) POLAROID_ANGLES[i % POLAROID_ANGLES.size] else 0f
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
                drawTile(canvas, config.frame, spec, card, photo, bmp, angle, s, ink, photoPaint, shape, xf)
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
        xf: PhotoTransform = PhotoTransform()
    ) {
        canvas.save()
        if (angle != 0f) canvas.rotate(angle, card.centerX(), card.centerY())

        // Paper, mat, glass or film base behind the photo
        if (spec.paperColor != null) {
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.paperColor
                if (spec.shadow) setShadowLayer(spec.shadowRadius, 0f, spec.shadowRadius * 0.4f, spec.shadowColor)
            }
            canvas.drawPath(shapePath(shape, card, spec.paperCorner), paper)
        }

        if (frame == CollageFrame.FILM && shape == TileShape.RECT) drawFilmHoles(canvas, card, photo, s)

        drawCover(canvas, bmp, photo, spec.photoCorner, photoPaint, xf.panX, xf.panY, xf.zoom, shape)

        when (frame) {
            CollageFrame.DARK -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * s
                    color = (ink and 0x00FFFFFF) or (0x26 shl 24)
                }
                val r = RectF(photo).apply { inset(s, s) }
                canvas.drawPath(shapePath(shape, r, spec.photoCorner), stroke)
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

    private fun drawFilmHoles(canvas: Canvas, card: RectF, photo: RectF, s: Float) {
        val margin = photo.left - card.left
        val hole = margin * 0.46f
        if (hole < 2f) return
        val pitch = hole * 2.1f
        val count = ((card.height() - hole) / pitch).toInt().coerceAtLeast(1)
        val span = (count - 1) * pitch + hole
        val startY = card.top + (card.height() - span) / 2f
        val leftX = card.left + (margin - hole) / 2f
        val rightX = card.right - margin + (margin - hole) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E5DC.toInt() }
        val r = 2f * s
        for (k in 0 until count) {
            val y = startY + k * pitch
            canvas.drawRoundRect(RectF(leftX, y, leftX + hole, y + hole * 0.78f), r, r, p)
            canvas.drawRoundRect(RectF(rightX, y, rightX + hole, y + hole * 0.78f), r, r, p)
        }
    }

    // ---------------------------------------------------------------------
    // Style specification
    // ---------------------------------------------------------------------

    private val POLAROID_ANGLES = floatArrayOf(-2.4f, 1.6f, -1.1f, 2.3f, -1.8f, 1.2f)

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

    private fun specFor(cfg: CollageConfig, s: Float): Spec {
        val frame = cfg.frame
        val gap = (cfg.spacing ?: defaultSpacing(frame)) * s
        val corner = (if (hasFixedCorner(frame)) defaultCorner(frame) else (cfg.corner ?: defaultCorner(frame))) * s
        val pad = cfg.padding?.let { it * s }

        return when (frame) {
            CollageFrame.CLEAN, CollageFrame.DARK -> Spec(
                padding = pad ?: max(gap * 2f, 24f * s),
                gap = gap,
                photoCorner = corner,
                paperCorner = 0f,
                paperColor = null,
                shadow = false,
                shadowRadius = 0f,
                shadowColor = 0
            )

            CollageFrame.FLOATING -> Spec(
                padding = pad ?: 32f * s,
                gap = gap,
                photoCorner = corner,
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 18f * s,
                shadowColor = 0x40000000
            )

            CollageFrame.POLAROID -> Spec(
                padding = pad ?: 48f * s,
                gap = max(gap, 16f * s),
                photoCorner = 2f * s,
                paperCorner = corner,
                paperColor = 0xFFFDFCF8.toInt(),
                shadow = true,
                shadowRadius = 14f * s,
                shadowColor = 0x50000000
            )

            CollageFrame.MATTED -> Spec(
                padding = pad ?: 36f * s,
                gap = gap,
                photoCorner = max(corner - 6f * s, 0f),
                paperCorner = corner,
                paperColor = Color.WHITE,
                shadow = true,
                shadowRadius = 16f * s,
                shadowColor = 0x55000000
            )

            CollageFrame.GLASS -> Spec(
                padding = pad ?: 36f * s,
                gap = gap,
                photoCorner = max(corner - 8f * s, 0f),
                paperCorner = corner,
                paperColor = 0x33FFFFFF,
                shadow = false,
                shadowRadius = 0f,
                shadowColor = 0
            )

            CollageFrame.FILM -> Spec(
                padding = pad ?: 32f * s,
                gap = gap,
                photoCorner = 2f * s,
                paperCorner = corner,
                paperColor = 0xFF111111.toInt(),
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
        }
    }

    /** Where the photo sits inside its card (mat, polaroid border, film margins). */
    private fun photoRect(frame: CollageFrame, card: RectF, s: Float, shape: TileShape): RectF {
        if (shape == TileShape.RECT) return rectPhotoRect(frame, card, s)
        // Round / hexagon tiles: a slim mat for every frame that has a border
        val margin = when (frame) {
            CollageFrame.MATTED -> 6f * s
            CollageFrame.GLASS, CollageFrame.POLAROID, CollageFrame.FILM -> 8f * s
            else -> 0f
        }
        return RectF(card).apply { inset(margin, margin) }
    }

    private fun rectPhotoRect(frame: CollageFrame, card: RectF, s: Float): RectF = when (frame) {
        CollageFrame.POLAROID -> {
            val b = min(card.width(), card.height()) * 0.06f
            RectF(card.left + b, card.top + b, card.right - b, card.bottom - b * 3.4f)
        }
        CollageFrame.MATTED -> RectF(card).apply { inset(6f * s, 6f * s) }
        CollageFrame.GLASS -> RectF(card).apply { inset(8f * s, 8f * s) }
        CollageFrame.FILM -> {
            val side = min(card.width(), card.height()) * 0.11f
            val vert = side * 0.45f
            RectF(card.left + side, card.top + vert, card.right - side, card.bottom - vert)
        }
        else -> RectF(card)
    }

    // ---------------------------------------------------------------------
    // Layouts (all coordinates relative to the content area)
    // ---------------------------------------------------------------------

    private class LayoutResult(
        val slots: List<RectF>,
        val contentHeight: Float,
        /** null = every tile is a rounded rectangle. */
        val shapes: List<TileShape>? = null
    )

    private fun buildLayout(
        requested: CollageLayout,
        columns: Int,
        sources: List<Source>,
        contentW: Float,
        gap: Float
    ): LayoutResult {
        val n = sources.size
        val layout = if (requested in layoutsFor(n)) requested else defaultLayout(n)

        if (n == 1) {
            val h = contentW / sources[0].aspect.coerceIn(0.75f, 1.5f)
            return LayoutResult(listOf(RectF(0f, 0f, contentW, h)), h)
        }

        return when (layout) {
            CollageLayout.GRID -> {
                val (slots, h) = gridSlots(n, columns, contentW, gap, 0f, 1f)
                LayoutResult(slots, h)
            }

            CollageLayout.HERO -> {
                val heroH = contentW * 0.68f
                val m = n - 1
                val cols = when {
                    m <= 3 -> m
                    m == 4 -> 2
                    else -> 3
                }
                val ratio = if (cols <= 2) 0.72f else 1f
                val (rest, h) = gridSlots(m, cols, contentW, gap, heroH + gap, ratio)
                LayoutResult(listOf(RectF(0f, 0f, contentW, heroH)) + rest, heroH + gap + h)
            }

            CollageLayout.MOSAIC -> mosaicSlots(sources, contentW, gap)
            CollageLayout.MASONRY -> masonrySlots(sources, columns, contentW, gap)
            CollageLayout.BENTO -> bentoSlots(n, contentW, gap)
            CollageLayout.MAGAZINE -> magazineSlots(n, contentW, gap)
            CollageLayout.STRIP -> stripSlots(sources, contentW, gap)
            CollageLayout.CIRCLE -> ringSlots(n, contentW, gap)
            CollageLayout.DOTS -> {
                val (slots, h) = gridSlots(n, columns, contentW, gap, 0f, 1f)
                LayoutResult(slots, h, List(n) { TileShape.CIRCLE })
            }
            CollageLayout.HEXAGON -> honeycombSlots(n, contentW, gap)
            CollageLayout.TRIANGLE -> pyramidSlots(n, contentW, gap)
        }
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

    /** Circles on a ring; with 6+ photos the first one sits large in the middle. */
    private fun ringSlots(n: Int, contentW: Float, gap: Float): LayoutResult {
        if (n == 2) {
            val d = (contentW - gap) / 2f
            return LayoutResult(
                listOf(RectF(0f, 0f, d, d), RectF(d + gap, 0f, contentW, d)),
                d,
                List(2) { TileShape.CIRCLE }
            )
        }
        val hasCenter = n >= 6
        val k = if (hasCenter) n - 1 else n
        val sn = sin(PI / k).toFloat()
        // Neighbouring circles on the ring just touch (plus the gap) and the ring fits the width
        val d = ((contentW * sn - gap) / (1f + sn)).coerceAtLeast(1f)
        val radius = contentW / 2f - d / 2f
        val c = contentW / 2f

        val slots = ArrayList<RectF>(n)
        if (hasCenter) {
            val dc = max(2f * radius - d - gap, d * 0.5f)
            slots += RectF(c - dc / 2f, c - dc / 2f, c + dc / 2f, c + dc / 2f)
        }
        for (i in 0 until k) {
            val a = -PI / 2 + 2 * PI * i / k
            val x = c + radius * cos(a).toFloat()
            val y = c + radius * sin(a).toFloat()
            slots += RectF(x - d / 2f, y - d / 2f, x + d / 2f, y + d / 2f)
        }
        val top = slots.minOf { it.top }
        val bottom = slots.maxOf { it.bottom }
        slots.forEach { it.offset(0f, -top) }
        return LayoutResult(slots, bottom - top, List(n) { TileShape.CIRCLE })
    }

    /** Hexagons packed as a honeycomb, spiralling out from the middle one. */
    private fun honeycombSlots(n: Int, contentW: Float, gap: Float): LayoutResult {
        val sq3 = sqrt(3f)
        val dirs = arrayOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)
        val hexes = ArrayList<Pair<Int, Int>>()
        hexes += 0 to 0
        var ring = 1
        while (hexes.size < n) {
            var q = -ring
            var r = ring
            for (side in 0 until 6) {
                for (step in 0 until ring) {
                    if (hexes.size < n) hexes += q to r
                    q += dirs[side].first
                    r += dirs[side].second
                }
            }
            ring++
        }

        // Pointy-top hexagons of radius 1
        val xs = hexes.map { sq3 * (it.first + it.second / 2f) }
        val ys = hexes.map { 1.5f * it.second }
        val minX = xs.minOf { it } - sq3 / 2f
        val maxX = xs.maxOf { it } + sq3 / 2f
        val minY = ys.minOf { it } - 1f
        val maxY = ys.maxOf { it } + 1f
        val scale = contentW / (maxX - minX)

        val w = (sq3 * scale - gap).coerceAtLeast(1f)
        val h = w * 2f / sq3
        val slots = hexes.indices.map { i ->
            val cx = (xs[i] - minX) * scale
            val cy = (ys[i] - minY) * scale
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }
        return LayoutResult(slots, (maxY - minY) * scale, List(n) { TileShape.HEXAGON })
    }

    /** Rows of 1, 2, 3 ... photos stacked into a pyramid; surplus spots are dropped from the top. */
    private fun pyramidSlots(n: Int, contentW: Float, gap: Float): LayoutResult {
        var m = 1
        while (m * (m + 1) / 2 < n) m++
        val sizes = MutableList(m) { it + 1 }
        var excess = m * (m + 1) / 2 - n
        var i = 0
        while (excess > 0 && i < sizes.size) {
            val take = min(sizes[i], excess)
            sizes[i] -= take
            excess -= take
            i++
        }
        val rows = sizes.filter { it > 0 }

        val cell = (contentW - gap * (m - 1)) / m
        val slots = ArrayList<RectF>(n)
        rows.forEachIndexed { row, count ->
            val rowW = count * cell + gap * (count - 1)
            val y = row * (cell + gap)
            var x = (contentW - rowW) / 2f
            repeat(count) {
                slots += RectF(x, y, x + cell, y + cell)
                x += cell + gap
            }
        }
        return LayoutResult(slots, rows.size * cell + gap * (rows.size - 1))
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

    private fun bentoSlots(n: Int, contentW: Float, gap: Float): LayoutResult {
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
        for (size in sizes) {
            val (rows, cells) = BENTO_BLOCKS.getValue(size)
            for (cell in cells) {
                val x = cell[0] * (unit + gap)
                val top = y + cell[1] * (unit + gap)
                val w = cell[2] * unit + (cell[2] - 1) * gap
                val h = cell[3] * unit + (cell[3] - 1) * gap
                slots += RectF(x, top, x + w, top + h)
            }
            y += rows * (unit + gap)
        }
        return LayoutResult(slots, y - gap)
    }

    private fun magazineSlots(n: Int, contentW: Float, gap: Float): LayoutResult {
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
        return LayoutResult(slots, h)
    }

    private fun stripSlots(sources: List<Source>, contentW: Float, gap: Float): LayoutResult {
        val slots = ArrayList<RectF>(sources.size)
        var y = 0f
        for (src in sources) {
            val h = contentW / src.aspect.coerceIn(0.8f, 1.8f)
            slots += RectF(0f, y, contentW, y + h)
            y += h + gap
        }
        return LayoutResult(slots, y - gap)
    }

    // ---------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------

    /** Outline of [shape] fitted into [r]. [corner] only applies to RECT. */
    private fun shapePath(shape: TileShape, r: RectF, corner: Float): Path {
        val path = Path()
        when (shape) {
            TileShape.RECT -> path.addRoundRect(r, corner, corner, Path.Direction.CW)
            TileShape.CIRCLE ->
                path.addCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) / 2f, Path.Direction.CW)
            TileShape.HEXAGON -> {
                // Pointy-top hexagon inscribed in the rectangle
                val q = r.height() / 4f
                path.moveTo(r.centerX(), r.top)
                path.lineTo(r.right, r.top + q)
                path.lineTo(r.right, r.bottom - q)
                path.lineTo(r.centerX(), r.bottom)
                path.lineTo(r.left, r.bottom - q)
                path.lineTo(r.left, r.top + q)
                path.close()
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
     * Cover-crop the bitmap into [dst] with anti-aliased rounded corners.
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