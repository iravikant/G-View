package com.acoder.gallery.core.collage


import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface as ATypeface
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.acoder.gallery.presentation.common.AppSlider
import com.acoder.gallery.presentation.common.CircleButton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private enum class EditorTab(val label: String, val icon: ImageVector) {
    LAYOUT("Layout", Icons.Default.GridView),
    STYLE("Style", Icons.Default.Style),
    BACKGROUND("Background", Icons.Default.Palette),
    TEXT("Text", Icons.Default.TextFields),
    ADJUST("Adjust", Icons.Default.Tune)
}

/**
 * Full-screen collage editor with a live preview.
 *
 * The photo part of the preview is re-rendered (debounced) when layout, style, background,
 * photo order, photo position or adjustments change. Text is drawn on top of the preview
 * instantly, so it can be dragged smoothly. "Create" returns the final [CollageConfig] to
 * the caller for the full-quality export.
 *
 * Outside the Text tab:
 *  - pinch to zoom a photo, drag to move it inside its tile (drawn live, no re-render lag)
 *  - double-tap a photo to reset it
 *  - long-press a photo and drop it on another tile to swap them
 */
@Composable
fun CollagePreviewDialog(
    uris: List<Uri>,
    onDismiss: () -> Unit,
    onCreate: (CollageConfig) -> Unit
) {
    val context = LocalContext.current

    var layout by remember { mutableStateOf(CollageGenerator.defaultLayout(uris.size)) }
    var frame by remember { mutableStateOf(CollageFrame.CLEAN) }
    var background by remember { mutableStateOf<CollageBackground?>(null) }
    var spacing by remember { mutableStateOf<Int?>(null) }
    var corner by remember { mutableStateOf<Int?>(null) }
    var columns by remember { mutableStateOf(CollageGenerator.defaultColumns(uris.size)) }
    var footer by remember { mutableStateOf(false) }
    // Variation number for dynamic layouts; the Shuffle button bumps it.
    var seed by remember { mutableStateOf(0) }
    var canvasRatio by remember { mutableStateOf(CollageCanvas.AUTO) }
    var texture by remember { mutableStateOf<CollageTexture?>(null) }
    var decor by remember { mutableStateOf<CollageDecor?>(null) }
    val textState = remember { mutableStateOf(CollageText()) }
    var tab by remember { mutableStateOf(EditorTab.LAYOUT) }

    // Photo order (slot i shows uris[order[i]]) and per-photo zoom/pan, keyed by uri index.
    var order by remember { mutableStateOf(uris.indices.toList()) }
    val xforms = remember { mutableStateMapOf<Int, PhotoTransform>() }
    // Bumped when a gesture ends (or a reset happens) to trigger the full re-render.
    var xformVersion by remember { mutableStateOf(0) }
    var gesturing by remember { mutableStateOf(false) }
    // Photo whose live overlay is drawn on top of the (possibly stale) preview.
    var overlayIdx by remember { mutableStateOf<Int?>(null) }
    // Per photo: progressively halved copies (smallest first), so the live overlay never
    // shrinks a big bitmap by more than 2x, which is what causes shimmering lines.
    val photoCache = remember { ConcurrentHashMap<Int, List<Bitmap>>() }
    val overlayPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }

    var tiles by remember { mutableStateOf<List<TileInfo>>(emptyList()) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }   // slot being dragged
    var dragTo by remember { mutableStateOf<Int?>(null) }     // slot under the finger

    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildConfig(withText: Boolean) = CollageConfig(
        layout = layout,
        frame = frame,
        columns = columns,
        spacing = spacing,
        corner = corner,
        background = background,
        footer = footer,
        order = order,
        transforms = xforms.toMap(),
        seed = seed,
        canvas = canvasRatio,
        texture = texture,
        decor = decor,
        text = if (withText) textState.value.takeIf { it.text.isNotBlank() } else null
    )

    // Decode every photo once at moderate size so zoom / pan can be drawn live.
    LaunchedEffect(uris) {
        withContext(Dispatchers.IO) {
            uris.forEachIndexed { i, u ->
                if (!photoCache.containsKey(i)) {
                    CollageGenerator.loadPhoto(context, u, 1280)?.let { photoCache[i] = buildLevels(it) }
                }
            }
        }
    }

    // Re-render the photos when anything except the text changes. Nothing renders while a
    // finger is down on a photo (the live overlay covers that); one render runs when it lifts.
    LaunchedEffect(layout, frame, background, spacing, corner, columns, footer, order, seed, canvasRatio, texture, decor, xformVersion, gesturing) {
        if (gesturing) return@LaunchedEffect
        loading = true
        delay(80)
        val cfg = buildConfig(withText = false)
        val result = withContext(Dispatchers.IO) {
            runCatching { CollageGenerator.renderPreview(context, uris, cfg, shouldContinue = { isActive }) }
        }
        if (result.exceptionOrNull() is CancellationException) return@LaunchedEffect
        result
            .onSuccess { r ->
                preview = r.bitmap.asImageBitmap()
                tiles = r.tiles
                error = null
                overlayIdx = null
            }
            .onFailure { e -> error = e.message ?: e::class.java.simpleName }
        loading = false
    }

    val autoBackground = background ?: CollageGenerator.defaultBackground(frame)
    val ink = CollageGenerator.inkFor(autoBackground)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ---------------- Top bar ----------------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleButton(Icons.Default.Close, "Close", onDismiss)
                    Text(
                        "Collage",
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(onClick = { onCreate(buildConfig(withText = true)) }, enabled = preview != null) {
                        Text("Create")
                    }
                }

                // ---------------- Preview ----------------
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = preview
                    val density = LocalDensity.current
                    val tilesNow by rememberUpdatedState(tiles)
                    val primary = MaterialTheme.colorScheme.primary
                    if (bmp != null) {
                        val scale = minOf(
                            constraints.maxWidth / bmp.width.toFloat(),
                            constraints.maxHeight / bmp.height.toFloat()
                        )
                        val wDp = with(density) { (bmp.width * scale).toDp() }
                        val hDp = with(density) { (bmp.height * scale).toDp() }

                        // While the Text tab is open, touching the preview places / drags the text.
                        val moveText = if (tab == EditorTab.TEXT) {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures { o ->
                                        textState.value = textState.value.copy(
                                            x = (o.x / size.width).coerceIn(0f, 1f),
                                            y = (o.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { o ->
                                            textState.value = textState.value.copy(
                                                x = (o.x / size.width).coerceIn(0f, 1f),
                                                y = (o.y / size.height).coerceIn(0f, 1f)
                                            )
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            textState.value = textState.value.copy(
                                                x = (change.position.x / size.width).coerceIn(0f, 1f),
                                                y = (change.position.y / size.height).coerceIn(0f, 1f)
                                            )
                                        }
                                    )
                                }
                        } else Modifier

                        // Outside the Text tab: pinch = zoom, drag = move the photo inside its tile,
                        // double-tap = reset, long-press + drag onto another tile = swap.
                        val editTiles = if (tab != EditorTab.TEXT) {
                            Modifier
                                .pointerInput(bmp) {
                                    detectTapGestures(onDoubleTap = { o ->
                                        val hit = hitTile(
                                            tilesNow, o.x / size.width, o.y / size.height, bmp.width, bmp.height
                                        )
                                        tilesNow.getOrNull(hit)?.let { t ->
                                            overlayIdx = t.sourceIndex
                                            xforms.remove(t.sourceIndex)
                                            xformVersion++
                                        }
                                    })
                                }
                                .pointerInput(bmp) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val hit = hitTile(
                                            tilesNow,
                                            down.position.x / size.width, down.position.y / size.height,
                                            bmp.width, bmp.height
                                        )
                                        val tile = tilesNow.getOrNull(hit) ?: return@awaitEachGesture
                                        val k = size.width / bmp.width.toFloat() // bitmap px -> box px
                                        val slop = viewConfiguration.touchSlop
                                        var pastSlop = false
                                        var accPan = Offset.Zero
                                        var accZoom = 1f
                                        try {
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Main)
                                                val cancelled = event.changes.any { it.isConsumed } || dragFrom != null
                                                if (!cancelled) {
                                                    val zoomChange = event.calculateZoom()
                                                    val panChange = event.calculatePan()
                                                    if (!pastSlop) {
                                                        accPan += panChange
                                                        accZoom *= zoomChange
                                                        if (accPan.getDistance() > slop || abs(accZoom - 1f) > 0.03f) {
                                                            pastSlop = true
                                                            gesturing = true
                                                            overlayIdx = tile.sourceIndex
                                                        }
                                                    }
                                                    if (pastSlop) {
                                                        val cur = xforms[tile.sourceIndex] ?: PhotoTransform()
                                                        xforms[tile.sourceIndex] = applyGesture(
                                                            tile, cur, zoomChange, event.calculateCentroid(), panChange, k
                                                        )
                                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    }
                                                }
                                            } while (!cancelled && event.changes.any { it.pressed })
                                        } finally {
                                            if (pastSlop) xformVersion++
                                            gesturing = false
                                        }
                                    }
                                }
                                .pointerInput(bmp) {
                                    fun reset() {
                                        dragFrom = null
                                        dragTo = null
                                    }
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { o ->
                                            val hit = hitTile(
                                                tilesNow,
                                                o.x / size.width, o.y / size.height,
                                                bmp.width, bmp.height
                                            )
                                            if (hit >= 0) {
                                                dragFrom = hit
                                                dragTo = hit
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val hit = hitTile(
                                                tilesNow,
                                                change.position.x / size.width,
                                                change.position.y / size.height,
                                                bmp.width, bmp.height
                                            )
                                            if (hit >= 0) dragTo = hit
                                        },
                                        onDragEnd = {
                                            val from = dragFrom
                                            val to = dragTo
                                            if (from != null && to != null && from != to) {
                                                val a = tilesNow.getOrNull(from)?.sourceIndex
                                                val b = tilesNow.getOrNull(to)?.sourceIndex
                                                if (a != null && b != null) {
                                                    val list = order.toMutableList()
                                                    val i = list.indexOf(a)
                                                    val j = list.indexOf(b)
                                                    if (i >= 0 && j >= 0) {
                                                        val tmp = list[i]
                                                        list[i] = list[j]
                                                        list[j] = tmp
                                                        order = list
                                                    }
                                                }
                                            }
                                            reset()
                                        },
                                        onDragCancel = { reset() }
                                    )
                                }
                        } else Modifier

                        Box(
                            Modifier
                                .size(wDp, hDp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(moveText)
                                .then(editTiles)
                        ) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Collage preview",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            val t = textState.value
                            Canvas(Modifier.fillMaxSize()) {
                                val k = size.width / bmp.width.toFloat()

                                // Live photo: redrawn every frame while pinching / dragging, and kept
                                // until the full re-render arrives so nothing jumps back.
                                val oi = overlayIdx
                                if (oi != null) {
                                    val tile = tiles.firstOrNull { it.sourceIndex == oi }
                                    val levels = photoCache[oi]
                                    if (tile != null && levels != null) {
                                        val xf = xforms[oi] ?: PhotoTransform()
                                        val neededW = tile.imgW * xf.zoom * k
                                        val photoBmp = levels.firstOrNull { it.width >= neededW } ?: levels.last()
                                        drawIntoCanvas { c ->
                                            val nc = c.nativeCanvas
                                            nc.save()
                                            nc.scale(k, k)
                                            CollageGenerator.drawTilePhoto(nc, photoBmp, tile, xf, overlayPaint)
                                            nc.restore()
                                        }
                                    }
                                }

                                drawIntoCanvas { c ->
                                    CollageGenerator.drawTextOverlay(c.nativeCanvas, t, ink, size.width, size.height)
                                }

                                // Swap highlight: target filled, dragged tile outlined.
                                val from = dragFrom
                                val to = dragTo
                                if (from != null && to != null && to != from) {
                                    tiles.getOrNull(to)?.card?.let { r ->
                                        drawRect(
                                            primary.copy(alpha = 0.35f),
                                            Offset(r.left * k, r.top * k),
                                            Size(r.width() * k, r.height() * k)
                                        )
                                    }
                                }
                                if (from != null) {
                                    tiles.getOrNull(from)?.card?.let { r ->
                                        drawRect(
                                            primary,
                                            Offset(r.left * k, r.top * k),
                                            Size(r.width() * k, r.height() * k),
                                            style = Stroke(width = 4.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (loading && !gesturing) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(10.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                    if (bmp == null && !loading && error != null) {
                        Text(
                            "Preview failed: $error",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                if (tab != EditorTab.TEXT) {
                    Text(
                        "Pinch to zoom \u2022 drag to move \u2022 double-tap to reset \u2022 long-press and drop to swap",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ---------------- Tool tabs ----------------
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    EditorTab.entries.forEach { t ->
                        val selected = t == tab
                        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { tab = t }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(t.icon, t.label, tint = tint)
                            Text(
                                t.label,
                                fontSize = 11.sp,
                                color = tint,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                // ---------------- Tool panel ----------------
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(232.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 12.dp)
                ) {
                    when (tab) {
                        EditorTab.LAYOUT -> LayoutPanel(
                            layout = layout,
                            onLayout = { layout = it },
                            columns = columns,
                            onColumns = { columns = it },
                            count = uris.size,
                            onShuffle = { seed++ },
                            canvas = canvasRatio,
                            onCanvas = { canvasRatio = it }
                        )
                        EditorTab.STYLE -> StylePanel(frame) { frame = it }
                        EditorTab.BACKGROUND -> BackgroundPanel(
                            background, autoBackground,
                            texture = texture, frame = frame, onTexture = { texture = it }
                        ) { background = it }
                        EditorTab.TEXT -> TextPanel(textState, ink)
                        EditorTab.ADJUST -> AdjustPanel(
                            frame = frame,
                            spacing = spacing,
                            onSpacing = { spacing = it },
                            corner = corner,
                            onCorner = { corner = it },
                            decor = decor,
                            onDecor = { decor = it },
                            footer = footer,
                            onFooter = { footer = it },
                            onReset = {
                                spacing = null
                                corner = null
                                footer = false
                                decor = null
                                xforms.clear()
                                xformVersion++
                                order = uris.indices.toList()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Applies one pinch / drag step to [cur]. Zooms around [centroid] (box px), then moves by [pan],
 * and keeps the photo covering its frame. [k] converts preview-bitmap px to box px.
 */
private fun applyGesture(
    t: TileInfo,
    cur: PhotoTransform,
    zoomChange: Float,
    centroid: Offset,
    pan: Offset,
    k: Float
): PhotoTransform {
    // The centroid is NaN on the event where the last finger lifts; ignore such events.
    if (centroid.x.isNaN() || centroid.y.isNaN() || !zoomChange.isFinite() || zoomChange <= 0f ||
        pan.x.isNaN() || pan.y.isNaN()
    ) return cur

    val newZoom = (cur.zoom * zoomChange).coerceIn(1f, 5f)
    val f = newZoom / cur.zoom

    val pw = t.photo.width()
    val ph = t.photo.height()
    val oldOx = t.imgW * cur.zoom - pw
    val oldOy = t.imgH * cur.zoom - ph
    val newOx = t.imgW * newZoom - pw
    val newOy = t.imgH * newZoom - ph

    // Centroid relative to the photo's top-left, in bitmap px
    val cx = centroid.x / k - t.photo.left
    val cy = centroid.y / k - t.photo.top

    // Offset of the visible window into the magnified photo
    val oldCx = oldOx * (0.5f + 0.5f * cur.panX)
    val oldCy = oldOy * (0.5f + 0.5f * cur.panY)
    val newCx = ((oldCx + cx) * f - cx - pan.x / k).coerceIn(0f, max(newOx, 0f))
    val newCy = ((oldCy + cy) * f - cy - pan.y / k).coerceIn(0f, max(newOy, 0f))

    return PhotoTransform(
        zoom = newZoom,
        panX = if (newOx > 0.5f) (newCx / newOx * 2f - 1f).coerceIn(-1f, 1f) else 0f,
        panY = if (newOy > 0.5f) (newCy / newOy * 2f - 1f).coerceIn(-1f, 1f) else 0f
    )
}

/** [full] plus progressively halved copies, ordered smallest to largest. */
private fun buildLevels(full: Bitmap): List<Bitmap> {
    val levels = ArrayList<Bitmap>()
    levels += full
    var cur = full
    while (minOf(cur.width, cur.height) >= 400) {
        cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        levels += cur
    }
    return levels.reversed()
}

/** Returns the slot index under a touch point given as fractions (0..1) of the preview, or -1. */
private fun hitTile(tiles: List<TileInfo>, fx: Float, fy: Float, bmpW: Int, bmpH: Int): Int {
    val x = fx * bmpW
    val y = fy * bmpH
    var best = -1
    var bestDist = Float.MAX_VALUE
    tiles.forEachIndexed { i, t ->
        val c = t.card
        if (x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) {
            // Tilted or overlapping cards: prefer the nearest centre
            val dx = x - c.centerX()
            val dy = y - c.centerY()
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
    }
    return best
}

// =========================================================================
// Layout tab
// =========================================================================

@Composable
private fun LayoutPanel(
    layout: CollageLayout,
    onLayout: (CollageLayout) -> Unit,
    columns: Int,
    onColumns: (Int) -> Unit,
    count: Int,
    onShuffle: () -> Unit,
    canvas: CollageCanvas,
    onCanvas: (CollageCanvas) -> Unit
) {
    val options = remember(count) { CollageGenerator.layoutsFor(count) }
    val maxCols = if (layout == CollageLayout.STAGGER) minOf(4, maxOf(2, count / 2)) else minOf(4, count)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (count == 1) "1 photo" else "Layouts for $count photos",
            Modifier.padding(horizontal = 16.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            options.forEach { option ->
                LayoutThumb(option, count, columns, selected = option == layout) { onLayout(option) }
            }
        }
        if (CollageGenerator.usesSeed(layout)) {
            TextButton(onClick = onShuffle, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Shuffle layout")
            }
        }
        if (CollageGenerator.usesCanvas(layout)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Canvas", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CollageCanvas.entries.forEach { c ->
                    FilterChip(selected = c == canvas, onClick = { onCanvas(c) }, label = { Text(c.label) })
                }
            }
        }
        if (CollageGenerator.usesColumns(layout) && maxCols >= 3) {
            LabeledSlider(
                label = "Columns",
                value = columns.toFloat(),
                range = 2f..maxCols.toFloat(),
                valueLabel = "$columns",
                steps = (maxCols - 3).coerceAtLeast(0)
            ) { onColumns(it.roundToInt()) }
        }
    }
}

@Composable
private fun LayoutThumb(layout: CollageLayout, count: Int, columns: Int, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val rects = remember(layout, count, columns) { CollageGenerator.thumbRects(layout, count, columns) }
    val separator = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(BorderStroke(if (selected) 2.5.dp else 1.dp, outline), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
        ) {
            val pad = 11.dp.toPx()
            val iw = size.width - pad * 2
            val ih = size.height - pad * 2
            val radius = CornerRadius(2.5.dp.toPx())
            rects.forEach { t ->
                val r = t.rect
                val topLeft = Offset(pad + r[0] * iw, pad + r[1] * ih)
                val sz = Size(r[2] * iw, r[3] * ih)
                rotate(t.angle, Offset(topLeft.x + sz.width / 2f, topLeft.y + sz.height / 2f)) {
                    when (t.shape) {
                        TileShape.RECT -> {
                            drawRoundRect(tint, topLeft, sz, radius)
                            // A thin outline keeps overlapping tiles readable
                            drawRoundRect(separator, topLeft, sz, radius, style = Stroke(1.2.dp.toPx()))
                        }
                        TileShape.ARCH -> {
                            val top = minOf(sz.width / 2f, sz.height / 2f)
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        topLeft.x, topLeft.y, topLeft.x + sz.width, topLeft.y + sz.height,
                                        CornerRadius(top), CornerRadius(top),
                                        CornerRadius(1.dp.toPx()), CornerRadius(1.dp.toPx())
                                    )
                                )
                            }
                            drawPath(path, tint)
                        }
                    }
                }
            }
        }
        Text(
            layout.label,
            Modifier.padding(top = 4.dp),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// =========================================================================
// Style tab
// =========================================================================

@Composable
private fun StylePanel(frame: CollageFrame, onFrame: (CollageFrame) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CollageFrame.entries.forEach { option ->
            FrameSwatch(option, selected = option == frame) { onFrame(option) }
        }
    }
}

/** A tiny 2x2 collage drawn in the style itself, so the swatch shows what you will get. */
@Composable
private fun FrameSwatch(frame: CollageFrame, selected: Boolean, onClick: () -> Unit) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .clip(shape)
                .border(BorderStroke(if (selected) 2.5.dp else 1.dp, outline), shape)
                .clickable(onClick = onClick)
        ) {
            GradientBox(CollageGenerator.defaultBackground(frame), Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) { drawFrameMini(frame) }
        }
        Text(
            frame.label,
            Modifier.padding(top = 4.dp),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private val SWATCH_PHOTOS = listOf(
    listOf(Color(0xFF6FA8F0), Color(0xFFB9D7FA)),
    listOf(Color(0xFFF2985F), Color(0xFFF9D2AE)),
    listOf(Color(0xFF6CC196), Color(0xFFC4EBD2)),
    listOf(Color(0xFFA98BE0), Color(0xFFDCCBF5))
)

private fun DrawScope.drawFrameMini(frame: CollageFrame) {
    val w = size.width
    val pad = w * (if (frame == CollageFrame.EDITORIAL) 0.22f else 0.15f)
    val gap = w * when (frame) {
        CollageFrame.GALLERY, CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> 0.10f
        CollageFrame.FLOAT, CollageFrame.STICKER, CollageFrame.SOFT -> 0.09f
        else -> 0.06f
    }
    val cell = (w - pad * 2 - gap) / 2f

    val paper: Color? = when (frame) {
        CollageFrame.SOFT, CollageFrame.FLOAT, CollageFrame.STICKER, CollageFrame.MINIMAL -> Color.White
        CollageFrame.TORN -> Color(0xFFFBFAF6)
        CollageFrame.GLASS -> Color.White.copy(alpha = 0.28f)
        CollageFrame.GALLERY -> Color(0xFFFBFAF7)
        CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> Color(0xFFFDFCF8)
        else -> null
    }
    val cardCorner = cell * when (frame) {
        CollageFrame.CLEAN -> 0.20f
        CollageFrame.SOFT -> 0.30f
        CollageFrame.NOIR -> 0.12f
        CollageFrame.GALLERY -> 0.02f
        CollageFrame.FLOAT -> 0.22f
        CollageFrame.GLASS -> 0.26f
        CollageFrame.EDITORIAL -> 0f
        CollageFrame.STICKER -> 0.26f
        CollageFrame.INSTANT -> 0.04f
        CollageFrame.MINIMAL -> 0.03f
        CollageFrame.SCRAPBOOK -> 0.04f
        CollageFrame.TORN -> 0.02f
    }
    val mat = cell * when (frame) {
        CollageFrame.FLOAT -> 0.07f
        CollageFrame.GLASS -> 0.08f
        CollageFrame.GALLERY -> 0.12f
        CollageFrame.STICKER -> 0.10f
        CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> 0.09f
        CollageFrame.TORN -> 0.08f
        else -> 0f
    }
    val bottomExtra = cell * when (frame) {
        CollageFrame.INSTANT, CollageFrame.SCRAPBOOK -> 0.16f
        CollageFrame.GALLERY -> 0.04f
        else -> 0f
    }
    val shadow = frame == CollageFrame.SOFT || frame == CollageFrame.FLOAT || frame == CollageFrame.GALLERY ||
            frame == CollageFrame.STICKER || frame == CollageFrame.INSTANT || frame == CollageFrame.MINIMAL ||
            frame == CollageFrame.SCRAPBOOK || frame == CollageFrame.TORN

    for (i in 0..3) {
        val x = pad + (i % 2) * (cell + gap)
        val y = pad + (i / 2) * (cell + gap)
        val tilt = if (frame == CollageFrame.INSTANT || frame == CollageFrame.SCRAPBOOK) (if (i % 2 == 0) -3f else 2.5f) else 0f
        rotate(tilt, Offset(x + cell / 2f, y + cell / 2f)) {
            if (shadow) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.12f),
                    Offset(x, y + w * 0.03f),
                    Size(cell, cell),
                    CornerRadius(cardCorner)
                )
            }
            if (paper != null) {
                drawRoundRect(paper, Offset(x, y), Size(cell, cell), CornerRadius(cardCorner))
                if (frame == CollageFrame.GLASS) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.6f), Offset(x, y), Size(cell, cell), CornerRadius(cardCorner),
                        style = Stroke(w * 0.012f)
                    )
                }
            }
            if (frame == CollageFrame.SCRAPBOOK) {
                drawRoundRect(
                    Color(0xCCF2BFC8), Offset(x + cell * 0.3f, y - cell * 0.07f),
                    Size(cell * 0.4f, cell * 0.15f), CornerRadius(1f)
                )
            }
            val px = x + mat
            val py = y + mat
            val pw = cell - mat * 2
            val ph = cell - mat * 2 - bottomExtra
            val pc = max(cardCorner - mat, 0f)
            drawRoundRect(
                Brush.linearGradient(SWATCH_PHOTOS[i], Offset(px, py), Offset(px + pw, py + ph)),
                Offset(px, py),
                Size(pw, ph),
                CornerRadius(pc)
            )
            if (frame == CollageFrame.NOIR) {
                drawRoundRect(
                    Color.White.copy(alpha = 0.22f), Offset(px, py), Size(pw, ph), CornerRadius(pc),
                    style = Stroke(w * 0.01f)
                )
            }
        }
    }
    if (frame == CollageFrame.EDITORIAL) {
        drawRect(
            Color(0xFF1C1C1E),
            Offset(w * 0.08f, w * 0.08f),
            Size(w * 0.84f, w * 0.84f),
            style = Stroke(w * 0.012f)
        )
    }
}

// =========================================================================
// Background tab
// =========================================================================

private fun bg(vararg c: Long, angle: Float = 135f) = CollageBackground(c.map { it.toInt() }, angle)

private val BACKGROUND_PRESETS = listOf(
    bg(0xFFFFFFFF), bg(0xFF111214), bg(0xFFF5F0E6), bg(0xFFFFE4EC),
    bg(0xFFFF512F, 0xFFDD2476), bg(0xFF2193B0, 0xFF6DD5ED), bg(0xFF00C9FF, 0xFF92FE9D),
    bg(0xFFFFECD2, 0xFFFCB69F), bg(0xFF0F2027, 0xFF203A43, 0xFF2C5364),
    bg(0xFFFF9A9E, 0xFFFAD0C4, 0xFFA1C4FD), bg(0xFFE0C3FC, 0xFF8EC5FC), bg(0xFF134E5E, 0xFF71B280),
    bg(0xFFF12711, 0xFFF5AF19), bg(0xFF3A1C71, 0xFFD76D77, 0xFFFFAF7B)
)

@Composable
private fun BackgroundPanel(
    background: CollageBackground?,
    autoBackground: CollageBackground,
    texture: CollageTexture?,
    frame: CollageFrame,
    onTexture: (CollageTexture?) -> Unit,
    onChange: (CollageBackground?) -> Unit
) {
    var stop by remember { mutableStateOf(0) }
    val current = background ?: autoBackground
    val mode = when {
        background == null -> 0
        background.colors.size == 1 -> 1
        else -> 2
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = mode == 0, onClick = { onChange(null) }, label = { Text("Auto") })
            FilterChip(
                selected = mode == 1,
                onClick = { onChange(CollageBackground(listOf(current.colors.first()))) },
                label = { Text("Solid") }
            )
            FilterChip(
                selected = mode == 2,
                onClick = {
                    stop = 0
                    onChange(
                        if (current.colors.size >= 2) current
                        else CollageBackground(listOf(current.colors.first(), shiftHue(current.colors.first(), 40f)))
                    )
                },
                label = { Text("Gradient") }
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Texture", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(
                selected = texture == null,
                onClick = { onTexture(null) },
                label = { Text(if (CollageGenerator.defaultTexture(frame) == CollageTexture.NONE) "Auto" else "Auto \u00B7 ${CollageGenerator.defaultTexture(frame).label}") }
            )
            CollageTexture.entries.forEach { tx ->
                FilterChip(selected = texture == tx, onClick = { onTexture(tx) }, label = { Text(tx.label) })
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BACKGROUND_PRESETS.forEach { preset ->
                val selected = background == preset
                val shape = RoundedCornerShape(12.dp)
                GradientBox(
                    preset,
                    Modifier
                        .size(44.dp)
                        .clip(shape)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape
                        )
                        .clickable { stop = 0; onChange(preset) }
                )
            }
        }

        if (background != null) {
            if (mode == 1) {
                ColorEditor(background.colors[0]) { onChange(CollageBackground(listOf(it))) }
            } else {
                val idx = stop.coerceIn(0, background.colors.lastIndex)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Color stops", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    background.colors.forEachIndexed { i, c -> ColorDot(c, selected = i == idx) { stop = i } }
                    if (background.colors.size < 3) {
                        TextButton(onClick = {
                            stop = background.colors.size
                            onChange(background.copy(colors = background.colors + shiftHue(background.colors.last(), 40f)))
                        }) { Text("+ Add") }
                    }
                    if (background.colors.size > 2) {
                        TextButton(onClick = {
                            stop = 0
                            onChange(background.copy(colors = background.colors.filterIndexed { i, _ -> i != idx }))
                        }) { Text("Remove") }
                    }
                }
                ColorEditor(background.colors[idx]) { newColor ->
                    onChange(background.copy(colors = background.colors.mapIndexed { i, c -> if (i == idx) newColor else c }))
                }
                LabeledSlider(
                    label = "Angle",
                    value = background.angle,
                    range = 0f..360f,
                    valueLabel = "${background.angle.roundToInt()}\u00B0"
                ) { onChange(background.copy(angle = it)) }
            }
        }
    }
}

// =========================================================================
// Text tab
// =========================================================================

@Composable
private fun TextPanel(state: MutableState<CollageText>, ink: Int) {
    val t = state.value
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = t.text,
            onValueChange = { state.value = state.value.copy(text = it) },
            label = { Text("Your text") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Text(
            if (t.text.isBlank()) "Type something, then drag on the preview to place it."
            else "Touch and drag on the preview to move the text.",
            Modifier.padding(horizontal = 16.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SmallLabel("Presets")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TEXT_PRESETS.forEach { (name, apply) ->
                FilterChip(selected = false, onClick = { state.value = apply(state.value) }, label = { Text(name) })
            }
        }

        SmallLabel("Font")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CollageFont.entries.forEach { f ->
                val family = remember(f) { FontFamily(ComposeTypeface(ATypeface.create(f.family, ATypeface.NORMAL))) }
                FilterChip(
                    selected = t.font == f,
                    onClick = { state.value = state.value.copy(font = f) },
                    label = { Text(f.label, fontFamily = family) }
                )
            }
        }

        SmallLabel("Style")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(t.bold, { state.value = state.value.copy(bold = !state.value.bold) }, { Text("Bold") })
            FilterChip(t.italic, { state.value = state.value.copy(italic = !state.value.italic) }, { Text("Italic") })
            FilterChip(t.uppercase, { state.value = state.value.copy(uppercase = !state.value.uppercase) }, { Text("CAPS") })
            FilterChip(t.shadow, { state.value = state.value.copy(shadow = !state.value.shadow) }, { Text("Shadow") })
            FilterChip(t.backdrop, { state.value = state.value.copy(backdrop = !state.value.backdrop) }, { Text("Backdrop") })
            FilterChip(t.tape, { state.value = state.value.copy(tape = !state.value.tape) }, { Text("Tape") })
        }

        LabeledSlider("Size", t.sizeFraction, 0.02f..0.16f, "${(t.sizeFraction * 100).roundToInt()}") {
            state.value = state.value.copy(sizeFraction = it)
        }
        LabeledSlider("Letter spacing", t.letterSpacing, -0.05f..0.4f, "%.2f".format(t.letterSpacing)) {
            state.value = state.value.copy(letterSpacing = it)
        }
        LabeledSlider("Rotate", t.rotation, -45f..45f, "${t.rotation.roundToInt()}\u00B0") {
            state.value = state.value.copy(rotation = it)
        }

        SmallLabel("Color")
        Row(Modifier.padding(horizontal = 16.dp)) {
            FilterChip(
                selected = t.color == null,
                onClick = { state.value = state.value.copy(color = null) },
                label = { Text("Auto") }
            )
        }
        ColorEditor(t.color ?: ink) { state.value = state.value.copy(color = it) }
    }
}

private val TEXT_PRESETS: List<Pair<String, (CollageText) -> CollageText>> = listOf(
    "Tape label" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.MONO, sizeFraction = 0.045f, tape = true, backdrop = false, uppercase = false,
            bold = false, italic = false, shadow = false, letterSpacing = 0.02f, rotation = -2f, color = null
        )
    },
    "Script" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.SCRIPT, sizeFraction = 0.12f, tape = false, backdrop = false, uppercase = false,
            bold = false, italic = false, shadow = true, letterSpacing = 0f, rotation = -4f
        )
    },
    "Typewriter" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.MONO, sizeFraction = 0.05f, tape = false, backdrop = false, uppercase = false,
            bold = false, italic = false, shadow = false, letterSpacing = 0.02f, rotation = 0f
        )
    },
    "Poster" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.BLACK, sizeFraction = 0.075f, tape = false, backdrop = false, uppercase = true,
            bold = false, italic = false, shadow = false, letterSpacing = 0.14f, rotation = 0f
        )
    },
    "Editorial" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.SERIF, sizeFraction = 0.07f, tape = false, backdrop = false, uppercase = false,
            bold = false, italic = true, shadow = false, letterSpacing = 0.02f, rotation = 0f
        )
    },
    "Caption" to { preset: CollageText ->
        preset.copy(
            font = CollageFont.CONDENSED, sizeFraction = 0.032f, tape = false, backdrop = true, uppercase = true,
            bold = false, italic = false, shadow = false, letterSpacing = 0.2f, rotation = 0f
        )
    }
)

