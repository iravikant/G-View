package com.acoder.gallery.presentation.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acoder.gallery.presentation.common.AppSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────────────────── Model ─────────────────────────

enum class SizeUnit(val label: String) { PX("px"), INCH("inch"), CM("cm"), MM("mm") }

enum class OutFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WebP", "image/webp", "webp")
}

/**
 * What the user chose in the Resize tab. [widthPx] / [heightPx] are ALWAYS stored in pixels; the unit + dpi only
 * decide how they are shown and typed. 0 means "same as the current image".
 */
data class ResizeSettings(
    val enabled: Boolean = false,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val unit: SizeUnit = SizeUnit.PX,
    val dpi: Int = 300,
    val lockAspect: Boolean = true,
    val format: OutFormat = OutFormat.JPEG,
    val quality: Int = 85
) {
    fun target(curW: Int, curH: Int): Pair<Int, Int> =
        if (widthPx > 0 && heightPx > 0) widthPx to heightPx else curW to curH
}

data class SavedImage(val uri: Uri, val bytes: Long, val width: Int, val height: Int)

private const val MAX_SIDE = 8192
private const val MAX_PIXELS = 40_000_000L

// ───────────────────────── Maths ─────────────────────────

fun unitToPx(value: Float, unit: SizeUnit, dpi: Int): Float = when (unit) {
    SizeUnit.PX -> value
    SizeUnit.INCH -> value * dpi
    SizeUnit.CM -> value / 2.54f * dpi
    SizeUnit.MM -> value / 25.4f * dpi
}

fun pxToUnit(px: Float, unit: SizeUnit, dpi: Int): Float = when (unit) {
    SizeUnit.PX -> px
    SizeUnit.INCH -> px / dpi
    SizeUnit.CM -> px / dpi * 2.54f
    SizeUnit.MM -> px / dpi * 25.4f
}

/** Keeps a requested size inside what a phone can safely process. */
private fun clampTarget(w: Int, h: Int): Pair<Int, Int> {
    var cw = w.coerceIn(1, MAX_SIDE)
    var ch = h.coerceIn(1, MAX_SIDE)
    val px = cw.toLong() * ch
    if (px > MAX_PIXELS) {
        val k = Math.sqrt(MAX_PIXELS.toDouble() / px).toFloat()
        cw = (cw * k).roundToInt().coerceAtLeast(1)
        ch = (ch * k).roundToInt().coerceAtLeast(1)
    }
    return cw to ch
}

/** Size (in pixels) the photo will have after rotate, straighten and crop, before any resize. */
fun editedSize(srcW: Int, srcH: Int, rotation: Int, straightenDeg: Float, crop: RectF?): Pair<Int, Int> {
    var w = srcW.toFloat()
    var h = srcH.toFloat()
    if (((rotation % 360) + 360) % 360 / 90 % 2 != 0) { val t = w; w = h; h = t }
    if (abs(straightenDeg) >= 0.05f) {
        val t = Math.toRadians(abs(straightenDeg).toDouble())
        val s = (1.0 / (cos(t) + max(w / h, h / w) * sin(t))).toFloat()
        w *= s; h *= s
    }
    if (crop != null) {
        w *= (crop.right - crop.left)
        h *= (crop.bottom - crop.top)
    }
    return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
}

fun fmtBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
}

// ───────────────────────── Resampling / encoding ─────────────────────────

/**
 * Scales [src] to [w] x [h]. Big reductions (e.g. 4K -> 260 px) are done in halving steps, which keeps the result
 * sharp instead of aliased / jagged like a single huge jump would.
 */
fun resampleHighQuality(src: Bitmap, w: Int, h: Int): Bitmap {
    if (src.width == w && src.height == h) return src
    var cur = src
    while (cur.width / 2 >= w && cur.height / 2 >= h) {
        val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        if (cur !== src) cur.recycle()
        cur = next
    }
    val out = Bitmap.createScaledBitmap(cur, w, h, true)
    if (cur !== src && cur !== out) cur.recycle()
    return out
}

@Suppress("DEPRECATION")
private fun compressFormat(f: OutFormat): Bitmap.CompressFormat = when (f) {
    OutFormat.JPEG -> Bitmap.CompressFormat.JPEG
    OutFormat.PNG -> Bitmap.CompressFormat.PNG
    OutFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}

private fun qualityFor(f: OutFormat, q: Int): Int = if (f == OutFormat.PNG) 100 else q.coerceIn(10, 100)

private fun encodedSize(bmp: Bitmap, format: OutFormat, quality: Int): Long {
    val os = ByteArrayOutputStream()
    bmp.compress(compressFormat(format), qualityFor(format, quality), os)
    return os.size().toLong()
}

/**
 * Real file size at the chosen settings. A reduced copy of the edited photo ([sample], at most ~1440 px) is encoded
 * and measured; if the target is bigger than the sample, the measurement is scaled by the pixel count.
 * The size therefore follows the actual picture, the dimensions, the format and the quality.
 */
