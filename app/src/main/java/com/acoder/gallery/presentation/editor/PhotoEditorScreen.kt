@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.acoder.gallery.presentation.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.acoder.gallery.core.editor.ImageEditor
import com.acoder.gallery.presentation.common.AppSlider
import com.acoder.gallery.presentation.editor.ai.MagicEraser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import com.acoder.gallery.presentation.editor.ai.AiPhotoTools
import com.acoder.gallery.presentation.editor.ai.AiTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

// ───────────────────────── Theme ─────────────────────────
private val PINK = Color(0xFFFFB1C1)
private val DARK = Color(0xFF2A2224)
private val DARK_DISABLED = Color(0xFF1C1819)

// ───────────────────────── Models ─────────────────────────
private enum class Tab(val label: String) {
    AUTO("Auto"), CROP("Crop"), FILTERS("Filters"), ADJUST("Adjust"), ACTIONS("Actions"), MARKUP("Markup")
}

private enum class Mode { EDIT, ADJUST, ERASER }
private enum class MarkupMode { PEN, TEXT }
private enum class CropSub { CROP, RESIZE }

/**
 * One undo step. [pixels] is the photo as it looked after the last Magic Eraser run (null = the
 * untouched original), so undo / redo also covers erasing and never loses it.
 */
private data class Snap(
    val state: ImageEditor.State,
    val angle: Float,
    val crop: RectF?,
    val pixels: Bitmap? = null
)

private data class MarkupStroke(val color: Color, val width: Float, val points: List<Offset>)
private data class TextOverlay(
    val id: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val color: Color
)

private class PhotoFilterPreset(
    val name: String,
    val apply: (ImageEditor.State) -> ImageEditor.State
)

private class AutoPreset(val name: String, val apply: (ImageEditor.State) -> ImageEditor.State)

private class AdjustSpec(
    val label: String,
    val icon: ImageVector,
    val min: Float,
    val max: Float,
    val neutral: Float,
    val get: (ImageEditor.State) -> Float,
    val set: (ImageEditor.State, Float) -> ImageEditor.State
)

private class ActionSpec(val label: String, val icon: ImageVector, val onTap: (() -> Unit)?)

private val ADJUSTS = listOf(
    AdjustSpec(
        "Brightness",
        Icons.Default.Brightness6,
        -1f,
        1f,
        0f,
        { it.brightness },
        { s, v -> s.copy(brightness = v) }),
    AdjustSpec(
        "Contrast",
        Icons.Default.Contrast,
        .2f,
        2f,
        1f,
        { it.contrast },
        { s, v -> s.copy(contrast = v) }),
    AdjustSpec(
        "Saturation",
        Icons.Default.Palette,
        0f,
        2f,
        1f,
        { it.saturation },
        { s, v -> s.copy(saturation = v) }),
    AdjustSpec(
        "Exposure",
        Icons.Default.WbSunny,
        -1f,
        1f,
        0f,
        { it.exposure },
        { s, v -> s.copy(exposure = v) }),
    AdjustSpec(
        "Temperature",
        Icons.Default.Thermostat,
        -1f,
        1f,
        0f,
        { it.temperature },
        { s, v -> s.copy(temperature = v) }),
    AdjustSpec(
        "Shadows",
        Icons.Default.Gradient,
        -1f,
        1f,
        0f,
        { it.shadows },
        { s, v -> s.copy(shadows = v) }),
    AdjustSpec(
        "Highlights",
        Icons.Default.LightMode,
        -1f,
        1f,
        0f,
        { it.highlights },
        { s, v -> s.copy(highlights = v) }),
    AdjustSpec(
        "Black point",
        Icons.Default.DarkMode,
        -1f,
        1f,
        0f,
        { it.blackPoint },
        { s, v -> s.copy(blackPoint = v) }),
    AdjustSpec(
        "Sharpness",
        Icons.Default.Details,
        0f,
        1f,
        0f,
        { it.sharpness },
        { s, v -> s.copy(sharpness = v) }),
    AdjustSpec(
        "Definition",
        Icons.Default.Deblur,
        0f,
        1f,
        0f,
        { it.definition },
        { s, v -> s.copy(definition = v) })
)

private val AUTO_PRESETS = listOf(
    AutoPreset("Enhance") {
        it.copy(
            contrast = 1.08f,
            saturation = 1.12f,
            exposure = 0.05f,
            definition = 0.25f
        )
    },
    AutoPreset("Dynamic") {
        it.copy(
            contrast = 1.2f,
            saturation = 1.25f,
            shadows = 0.25f,
            highlights = -0.2f,
            definition = 0.3f
        )
    }
)

private val FILTER_PRESETS = listOf(
    PhotoFilterPreset("Original") {
        it.copy(
            brightness = 0f,
            contrast = 1f,
            saturation = 1f,
            temperature = 0f,
            exposure = 0f
        )
    },
    PhotoFilterPreset("Mono") { it.copy(saturation = 0f, contrast = 1.1f) },
    PhotoFilterPreset("Vivid") { it.copy(saturation = 1.45f, contrast = 1.15f) },
    PhotoFilterPreset("Warm") { it.copy(temperature = 0.35f, saturation = 1.1f) },
    PhotoFilterPreset("Cool") { it.copy(temperature = -0.35f) },
    PhotoFilterPreset("Fade") { it.copy(contrast = 0.85f, brightness = 0.06f, saturation = 0.8f) },
    PhotoFilterPreset("Noir") { it.copy(saturation = 0f, contrast = 1.3f, brightness = -0.05f) }
)

private val ASPECTS = listOf(
    "Free" to null,
    "Original" to 0f,
    "1:1" to 1f,
    "4:3" to 4f / 3f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f
)
private val MARKUP_COLORS = listOf(
    Color.Red,
    Color(0xFFFFC107),
    Color(0xFF2196F3),
    Color(0xFF4CAF50),
    Color.White,
    Color.Black
)

