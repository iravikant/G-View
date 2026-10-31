package com.acoder.gallery.presentation.editor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.acoder.gallery.presentation.common.AppSlider
import com.acoder.gallery.presentation.editor.resampleHighQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "MagicEraser"

// Google Photos Inspired Theme Colors
private val GOOGLE_BLUE = Color(0xFFA8C7FA)
private val GOOGLE_ON_BLUE = Color(0xFF041E49)
private val GOOGLE_SURFACE = Color(0xFF131314)
private val GOOGLE_CARD = Color(0xFF1E1F20)
private val GOOGLE_SELECTED = Color(0xFF004A77)
private val MARK_GLOW = Color(0xAAFF3B5C)

// =========================================================================
// Magic Eraser Action Modes & Tools
// =========================================================================

enum class MagicEraserMode(val label: String) {
    ERASE("Erase"),
    CAMOUFLAGE("Camouflage")
}

private enum class EraseTool(val label: String, val hint: String) {
    BRUSH("Brush", "Brush over what you want to change"),
    LASSO("Circle", "Circle around an item to select it"),
    OBJECT("Tap", "Tap an object to select it"),
    WAND("Wand", "Tap a color area to select it")
}

private sealed class Mark

/** Painted line; [points] are 0..1 positions on the photo and [brush] is the width as a fraction of the short side. */
private class StrokeMark(val points: List<Offset>, val brush: Float) : Mark()

/** A freehand loop; everything inside it is selected. */
private class LassoMark(val points: List<Offset>) : Mark()

/** A selected area: [mask] is 0..255 at [mw] x [mh], covering the whole photo. */
private class RegionMark(val mask: ByteArray, val mw: Int, val mh: Int, val preview: ImageBitmap) : Mark()

// =========================================================================
// Main Screen - Google Photos Style Magic Eraser
// =========================================================================

/**
 * Google Photos Style Magic Eraser.
 *
 * Automatically scans for background suggestions/distractions when opened.
 * Supports Erase (AI inpainting) and Camouflage (Color desaturation and context blending),
 * brush selection, circle/lasso selection, tap-to-select objects, zoom/pan, and hold-to-compare.
 */
