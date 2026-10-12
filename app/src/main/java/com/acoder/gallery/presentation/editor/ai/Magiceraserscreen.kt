package com.acoder.gallery.presentation.editor.ai

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.unit.dp

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EraserPink = Color(0xFFFFB1C1)
private val EraserDark = Color(0xFF2A2224)
private val EraserDarkOff = Color(0xFF1C1819)

/** Oldest erase results are dropped beyond this many steps (the original is always kept). */
private const val MAX_ERASE_HISTORY = 7

/**
 * Magic Eraser: paint (or circle) what should disappear and an on-device inpainting model rebuilds
 * the background. Owns all of its state; the host only supplies the current photo and gets back the
 * finished bitmap through [onApply]. [source] is never modified.
 *
 * [classicFallback] is the older patch-based filler. It is used only when the AI model file is
 * missing or cannot be loaded, and the user is told so.
 */
@Composable
internal fun MagicEraser(
    source: Bitmap,
    classicFallback: (Bitmap, List<List<Offset>>, Float) -> Bitmap,
    onCancel: () -> Unit,
    onApply: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { InpaintEngineHolder(context) }

    val history = remember(source) { mutableStateListOf(source) }
    var index by remember(source) { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    val live = remember { mutableStateListOf<Offset>() }
    var brush by remember { mutableFloatStateOf(0.05f) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var view by remember { mutableStateOf(ViewState()) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            // Releasing the native session waits for a running inference, so keep it off the main thread.
            Thread { holder.close() }.start()
        }
    }
    BackHandler(enabled = busy) { job?.cancel() }

    val shown = remember(history[index]) { previewBitmap(history[index], 1440) }
    val hasSelection = selection.isNotEmpty()
    val canUndo = index > 0
    val canRedo = index < history.lastIndex

    fun runErase() {
        if (busy || selection.isEmpty()) return
        val src = history[index]
        val sel = selection
        val brushNow = brush
        message = null
        job = scope.launch {
            busy = true
            progress = 0 to 0
            try {
                val strokes = sel.map { pts -> FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y } }
                val result = try {
                    val engine = holder.get()
                    MagicEraserRunner.erase(src, strokes, brushNow, engine) { done, total -> progress = done to total }
                } catch (e: ModelMissingException) {
                    message = "AI model not installed, so basic erase was used."
                    withContext(Dispatchers.Default) { classicFallback(src, sel, brushNow) }
                } catch (e: ModelIncompatibleException) {
                    message = "AI model could not be loaded (${e.message}). Basic erase was used."
                    withContext(Dispatchers.Default) { classicFallback(src, sel, brushNow) }
                }
                while (history.size > index + 1) history.removeAt(history.lastIndex)
                history.add(result)
                index++
                if (history.size > MAX_ERASE_HISTORY + 1) {
                    history.removeAt(1)
                    index--
                }
                selection = emptyList()
            } catch (e: CancellationException) {
                message = "Erase cancelled"
                throw e
            } catch (e: IllegalArgumentException) {
                message = e.message ?: "Nothing is selected"
            } catch (e: Throwable) {
                message = "Erase failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                busy = false
                progress = 0 to 0
                job = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        // ───── Top bar ─────
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasSelection) {
                EraserPill("Deselect", enabled = !busy) { selection = emptyList() }
                Spacer(Modifier.weight(1f))
                EraserPill("Undo stroke", enabled = !busy) { selection = selection.dropLast(1) }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (view.zoom > 1.01f) {
                IconButton(onClick = { view = ViewState() }) {
                    Icon(Icons.Default.FitScreen, "Fit to screen", tint = Color.White)
                }
            }
        }

        // ───── Preview with zoom / pan and selection overlay ─────
        Box(Modifier.fillMaxWidth().weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                val cw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val ch = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val fit = min(cw / shown.width, ch / shown.height)
                val wPx = shown.width * fit
                val hPx = shown.height * fit
                val density = LocalDensity.current
                val image = remember(shown) { shown.asImageBitmap() }

                fun toNorm(p: Offset): Offset {
                    val (x, y) = ViewMath.toNormalized(view, p.x, p.y, cw, ch, wPx, hPx)
                    return Offset(x, y)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(busy, cw, ch, wPx, hPx) {
                            if (busy) return@pointerInput
                            // One finger paints, two fingers pinch-zoom and pan. A stroke only starts
                            // once the finger has moved, so landing a second finger never leaves a dot.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                val start = down.position
                                var drawing = false
                                var pinching = false
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2) {
                                        if (!pinching) {
                                            pinching = true
                                            drawing = false
                                            live.clear()
                                        }
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroid = event.calculateCentroid()
                                        if (centroid.isSpecified) {
                                            var v = ViewMath.zoomAbout(view, centroid.x, centroid.y, zoomChange, cw, ch, wPx, hPx)
                                            v = ViewMath.pan(v, panChange.x, panChange.y, cw, ch, wPx, hPx)
                                            view = v
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (!pinching) {
                                        val change = event.changes.first()
                                        if (change.pressed) {
                                            if (!drawing && (change.position - start).getDistance() > slop) {
                                                drawing = true
                                                live.clear()
                                                live.add(toNorm(start))
                                            }
                                            if (drawing) {
                                                live.add(toNorm(change.position))
                                                change.consume()
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!pinching) {
                                    if (drawing) {
                                        if (live.size > 1) selection = selection + listOf(live.toList())
                                    } else {
                                        val p = toNorm(start)
                                        selection = selection + listOf(listOf(p, Offset(p.x + 0.0005f, p.y)))
                                    }
                                }
                                live.clear()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                            .graphicsLayer {
                                scaleX = view.zoom
                                scaleY = view.zoom
                                translationX = view.tx
                                translationY = view.ty
                            }
                    ) {
                        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                            val strokes = selection + if (live.isNotEmpty()) listOf(live.toList()) else emptyList()
                            if (strokes.isEmpty()) return@Canvas
                            val brushPx = brush * min(size.width, size.height)
                            val ring = 3.dp.toPx() / view.zoom
                            val paths = strokes.filter { it.size > 1 }.map { pts ->
                                val flat = FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }
                                val path = Path().apply {
                                    moveTo(pts[0].x * size.width, pts[0].y * size.height)
                                    for (i in 1 until pts.size) lineTo(pts[i].x * size.width, pts[i].y * size.height)
                                }
                                path to isClosedStroke(flat, size.width.roundToInt(), size.height.roundToInt(), brushPx)
                            }
                            // Dim everything, then punch the selection out with a white outline.
                            drawRect(Color.Black.copy(alpha = 0.5f))
                            paths.forEach { (path, _) ->
                                drawPath(path, Color.White, style = Stroke(brushPx + 2 * ring, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            paths.forEach { (path, closed) ->
                                drawPath(path, Color.Black, style = Stroke(brushPx, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
                                if (closed) drawPath(path, Color.Black, blendMode = BlendMode.Clear)
                            }
                        }
                    }
                }
            }

            if (busy) {
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = EraserPink)
                    Spacer(Modifier.height(10.dp))
                    val (done, total) = progress
                    Text(if (total > 1) "Erasing… $done / $total" else "Erasing…", color = Color.White)
                }
            }
            message?.let { text ->
                Text(
                    text,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                        .clip(RoundedCornerShape(14.dp)).background(EraserDark)
                        .clickable { message = null }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            if (!hasSelection && !busy && message == null) {
                Text(
                    "Paint or circle what to remove  •  pinch to zoom, two fingers to move",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp)).background(EraserDark)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        // ───── Brush size ─────
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Brush, "Brush size", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            Slider(brush, { brush = it }, valueRange = 0.01f..0.12f, enabled = !busy, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }

        // ───── Actions ─────
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EraserButton({ if (busy) job?.cancel() else onCancel() }, Modifier.width(120.dp)) {
                Icon(Icons.Default.Close, "Cancel", tint = Color.White)
            }
            Box(Modifier.weight(1f), Alignment.Center) {
                when {
                    busy -> Text("Tap ✕ to stop", color = Color.White.copy(alpha = 0.7f))
                    hasSelection -> EraserButton(::runErase, Modifier.width(150.dp), EraserPink) {
                        Icon(Icons.Default.AutoFixHigh, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Erase", color = Color.Black, fontWeight = FontWeight.Medium)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ index--; selection = emptyList() }, enabled = canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White.copy(alpha = if (canUndo) 1f else 0.35f))
                        }
                        TextButton({ index = 0; selection = emptyList() }, enabled = canUndo) {
                            Text("Reset", color = Color.White.copy(alpha = if (canUndo) 1f else 0.35f))
                        }
                        IconButton({ index++; selection = emptyList() }, enabled = canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = Color.White.copy(alpha = if (canRedo) 1f else 0.35f))
                        }
                    }
                }
            }
            val canApply = !hasSelection && canUndo && !busy
            EraserButton({ onApply(history[index]) }, Modifier.width(120.dp), EraserPink, enabled = canApply) {
                Icon(Icons.Default.Check, "Apply", tint = if (canApply) Color.Black else Color.White.copy(alpha = 0.35f))
            }
        }
    }
}

// ───────────────────────── Small pieces ─────────────────────────

@Composable
private fun EraserPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.clip(CircleShape).background(EraserDark).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

@Composable
private fun EraserButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = EraserDark,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.height(56.dp).clip(CircleShape)
            .background(if (enabled) container else EraserDarkOff)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private fun previewBitmap(src: Bitmap, maxSide: Int): Bitmap {
    val m = maxOf(src.width, src.height)
    if (m <= maxSide) return src
    val s = maxSide.toFloat() / m
    return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
}