// ───────────────────────── Screen ─────────────────────────
@Composable
fun PhotoEditorScreen(uri: Uri, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // `original` is the decoded photo and is never modified. `base` is the working pixels: the original
    // until a Magic Eraser result is applied (then that bitmap). Undo / redo switches between them.
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val decoded = runCatching {
            withContext(Dispatchers.IO) {
                decodeSampled(
                    context,
                    uri,
                    4096
                )
            }
        }.getOrNull()
        if (decoded == null) {
            loadFailed = true
        } else {
            original = decoded
            base = decoded
        }
    }

    var state by remember { mutableStateOf(ImageEditor.State()) }
    var straighten by remember { mutableFloatStateOf(0f) }   // degrees, -45..45
    var showStraighten by remember { mutableStateOf(true) }
    var cropRect by remember { mutableStateOf<RectF?>(null) }      // normalised to the straightened image
    var draftCrop by remember { mutableStateOf<RectF?>(null) }     // live rect while dragging handles
    var cropLock by remember { mutableStateOf<Float?>(null) }      // locked w/h pixel ratio, null = free
    var aspectLabel by remember { mutableStateOf("Free") }
    var cropSub by remember { mutableStateOf(CropSub.CROP) }
    var resize by remember { mutableStateOf(ResizeSettings()) }
    var originalBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(uri) {
        originalBytes = withContext(Dispatchers.IO) { queryFileSize(context, uri) }
    }
    val history = remember { mutableStateListOf(Snap(state, 0f, null)) }
    var historyIndex by remember { mutableIntStateOf(0) }

    var mode by remember { mutableStateOf(Mode.EDIT) }
    var tab by remember { mutableStateOf(Tab.AUTO) }
    var adjustIndex by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var selectedAuto by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf("Original") }
    // AI tools
    var aiTool by remember { mutableStateOf<AiTool?>(null) }
    var aiProgress by remember { mutableFloatStateOf(0f) }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    // Markup + text
    val markupStrokes = remember { mutableStateListOf<MarkupStroke>() }
    var currentStroke by remember { mutableStateOf<List<Offset>?>(null) }
    var markupColor by remember { mutableStateOf(Color.Red) }
    var markupWidth by remember { mutableFloatStateOf(0.01f) }
    var markupMode by remember { mutableStateOf(MarkupMode.PEN) }
    val textOverlays = remember { mutableStateListOf<TextOverlay>() }
    var nextTextId by remember { mutableIntStateOf(0) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var selectedTextId by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        if (mode != Mode.EDIT) mode = Mode.EDIT else nav.popBackStack()
    }

    if (loadFailed) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "This photo could not be opened",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { nav.popBackStack() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PINK,
                    contentColor = Color.Black
                )
            ) { Text("Back") }
        }
        return
    }

    val source = base
    val untouched = original
    if (source == null || untouched == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            Alignment.Center
        ) { CircularProgressIndicator(color = Color.White) }
        return
    }

    // Edit on a downscaled preview so sliders stay smooth; full-res is rendered only on save.
    val preview = remember(source) { downscale(source, 1440) }
    // Pipeline: tone/rotate/flip -> straighten -> free crop. The crop tab shows the image *before* cropping.
    val baseRendered = remember(
        preview,
        state,
        straighten
    ) { straightenBitmap(ImageEditor(preview).render(state.copy(crop = null)), straighten) }
    val rendered = remember(baseRendered, cropRect) { cropBitmap(baseRendered, cropRect) }
    //  val displayBmp = if (showOriginal) preview else if (tab == Tab.CROP) baseRendered else rendered
    // In Crop mode show the uncropped photo (the crop box sits on it); in Resize mode show the final result.
    val displayBmp =
        if (showOriginal) preview
        else if (tab == Tab.CROP && cropSub == CropSub.CROP) baseRendered
        else rendered

    // Size of the photo after rotate / straighten / crop: this is what Resize works from.
    val (curW, curH) = editedSize(
        source.width,
        source.height,
        state.rotation.toInt(),
        straighten,
        cropRect
    )
    LaunchedEffect(curW, curH) {
        // A size chosen for a different shape would stretch the photo, so start over if the shape changed.
        if (resize.widthPx > 0 && resize.heightPx > 0 &&
            abs(resize.widthPx.toFloat() / resize.heightPx - curW.toFloat() / curH) > 0.02f
        ) resize = resize.copy(widthPx = 0, heightPx = 0)
    }
    fun push(next: ImageEditor.State = state, angle: Float = straighten, crop: RectF? = cropRect) {
        state = next; straighten = angle; cropRect = crop
        val entry = Snap(next, angle, crop, if (base === untouched) null else base)
        if (entry == history[historyIndex]) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(entry)
        historyIndex = history.lastIndex
    }

    fun restore(i: Int) {
        historyIndex = i
        val sn = history[i]
        state = sn.state; straighten = sn.angle; cropRect = sn.crop; draftCrop = null
        base = sn.pixels ?: untouched
    }

    fun commitCrop(r: RectF) {
        val full = r.left <= 0.002f && r.top <= 0.002f && r.right >= 0.998f && r.bottom >= 0.998f
        draftCrop = null
        push(state, straighten, if (full) null else r)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** Runs an AI tool on the working photo. The result becomes the new working photo (one undo step). */
    fun runAi(tool: AiTool) {
        if (aiJob != null) return
        if (!AiPhotoTools.isAvailable(context, tool)) {
            toast("${tool.label} needs the model file assets/${tool.asset}")
            return
        }
        aiTool = tool
        aiProgress = 0f
        aiJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    AiPhotoTools.run(context, tool, source) { aiProgress = it }
                }
                base = result
                push()               // records the new pixels as an undo step, like Magic Eraser
                toast("${tool.label} done")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(e.message ?: "${tool.label} failed")
            } finally {
                aiJob = null
                aiTool = null
            }
        }
    }

    fun resetAll() {
        state = ImageEditor.State(); straighten = 0f
        cropRect = null; draftCrop = null; cropLock = null; aspectLabel = "Free"
        base = untouched
        history.clear(); history.add(Snap(state, 0f, null)); historyIndex = 0
        /* markupStrokes.clear(); textOverlays.clear()
         selectedAuto = null; selectedFilter = "Original"*/
        markupStrokes.clear(); textOverlays.clear()
        selectedAuto = null; selectedFilter = "Original"
        resize = ResizeSettings(); cropSub = CropSub.CROP
    }

    fun applyRatio(label: String, r: Float?) {
        aspectLabel = label
        if (r == null) {
            cropLock = null; return
        }
        val bw = baseRendered.width.toFloat();
        val bh = baseRendered.height.toFloat()
        val ratio = if (r == 0f) bw / bh else r
        val cw = minOf(bw, bh * ratio);
        val ch = cw / ratio
        cropLock = ratio
        commitCrop(
            RectF(
                (bw - cw) / (2 * bw),
                (bh - ch) / (2 * bh),
                (bw + cw) / (2 * bw),
                (bh + ch) / (2 * bh)
            )
        )
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val strokes = markupStrokes.toList();
            val texts = textOverlays.toList();
            val st = state;
            val ang = straighten;
            val crp = cropRect
            val rs = resize
            var info: String? = null
            val saved: Uri? = try {
                withContext(Dispatchers.Default) {
                    val full = cropBitmap(
                        straightenBitmap(
                            ImageEditor(source).render(st.copy(crop = null)),
                            ang
                        ), crp
                    )
                    val out = bakeOverlays(full, strokes, texts)
                    val u: Uri? = if (rs.enabled) {
                        // Resize + chosen format / quality
                        val (tw, th) = rs.target(out.width, out.height)
                        val scaled = resampleHighQuality(out, tw, th)
                        val result = saveBitmapAs(context, scaled, rs.format, rs.quality)
                        if (scaled !== out) scaled.recycle()
                        if (result != null) info =
                            "Saved ${result.width} × ${result.height} · ${fmtBytes(result.bytes)}"
                        result?.uri
                    } else {
                        saveBitmap(context, out)
                    }
                    out.recycle()
                    u
                }
            } catch (e: Throwable) {
                null
            }
            saving = false
            if (saved == null) {
                toast("Could not save the photo")
                return@launch
            }
            info?.let { toast(it) }
        }
    }
    /*  fun save() {
          if (saving) return
          saving = true
          scope.launch {
              val strokes = markupStrokes.toList(); val texts = textOverlays.toList(); val st = state; val ang = straighten; val crp = cropRect
              val saved: Uri? = try {
                  withContext(Dispatchers.Default) {
                      val full = cropBitmap(straightenBitmap(ImageEditor(source).render(st.copy(crop = null)), ang), crp)
                      val out = bakeOverlays(full, strokes, texts)
                      val u = saveBitmap(context, out)
                      out.recycle()
                      u
                  }
              } catch (e: Throwable) {
                  null
              }
              saving = false
              if (saved == null) {
                  toast("Could not save the photo")
                  return@launch
              }
              // Tell the screen we came from which photo was created, then go back to it.
              nav.previousBackStackEntry?.savedStateHandle?.set(EDITOR_RESULT_KEY, saved.toString())
              nav.popBackStack()
          }
      }*/


    val actions = listOf(
        ActionSpec("Magic Eraser", Icons.Default.AutoFixHigh) { mode = Mode.ERASER },
        ActionSpec("Unblur", Icons.Default.Deblur) { runAi(AiTool.UNBLUR) },
        ActionSpec("Touch Up", Icons.Default.Face, null),
        ActionSpec("Move", Icons.Default.OpenWith, null),
        ActionSpec("Portrait Blur", Icons.Default.BlurOn) { runAi(AiTool.PORTRAIT) },
        ActionSpec("Upscale", Icons.Default.ZoomOutMap) { runAi(AiTool.UPSCALE) },
        ActionSpec("Remove BG", Icons.Default.Wallpaper) { runAi(AiTool.BACKGROUND) },
        ActionSpec("Pop", Icons.Default.AutoAwesome) {
            push(
                state.copy(
                    contrast = (state.contrast * 1.1f).coerceAtMost(2f),
                    saturation = (state.saturation + 0.15f).coerceAtMost(2f)
                )
            )
        },
        ActionSpec(
            "Sharpen",
            Icons.Default.Details
        ) { push(state.copy(sharpness = (state.sharpness + 0.3f).coerceAtMost(1f))) },
        ActionSpec("Denoise", Icons.Default.Grain) { runAi(AiTool.DENOISE) }
    )

    // ─────────── Magic eraser mode (AI inpainting, see MagicEraserScreen.kt) ───────────
    if (mode == Mode.ERASER) {
        MagicEraser(
            source = source,
            classicFallback = { bmp, selection, brush -> inpaint(bmp, selection, brush) },
            onCancel = { mode = Mode.EDIT },
            onApply = { result ->
                base = result
                push()          // records the new pixels as an undo step
                mode = Mode.EDIT
            }
        )
        return
    }

    // ─────────── Adjust detail mode (ruler slider) ───────────
    if (mode == Mode.ADJUST) {
        val spec = ADJUSTS[adjustIndex]
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            ImagePreview(
                displayBmp,
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 16.dp)
            ) { _, _ -> }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ADJUSTS.forEachIndexed { i, s ->
                    Text(
                        s.label,
                        color = if (i == adjustIndex) PINK else Color.White.copy(alpha = 0.6f),
                        fontWeight = if (i == adjustIndex) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { adjustIndex = i }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            RulerSlider(
                value = (spec.get(state) - spec.neutral) * 100f,
                min = (spec.min - spec.neutral) * 100f,
                max = (spec.max - spec.neutral) * 100f,
                onChange = { d -> state = spec.set(state, spec.neutral + d / 100f) },
                onFinished = { push(state) },
                onReset = { push(spec.set(state, spec.neutral)) }
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp), Alignment.Center
            ) {
                PillButton({ mode = Mode.EDIT }, Modifier.width(150.dp), PINK) {
                    Text(
                        "Done",
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        return
    }

    // ─────────── Main edit mode ───────────
    val dirty =
        historyIndex > 0 || markupStrokes.isNotEmpty() || textOverlays.isNotEmpty() || base !== untouched || resize.enabled
    val density = LocalDensity.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ nav.popBackStack() }) {
                Icon(
                    Icons.Default.Close,
                    "Close",
                    tint = Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { if (historyIndex > 0) restore(historyIndex - 1) },
                enabled = historyIndex > 0
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    tint = Color.White.copy(alpha = if (historyIndex > 0) 1f else 0.35f)
                )
            }
            IconButton(
                onClick = { if (historyIndex < history.lastIndex) restore(historyIndex + 1) },
                enabled = historyIndex < history.lastIndex
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    "Redo",
                    tint = Color.White.copy(alpha = if (historyIndex < history.lastIndex) 1f else 0.35f)
                )
            }
            if (dirty) {
                Button(
                    onClick = ::save, enabled = !saving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PINK,
                        contentColor = Color.Black
                    )
                ) { Text(if (saving) "Saving…" else "Save", fontWeight = FontWeight.SemiBold) }
            }
            Box {
                IconButton({ menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        "More",
                        tint = Color.White
                    )
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset all edits") },
                        onClick = { menuOpen = false; resetAll() })
                }
            }
        }

        // Crop controls row (aspect ratio / flip / rotate)
        if (tab == Tab.CROP && cropSub == CropSub.CROP) {
            var aspectOpen by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    Icons.Default.Straighten,
                    "Straighten",
                    selected = showStraighten
                ) { showStraighten = !showStraighten }
                Spacer(Modifier.weight(1f))
                Box {
                    Row(
                        Modifier
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(DARK)
                            .clickable { aspectOpen = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White)
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
                    }
                    DropdownMenu(aspectOpen, { aspectOpen = false }) {
                        ASPECTS.forEach { (label, ratio) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = {
                                    if (aspectLabel == label) Icon(
                                        Icons.Default.Check,
                                        null
                                    )
                                },
                                onClick = { aspectOpen = false; applyRatio(label, ratio) }
                            )
                        }
                    }
                }
                CircleIconButton(
                    Icons.Default.Flip,
                    "Flip"
                ) { push(state.copy(flipX = !state.flipX), straighten, null) }
                CircleIconButton(
                    Icons.Default.RotateRight,
                    "Rotate"
                ) { push(state.copy(rotation = (state.rotation + 90) % 360), straighten, null) }
            }
        }

        // Preview (press & hold to compare with the original)
        val hold = if (tab != Tab.MARKUP && tab != Tab.CROP) Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                showOriginal = true; tryAwaitRelease(); showOriginal = false
            })
        } else Modifier

        ImagePreview(
            displayBmp, Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(hold)
        ) { wPx, hPx ->
            if (tab == Tab.CROP && cropSub == CropSub.CROP && !showOriginal) {
                CropOverlay(
                    rect = draftCrop ?: cropRect ?: RectF(0f, 0f, 1f, 1f),
                    lockRatio = cropLock,
                    imgW = baseRendered.width, imgH = baseRendered.height,
                    onChange = { draftCrop = it },
                    onFinished = { draftCrop?.let { commitCrop(it) } }
                )
            }
            if (showOriginal) {
                Surface(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        "Original",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (tab == Tab.MARKUP && markupMode == MarkupMode.PEN && !showOriginal) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(markupColor, markupWidth) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    currentStroke =
                                        listOf(Offset(p.x / size.width, p.y / size.height))
                                },
                                onDrag = { c, _ ->
                                    currentStroke = (currentStroke ?: emptyList()) + Offset(
                                        c.position.x / size.width,
                                        c.position.y / size.height
                                    )
                                },
                                onDragEnd = {
                                    currentStroke?.let {
                                        if (it.size > 1) markupStrokes.add(
                                            MarkupStroke(markupColor, markupWidth, it)
                                        )
                                    }
                                    currentStroke = null
                                }
                            )
                        }
                ) {
                    val all = markupStrokes.toList() + (currentStroke?.let {
                        listOf(
                            MarkupStroke(
                                markupColor,
                                markupWidth,
                                it
                            )
                        )
                    } ?: emptyList())
                    all.forEach { s ->
                        if (s.points.size > 1) {
                            val path = Path().apply {
                                moveTo(s.points[0].x * size.width, s.points[0].y * size.height)
                                for (i in 1 until s.points.size) lineTo(
                                    s.points[i].x * size.width,
                                    s.points[i].y * size.height
                                )
                            }
                            drawPath(
                                path,
                                s.color,
                                style = Stroke(
                                    s.width * minOf(size.width, size.height),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }
            if (!showOriginal) {
                val textSize = with(density) { (hPx * 0.045f).toSp() }
                val editingText = tab == Tab.MARKUP && markupMode == MarkupMode.TEXT
                textOverlays.forEach { overlay ->
                    Text(
                        overlay.text,
                        color = overlay.color,
                        fontSize = textSize,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(
                                    (wPx * overlay.x).roundToInt(),
                                    (hPx * overlay.y).roundToInt()
                                )
                            }
                            .then(
                                if (editingText) Modifier
                                    .then(
                                        if (selectedTextId == overlay.id) Modifier.border(
                                            1.dp,
                                            PINK
                                        ) else Modifier
                                    )
                                    .clickable {
                                        selectedTextId =
                                            if (selectedTextId == overlay.id) null else overlay.id
                                    }
                                    .pointerInput(overlay.id, wPx, hPx) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            val idx =
                                                textOverlays.indexOfFirst { it.id == overlay.id }
                                            if (idx >= 0) {
                                                val cur = textOverlays[idx]
                                                textOverlays[idx] = cur.copy(
                                                    x = (cur.x + drag.x / wPx).coerceIn(0f, 1f),
                                                    y = (cur.y + drag.y / hPx).coerceIn(0f, 1f)
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

        // Contextual panel (fixed height so the preview never jumps)
        val resizing = tab == Tab.CROP && cropSub == CropSub.RESIZE
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (resizing) 330.dp else 160.dp), Alignment.Center
        ) {
            when (tab) {
                Tab.AUTO -> {
                    val thumb = remember(preview) { downscale(preview, 240) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AUTO_PRESETS.forEach { p ->
                            val t =
                                remember(thumb) { ImageEditor(thumb).render(p.apply(ImageEditor.State())) }
                            AutoCard(p.name, t, selectedAuto == p.name, true) {
                                if (selectedAuto == p.name) {
                                    selectedAuto = null; push(
                                        state.copy(
                                            contrast = 1f,
                                            saturation = 1f,
                                            exposure = 0f,
                                            shadows = 0f,
                                            highlights = 0f,
                                            definition = 0f
                                        )
                                    )
                                } else {
                                    selectedAuto = p.name; push(p.apply(state))
                                }
                            }
                        }
                        AutoCard("AI enhance", thumb, false, false) { runAi(AiTool.ENHANCE) }
                    }
                }

                Tab.CROP -> Column(Modifier.fillMaxSize()) {
                    CropResizeSwitch(cropSub) { cropSub = it }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(), Alignment.Center
                    ) {
                        if (cropSub == CropSub.RESIZE) {
                            ResizePanel(
                                settings = resize,
                                onChange = { resize = it },
                                currentW = curW,
                                currentH = curH,
                                originalBytes = originalBytes,
                                sample = rendered
                            )
                        } else if (showStraighten) {
                            RulerSlider(
                                value = straighten,
                                min = -45f,
                                max = 45f,
                                unitDp = 10f,
                                suffix = "°",
                                onChange = { straighten = it; cropRect = null; draftCrop = null },
                                onFinished = { push(state, straighten, null) },
                                onReset = { push(state, 0f, null) }
                            )
                        } else {
                            Text(
                                "Pick an aspect ratio, rotate or flip",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Tab.FILTERS -> {
                    val thumb = remember(preview) { downscale(preview, 200) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FILTER_PRESETS.forEach { preset ->
                            val t = remember(
                                thumb,
                                preset
                            ) { ImageEditor(thumb).render(preset.apply(ImageEditor.State())) }
                            FilterThumb(preset.name, t, selectedFilter == preset.name) {
                                selectedFilter = preset.name; push(preset.apply(state))
                            }
                        }
                    }
                }

                Tab.ADJUST -> ToolTileRow(ADJUSTS.mapIndexed { i, s ->
                    ActionSpec(
                        s.label,
                        s.icon
                    ) { adjustIndex = i; mode = Mode.ADJUST }
                }) { toast("$it is coming soon") }

                Tab.ACTIONS -> ToolTileRow(actions) { toast("$it is coming soon") }
                Tab.MARKUP -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            markupMode == MarkupMode.PEN,
                            { markupMode = MarkupMode.PEN },
                            { Text("Pen") })
                        FilterChip(
                            markupMode == MarkupMode.TEXT,
                            { markupMode = MarkupMode.TEXT },
                            { Text("Text") })
                    }
                    if (markupMode == MarkupMode.PEN) {
                        MarkupPanel(
                            markupColor,
                            markupWidth,
                            { markupColor = it },
                            { markupWidth = it }) {
                            if (markupStrokes.isNotEmpty()) markupStrokes.removeAt(markupStrokes.lastIndex)
                        }
                    } else {
                        TextPanel(
                            selected = textOverlays.find { it.id == selectedTextId },
                            onAdd = { showAddTextDialog = true },
                            onDelete = { id ->
                                textOverlays.removeAll { it.id == id }; selectedTextId = null
                            }
                        )
                    }
                }
            }
        }

        // Bottom tab row
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Tab.values().forEach { t ->
                Box(
                    Modifier
                        .clip(CircleShape)
                        .then(
                            if (tab == t) Modifier.border(
                                BorderStroke(1.5.dp, Color.White),
                                CircleShape
                            ) else Modifier
                        )
                        .clickable { tab = t }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        t.label,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (showAddTextDialog) {
        aiTool?.let { t ->
            AlertDialog(
                onDismissRequest = { },
                containerColor = DARK,
                title = { Text("${t.label}…", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { aiProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = PINK,
                            trackColor = Color(0x33FFFFFF)
                        )
                        Text(
                            "${(aiProgress * 100).roundToInt()}% · runs on your phone",
                            color = Color(0xFF9A9099),
                            fontSize = 13.sp
                        )
                    }
                },
                confirmButton = { TextButton({ aiJob?.cancel() }) { Text("Cancel") } }
            )
        }
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    singleLine = true,
                    placeholder = { Text("Type something…") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) textOverlays.add(
                        TextOverlay(
                            nextTextId++,
                            draft.trim(),
                            0.35f,
                            0.4f,
                            Color.White
                        )
                    )
                    showAddTextDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ showAddTextDialog = false }) { Text("Cancel") } }
        )
    }
}

// ───────────────────────── Reusable UI ─────────────────────────

/** Shows [bmp] letterboxed and gives [overlay] a box that matches the displayed image exactly (so strokes/text line up). */
@Composable
private fun ImagePreview(
    bmp: Bitmap,
    modifier: Modifier,
    overlay: @Composable BoxScope.(wPx: Float, hPx: Float) -> Unit
) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val bw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val bh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(bw / bmp.width, bh / bmp.height)
        val wPx = bmp.width * scale
        val hPx = bmp.height * scale
        val d = LocalDensity.current
        Box(Modifier.size(with(d) { wPx.toDp() }, with(d) { hPx.toDp() })) {
            if (bmp.hasAlpha()) {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = 14.dp.toPx()
                    val cols = ceil(size.width / cell).toInt()
                    val rows = ceil(size.height / cell).toInt()
                    for (r in 0 until rows) for (c in 0 until cols) {
                        drawRect(
                            if ((r + c) % 2 == 0) Color(0xFF4A4A4A) else Color(0xFF353535),
                            Offset(c * cell, r * cell), Size(cell, cell)
                        )
                    }
                }
            }
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            overlay(wPx, hPx)
        }
    }
}

private const val HANDLE_L = 1
private const val HANDLE_T = 2
private const val HANDLE_R = 4
private const val HANDLE_B = 8
private const val HANDLE_MOVE = 16

private class RectHolder(var r: RectF)

/** Free-size crop box: drag corners/edges to resize, drag inside to move. [lockRatio] (pixel w/h) keeps an aspect ratio. */
@Composable
private fun CropOverlay(
    rect: RectF, lockRatio: Float?, imgW: Int, imgH: Int,
    onChange: (RectF) -> Unit, onFinished: () -> Unit
) {
    val shownRect by rememberUpdatedState(rect)
    val lock by rememberUpdatedState(lockRatio)
    val notify by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val holder = remember { RectHolder(rect) }
    var grab by remember { mutableIntStateOf(0) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imgW, imgH) {
                detectDragGestures(
                    onDragStart = { p ->
                        holder.r = RectF(shownRect)
                        val w = size.width.toFloat();
                        val h = size.height.toFloat()
                        val l = holder.r.left * w;
                        val t = holder.r.top * h
                        val rt = holder.r.right * w;
                        val b = holder.r.bottom * h
                        val slop = 32.dp.toPx()
                        val inX = p.x in (l - slop)..(rt + slop)
                        val inY = p.y in (t - slop)..(b + slop)
                        var g = 0
                        if (abs(p.x - l) < slop && inY) g = g or HANDLE_L
                        if (abs(p.x - rt) < slop && inY) g = g or HANDLE_R
                        if (abs(p.y - t) < slop && inX) g = g or HANDLE_T
                        if (abs(p.y - b) < slop && inX) g = g or HANDLE_B
                        if (g == 0 && p.x in l..rt && p.y in t..b) g = HANDLE_MOVE
                        grab = g
                    },
                    onDragEnd = { if (grab != 0) finish(); grab = 0 },
                    onDragCancel = { if (grab != 0) finish(); grab = 0 }
                ) { change, drag ->
                    change.consume()
                    if (grab != 0) {
                        holder.r = dragCrop(
                            holder.r,
                            grab,
                            drag.x / size.width,
                            drag.y / size.height,
                            lock,
                            imgW.toFloat(),
                            imgH.toFloat()
                        )
                        notify(holder.r)
                    }
                }
            }
    ) {
        val w = size.width;
        val h = size.height
        val l = rect.left * w;
        val t = rect.top * h;
        val rt = rect.right * w;
        val b = rect.bottom * h
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset(0f, 0f), Size(w, t))
        drawRect(dim, Offset(0f, b), Size(w, h - b))
        drawRect(dim, Offset(0f, t), Size(l, b - t))
        drawRect(dim, Offset(rt, t), Size(w - rt, b - t))
        val grid = Color.White.copy(alpha = 0.35f);
        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val gx = l + (rt - l) * i / 3f;
            val gy = t + (b - t) * i / 3f
            drawLine(grid, Offset(gx, t), Offset(gx, b), thin)
            drawLine(grid, Offset(l, gy), Offset(rt, gy), thin)
        }
        drawRect(
            Color.White.copy(alpha = 0.9f),
            Offset(l, t),
            Size(rt - l, b - t),
            style = Stroke(thin)
        )
        val len = 22.dp.toPx();
        val sw = 4.dp.toPx()
        fun ln(a: Offset, c: Offset) = drawLine(Color.White, a, c, sw, StrokeCap.Square)
        ln(Offset(l, t), Offset(l + len, t)); ln(Offset(l, t), Offset(l, t + len))
        ln(Offset(rt, t), Offset(rt - len, t)); ln(Offset(rt, t), Offset(rt, t + len))
        ln(Offset(l, b), Offset(l + len, b)); ln(Offset(l, b), Offset(l, b - len))
        ln(Offset(rt, b), Offset(rt - len, b)); ln(Offset(rt, b), Offset(rt, b - len))
    }
}

