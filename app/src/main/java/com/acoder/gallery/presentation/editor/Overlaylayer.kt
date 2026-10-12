package com.acoder.gallery.presentation.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * A piece of text on the video. Position/size are fractions of the frame, so the same item looks identical in the
 * live preview and in the exported file, whatever the resolution.
 */
data class TextItem(
    val id: Long,
    val text: String = "",
    /** Centre of the text, 0..1 of frame width / height. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Font size as a fraction of frame width. */
    val size: Float = 0.07f,
    val template: Int = 0,
    val font: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Int = Color.WHITE,
    val strokeColor: Int = Color.BLACK,
    /** Stroke thickness as a fraction of the font size (0 = no stroke). */
    val strokeWidth: Float = 0f,
    /** 0..1 */
    val shadow: Float = 0f,
    val shadowColor: Int = Color.BLACK,
    /** Transparent = no background box. */
    val bgColor: Int = Color.TRANSPARENT
)

object TextFonts {
    val names = listOf("Sans", "Serif", "Mono", "Condensed", "Casual", "Cursive")
    val families = listOf("sans-serif", "serif", "monospace", "sans-serif-condensed", "casual", "cursive")
}

class TextTemplate(val label: String, val apply: (TextItem) -> TextItem)

private fun argb(v: Long): Int = v.toInt()

private fun TextItem.plain() = copy(
    bold = false, italic = false, color = Color.WHITE, strokeWidth = 0f, shadow = 0f, bgColor = Color.TRANSPARENT
)

val TextTemplates = listOf(
    TextTemplate("Plain") { it.plain().copy(template = 0) },
    TextTemplate("Cream") { it.plain().copy(template = 1, color = argb(0xFFFFF1C1)) },
    TextTemplate("Sunny") { it.plain().copy(template = 2, color = argb(0xFFFFEB3B), bold = true) },
    TextTemplate("Neon") { it.plain().copy(template = 3, color = argb(0xFFFF80AB), bold = true, shadow = 1f, shadowColor = argb(0xFFFF4081)) },
    TextTemplate("Glow") { it.plain().copy(template = 4, shadow = 1f, shadowColor = argb(0xFF00E5FF)) },
    TextTemplate("Outline") { it.plain().copy(template = 5, bold = true, strokeColor = Color.BLACK, strokeWidth = 0.14f) },
    TextTemplate("Gold") { it.plain().copy(template = 6, color = argb(0xFFFFD54F), bold = true, shadow = 0.6f) },
    TextTemplate("Pop") { it.plain().copy(template = 7, bold = true, strokeColor = argb(0xFFE91E63), strokeWidth = 0.16f) },
    TextTemplate("Label") { it.plain().copy(template = 8, color = Color.BLACK, bgColor = Color.WHITE) },
    TextTemplate("Dark label") { it.plain().copy(template = 9, bgColor = argb(0xB3000000)) },
    TextTemplate("Italic") { it.plain().copy(template = 10, italic = true, shadow = 0.5f) },
    TextTemplate("Mint") { it.plain().copy(template = 11, color = argb(0xFFB9F6CA), bold = true, shadow = 0.5f, shadowColor = argb(0xFF00C853)) }
)

/** Draws the text + frame-background layer. Used for the preview canvas AND for the exported overlay bitmap. */
object OverlayLayer {

    /** Coloured bars shown around the video when the frame ratio differs from the video. */
    class Bars(val color: Int, val contentAspect: Float)

    fun needsOverlay(texts: List<TextItem>, frameRatio: Float?, frameBg: Int): Boolean =
        texts.any { it.text.isNotBlank() } || (frameRatio != null && frameBg != Color.BLACK)

    private class Laid(
        val lines: List<String>,
        val paint: Paint,
        val fontPx: Float,
        val pad: Float,
        val lineH: Float,
        val ascent: Float,
        val rect: RectF
    )