fun estimateBytes(sample: Bitmap, outW: Int, outH: Int, format: OutFormat, quality: Int): Long {
    val k = min(sample.width.toFloat() / outW, sample.height.toFloat() / outH)
    val w: Int
    val h: Int
    if (k >= 1f) { w = outW; h = outH } else {
        w = (outW * k).roundToInt().coerceAtLeast(1)
        h = (outH * k).roundToInt().coerceAtLeast(1)
    }
    val bmp = resampleHighQuality(sample, w, h)
    val bytes = encodedSize(bmp, format, quality)
    if (bmp !== sample) bmp.recycle()
    val pixelRatio = (outW.toLong() * outH).toDouble() / (w.toLong() * h)
    return (bytes * pixelRatio).toLong().coerceAtLeast(1L)
}

/** Size in bytes of the file behind [uri], or -1 if unknown. */
fun queryFileSize(context: Context, uri: Uri): Long {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) return c.getLong(i)
            }
        }
    }
    return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
        .getOrDefault(-1L)
}

/** Saves [bmp] to Pictures in the chosen format / quality and returns where it went and how big the file is. */
fun saveBitmapAs(context: Context, bmp: Bitmap, format: OutFormat, quality: Int): SavedImage? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${System.currentTimeMillis()}.${format.ext}")
        put(MediaStore.Images.Media.MIME_TYPE, format.mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        // JPEG has no transparency: put the picture on white so transparent areas don't turn black.
        val toWrite = if (format == OutFormat.JPEG && bmp.hasAlpha()) {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(android.graphics.Color.WHITE)
                c.drawBitmap(bmp, 0f, 0f, null)
            }
        } else bmp
        val ok = resolver.openOutputStream(uri)?.use { toWrite.compress(compressFormat(format), qualityFor(format, quality), it) } ?: false
        if (toWrite !== bmp) toWrite.recycle()
        if (!ok) throw java.io.IOException("compress failed")
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        SavedImage(uri, queryFileSize(context, uri), bmp.width, bmp.height)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)   // never leave a half-written item in the gallery
        null
    }
}

// ───────────────────────── UI ─────────────────────────

private val PINK = Color(0xFFFFB1C1)
private val DARK = Color(0xFF2A2224)
private val DIM = Color(0xFF9A9099)

@Composable
private fun SizeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (selected) PINK else DARK).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

private fun fmtNum(v: Float, unit: SizeUnit): String =
    if (unit == SizeUnit.PX) v.roundToInt().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