private fun dragCrop(
    cur: RectF,
    grab: Int,
    dx: Float,
    dy: Float,
    lock: Float?,
    bw: Float,
    bh: Float
): RectF {
    val minS = 0.1f
    var l = cur.left;
    var t = cur.top;
    var r = cur.right;
    var b = cur.bottom
    if (grab == HANDLE_MOVE) {
        val w = r - l;
        val h = b - t
        l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h)
        return RectF(l, t, l + w, t + h)
    }
    if ((grab and HANDLE_L) != 0) l = (l + dx).coerceIn(0f, r - minS)
    if ((grab and HANDLE_R) != 0) r = (r + dx).coerceIn(l + minS, 1f)
    if ((grab and HANDLE_T) != 0) t = (t + dy).coerceIn(0f, b - minS)
    if ((grab and HANDLE_B) != 0) b = (b + dy).coerceIn(t + minS, 1f)
    if (lock != null) {
        val horiz = (grab and (HANDLE_L or HANDLE_R)) != 0
        val vert = (grab and (HANDLE_T or HANDLE_B)) != 0
        if (horiz && !vert) {
            val nh = (r - l) * bw / lock / bh;
            val cy = (t + b) / 2f
            t = cy - nh / 2f; b = cy + nh / 2f
        } else if (vert && !horiz) {
            val nw = (b - t) * bh * lock / bw;
            val cx = (l + r) / 2f
            l = cx - nw / 2f; r = cx + nw / 2f
        } else {
            val nh = (r - l) * bw / lock / bh
            if ((grab and HANDLE_T) != 0) t = b - nh else b = t + nh
        }
        if (l < 0f || t < 0f || r > 1f || b > 1f) return cur   // would leave the image: stop here
    }
    return RectF(l, t, r, b)
}