@Composable
fun MagicEraser(
    source: Bitmap,
    classicFallback: (Bitmap, List<List<Offset>>, Float) -> Bitmap,
    onCancel: () -> Unit,
    onApply: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var work by remember(source) { mutableStateOf(source) }
    val undo = remember(source) { mutableStateListOf<Bitmap>() }
    val marks = remember(source) { mutableStateListOf<Mark>() }
    val suggestions = remember(source) { mutableStateListOf<RegionMark>() }
    val live = remember(source) { mutableStateListOf<Offset>() }

    var mode by remember { mutableStateOf(MagicEraserMode.ERASE) }
    var tool by remember { mutableStateOf(EraseTool.BRUSH) }
    var brush by remember { mutableFloatStateOf(0.045f) }
    var tolerance by remember { mutableFloatStateOf(0.25f) }

    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(-1f) }
    var isScanningSuggestions by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var engine by remember { mutableStateOf("") }
    var touchPosition by remember { mutableStateOf<Offset?>(null) }

    val subjectCache = remember(work) { arrayOfNulls<FloatArray>(1) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // ---------------- Auto Suggestion Detection (Google Photos Signature) ----------------
    LaunchedEffect(work) {
        if (work === source) {
            isScanningSuggestions = true
            withContext(Dispatchers.Default) {
                try {
                    val s = Neural.scaleTo(work, 512)
                    val w = s.width
                    val h = s.height
                    val conf = subjectCache[0] ?: AiPhotoTools.subjectConfidence(s).also { subjectCache[0] = it }

                    // Find background distraction candidates (moderate confidence clusters)
                    val mask = BooleanArray(w * h) { conf[it] in 0.25f..0.85f }
                    val reg = regionFromMask(mask, w, h)
                    if (reg != null) {
                        withContext(Dispatchers.Main) {
                            suggestions.clear()
                            suggestions.add(reg)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Suggestion scan complete without candidates", e)
                } finally {
                    isScanningSuggestions = false
                }
            }
        }
    }

    // ---------------- Primary Execute Action (Erase or Camouflage) ----------------
    fun applyChanges() {
        if (marks.isEmpty() && suggestions.isEmpty() || busy) return
        val todo = if (marks.isNotEmpty()) marks.toList() else suggestions.toList()
        val img = work
        val currentMode = mode
        busy = true
        busyText = if (currentMode == MagicEraserMode.ERASE) "Erasing" else "Camouflaging"
        progress = 0f

        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val mask = buildMask(img.width, img.height, todo)
                    if (currentMode == MagicEraserMode.ERASE) {
                        inpaint(context, img, mask, todo, classicFallback) { progress = it }
                    } else {
                        applyCamouflage(img, mask) to "Camouflage engine"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Operation failed", e)
                null
            }

            busy = false
            if (result == null) {
                toast("Could not process selected area. Try a smaller selection.")
                return@launch
            }

            undo.add(img)
            if (undo.size > 10) undo.removeAt(0)
            work = result.first
            engine = result.second
            marks.clear()
            suggestions.clear()
        }
    }

    fun eraseAllSuggestions() {
        if (suggestions.isEmpty() || busy) return
        marks.clear()
        marks.addAll(suggestions)
        suggestions.clear()
        applyChanges()
    }

    /** Object / Wand selection */
    fun selectAt(p: Offset, selectTool: EraseTool) {
        if (busy) return
        val img = work
        val tol = tolerance
        busy = true
        busyText = if (selectTool == EraseTool.OBJECT) "Finding object" else "Selecting area"
        progress = -1f

        scope.launch {
            val region = try {
                withContext(Dispatchers.Default) {
                    if (selectTool == EraseTool.OBJECT) objectAt(img, p, subjectCache) else wandAt(img, p, tol)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Selection failed", e)
                null
            }

            busy = false
            if (region == null) {
                toast(
                    if (selectTool == EraseTool.OBJECT) "No distinct object found there. Try Brush or Lasso."
                    else "Adjust tolerance and tap again."
                )
            } else {
                marks.add(region)
            }
        }
    }

    fun undoStep() {
        if (busy) return
        if (marks.isNotEmpty()) {
            marks.removeAt(marks.lastIndex)
        } else if (undo.isNotEmpty()) {
            work = undo.removeAt(undo.lastIndex)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GOOGLE_SURFACE)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---------------- Google Photos Top Header ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!busy) onCancel() }) {
                Icon(Icons.Default.Close, "Cancel", tint = Color.White)
            }

            Row(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = GOOGLE_BLUE, modifier = Modifier.size(20.dp))
                Text(
                    "Magic Eraser",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val canUndo = (marks.isNotEmpty() || undo.isNotEmpty()) && !busy
            IconButton(onClick = { undoStep() }, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }

            Button(
                onClick = { if (work !== source) onApply(work) else onCancel() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = GOOGLE_BLUE, contentColor = GOOGLE_ON_BLUE),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Save copy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // ---------------- Main Image Viewport & Touch Surface ----------------
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val cw = constraints.maxWidth.toFloat()
            val ch = constraints.maxHeight.toFloat()
            val fit = min(cw / work.width, ch / work.height)
            val wPx = work.width * fit
            val hPx = work.height * fit
            val density = LocalDensity.current
            val image = remember(work, showOriginal) { (if (showOriginal) source else work).asImageBitmap() }

            fun clampPan(p: Offset, z: Float): Offset {
                val mx = max(0f, (wPx * z - cw) / 2f)
                val my = max(0f, (hPx * z - ch) / 2f)
                return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
            }

            Box(
                Modifier
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
            ) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

                Canvas(Modifier.fillMaxSize()) {
                    val short = min(size.width, size.height)

                    fun line(points: List<Offset>): Path = Path().apply {
                        moveTo(points[0].x * size.width, points[0].y * size.height)
                        if (points.size == 1) lineTo(points[0].x * size.width + 0.1f, points[0].y * size.height)
                        for (i in 1 until points.size) lineTo(points[i].x * size.width, points[i].y * size.height)
                    }

                    // Render Suggestions Overlay
                    for (s in suggestions) {
                        drawImage(
                            s.preview,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium
                        )
                    }

                    // Render Selected Marks Overlay
                    for (m in marks) {
                        when (m) {
                            is StrokeMark -> drawPath(
                                line(m.points),
                                MARK_GLOW,
                                style = Stroke(m.brush * short, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            is LassoMark -> {
                                drawPath(line(m.points).apply { close() }, MARK_GLOW)
                                drawPath(
                                    line(m.points),
                                    Color.White,
                                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                            is RegionMark -> drawImage(
                                m.preview,
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.Medium
                            )
                        }
                    }

                    // Live drawing stroke
                    val l = live.toList()
                    if (l.isNotEmpty()) {
                        if (tool == EraseTool.BRUSH) {
                            drawPath(
                                line(l),
                                MARK_GLOW,
                                style = Stroke(brush * short, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        } else if (tool == EraseTool.LASSO) {
                            drawPath(line(l).apply { close() }, MARK_GLOW)
                            drawPath(
                                line(l),
                                Color.White,
                                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    // Floating Brush Radius Circle Indicator
                    touchPosition?.let { pos ->
                        if (tool == EraseTool.BRUSH && !busy) {
                            val radiusPx = (brush * short) / 2f
                            val px = pos.x * size.width
                            val py = pos.y * size.height
                            drawCircle(Color.White.copy(alpha = 0.5f), radius = radiusPx, center = Offset(px, py))
                            drawCircle(
                                Color.White,
                                radius = radiusPx,
                                center = Offset(px, py),
                                style = Stroke(1.5.dp.toPx())
                            )
                        }
                    }
                }
            }

            // Gesture & Touch Layer
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(wPx, hPx, cw, ch) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (busy) return@awaitEachGesture
                            val currentTool = tool

                            fun toPhoto(p: Offset): Offset {
                                val ox = (cw - wPx) / 2f
                                val oy = (ch - hPx) / 2f
                                val lx = wPx / 2f + (p.x - ox - wPx / 2f - pan.x) / zoom
                                val ly = hPx / 2f + (p.y - oy - hPx / 2f - pan.y) / zoom
                                return Offset((lx / wPx).coerceIn(0f, 1f), (ly / hPx).coerceIn(0f, 1f))
                            }

                            val first = toPhoto(down.position)
                            touchPosition = first
                            var painting = true
                            var moved = 0f
                            live.clear()
                            if (currentTool == EraseTool.BRUSH || currentTool == EraseTool.LASSO) live.add(first)

                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }

                                if (pressed.size >= 2) {
                                    if (painting) {
                                        painting = false
                                        live.clear()
                                        touchPosition = null
                                    }
                                    val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                                    zoom = newZoom
                                    pan = clampPan(pan + event.calculatePan(), newZoom)
                                    event.changes.forEach { it.consume() }
                                } else if (painting && pressed.size == 1) {
                                    val c = pressed[0]
                                    val photoPos = toPhoto(c.position)
                                    touchPosition = photoPos
                                    moved = max(moved, (c.position - down.position).getDistance())
                                    if (currentTool == EraseTool.BRUSH || currentTool == EraseTool.LASSO) {
                                        live.add(photoPos)
                                    }
                                    c.consume()
                                }
                            } while (event.changes.any { it.pressed })

                            if (painting) {
                                when (currentTool) {
                                    EraseTool.BRUSH -> if (live.isNotEmpty()) {
                                        val pts = live.toList()
                                        marks.add(
                                            StrokeMark(
                                                if (pts.size == 1) listOf(pts[0], pts[0] + Offset(0.0005f, 0f)) else pts,
                                                brush
                                            )
                                        )
                                    }
                                    EraseTool.LASSO -> if (live.size >= 3) marks.add(LassoMark(live.toList()))
                                    EraseTool.OBJECT, EraseTool.WAND -> if (moved < 24f) selectAt(first, currentTool)
                                }
                            }
                            live.clear()
                            touchPosition = null
                        }
                    }
            )

            // ---------------- Google Photos Suggestions Banner ----------------
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = suggestions.isNotEmpty() && marks.isEmpty() && !busy,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = GOOGLE_CARD,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.border(1.dp, GOOGLE_BLUE.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = GOOGLE_BLUE, modifier = Modifier.size(18.dp))
                            Text(
                                "Suggestions found",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Button(
                                onClick = { eraseAllSuggestions() },
                                colors = ButtonDefaults.buttonColors(containerColor = GOOGLE_BLUE, contentColor = GOOGLE_ON_BLUE),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                shape = CircleShape,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Erase all", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ---------------- Floating Controls (Zoom Reset & Hold to Compare) ----------------
            if (zoom > 1.02f) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC1E1F20))
                        .clickable { zoom = 1f; pan = Offset.Zero },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FitScreen, "Fit screen", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            if (work !== source) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (showOriginal) GOOGLE_BLUE else Color(0xCC1E1F20))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                showOriginal = true
                                tryAwaitRelease()
                                showOriginal = false
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Compare,
                        "Hold to compare",
                        tint = if (showOriginal) GOOGLE_ON_BLUE else Color.White
                    )
                }
            }

            // Loading / Scanning Indicator
            if (busy || isScanningSuggestions) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = GOOGLE_BLUE)
                        Text(
                            if (isScanningSuggestions) "Searching for suggestions…"
                            else if (progress >= 0f) "$busyText… ${(progress * 100).roundToInt()}%"
                            else "$busyText…",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ---------------- Google Photos Bottom Controls Sheet ----------------
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(GOOGLE_CARD)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Mode Selector Pill (Erase vs Camouflage)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(Color(0xFF2B2D30))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MagicEraserMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selected) GOOGLE_SELECTED else Color.Transparent)
                            .clickable { if (!busy) mode = m }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (m == MagicEraserMode.ERASE) Icons.Default.Brush else Icons.Default.ColorLens,
                                null,
                                tint = if (selected) GOOGLE_BLUE else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                m.label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Tool Switcher (Brush, Circle/Lasso, Tap Object, Wand)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EraseTool.entries.forEach { t ->
                    val selected = t == tool
                    val icon = when (t) {
                        EraseTool.BRUSH -> Icons.Default.Brush
                        EraseTool.LASSO -> Icons.Default.Gesture
                        EraseTool.OBJECT -> Icons.Default.TouchApp
                        EraseTool.WAND -> Icons.Default.AutoAwesome
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (selected) GOOGLE_BLUE else Color(0xFF2B2D30),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { if (!busy) tool = t }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                icon,
                                null,
                                tint = if (selected) GOOGLE_ON_BLUE else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                t.label,
                                color = if (selected) GOOGLE_ON_BLUE else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Tool Slider Controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (tool) {
                        EraseTool.BRUSH -> "Brush size"
                        EraseTool.WAND -> "Color tolerance"
                        else -> tool.hint
                    },
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            when (tool) {
                EraseTool.BRUSH -> AppSlider(brush, { brush = it }, 0.015f..0.12f)
                EraseTool.WAND -> AppSlider(tolerance, { tolerance = it }, 0.05f..0.6f)
                else -> Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Action Buttons Bar
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        marks.clear()
                        suggestions.clear()
                        live.clear()
                    },
                    enabled = (marks.isNotEmpty() || suggestions.isNotEmpty()) && !busy,
                    shape = CircleShape,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Clear", color = Color.White)
                }

                Button(
                    onClick = { applyChanges() },
                    enabled = (marks.isNotEmpty() || suggestions.isNotEmpty()) && !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GOOGLE_BLUE,
                        contentColor = GOOGLE_ON_BLUE,
                        disabledContainerColor = Color(0xFF2B2D30),
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        if (mode == MagicEraserMode.ERASE) "Erase" else "Camouflage",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// =========================================================================
// Inpainting & Camouflage Core Processing
// =========================================================================

private suspend fun inpaint(
    context: Context,
    img: Bitmap,
    mask: ByteArray,
    marks: List<Mark>,
    classic: (Bitmap, List<List<Offset>>, Float) -> Bitmap,
    onProgress: (Float) -> Unit
): Pair<Bitmap, String> {
    val w = img.width
    val h = img.height

    // Build subject mask (men, women, baby, animals/pets) to avoid picking object colors when filling
    val avoidSubject = try {
        val s = Neural.scaleTo(img, 512)
        val conf = AiPhotoTools.subjectConfidence(s)
        val sw = s.width
        val sh = s.height
        val bools = BooleanArray(w * h)
        var anySubject = false
        for (y in 0 until h) {
            val sy = ((y + 0.5f) * sh / h).toInt().coerceIn(0, sh - 1)
            val row = y * w
            val sRow = sy * sw
            for (x in 0 until w) {
                val idx = row + x
                val isErased = (mask[idx].toInt() and 0xFF) > 127
                val sx = ((x + 0.5f) * sw / w).toInt().coerceIn(0, sw - 1)
                if (!isErased && conf[sRow + sx] > 0.25f) {
                    bools[idx] = true
                    anySubject = true
                }
            }
        }
        if (anySubject) grow1(bools, w, h, 6) else null
    } catch (e: Throwable) {
        Log.d(TAG, "Subject mask detection skipped", e)
        null
    }

    if (Lama.available(context)) {
        try {
            return Lama.erase(context, img, mask, avoidSubject) to "AI model"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Model failed, using patch synthesis", e)
        }
    }
    val job = currentCoroutineContext()[Job]
    try {
        val grow = max(3, min(w, h) / 250)
        return PatchInpaint.erase(img, mask, grow, avoidSubject, onProgress) { job?.ensureActive() } to "Patch synthesis"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.e(TAG, "Patch synthesis failed", e)
        val strokes = marks.filterIsInstance<StrokeMark>()
        if (strokes.size != marks.size) throw e
        var cur = img
        strokes.groupBy { it.brush }.forEach { (b, group) -> cur = classic(cur, group.map { it.points }, b) }
        return cur to "Built-in filler"
    }
}

/**
 * Google Photos Camouflage Mode:
 * Desaturates object colors and blends them seamlessly into the surrounding background palette.
 */
private fun applyCamouflage(img: Bitmap, mask: ByteArray): Bitmap {
    val w = img.width
    val h = img.height
    val out = img.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)

    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var count = 0

    // Find average boundary color around masked area
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val idx = row + x
            if ((mask[idx].toInt() and 0xFF) <= 127) {
                var nearMask = false
                for (dy in -3..3) {
                    for (dx in -3..3) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val ny = (y + dy).coerceIn(0, h - 1)
                        if ((mask[ny * w + nx].toInt() and 0xFF) > 127) {
                            nearMask = true
                            break
                        }
                    }
                    if (nearMask) break
                }
                if (nearMask) {
                    val p = pixels[idx]
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
            }
        }
    }

    if (count == 0) return out

    val avgR = (sumR / count).toInt()
    val avgG = (sumG / count).toInt()
    val avgB = (sumB / count).toInt()

    val bgHsl = FloatArray(3)
    ColorUtils.RGBToHSL(avgR, avgG, avgB, bgHsl)

    val pxHsl = FloatArray(3)
    val outHsl = FloatArray(3)

    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val idx = row + x
            val mVal = mask[idx].toInt() and 0xFF
            if (mVal > 0) {
                val factor = (mVal / 255f).coerceIn(0f, 1f)
                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                ColorUtils.RGBToHSL(r, g, b, pxHsl)

                outHsl[0] = bgHsl[0]
                outHsl[1] = pxHsl[1] * 0.25f + bgHsl[1] * 0.15f
                outHsl[2] = pxHsl[2] * 0.7f + bgHsl[2] * 0.3f

                val blendedRgb = ColorUtils.HSLToColor(outHsl)
                val newR = (blendedRgb shr 16) and 0xFF
                val newG = (blendedRgb shr 8) and 0xFF
                val newB = blendedRgb and 0xFF

                val finalR = (r * (1f - factor) + newR * factor).roundToInt().coerceIn(0, 255)
                val finalG = (g * (1f - factor) + newG * factor).roundToInt().coerceIn(0, 255)
                val finalB = (b * (1f - factor) + newB * factor).roundToInt().coerceIn(0, 255)

                pixels[idx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }
    }

    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/** White (255) where anything is selected, at the size of the photo. */