// =========================================================================
// Adjust tab
// =========================================================================

@Composable
private fun AdjustPanel(
    frame: CollageFrame,
    spacing: Int?,
    onSpacing: (Int) -> Unit,
    corner: Int?,
    onCorner: (Int) -> Unit,
    decor: CollageDecor?,
    onDecor: (CollageDecor?) -> Unit,
    footer: Boolean,
    onFooter: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    val spacingValue = spacing ?: CollageGenerator.defaultSpacing(frame)
    val cornerValue = corner ?: CollageGenerator.defaultCorner(frame)
    val fixedCorner = CollageGenerator.hasFixedCorner(frame)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Decor", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(selected = decor == null, onClick = { onDecor(null) }, label = { Text("Auto") })
            CollageDecor.entries.forEach { d ->
                FilterChip(selected = decor == d, onClick = { onDecor(d) }, label = { Text(d.label) })
            }
        }
        LabeledSlider("Spacing", spacingValue.toFloat(), 0f..48f, "$spacingValue") { onSpacing(it.roundToInt()) }
        LabeledSlider(
            label = if (fixedCorner) "Corners (fixed for ${frame.label})" else "Corners",
            value = cornerValue.toFloat(),
            range = 0f..60f,
            valueLabel = "$cornerValue",
            enabled = !fixedCorner
        ) { onCorner(it.roundToInt()) }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Extra space below", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Adds a clean band for text",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = footer, onCheckedChange = onFooter)
        }

        TextButton(onClick = onReset, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Reset adjustments")
        }
    }
}