private fun cropBitmap(src: Bitmap, r: RectF?): Bitmap {
    if (r == null) return src
    val x = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
    val y = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
    val w = ((r.right - r.left) * src.width).roundToInt().coerceIn(1, src.width - x)
    val h = ((r.bottom - r.top) * src.height).roundToInt().coerceIn(1, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = DARK,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(if (enabled) container else DARK_DISABLED)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    desc: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) PINK else DARK)
            .clickable(onClick = onClick), Alignment.Center
    ) {
        Icon(icon, desc, tint = if (selected) Color.Black else Color.White)
    }
}

/** Two pills at the top of the Crop tab panel: Crop | Resize. */
@Composable
private fun CropResizeSwitch(selected: CropSub, onSelect: (CropSub) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CropSub.entries.forEach { s ->
            val sel = s == selected
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (sel) PINK else DARK)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (s == CropSub.CROP) Icons.Default.Crop else Icons.Default.PhotoSizeSelectLarge,
                    null,
                    tint = if (sel) Color.Black else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (s == CropSub.CROP) "Crop" else "Resize",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** Label above a dark rounded tile, like the Actions / Adjust rows in the reference. */
@Composable
private fun ToolTileRow(items: List<ActionSpec>, onUnavailable: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { item ->
            Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    item.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(DARK)
                        .clickable { item.onTap?.invoke() ?: onUnavailable(item.label) },
                    Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        item.label,
                        tint = Color.White.copy(alpha = if (item.onTap != null) 1f else 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoCard(
    label: String,
    thumb: Bitmap,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val img = remember(thumb) { thumb.asImageBitmap() }
    Box(
        Modifier
            .width(128.dp)
            .height(88.dp)
            .clip(RoundedCornerShape(22.dp))
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    PINK,
                    RoundedCornerShape(22.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        Image(
            img,
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (enabled) 1f else 0.6f
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(38.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            if (enabled) Color(0xCC2A2224) else Color(0xCCB0505F)
                        )
                    )
                )
        )
        if (!enabled) Icon(
            Icons.Default.AutoAwesome,
            null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .size(22.dp)
        )
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun FilterThumb(label: String, thumb: Bitmap, selected: Boolean, onClick: () -> Unit) {
    val img = remember(thumb) { thumb.asImageBitmap() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            img, label,
            Modifier
                .size(width = 76.dp, height = 96.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (selected) Modifier.border(
                        2.dp,
                        PINK,
                        RoundedCornerShape(14.dp)
                    ) else Modifier
                )
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (selected) PINK else Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Scrolling ruler slider (value pill + tick strip + reset). [value]/[min]/[max] are in display units (e.g. -100..100). */
@Composable
private fun RulerSlider(
    value: Float, min: Float, max: Float,
    onChange: (Float) -> Unit, onFinished: () -> Unit, onReset: () -> Unit,
    unitDp: Float = 6f, suffix: String = ""
) {
    val unitPx = with(LocalDensity.current) { unitDp.dp.toPx() }
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .clip(CircleShape)
            .background(DARK),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${value.roundToInt()}$suffix",
            color = PINK,
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            modifier = Modifier.width(64.dp)
        )
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(min, max) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = cur },
                        onDragEnd = { finished() },
                        onDragCancel = { finished() }) { c, dx ->
                        c.consume()
                        acc = (acc - dx / unitPx).coerceIn(min, max)
                        change(acc)
                    }
                }
        ) {
            val cx = size.width / 2f
            val half = cx / unitPx
            val from = floor(value - half).toInt().coerceAtLeast(min.roundToInt())
            val to = ceil(value + half).toInt().coerceAtMost(max.roundToInt())
            for (u in from..to) {
                val x = cx + (u - value) * unitPx
                val major = u % 10 == 0
                val hgt = size.height * if (major) 0.5f else 0.25f
                drawLine(
                    Color.White.copy(alpha = if (major) 0.8f else 0.35f),
                    Offset(x, (size.height - hgt) / 2f),
                    Offset(x, (size.height + hgt) / 2f),
                    1.5.dp.toPx()
                )
            }
            drawLine(PINK, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx())
        }
        IconButton(onReset, Modifier.padding(end = 8.dp)) {
            Icon(
                Icons.Default.History,
                "Reset",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun MarkupPanel(
    color: Color,
    width: Float,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MARKUP_COLORS.forEach { c ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (c == color) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onColor(c) },
                    Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onUndo) { Text("Undo stroke", color = Color.White) }
        }
        AppSlider(width, onWidth, 0.004f..0.05f)
    }
}

@Composable
private fun TextPanel(selected: TextOverlay?, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(onAdd) {
            Icon(
                Icons.Default.Add,
                null
            ); Spacer(Modifier.width(6.dp)); Text("Add text")
        }
        Text(
            "Drag text on the photo to move it",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        if (selected != null) IconButton({ onDelete(selected.id) }) {
            Icon(
                Icons.Default.Delete,
                "Delete text",
                tint = Color.White
            )
        }
    }
}

// ───────────────────────── Image helpers ─────────────────────────

/** Decodes [uri] to at most [maxSide] px on its longer side and applies its EXIF orientation. */
private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)
        ?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = context.contentResolver.openInputStream(uri)
        ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f); m.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f); m.postScale(-1f, 1f)
        }

        else -> return raw
    }
    val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (turned !== raw) raw.recycle()
    return turned
}