@Composable
private fun NumberField(label: String, value: Float, unit: SizeUnit, modifier: Modifier, onValue: (Float) -> Unit) {
    var text by remember { mutableStateOf(fmtNum(value, unit)) }
    var focused by remember { mutableStateOf(false) }
    // Follow outside changes (presets, aspect lock, unit switch) but never fight the user while they type.
    LaunchedEffect(value, unit) {
        val typed = text.toFloatOrNull()
        val tol = if (unit == SizeUnit.PX) 0.5f else 0.02f
        if (!focused || typed == null || abs(typed - value) > tol) text = fmtNum(value, unit)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { n ->
            val clean = n.filter { it.isDigit() || (unit != SizeUnit.PX && it == '.') }
            text = clean
            clean.toFloatOrNull()?.let { if (it > 0f) onValue(it) }
        },
        label = { Text(label) },
        suffix = { Text(unit.label, color = DIM) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (unit == SizeUnit.PX) KeyboardType.Number else KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = PINK,
            unfocusedBorderColor = Color(0x55FFFFFF),
            focusedLabelColor = PINK,
            unfocusedLabelColor = DIM,
            cursorColor = PINK
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    )
}

/**
 * The Resize tab. [currentW] x [currentH] is the size of the edited photo (see [editedSize]); [sample] is the
 * preview bitmap used to measure the file size; [originalBytes] is the size of the file that was opened.
 */
@Composable
fun ResizePanel(
    settings: ResizeSettings,
    onChange: (ResizeSettings) -> Unit,
    currentW: Int,
    currentH: Int,
    originalBytes: Long,
    sample: Bitmap
) {
    val (outW, outH) = settings.target(currentW, currentH)

    val estimate by produceState<Long?>(null, outW, outH, settings.format, settings.quality, sample, settings.enabled) {
        value = null
        if (settings.enabled) {
            delay(250)   // wait until the user stops typing / dragging
            value = withContext(Dispatchers.Default) {
                runCatching { estimateBytes(sample, outW, outH, settings.format, settings.quality) }.getOrNull()
            }
        }
    }

    fun setSize(w: Int, h: Int) {
        val (cw, ch) = clampTarget(w, h)
        onChange(settings.copy(enabled = true, widthPx = cw, heightPx = ch))
    }

    fun scaled(f: Float) = (currentW * f).roundToInt().coerceAtLeast(1) to (currentH * f).roundToInt().coerceAtLeast(1)
    fun longSide(px: Int) = scaled(px.toFloat() / max(currentW, currentH))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // header + master switch
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resize & compress", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Current $currentW × $currentH px · ${fmtBytes(originalBytes)}", color = DIM, fontSize = 12.sp)
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onChange(settings.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = PINK)
            )
        }

        if (!settings.enabled) {
            Text("Turn this on to change the size, format and quality of the saved photo.", color = DIM, fontSize = 13.sp)
            return@Column
        }

        // unit
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SizeUnit.entries.forEach { u -> SizeChip(u.label, settings.unit == u) { onChange(settings.copy(unit = u)) } }
        }

        // width  [link]  height
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Width", pxToUnit(outW.toFloat(), settings.unit, settings.dpi), settings.unit, Modifier.weight(1f)) { v ->
                val px = unitToPx(v, settings.unit, settings.dpi).roundToInt().coerceAtLeast(1)
                if (settings.lockAspect) setSize(px, (px.toFloat() * currentH / currentW).roundToInt().coerceAtLeast(1))
                else setSize(px, outH)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (settings.lockAspect) PINK else DARK)
                    .clickable { onChange(settings.copy(lockAspect = !settings.lockAspect)) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (settings.lockAspect) Icons.Default.Link else Icons.Default.LinkOff,
                    "Keep aspect ratio",
                    tint = if (settings.lockAspect) Color.Black else Color.White
                )
            }
            NumberField("Height", pxToUnit(outH.toFloat(), settings.unit, settings.dpi), settings.unit, Modifier.weight(1f)) { v ->
                val px = unitToPx(v, settings.unit, settings.dpi).roundToInt().coerceAtLeast(1)
                if (settings.lockAspect) setSize((px.toFloat() * currentW / currentH).roundToInt().coerceAtLeast(1), px)
                else setSize(outW, px)
            }
        }

        // dpi (only matters for inch / cm / mm)
        if (settings.unit != SizeUnit.PX) {
            Text("Print resolution (DPI)", color = DIM, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(72, 96, 150, 300, 600).forEach { d -> SizeChip("$d", settings.dpi == d) { onChange(settings.copy(dpi = d)) } }
            }
        }
        Text("Output: $outW × $outH px", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (outW > currentW || outH > currentH) {
            Text("This is larger than the current photo. It will be enlarged, but it won't get sharper.", color = PINK, fontSize = 12.sp)
        }

        // presets
        Text("Quick sizes (numbers are the longest side in px)", color = DIM, fontSize = 12.sp)
        val presets = listOf(
            "Original" to (currentW to currentH),
            "75%" to scaled(0.75f),
            "50%" to scaled(0.5f),
            "25%" to scaled(0.25f),
            "4K · 3840" to longSide(3840),
            "Full HD · 1920" to longSide(1920),
            "HD · 1280" to longSide(1280),
            "1080" to longSide(1080),
            "720" to longSide(720),
            "480" to longSide(480)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, dims) ->
                SizeChip(name, dims.first == outW && dims.second == outH) { setSize(dims.first, dims.second) }
            }
        }

        // format + quality
        Text("Format", color = DIM, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutFormat.entries.forEach { f -> SizeChip(f.label, settings.format == f) { onChange(settings.copy(format = f)) } }
        }
        if (settings.format != OutFormat.PNG) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Quality", color = Color.White, fontSize = 14.sp)
                Text("${settings.quality}%", color = DIM, fontSize = 14.sp)
            }
            AppSlider(
                value = settings.quality.toFloat(),
                onValueChange = { onChange(settings.copy(quality = it.roundToInt().coerceIn(10, 100))) },
                valueRange = 10f..100f,
                format = { "${it.roundToInt()}%" },
                snaps = listOf(60f, 85f, 100f)
            )
        } else {
            Text("PNG is lossless, so its size only depends on the dimensions.", color = DIM, fontSize = 12.sp)
        }

        // file size card
        val est = estimate
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DARK).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Original", color = DIM, fontSize = 12.sp)
                    Text(fmtBytes(originalBytes), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("$currentW × $currentH", color = DIM, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = DIM)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("After (estimated)", color = DIM, fontSize = 12.sp)
                    Text(if (est != null) "≈ ${fmtBytes(est)}" else "Calculating…", color = PINK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("$outW × $outH", color = DIM, fontSize = 12.sp)
                }
            }
            if (est != null && originalBytes > 0) {
                val change = ((est.toDouble() / originalBytes - 1.0) * 100).roundToInt()
                Text(
                    if (change < 0) "About ${-change}% smaller" else if (change > 0) "About $change% larger" else "About the same size",
                    color = Color.White, fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}