private fun buildMask(w: Int, h: Int, marks: List<Mark>): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val canvas = AndroidCanvas(bmp)
    val stroke = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    val fill = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = AndroidPaint.Style.FILL
    }
    val bitmapPaint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)

    for (m in marks) {
        when (m) {
            is StrokeMark -> {
                stroke.strokeWidth = max(2f, m.brush * min(w, h))
                val path = AndroidPath()
                path.moveTo(m.points[0].x * w, m.points[0].y * h)
                if (m.points.size == 1) path.lineTo(m.points[0].x * w + 0.1f, m.points[0].y * h)
                for (i in 1 until m.points.size) path.lineTo(m.points[i].x * w, m.points[i].y * h)
                canvas.drawPath(path, stroke)
            }
            is LassoMark -> {
                val path = AndroidPath()
                path.moveTo(m.points[0].x * w, m.points[0].y * h)
                for (i in 1 until m.points.size) path.lineTo(m.points[i].x * w, m.points[i].y * h)
                path.close()
                canvas.drawPath(path, fill)
            }
            is RegionMark -> {
                val ib = Bitmap.createBitmap(m.mw, m.mh, Bitmap.Config.ARGB_8888)
                ib.setPixels(IntArray(m.mw * m.mh) { (m.mask[it].toInt() and 255) shl 24 }, 0, m.mw, 0, 0, m.mw, m.mh)
                canvas.drawBitmap(ib, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), bitmapPaint)
                ib.recycle()
            }
        }
    }

    val rowBytes = bmp.rowBytes
    val buf = ByteBuffer.allocate(rowBytes * h)
    bmp.copyPixelsToBuffer(buf)
    bmp.recycle()
    val raw = buf.array()
    if (rowBytes == w) return raw
    val out = ByteArray(w * h)
    for (y in 0 until h) System.arraycopy(raw, y * rowBytes, out, y * w, w)
    return out
}