private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
    val m = maxOf(src.width, src.height)
    if (m <= maxSide) return src
    val s = maxSide.toFloat() / m
    return Bitmap.createScaledBitmap(
        src,
        (src.width * s).roundToInt().coerceAtLeast(1),
        (src.height * s).roundToInt().coerceAtLeast(1),
        true
    )
}

// ───────────────────────── Classic (non-AI) fallback eraser ─────────────────────────
// Used ONLY when the AI model file is missing or cannot be loaded (see MagicEraser in MagicEraserScreen.kt).

/**
 * Patch-based (exemplar) inpainting.
 * 1. Works on a region around the selection (with context), downscaled to <= 640px so it stays fast.
 * 2. Fills the hole from the outside in; every pixel copies the centre of the best-matching 7x7 patch found in the
 *    untouched surroundings. Neighbouring pixels propagate their source offsets, which recreates textures/patterns
 *    (bedsheet stitching, walls, grass...) instead of smearing colour.
 * 3. The result is scaled back up and feather-blended into the full-resolution photo.
 * Classic (non-AI) inpainting: fine for small objects, weaker for large areas or faces.
 */
private fun inpaint(src: Bitmap, strokes: List<List<Offset>>, brushFrac: Float): Bitmap {
    val w = src.width;
    val h = src.height
    val brushPx = brushFrac * minOf(w, h)

    var minX = Float.MAX_VALUE;
    var minY = Float.MAX_VALUE;
    var maxX = 0f;
    var maxY = 0f
    strokes.forEach { pts ->
        pts.forEach {
            minX = minOf(minX, it.x * w); maxX = maxOf(maxX, it.x * w); minY =
            minOf(minY, it.y * h); maxY = maxOf(maxY, it.y * h)
        }
    }
    if (minX > maxX) return src.copy(Bitmap.Config.ARGB_8888, true)

    val pad = brushPx / 2f + 2f
    val margin = maxOf(maxX - minX, maxY - minY, brushPx) * 0.6f + 24f
    val rx = (minX - pad - margin).toInt().coerceAtLeast(0)
    val ry = (minY - pad - margin).toInt().coerceAtLeast(0)
    val rw = (maxX + pad + margin).toInt().coerceAtMost(w) - rx
    val rh = (maxY + pad + margin).toInt().coerceAtMost(h) - ry
    if (rw < 8 || rh < 8) return src.copy(Bitmap.Config.ARGB_8888, true)

    // Selection mask (full-res, region-local)
    val maskBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
    val mc = android.graphics.Canvas(maskBmp)
    mc.translate(-rx.toFloat(), -ry.toFloat())
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = brushPx; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.FILL
    }
    strokes.forEach { pts ->
        if (pts.size < 2) return@forEach
        val path = android.graphics.Path()
        path.moveTo(pts[0].x * w, pts[0].y * h)
        for (i in 1 until pts.size) path.lineTo(pts[i].x * w, pts[i].y * h)
        mc.drawPath(path, strokePaint)
        if (pts.size > 8) mc.drawPath(path, fillPaint)
    }
    val maskPx = IntArray(rw * rh)
    maskBmp.getPixels(maskPx, 0, rw, 0, 0, rw, rh)

    // Working-resolution copies
    val sc = minOf(1f, 640f / maxOf(rw, rh))
    val ww = (rw * sc).roundToInt().coerceAtLeast(1);
    val wh = (rh * sc).roundToInt().coerceAtLeast(1)
    val region = Bitmap.createBitmap(src, rx, ry, rw, rh)
    val workBmp = if (sc < 1f) Bitmap.createScaledBitmap(region, ww, wh, true) else region
    val workMask = if (sc < 1f) Bitmap.createScaledBitmap(maskBmp, ww, wh, true) else maskBmp
    val wpx = IntArray(ww * wh); workBmp.getPixels(wpx, 0, ww, 0, 0, ww, wh)
    val wmp = IntArray(ww * wh); workMask.getPixels(wmp, 0, ww, 0, 0, ww, wh)
    var hole = BooleanArray(ww * wh) { (wmp[it] ushr 24) > 40 }
    hole = dilate(hole, ww, wh, 3)   // swallow soft edges, halos and thin shadows

    fillPatches(wpx, hole, ww, wh)
    matchHoleColors(
        wpx,
        hole,
        ww,
        wh
    )   // make the fill's colours flow seamlessly into its surroundings

    val filled = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(wpx, 0, ww, 0, 0, ww, wh) }
    val up = if (sc < 1f) Bitmap.createScaledBitmap(filled, rw, rh, true) else filled
    val upPx = IntArray(rw * rh); up.getPixels(upPx, 0, rw, 0, 0, rw, rh)
    val orig = IntArray(rw * rh); src.getPixels(orig, 0, rw, rx, ry, rw, rh)

    // Blend mask: fully solid over the dilated hole (so no leftover object edge/halo colour bleeds through),
    // feathering only OUTSIDE it where the original pixels are clean.
    val dil = ceil(3f / sc).toInt() + 1
    val alpha = FloatArray(rw * rh) { if ((maskPx[it] ushr 24) > 40) 1f else 0f }
    boxBlur(alpha, rw, rh, dil)
    for (i in alpha.indices) alpha[i] = if (alpha[i] > 0.001f) 1f else 0f
    val fr = maxOf(3, (brushPx * 0.08f).toInt())
    boxBlur(alpha, rw, rh, fr)
    for (i in orig.indices) {
        val a = (alpha[i] * 2f).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val o = orig[i];
        val u = upPx[i]
        val r = (((o shr 16) and 255) * (1 - a) + ((u shr 16) and 255) * a).toInt()
        val g = (((o shr 8) and 255) * (1 - a) + ((u shr 8) and 255) * a).toInt()
        val b = ((o and 255) * (1 - a) + (u and 255) * a).toInt()
        orig[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
    maskBmp.recycle()
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    out.setPixels(orig, 0, rw, rx, ry, rw, rh)
    return out
}

/**
 * Colour matching ("mean-value cloning"): for every hole pixel touching the outside, measure how far the filled colour
 * is from the real neighbouring pixel, then spread that difference smoothly through the hole (Laplace / SOR solve) and
 * add it to the fill. Texture is kept, but brightness, tint and gradients now line up with the surroundings.
 */
private fun matchHoleColors(px: IntArray, hole: BooleanArray, w: Int, h: Int) {
    val n = w * h
    val mr = FloatArray(n);
    val mg = FloatArray(n);
    val mb = FloatArray(n)
    val fixed = BooleanArray(n)
    val interior = IntArray(n);
    var ni = 0
    val ddx = intArrayOf(1, -1, 0, 0);
    val ddy = intArrayOf(0, 0, 1, -1)
    var minX = w;
    var maxX = 0;
    var minY = h;
    var maxY = 0
    var sr = 0f;
    var sg = 0f;
    var sb = 0f;
    var nf = 0

    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!hole[i]) continue
        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY =
            y; if (y > maxY) maxY = y
        var cr = 0f;
        var cg = 0f;
        var cb = 0f;
        var c = 0
        for (k in 0 until 4) {
            val nx = x + ddx[k];
            val ny = y + ddy[k]
            if (nx in 0 until w && ny in 0 until h && !hole[ny * w + nx]) {
                val q = px[ny * w + nx]
                cr += (q shr 16) and 255; cg += (q shr 8) and 255; cb += q and 255; c++
            }
        }
        if (c > 0) {
            val g = px[i]
            mr[i] = cr / c - ((g shr 16) and 255)
            mg[i] = cg / c - ((g shr 8) and 255)
            mb[i] = cb / c - (g and 255)
            fixed[i] = true
            sr += mr[i]; sg += mg[i]; sb += mb[i]; nf++
        } else interior[ni++] = i
    }
    if (nf == 0) return
    // Start the interior at the mean boundary offset so the solver converges quickly
    for (t in 0 until ni) {
        val i = interior[t]; mr[i] = sr / nf; mg[i] = sg / nf; mb[i] = sb / nf
    }

    val diameter = maxOf(maxX - minX, maxY - minY) + 1
    val iterations = (60_000_000L / (ni * 12L + 1)).toInt().coerceIn(20, 2 * diameter)
    val omega = 1.85f
    repeat(iterations) {
        for (t in 0 until ni) {
            val i = interior[t]
            val x = i % w;
            val y = i / w
            var ar = 0f;
            var ag = 0f;
            var ab = 0f;
            var c = 0
            for (k in 0 until 4) {
                val nx = x + ddx[k];
                val ny = y + ddy[k]
                if (nx in 0 until w && ny in 0 until h) {
                    val j = ny * w + nx
                    ar += mr[j]; ag += mg[j]; ab += mb[j]; c++
                }
            }
            if (c > 0) {
                mr[i] += omega * (ar / c - mr[i]); mg[i] += omega * (ag / c - mg[i]); mb[i] += omega * (ab / c - mb[i])
            }
        }
    }
    for (i in 0 until n) {
        if (!hole[i]) continue
        val g = px[i]
        val r = (((g shr 16) and 255) + mr[i]).roundToInt().coerceIn(0, 255)
        val gg = (((g shr 8) and 255) + mg[i]).roundToInt().coerceIn(0, 255)
        val b = ((g and 255) + mb[i]).roundToInt().coerceIn(0, 255)
        px[i] = (255 shl 24) or (r shl 16) or (gg shl 8) or b
    }
}