    private fun typefaceOf(item: TextItem): Typeface {
        val style = when {
            item.bold && item.italic -> Typeface.BOLD_ITALIC
            item.bold -> Typeface.BOLD
            item.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(TextFonts.families[item.font.coerceIn(0, TextFonts.families.lastIndex)], style)
    }

    private fun layout(item: TextItem, text: String, w: Int, h: Int): Laid {
        val fontPx = max(item.size * w, 4f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontPx
            typeface = typefaceOf(item)
            textAlign = Paint.Align.CENTER
        }
        val lines = text.split('\n')
        val widest = lines.maxOf { paint.measureText(it) }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.05f
        val hasBg = Color.alpha(item.bgColor) > 0
        val pad = if (hasBg) fontPx * 0.3f else fontPx * (0.1f + item.strokeWidth)
        val cx = item.x * w
        val cy = item.y * h
        val bw = widest + pad * 2
        val bh = lineH * lines.size + pad * 2
        return Laid(lines, paint, fontPx, pad, lineH, fm.ascent, RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2))
    }

    private fun shown(item: TextItem, placeholderFor: Long?): String? = when {
        item.text.isNotBlank() -> item.text
        item.id == placeholderFor -> "Enter text"
        else -> null
    }

    fun draw(canvas: Canvas, w: Int, h: Int, texts: List<TextItem>, bars: Bars?, placeholderFor: Long? = null) {
        if (bars != null) {
            val frameAspect = w.toFloat() / h
            val cw: Float
            val ch: Float
            if (bars.contentAspect > frameAspect) {
                cw = w.toFloat(); ch = w / bars.contentAspect
            } else {
                ch = h.toFloat(); cw = h * bars.contentAspect
            }
            val left = (w - cw) / 2f
            val top = (h - ch) / 2f
            val p = Paint().apply { color = bars.color; style = Paint.Style.FILL }
            if (left > 0.5f) {
                canvas.drawRect(0f, 0f, left, h.toFloat(), p)
                canvas.drawRect(w - left, 0f, w.toFloat(), h.toFloat(), p)
            }
            if (top > 0.5f) {
                canvas.drawRect(0f, 0f, w.toFloat(), top, p)
                canvas.drawRect(0f, h - top, w.toFloat(), h.toFloat(), p)
            }
        }

        for (item in texts) {
            val text = shown(item, placeholderFor) ?: continue
            val l = layout(item, text, w, h)
            val paint = l.paint

            if (Color.alpha(item.bgColor) > 0) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.bgColor; style = Paint.Style.FILL }
                canvas.drawRoundRect(l.rect, l.fontPx * 0.3f, l.fontPx * 0.3f, bg)
            }

            val cx = l.rect.centerX()
            val hasStroke = item.strokeWidth > 0f
            val shadowR = item.shadow * l.fontPx * 0.25f

            l.lines.forEachIndexed { i, line ->
                val baseline = l.rect.top + l.pad + l.lineH * i - l.ascent
                if (hasStroke) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = item.strokeWidth * l.fontPx
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = item.strokeColor
                    if (shadowR > 0f) paint.setShadowLayer(shadowR, 0f, l.fontPx * 0.04f, item.shadowColor)
                    canvas.drawText(line, cx, baseline, paint)
                    paint.clearShadowLayer()
                }
                paint.style = Paint.Style.FILL
                paint.color = item.color
                if (!hasStroke && shadowR > 0f) paint.setShadowLayer(shadowR, 0f, l.fontPx * 0.04f, item.shadowColor)
                canvas.drawText(line, cx, baseline, paint)
                paint.clearShadowLayer()
            }
        }
    }

    /** Pixel bounds of every visible text (for tapping / the selection frame). */
    fun measure(texts: List<TextItem>, w: Int, h: Int, placeholderFor: Long? = null): Map<Long, RectF> {
        val out = LinkedHashMap<Long, RectF>()
        for (item in texts) {
            val text = shown(item, placeholderFor) ?: continue
            out[item.id] = layout(item, text, w, h).rect
        }
        return out
    }
}