// =========================================================================
// Reusable pieces
// =========================================================================

@Composable
private fun SmallLabel(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Label + current value on top, [AppSlider] below. [steps] > 0 shows a snap dot at every allowed value. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val snaps = remember(range, steps) {
        if (steps <= 0) emptyList()
        else (0..steps + 1).map { range.start + (range.endInclusive - range.start) * it / (steps + 1) }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(valueLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.alpha(if (enabled) 1f else 0.38f)) {
            AppSlider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                format = { valueLabel },
                snaps = snaps
            )
            if (!enabled) {
                // AppSlider has no `enabled` flag: a transparent layer swallows the touches instead.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                )
            }
        }
    }
}

/** Draws a solid color or an angled gradient that fills the box. */
@Composable
private fun GradientBox(bg: CollageBackground, modifier: Modifier) {
    Canvas(modifier) {
        val colors = bg.colors.map { Color(it) }
        if (colors.size == 1) {
            drawRect(colors[0])
        } else {
            val rad = Math.toRadians(bg.angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            drawRect(
                Brush.linearGradient(
                    colors,
                    start = Offset(center.x - dx * half, center.y - dy * half),
                    end = Offset(center.x + dx * half, center.y + dy * half)
                )
            )
        }
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, size: Dp = 30.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private val COLOR_PRESETS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF1C1C1E, 0xFFF5F0E6, 0xFFFFE4EC, 0xFFFF6B6B, 0xFFFFA94D,
    0xFFFFD43B, 0xFF69DB7C, 0xFF38D9A9, 0xFF4DABF7, 0xFF748FFC, 0xFFB197FC, 0xFFF783AC
).map { it.toInt() }

private fun toHsv(color: Int): Triple<Float, Float, Float> {
    val a = FloatArray(3)
    AColor.colorToHSV(color, a)
    return Triple(a[0], a[1], a[2])
}

/** A pleasant second color for a new gradient stop. */
private fun shiftHue(color: Int, degrees: Float): Int {
    val (h, s, v) = toHsv(color)
    return AColor.HSVToColor(floatArrayOf((h + degrees) % 360f, max(s, 0.45f), v.coerceIn(0.35f, 0.9f)))
}

/** Preset swatches, hue / saturation / brightness sliders and a hex field. */
@Composable
private fun ColorEditor(color: Int, onColor: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(toHsv(color)) }
    var lastEmitted by remember { mutableStateOf<Int?>(null) }

    // Re-sync when the color changes from outside (another stop selected, a preset tapped).
    LaunchedEffect(color) { if (color != lastEmitted) hsv = toHsv(color) }

    fun emit(h: Float, s: Float, v: Float) {
        hsv = Triple(h, s, v)
        val c = AColor.HSVToColor(floatArrayOf(h, s, v))
        lastEmitted = c
        onColor(c)
    }

    val (h, s, v) = hsv
    var hex by remember(color) { mutableStateOf(String.format("%06X", color and 0xFFFFFF)) }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            COLOR_PRESETS.forEach { preset ->
                ColorDot(preset, selected = preset == color) {
                    hsv = toHsv(preset)
                    lastEmitted = preset
                    onColor(preset)
                }
            }
        }

        val hueBrush = Brush.horizontalGradient(
            listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) }
        )
        GradientSlider(h, 0f..360f, hueBrush, format = { "${it.roundToInt()}\u00B0" }) { emit(it, s, v) }
        GradientSlider(
            s, 0f..1f,
            Brush.horizontalGradient(listOf(Color.hsv(h, 0f, v), Color.hsv(h, 1f, v)))
        ) { emit(h, it, v) }
        GradientSlider(
            v, 0f..1f,
            Brush.horizontalGradient(listOf(Color.hsv(h, s, 0f), Color.hsv(h, s, 1f)))
        ) { emit(h, s, it) }

        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                val clean = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                hex = clean
                if (clean.length == 6) {
                    runCatching { AColor.parseColor("#$clean") }.onSuccess { parsed ->
                        hsv = toHsv(parsed)
                        lastEmitted = parsed
                        onColor(parsed)
                    }
                }
            },
            singleLine = true,
            label = { Text("Hex (RRGGBB)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** [AppSlider] whose track shows the range of values it controls (rainbow, saturation ramp...). */
@Composable
private fun GradientSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    format: ((Float) -> String)? = null,
    onChange: (Float) -> Unit
) {
    AppSlider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        format = format,
        trackBrush = brush
    )
}