private fun regionFromMask(mask: BooleanArray, w: Int, h: Int): RegionMark? {
    if (mask.none { it }) return null
    val bytes = ByteArray(w * h) { if (mask[it]) 255.toByte() else 0 }
    val px = IntArray(w * h) { if (mask[it]) 0x77FFB4A2.toInt() else 0 }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    return RegionMark(bytes, w, h, bmp.asImageBitmap())
}

/** Selects everything connected to ([sx], [sy]) that [accept] says yes to. */
private fun flood(w: Int, h: Int, sx: Int, sy: Int, accept: (Int) -> Boolean): BooleanArray? {
    val n = w * h
    val sel = BooleanArray(n)
    val queue = IntArray(n)
    var head = 0
    var tail = 0
    val start = sy * w + sx
    if (!accept(start)) return null
    sel[start] = true
    queue[tail++] = start
    while (head < tail) {
        val p = queue[head++]
        val x = p % w
        val y = p / w
        if (x > 0 && !sel[p - 1] && accept(p - 1)) { sel[p - 1] = true; queue[tail++] = p - 1 }
        if (x < w - 1 && !sel[p + 1] && accept(p + 1)) { sel[p + 1] = true; queue[tail++] = p + 1 }
        if (y > 0 && !sel[p - w] && accept(p - w)) { sel[p - w] = true; queue[tail++] = p - w }
        if (y < h - 1 && !sel[p + w] && accept(p + w)) { sel[p + w] = true; queue[tail++] = p + w }
    }
    return if (tail < 6 || tail > n * 0.6f) null else sel
}