private fun dilate(m: BooleanArray, w: Int, h: Int, iterations: Int): BooleanArray {
    var cur = m
    repeat(iterations) {
        val next = cur.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!cur[i] && ((x > 0 && cur[i - 1]) || (x < w - 1 && cur[i + 1]) || (y > 0 && cur[i - w]) || (y < h - 1 && cur[i + w]))) next[i] =
                true
        }
        cur = next
    }
    return cur
}

private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
    if (r < 1) return
    val tmp = FloatArray(maxOf(w, h))
    val div = (2 * r + 1).toFloat()
    for (y in 0 until h) {
        val o = y * w
        var sum = 0f
        for (x in -r..r) sum += a[o + x.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[x] = sum / div
            sum += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)]
        }
        System.arraycopy(tmp, 0, a, o, w)
    }
    for (x in 0 until w) {
        var sum = 0f
        for (y in -r..r) sum += a[y.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            tmp[y] = sum / div
            sum += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
        }
        for (y in 0 until h) a[y * w + x] = tmp[y]
    }
}

private fun patchCost(
    px: IntArray,
    known: BooleanArray,
    w: Int,
    h: Int,
    r: Int,
    x: Int,
    y: Int,
    c: Int,
    limit: Long
): Long {
    val cx = c % w;
    val cy = c / w
    var sum = 0L
    for (j in -r..r) {
        val ty = y + j
        if (ty < 0 || ty >= h) continue
        val trow = ty * w;
        val crow = (cy + j) * w + cx
        for (i in -r..r) {
            val tx = x + i
            if (tx < 0 || tx >= w || !known[trow + tx]) continue
            val a = px[trow + tx];
            val b = px[crow + i]
            val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
            val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
            val db = (a and 255) - (b and 255)
            sum += dr * dr + dg * dg + db * db
        }
        if (sum >= limit) return sum
    }
    return sum
}

