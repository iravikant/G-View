package com.acoder.gallery.presentation.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.acoder.gallery.core.editor.ImageEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tool { ADJUST, CROP, ERASER }

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
    var tool by remember { mutableStateOf(Tool.ADJUST) }
    var brush by remember { mutableFloatStateOf(0.08f) }
    val eraserStrokes = remember { mutableStateListOf<Offset>() }
    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    BackHandler { nav.popBackStack() }
    val source = original
    if (source == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
                actions = {
                    IconButton({ showOriginal = !showOriginal }) {
                        Icon(if (showOriginal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Preview original")
                    }
                    IconButton(onClick = { if (historyIndex > 0) { historyIndex--; state = history[historyIndex] } }, enabled = historyIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = { if (historyIndex < history.lastIndex) { historyIndex++; state = history[historyIndex] } }, enabled = historyIndex < history.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            val finalBmp = applyEraser(rendered, eraserStrokes, brush)
                            saveBitmap(context, finalBmp)
                            finalBmp.recycle()
                            nav.popBackStack()
                        }
                    ) { Text("Save copy") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                ToolTab("Adjust", Icons.Filled.Tune, tool == Tool.ADJUST) { tool = Tool.ADJUST }
                ToolTab("Crop", Icons.Filled.Crop, tool == Tool.CROP) { tool = Tool.CROP }
                ToolTab("Rotate", Icons.Filled.RotateRight, false) { update(state.copy(rotation = (state.rotation + 90) % 360)) }
                ToolTab("Flip", Icons.Filled.Flip, false) { update(state.copy(flipX = !state.flipX)) }
                ToolTab("Erase", Icons.Outlined.AutoFixHigh, tool == Tool.ERASER) { tool = Tool.ERASER }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    ) { Text("Original", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
                }
                if (tool == Tool.ERASER && !showOriginal) {
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize().pointerInput(brush, rendered) {
                            detectDragGestures(
                                onDragStart = { p -> eraserStrokes.add(Offset(p.x / size.width, p.y / size.height)) },
                                onDrag = { c, _ -> eraserStrokes.add(Offset(c.position.x / size.width, c.position.y / size.height)) }
                            )
                        }
                    ) { }
                }
            }

            when (tool) {
                Tool.ADJUST -> AdjustPanel(state, ::update)
                Tool.CROP -> CropPanel { ratio ->
                    if (ratio == null) {
                        update(state.copy(crop = null))
                    } else {
                        val w = rendered.width.toFloat(); val h = rendered.height.toFloat()
                        val cw = minOf(w, h * ratio); val ch = cw / ratio
                        update(state.copy(crop = RectF((w - cw) / (2 * w), (h - ch) / (2 * h), (w + cw) / (2 * w), (h + ch) / (2 * h))))
                    }
                }
                Tool.ERASER -> EraserPanel(brush) { brush = it }
            }
        }
    }
}

@Composable
private fun AdjustPanel(state: ImageEditor.State, update: (ImageEditor.State) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Adjustment("Brightness", state.brightness, -1f, 1f) { update(state.copy(brightness = it)) }
        Adjustment("Contrast", state.contrast, .2f, 2f) { update(state.copy(contrast = it)) }
        Adjustment("Saturation", state.saturation, 0f, 2f) { update(state.copy(saturation = it)) }
        Adjustment("Exposure", state.exposure, -1f, 1f) { update(state.copy(exposure = it)) }
        Adjustment("Temperature", state.temperature, -1f, 1f) { update(state.copy(temperature = it)) }
        Adjustment("Shadows", state.shadows, -1f, 1f) { update(state.copy(shadows = it)) }
        Adjustment("Highlights", state.highlights, -1f, 1f) { update(state.copy(highlights = it)) }
        Adjustment("Black point", state.blackPoint, -1f, 1f) { update(state.copy(blackPoint = it)) }
        Adjustment("Sharpness", state.sharpness, 0f, 1f) { update(state.copy(sharpness = it)) }
        Adjustment("Definition", state.definition, 0f, 1f) { update(state.copy(definition = it)) }
    }
}

@Composable
private fun CropPanel(onPick: (Float?) -> Unit) {
    var selectedLabel by remember { mutableStateOf("Free") }
    Row(
        Modifier.fillMaxWidth().padding(12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val options = listOf("Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
        options.forEach { (label, ratio) ->
            FilterChip(selected = selectedLabel == label, onClick = { selectedLabel = label; onPick(ratio) }, label = { Text(label) })
        }
    }
}

@Composable
private fun EraserPanel(brush: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Brush size", style = MaterialTheme.typography.labelLarge)
        Slider(value = brush, onValueChange = onChange, valueRange = 0.02f..0.25f)
    }
}

@Composable
private fun Adjustment(name: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun RowScope.ToolTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) }
    )
}

/** [strokes] and [brush] are normalised (0..1) against the bitmap's own size, so erasing survives any zoom or aspect ratio. */
private fun applyEraser(src: Bitmap, strokes: SnapshotStateList<Offset>, brush: Float): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    if (strokes.isEmpty()) return out
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    val radius = brush * minOf(out.width, out.height) / 2f
    strokes.forEach { canvas.drawCircle(it.x * out.width, it.y * out.height, radius, paint) }
    return out
}

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