private fun grow1(a: BooleanArray, w: Int, h: Int, times: Int): BooleanArray {
    var cur = a
    repeat(times) {
        val next = cur.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (cur[i]) continue
            if ((x > 0 && cur[i - 1]) || (x < w - 1 && cur[i + 1]) || (y > 0 && cur[i - w]) || (y < h - 1 && cur[i + w])) next[i] = true
        }
        cur = next
    }
    return cur
}

private fun region(sel: BooleanArray, w: Int, h: Int): RegionMark {
    val bytes = ByteArray(w * h) { if (sel[it]) 255.toByte() else 0 }
    val px = IntArray(w * h) { if (sel[it]) 0x99FF3B5C.toInt() else 0 }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    return RegionMark(bytes, w, h, bmp.asImageBitmap())
}

/** Colour wand */
private fun wandAt(work: Bitmap, p: Offset, tolerance: Float): RegionMark? {
    val s = Neural.scaleTo(work, 512)
    val w = s.width
    val h = s.height
    val px = IntArray(w * h)
    s.getPixels(px, 0, w, 0, 0, w, h)
    val sx = (p.x * (w - 1)).roundToInt().coerceIn(0, w - 1)
    val sy = (p.y * (h - 1)).roundToInt().coerceIn(0, h - 1)

    var r = 0; var g = 0; var b = 0; var c = 0
    for (dy in -2..2) for (dx in -2..2) {
        val x = (sx + dx).coerceIn(0, w - 1)
        val y = (sy + dy).coerceIn(0, h - 1)
        val q = px[y * w + x]
        r += (q shr 16) and 255; g += (q shr 8) and 255; b += q and 255; c++
    }
    r /= c; g /= c; b /= c
    val limit = tolerance * 140f
    val limitSq = limit * limit
    val sel = flood(w, h, sx, sy) { i ->
        val q = px[i]
        val dr = ((q shr 16) and 255) - r
        val dg = ((q shr 8) and 255) - g
        val db = (q and 255) - b
        (dr * dr + dg * dg + db * db) <= limitSq
    } ?: return null
    return region(grow1(sel, w, h, 1), w, h)
}