/** Fills [hole] pixels of [px] in place, outside-in, by copying the best-matching source patch centres. */
private fun fillPatches(px: IntArray, hole: BooleanArray, w: Int, h: Int) {
    val r = 3
    val n = w * h
    var holes = 0
    for (i in 0 until n) if (hole[i]) holes++
    if (holes == 0) return

    // Source centres: patches that contain no hole pixels at all (via a summed-area table)
    val iw = w + 1
    val integ = IntArray(iw * (h + 1))
    for (y in 0 until h) {
        var row = 0
        for (x in 0 until w) {
            if (hole[y * w + x]) row++
            integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row
        }
    }
    val valid = BooleanArray(n)
    val cands = IntArray(n);
    var nc = 0
    for (y in r until h - r) for (x in r until w - r) {
        val x0 = x - r;
        val y0 = y - r;
        val x1 = x + r + 1;
        val y1 = y + r + 1
        if (integ[y1 * iw + x1] - integ[y0 * iw + x1] - integ[y1 * iw + x0] + integ[y0 * iw + x0] == 0) {
            valid[y * w + x] = true; cands[nc++] = y * w + x
        }
    }

    val known = BooleanArray(n) { !hole[it] }
    val srcOf = IntArray(n) { -1 }
    val rnd = java.util.Random(7)
    val randomTries = (60_000_000L / (holes.toLong() * 49)).toInt().coerceIn(20, 200)
    val ddx = intArrayOf(1, -1, 0, 0);
    val ddy = intArrayOf(0, 0, 1, -1)
    val queue = IntArray(holes);
    val queued = BooleanArray(n)
    var head = 0;
    var tail = 0
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        if (!hole[i]) continue
        for (k in 0 until 4) {
            val nx = x + ddx[k];
            val ny = y + ddy[k]
            if (nx in 0 until w && ny in 0 until h && known[ny * w + nx]) {
                queue[tail++] = i; queued[i] = true; break
            }
        }
    }

    while (head < tail) {
        val idx = queue[head++]
        val x = idx % w;
        val y = idx / w
        var best = -1;
        var bestCost = Long.MAX_VALUE
        fun tryC(c: Int) {
            if (c < 0 || c >= n || !valid[c]) return
            val cost = patchCost(px, known, w, h, r, x, y, c, bestCost)
            if (cost < bestCost) {
                bestCost = cost; best = c
            }
        }
        // 1) propagate the neighbours' source offsets (keeps texture structure coherent)
        for (k in 0 until 4) {
            val nx = x + ddx[k];
            val ny = y + ddy[k]
            if (nx in 0 until w && ny in 0 until h) {
                val s = srcOf[ny * w + nx]
                if (s >= 0) {
                    val sx = s % w - ddx[k];
                    val sy = s / w - ddy[k]
                    if (sx in 0 until w && sy in 0 until h) tryC(sy * w + sx)
                }
            }
        }
        // 2) random candidates + 3) local refinement around the best one
        if (nc > 0) {
            repeat(randomTries) { tryC(cands[rnd.nextInt(nc)]) }
            if (best >= 0) repeat(8) {
                val bx = best % w + rnd.nextInt(7) - 3;
                val by = best / w + rnd.nextInt(7) - 3
                if (bx in 0 until w && by in 0 until h) tryC(by * w + bx)
            }
        }
        if (best >= 0) {
            px[idx] = px[best]; srcOf[idx] = best
        } else {
            var a = 0;
            var rr = 0;
            var g = 0;
            var b = 0;
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx;
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && known[ny * w + nx]) {
                    val p = px[ny * w + nx]
                    a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
                }
            }
            if (c > 0) px[idx] =
                ((a / c) shl 24) or ((rr / c) shl 16) or ((g / c) shl 8) or (b / c)
        }
        known[idx] = true
        for (k in 0 until 4) {
            val nx = x + ddx[k];
            val ny = y + ddy[k]
            if (nx in 0 until w && ny in 0 until h) {
                val j = ny * w + nx
                if (!known[j] && !queued[j]) {
                    queued[j] = true; queue[tail++] = j
                }
            }
        }
    }
}

