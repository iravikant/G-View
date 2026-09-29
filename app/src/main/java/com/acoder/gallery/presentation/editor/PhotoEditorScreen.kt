@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.acoder.gallery.core.editor.ImageEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tool { NONE, ADJUST, CROP, FILTER, MARKUP, TEXT }

private data class MarkupStroke(val color: Color, val width: Float, val points: List<Offset>)
private data class TextOverlay(val id: Int, val text: String, val x: Float, val y: Float, val color: Color)
private data class FilterPreset(val name: String, val apply: (ImageEditor.State) -> ImageEditor.State)

private val FILTER_PRESETS = listOf(
    FilterPreset("Original") { it.copy(brightness = 0f, contrast = 1f, saturation = 1f, temperature = 0f, exposure = 0f) },
    FilterPreset("Mono") { it.copy(saturation = 0f, contrast = 1.1f) },
    FilterPreset("Vivid") { it.copy(saturation = 1.45f, contrast = 1.15f) },
    FilterPreset("Warm") { it.copy(temperature = 0.35f, saturation = 1.1f) },
    FilterPreset("Cool") { it.copy(temperature = -0.35f) },
    FilterPreset("Fade") { it.copy(contrast = 0.85f, brightness = 0.06f, saturation = 0.8f) },
    FilterPreset("Noir") { it.copy(saturation = 0f, contrast = 1.3f, brightness = -0.05f) }
)