/** Object tap selection */
private suspend fun objectAt(work: Bitmap, p: Offset, cache: Array<FloatArray?>): RegionMark? {
    val s = Neural.scaleTo(work, 1024)
    val w = s.width
    val h = s.height
    val conf = cache[0]?.takeIf { it.size == w * h } ?: AiPhotoTools.subjectConfidence(s).also { cache[0] = it }
    var sx = (p.x * (w - 1)).roundToInt().coerceIn(0, w - 1)
    var sy = (p.y * (h - 1)).roundToInt().coerceIn(0, h - 1)

    if (conf[sy * w + sx] <= 0.5f) {
        val reach = max(8, min(w, h) / 25)
        var bestD = Int.MAX_VALUE
        var bx = -1
        var by = -1
        for (dy in -reach..reach) for (dx in -reach..reach) {
            val x = sx + dx
            val y = sy + dy
            if (x < 0 || y < 0 || x >= w || y >= h || conf[y * w + x] <= 0.5f) continue
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; bx = x; by = y }
        }
        if (bx < 0) return null
        sx = bx
        sy = by
    }
    val sel = flood(w, h, sx, sy) { conf[it] > 0.5f } ?: return null
    return region(grow1(sel, w, h, 2), w, h)
}

// =========================================================================
// Inpainting Model (LaMa / TFLite)
// =========================================================================