/** Rotates [src] by [deg] and crops to the largest same-aspect rectangle that fits inside, so no empty corners appear. */
private fun straightenBitmap(src: Bitmap, deg: Float): Bitmap {
    if (abs(deg) < 0.05f) return src
    val w = src.width.toFloat();
    val h = src.height.toFloat()
    val t = Math.toRadians(abs(deg).toDouble())
    val s = (1.0 / (cos(t) + maxOf(w / h, h / w) * sin(t))).toFloat()
    val outW = (w * s).roundToInt().coerceAtLeast(1)
    val outH = (h * s).roundToInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.translate(outW / 2f, outH / 2f)
    c.rotate(deg)
    c.translate(-w / 2f, -h / 2f)
    c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}

/** Bakes markup strokes and text into the final bitmap; positions are normalised (0..1) to the bitmap. */
private fun bakeOverlays(
    src: Bitmap,
    strokes: List<MarkupStroke>,
    texts: List<TextOverlay>
): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val minDim = minOf(out.width, out.height).toFloat()

    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color.toArgb(); style = Paint.Style.STROKE
            strokeWidth = stroke.width * minDim; strokeCap = Paint.Cap.ROUND; strokeJoin =
            Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        path.moveTo(stroke.points[0].x * out.width, stroke.points[0].y * out.height)
        for (i in 1 until stroke.points.size) path.lineTo(
            stroke.points[i].x * out.width,
            stroke.points[i].y * out.height
        )
        canvas.drawPath(path, paint)
    }
    texts.forEach { overlay ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.color.toArgb(); textSize = out.height * 0.045f; isFakeBoldText =
            true
        }
        // The on-screen text is positioned by its top-left corner; drawText uses the baseline.
        canvas.drawText(
            overlay.text,
            overlay.x * out.width,
            overlay.y * out.height + paint.textSize * 0.85f,
            paint
        )
    }
    return out
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** Saves [bmp] to Pictures/Gallery and returns the new item's content Uri, or null if it could not be written. */
private fun saveBitmap(context: Context, bmp: Bitmap): Uri? {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        val written = resolver.openOutputStream(uri)
            ?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (!written) throw java.io.IOException("compress failed")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            resolver.update(
                uri,
                android.content.ContentValues()
                    .apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
        uri
    } catch (e: Exception) {
        resolver.delete(
            uri,
            null,
            null
        )   // never leave a broken half-written item in the gallery
        null
    }
}