private val MARKUP_COLORS = listOf(Color.Red, Color(0xFFFFC107), Color(0xFF2196F3), Color(0xFF4CAF50), Color.White, Color.Black)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(uri: Uri, nav: NavHostController) {
    val context = LocalContext.current
    var original by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        original = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
    }

    var state by remember { mutableStateOf(ImageEditor.State()) }
    val history = remember { mutableStateListOf(state) }
    var historyIndex by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.NONE) }
    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Markup (freehand pen)
    val markupStrokes = remember { mutableStateListOf<MarkupStroke>() }
    var currentStroke by remember { mutableStateOf<List<Offset>?>(null) }
    var markupColor by remember { mutableStateOf(Color.Red) }
    var markupWidth by remember { mutableFloatStateOf(0.01f) }

    // Text overlays
    val textOverlays = remember { mutableStateListOf<TextOverlay>() }
    var nextTextId by remember { mutableIntStateOf(0) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var selectedTextId by remember { mutableStateOf<Int?>(null) }

    BackHandler { nav.popBackStack() }
    val source = original
    if (source == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        return
    }

    val rendered = remember(source, state) { ImageEditor(source).render(state) }
    val displayBmp = if (showOriginal) source else rendered

    fun update(next: ImageEditor.State) {
        state = next
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(next)
        historyIndex = history.lastIndex
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = Color.White) }
                Row {
                    IconButton({ showOriginal = !showOriginal }) {
                        Icon(if (showOriginal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Preview original", tint = Color.White)
                    }
                    IconButton(onClick = { if (historyIndex > 0) { historyIndex--; state = history[historyIndex] } }, enabled = historyIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (historyIndex > 0) Color.White else Color.White.copy(alpha = 0.35f))
                    }
                    IconButton(onClick = { if (historyIndex < history.lastIndex) { historyIndex++; state = history[historyIndex] } }, enabled = historyIndex < history.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (historyIndex < history.lastIndex) Color.White else Color.White.copy(alpha = 0.35f))
                    }
                }
            }

            // Letterboxed preview, like a native camera-roll editor
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val boxWidth = maxWidth
                val boxHeight = maxHeight
                val density = androidx.compose.ui.platform.LocalDensity.current
                val boxWidthPx = with(density) { boxWidth.toPx() }.coerceAtLeast(1f)
                val boxHeightPx = with(density) { boxHeight.toPx() }.coerceAtLeast(1f)
                androidx.compose.foundation.Image(
                    bitmap = displayBmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (showOriginal) {
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = Color.Black.copy(alpha = 0.6f)
                    ) { Text("Original", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium) }
                }
                if (tool == Tool.MARKUP && !showOriginal) {
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize().pointerInput(markupColor, markupWidth) {
                            detectDragGestures(
                                onDragStart = { p -> currentStroke = listOf(Offset(p.x / size.width, p.y / size.height)) },
                                onDrag = { c, _ -> currentStroke = (currentStroke ?: emptyList()) + Offset(c.position.x / size.width, c.position.y / size.height) },
                                onDragEnd = {
                                    currentStroke?.let { if (it.size > 1) markupStrokes.add(MarkupStroke(markupColor, markupWidth, it)) }
                                    currentStroke = null
                                }
                            )
                        }
                    ) {
                        val allStrokes = markupStrokes.map { it.color to (it.width to it.points) } +
                            (currentStroke?.let { listOf(markupColor to (markupWidth to it)) } ?: emptyList())
                        allStrokes.forEach { (color, widthPoints) ->
                            val (w, pts) = widthPoints
                            if (pts.size > 1) {
                                val strokeWidthPx = w * minOf(this.size.width, this.size.height)
                                for (i in 0 until pts.size - 1) {
                                    drawLine(
                                        color = color,
                                        start = Offset(pts[i].x * this.size.width, pts[i].y * this.size.height),
                                        end = Offset(pts[i + 1].x * this.size.width, pts[i + 1].y * this.size.height),
                                        strokeWidth = strokeWidthPx,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }
                }
                if (tool == Tool.TEXT || textOverlays.isNotEmpty()) {
                    textOverlays.forEach { overlay ->
                        Text(
                            overlay.text,
                            color = overlay.color,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = boxWidth * overlay.x, y = boxHeight * overlay.y)
                                .then(
                                    if (tool == Tool.TEXT) Modifier
                                        .clickable { selectedTextId = if (selectedTextId == overlay.id) null else overlay.id }
                                        .pointerInput(overlay.id, boxWidthPx, boxHeightPx) {
                                            detectDragGestures { change, drag ->
                                                change.consume()
                                                val idx = textOverlays.indexOfFirst { it.id == overlay.id }
                                                if (idx >= 0) {
                                                    val current = textOverlays[idx]
                                                    textOverlays[idx] = current.copy(
                                                        x = (current.x + drag.x / boxWidthPx).coerceIn(0f, 1f),
                                                        y = (current.y + drag.y / boxHeightPx).coerceIn(0f, 1f)
                                                    )
                                                }
                                            }
                                        }
                                    else Modifier
                                )
                        )
                    }
                }
            }

            // Contextual tool panel
            AnimatedToolPanel(tool) {
                when (tool) {
                    Tool.ADJUST -> AdjustPanel(state, ::update)
                    Tool.CROP -> CropPanel(
                        onRatio = { ratio ->
                            if (ratio == null) update(state.copy(crop = null))
                            else {
                                val w = rendered.width.toFloat(); val h = rendered.height.toFloat()
                                val cw = minOf(w, h * ratio); val ch = cw / ratio
                                update(state.copy(crop = RectF((w - cw) / (2 * w), (h - ch) / (2 * h), (w + cw) / (2 * w), (h + ch) / (2 * h))))
                            }
                        },
                        onRotate = { update(state.copy(rotation = (state.rotation + 90) % 360)) },
                        onFlip = { update(state.copy(flipX = !state.flipX)) }
                    )
                    Tool.FILTER -> FilterPanel(state, ::update)
                    Tool.MARKUP -> MarkupPanel(markupColor, markupWidth, onColor = { markupColor = it }, onWidth = { markupWidth = it }, onUndo = {
                        if (markupStrokes.isNotEmpty()) markupStrokes.removeAt(markupStrokes.lastIndex)
                    })
                    Tool.TEXT -> TextPanel(
                        selected = textOverlays.find { it.id == selectedTextId },
                        onAdd = { showAddTextDialog = true },
                        onDelete = { id -> textOverlays.removeAll { it.id == id }; selectedTextId = null }
                    )
                    Tool.NONE -> Unit
                }
            }

            // Bottom tool icon row, matching the reference screenshot's layout
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)
            ) {
                Spacer(Modifier.width(4.dp))
                EditorToolIcon("Crop", Icons.Default.Crop, tool == Tool.CROP) { tool = if (tool == Tool.CROP) Tool.NONE else Tool.CROP }
                EditorToolIcon("Adjust", Icons.Default.Tune, tool == Tool.ADJUST) { tool = if (tool == Tool.ADJUST) Tool.NONE else Tool.ADJUST }
                EditorToolIcon("Filter", Icons.Default.FilterVintage, tool == Tool.FILTER) { tool = if (tool == Tool.FILTER) Tool.NONE else Tool.FILTER }
                EditorToolIcon("Markup", Icons.Default.Brush, tool == Tool.MARKUP) { tool = if (tool == Tool.MARKUP) Tool.NONE else Tool.MARKUP }
                EditorToolIcon("Text", Icons.Default.TextFields, tool == Tool.TEXT) { tool = if (tool == Tool.TEXT) Tool.NONE else Tool.TEXT }
                Spacer(Modifier.width(4.dp))
            }

            // Cancel / Save pill row, matching the reference screenshot
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { nav.popBackStack() },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Cancel") }
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        val finalBmp = bakeOverlays(rendered, markupStrokes, textOverlays)
                        saveBitmap(context, finalBmp)
                        finalBmp.recycle()
                        nav.popBackStack()
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9A441), contentColor = Color.Black)
                ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showAddTextDialog) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("Add text") },
            text = { OutlinedTextField(draft, { draft = it }, singleLine = true, placeholder = { Text("Type something…") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) {
                        textOverlays.add(TextOverlay(nextTextId++, draft.trim(), 0.35f, 0.4f, Color.White))
                    }
                    showAddTextDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ showAddTextDialog = false }) { Text("Cancel") } }
        )
    }
}