private object Lama {

    const val ASSET = "ai/lama.tflite"

    fun available(context: Context): Boolean = AiPhotoTools.hasAsset(context, ASSET)

    private fun dilate(a: FloatArray, w: Int, h: Int, times: Int): FloatArray {
        var cur = a
        repeat(times) {
            val next = cur.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (cur[i] > 0.5f) continue
                if ((x > 0 && cur[i - 1] > 0.5f) || (x < w - 1 && cur[i + 1] > 0.5f) ||
                    (y > 0 && cur[i - w] > 0.5f) || (y < h - 1 && cur[i + w] > 0.5f)
                ) next[i] = 1f
            }
            cur = next
        }
        return cur
    }

    private fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f; var cnt = 0
            for (x in 0..min(r, w - 1)) { sum += a[o + x]; cnt++ }
            for (x in 0 until w) {
                tmp[o + x] = sum / cnt
                val add = x + r + 1; val rem = x - r
                if (add < w) { sum += a[o + add]; cnt++ }
                if (rem >= 0) { sum -= a[o + rem]; cnt-- }
            }
        }
        for (x in 0 until w) {
            var sum = 0f; var cnt = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; cnt++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / cnt
                val add = y + r + 1; val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; cnt++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; cnt-- }
            }
        }
        return out
    }

    fun erase(context: Context, img: Bitmap, mask: ByteArray, avoidSubject: BooleanArray? = null): Bitmap {
        val w = img.width
        val h = img.height

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((mask[row + x].toInt() and 0xFF) > 127) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return img

        val margin = max(48, (max(maxX - minX, maxY - minY) * 0.6f).toInt())
        var x0 = max(0, minX - margin)
        var y0 = max(0, minY - margin)
        val x1 = min(w, maxX + margin + 1)
        val y1 = min(h, maxY + margin + 1)
        var cw = x1 - x0
        var ch = y1 - y0
        val side = max(cw, ch)
        if (side <= min(w, h)) {
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            x0 = (cx - side / 2).coerceIn(0, w - side)
            y0 = (cy - side / 2).coerceIn(0, h - side)
            cw = side
            ch = side
        }

        val interp = Neural.load(context, ASSET)
        try {
            val n = interp.inputTensorCount
            var imgIdx = -1
            var maskIdx = -1
            for (i in 0 until n) {
                val s = interp.getInputTensor(i).shape()
                if (s.size == 4) {
                    if (s[3] == 3 && imgIdx < 0) imgIdx = i
                    else if (s[3] == 1 && maskIdx < 0) maskIdx = i
                }
            }
            if (n != 2 || imgIdx < 0 || maskIdx < 0) throw IllegalStateException("Unexpected inpainting model inputs")
            for (i in 0 until n) {
                val t = interp.getInputTensor(i)
                if (t.shapeSignature().any { it < 0 }) interp.resizeInput(i, intArrayOf(1, 512, 512, t.shape()[3]))
            }
            interp.allocateTensors()
            val shape = interp.getInputTensor(imgIdx).shape()
            val mh = shape[1]
            val mw = shape[2]

            val crop = Bitmap.createBitmap(img, x0, y0, cw, ch)
            val small = if (cw == mw && ch == mh) crop else resampleHighQuality(crop, mw, mh)
            val px = IntArray(mw * mh)
            small.getPixels(px, 0, mw, 0, 0, mw, mh)

            var maskS = FloatArray(mw * mh)
            for (v in 0 until mh) {
                val sy = y0 + ((v + 0.5f) * ch / mh).toInt().coerceIn(0, ch - 1)
                for (u in 0 until mw) {
                    val sx = x0 + ((u + 0.5f) * cw / mw).toInt().coerceIn(0, cw - 1)
                    val idx = sy * w + sx
                    if ((mask[idx].toInt() and 0xFF) > 127 || (avoidSubject != null && avoidSubject[idx])) {
                        maskS[v * mw + u] = 1f
                    }
                }
            }
            maskS = dilate(maskS, mw, mh, 3)

            val imgArr = FloatArray(mw * mh * 3)
            for (k in 0 until mw * mh) {
                if (maskS[k] > 0.5f) continue
                val p = px[k]
                imgArr[k * 3] = ((p shr 16) and 255) / 255f
                imgArr[k * 3 + 1] = ((p shr 8) and 255) / 255f
                imgArr[k * 3 + 2] = (p and 255) / 255f
            }
            val imgBuf = Neural.floatBuffer(imgArr.size).also { it.asFloatBuffer().put(imgArr) }
            val maskBuf = Neural.floatBuffer(maskS.size).also { it.asFloatBuffer().put(maskS) }
            val inputs = arrayOfNulls<Any>(2)
            inputs[imgIdx] = imgBuf
            inputs[maskIdx] = maskBuf

            val outShape = interp.getOutputTensor(0).shape()
            if (outShape.size != 4 || outShape[3] != 3) throw IllegalStateException("Unexpected inpainting model output")
            val oh = outShape[1]
            val ow = outShape[2]
            val outBufs = HashMap<Int, Any>()
            for (k in 0 until interp.outputTensorCount) {
                outBufs[k] = Neural.floatBuffer(interp.getOutputTensor(k).shape().fold(1) { a, b -> a * b })
            }
            interp.runForMultipleInputsOutputs(arrayOf<Any>(inputs[0]!!, inputs[1]!!), outBufs)

            val outArr = FloatArray(oh * ow * 3)
            (outBufs[0] as ByteBuffer).let { it.rewind(); it.asFloatBuffer().get(outArr) }
            var top = 0f
            for (k in outArr.indices step 11) if (outArr[k] > top) top = outArr[k]
            val kScale = if (top > 2f) 1f else 255f
            val predPx = IntArray(ow * oh) {
                (255 shl 24) or
                        ((outArr[it * 3] * kScale).roundToInt().coerceIn(0, 255) shl 16) or
                        ((outArr[it * 3 + 1] * kScale).roundToInt().coerceIn(0, 255) shl 8) or
                        (outArr[it * 3 + 2] * kScale).roundToInt().coerceIn(0, 255)
            }
            val predSmall = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { it.setPixels(predPx, 0, ow, 0, 0, ow, oh) }
            val pred = if (ow == cw && oh == ch) predSmall else Bitmap.createScaledBitmap(predSmall, cw, ch, true)
            val predFull = IntArray(cw * ch)
            pred.getPixels(predFull, 0, cw, 0, 0, cw, ch)

            var cm = FloatArray(cw * ch)
            for (v in 0 until ch) for (u in 0 until cw) {
                if ((mask[(y0 + v) * w + (x0 + u)].toInt() and 0xFF) > 127) cm[v * cw + u] = 1f
            }
            cm = dilate(cm, cw, ch, 3)
            val feather = blur(cm, cw, ch, 3)

            val out = img.copy(Bitmap.Config.ARGB_8888, true)
            val orig = IntArray(cw * ch)
            out.getPixels(orig, 0, cw, x0, y0, cw, ch)
            for (k in orig.indices) {
                val a = if (cm[k] > 0.5f) 1f else (feather[k] * 1.6f).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val o = orig[k]
                val p = predFull[k]
                val r = (((o shr 16) and 255) * (1f - a) + ((p shr 16) and 255) * a).roundToInt()
                val g = (((o shr 8) and 255) * (1f - a) + ((p shr 8) and 255) * a).roundToInt()
                val b = ((o and 255) * (1f - a) + (p and 255) * a).roundToInt()
                orig[k] = (o and (255 shl 24)) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(orig, 0, cw, x0, y0, cw, ch)
            return out
        } finally {
            interp.close()
        }
    }
}