// The tool panel used to expand to fit all of its content (e.g. Adjust has 10 sliders), which
// could push the image preview and the Cancel/Save row off-screen entirely on smaller phones —
// exactly the "no preview while adjusting" bug. Capping its height and scrolling internally
// guarantees the preview above and the buttons below always stay visible.
@Composable
private fun AnimatedToolPanel(tool: Tool, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = tool != Tool.NONE,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
    ) {
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun EditorToolIcon(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFFD9A441) else Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (selected) Color.Black else Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AdjustPanel(state: ImageEditor.State, update: (ImageEditor.State) -> Unit) {
    Column(Modifier.padding(bottom = 4.dp)) {
        DarkAdjustment("Brightness", state.brightness, -1f, 1f) { update(state.copy(brightness = it)) }
        DarkAdjustment("Contrast", state.contrast, .2f, 2f) { update(state.copy(contrast = it)) }
        DarkAdjustment("Saturation", state.saturation, 0f, 2f) { update(state.copy(saturation = it)) }
        DarkAdjustment("Exposure", state.exposure, -1f, 1f) { update(state.copy(exposure = it)) }
        DarkAdjustment("Temperature", state.temperature, -1f, 1f) { update(state.copy(temperature = it)) }
        DarkAdjustment("Shadows", state.shadows, -1f, 1f) { update(state.copy(shadows = it)) }
        DarkAdjustment("Highlights", state.highlights, -1f, 1f) { update(state.copy(highlights = it)) }
        DarkAdjustment("Black point", state.blackPoint, -1f, 1f) { update(state.copy(blackPoint = it)) }
        DarkAdjustment("Sharpness", state.sharpness, 0f, 1f) { update(state.copy(sharpness = it)) }
        DarkAdjustment("Definition", state.definition, 0f, 1f) { update(state.copy(definition = it)) }
    }
}

@Composable
private fun CropPanel(onRatio: (Float?) -> Unit, onRotate: () -> Unit, onFlip: () -> Unit) {
    var selectedLabel by remember { mutableStateOf("Free") }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onRotate) { Icon(Icons.Default.RotateRight, "Rotate", tint = Color.White) }
            IconButton(onFlip) { Icon(Icons.Default.Flip, "Flip", tint = Color.White) }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf("Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
            options.forEach { (label, ratio) ->
                FilterChip(selected = selectedLabel == label, onClick = { selectedLabel = label; onRatio(ratio) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun FilterPanel(state: ImageEditor.State, update: (ImageEditor.State) -> Unit) {
    var selected by remember { mutableStateOf("Original") }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FILTER_PRESETS.forEach { preset ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                        .background(if (selected == preset.name) Color(0xFFD9A441) else Color.White.copy(alpha = 0.14f))
                        .clickable { selected = preset.name; update(preset.apply(state)) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.FilterVintage, preset.name, tint = if (selected == preset.name) Color.Black else Color.White) }
                Spacer(Modifier.height(2.dp))
                Text(preset.name, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MarkupPanel(color: Color, width: Float, onColor: (Color) -> Unit, onWidth: (Float) -> Unit, onUndo: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MARKUP_COLORS.forEach { c ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (c == color) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onColor(c) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(c))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onUndo) { Text("Undo stroke", color = Color.White) }
        }
        Spacer(Modifier.height(4.dp))
        Text("Brush size", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(value = width, onValueChange = onWidth, valueRange = 0.004f..0.05f)
    }
}

@Composable
private fun TextPanel(selected: TextOverlay?, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add text") }
        Text("Drag text on the photo to move it", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (selected != null) {
            IconButton({ onDelete(selected.id) }) { Icon(Icons.Default.Delete, "Delete text", tint = Color.White) }
        }
    }
}

@Composable
private fun DarkAdjustment(name: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

/**
 * Bakes markup strokes and text overlays into the final bitmap at save time. Strokes/positions are
 * normalised (0..1) against the bitmap's own size, so they survive any zoom, rotation or aspect ratio.
 */
private fun bakeOverlays(src: Bitmap, strokes: SnapshotStateList<MarkupStroke>, texts: SnapshotStateList<TextOverlay>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val minDim = minOf(out.width, out.height).toFloat()

    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = stroke.width * minDim
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        path.moveTo(stroke.points[0].x * out.width, stroke.points[0].y * out.height)
        for (i in 1 until stroke.points.size) path.lineTo(stroke.points[i].x * out.width, stroke.points[i].y * out.height)
        canvas.drawPath(path, paint)
    }

    texts.forEach { overlay ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.color.toArgb()
            textSize = out.height * 0.045f
            isFakeBoldText = true
        }
        canvas.drawText(overlay.text, overlay.x * out.width, overlay.y * out.height, paint)
    }
    return out
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

private fun saveBitmap(context: android.content.Context, bmp: Bitmap) {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
