@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.dateLabel
import com.acoder.gallery.core.util.dateTimeLabel
import com.acoder.gallery.core.util.formatBytes
import com.acoder.gallery.core.util.mediaSharedBounds
import com.acoder.gallery.core.util.timeLabel
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.presentation.common.ForceLightSystemBarIcons
import com.acoder.gallery.presentation.common.GalleryDialog
import com.acoder.gallery.presentation.home.HomeEvent
import com.acoder.gallery.presentation.home.HomeViewModel
import com.acoder.gallery.presentation.home.MediaAction
import com.acoder.gallery.presentation.media.deleteMessage
import com.acoder.gallery.presentation.media.deleteTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val PillColor = Color(0xFF2A2A2C)
private val SheetColor = Color(0xFF121214)
private val ChipColor = Color(0xFF232326)

@Composable
fun MediaViewerScreen(vm: HomeViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(vm.viewerItems.value) }
    val start = vm.viewerIndex.value
    var showInfo by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    val scope = rememberCoroutineScope()

    BackHandler { nav.popBackStack() }
    ForceLightSystemBarIcons()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            if (event is HomeEvent.ActionDone) {
                when (event.action) {
                    MediaAction.TRASH, MediaAction.DELETE_FOREVER -> items = items.filterNot { it.key in event.keys }
                    MediaAction.FAVORITE -> items = items.map { if (it.key in event.keys) it.copy(isFavorite = event.flag) else it }
                    MediaAction.RESTORE -> Unit
                }
            }
        }
    }
    if (items.isEmpty()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }

    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex), pageCount = { items.size })
    val current = items.getOrNull(pager.currentPage) ?: items.first()

    // ---- swipe-down / up to dismiss ----
    val dragY = remember { Animatable(0f) }
    var screenH by remember { mutableFloatStateOf(1f) }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val dismissProgress = (abs(dragY.value) / (screenH * 0.5f)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenH = it.height.toFloat().coerceAtLeast(1f) }
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.75f))
            .pointerInput(zoomed) {
                if (!zoomed) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            scope.launch { dragY.snapTo(dragY.value + amount) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (abs(dragY.value) > screenH * 0.15f) {
                                    // the shared-element transition flies the photo back into its tile
                                    nav.popBackStack()
                                } else {
                                    dragY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f, spring()) } }
                    )
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragY.value
                    val sc = 1f - dismissProgress * 0.25f
                    scaleX = sc; scaleY = sc
                }
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 12.dp) { page ->
                // subtle scale + fade while swiping between pages
                val pageOffset = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val sc = lerp(1f, 0.9f, pageOffset)
                        scaleX = sc; scaleY = sc
                        alpha = lerp(1f, 0.5f, pageOffset)
                    }
                ) {
                    items.getOrNull(page)?.let { item ->
                        // only the visible page shares bounds with its grid tile
                        val sharedMod = if (page == pager.currentPage) Modifier.mediaSharedBounds(item.key) else Modifier
                        if (item.isVideoMedia) {
                            VideoPreviewPage(
                                item,
                                modifier = sharedMod,
                                onTap = { chrome = !chrome },
                                onPlay = { openMediaFromViewer(vm, nav, items, page) }
                            )
                        } else {
                            ZoomableImage(
                                item,
                                modifier = sharedMod,
                                onTap = { chrome = !chrome },
                                onZoomChanged = { z -> if (page == pager.currentPage) zoomed = z }
                            )
                        }
                    }
                }
            }
        }

        // ---------- top bar ----------
        AnimatedVisibility(
            chrome && dismissProgress == 0f, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .edgeBleedGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), top = true)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        current.bucketName ?: current.displayDate.dateLabel(),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    Text(
                        "${current.displayDate.dateLabel()} at ${current.displayDate.timeLabel().lowercase()}",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1
                    )
                }
                Box {
                    IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Details") }, onClick = { menuOpen = false; showInfo = true })
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { menuOpen = false; MediaShareManager.share(context, listOf(current.uri), current.mimeType ?: "*/*") }
                        )
                    }
                }
            }
        }

        // ---------- bottom: thumbnail strip + actions ----------
        AnimatedVisibility(
            chrome && dismissProgress == 0f, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .edgeBleedGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), top = false)
                    .navigationBarsPadding()
                    .padding(top = 24.dp, bottom = 12.dp)
            ) {
                MediaThumbnailStrip(
                    items, pager.currentPage,
                    // photos follow the strip live while scrolling; videos open when it settles
                    onLiveScrub = { i -> if (items.getOrNull(i)?.isVideoMedia == false) scope.launch { pager.scrollToPage(i) } }
                ) { index ->
                    if (items[index].isVideoMedia) openMediaFromViewer(vm, nav, items, index)
                    else scope.launch { pager.animateScrollToPage(index) }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleAction(Icons.Default.Share, "Share") {
                        MediaShareManager.share(context, listOf(current.uri), current.mimeType ?: "*/*")
                    }
                    Surface(shape = RoundedCornerShape(50), color = PillColor) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ vm.toggleFavorite(listOf(current)) }) {
                                Icon(
                                    if (current.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    if (current.isFavorite) "Favourited" else "Favourite",
                                    tint = if (current.isFavorite) Color(0xFFFF5A5F) else Color.White
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton({ nav.navigate("editor/${android.net.Uri.encode(current.uri.toString())}") }) {
                                Icon(Icons.Default.Edit, "Edit", tint = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton({ deleteConfirm = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
                        }
                    }
                    CircleAction(Icons.Default.Info, "Details") { showInfo = true }
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding())
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = SheetColor,
            contentColor = Color.White
        ) { DetailsContent(current) }
    }

    if (deleteConfirm) {
        GalleryDialog(
            title = deleteTitle(listOf(current)),
            message = deleteMessage(listOf(current), vm.trashSupported),
            confirmLabel = "Delete",
            onDismiss = { deleteConfirm = false },
            onConfirm = { deleteConfirm = false; vm.moveToTrash(listOf(current)) }
        )
    }
}

// ============================ Bottom widgets ============================

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = PillColor, modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }
}

// ============================ Video page inside the photo pager ============================

@Composable
private fun VideoPreviewPage(item: MediaItem, modifier: Modifier = Modifier, onTap: () -> Unit, onPlay: () -> Unit) {
    Box(modifier.fillMaxSize().pointerInput(item.key) { detectTapGestures(onTap = { onTap() }) }) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, "Play video", tint = Color.White, modifier = Modifier.size(42.dp))
        }
    }
}

// ============================ Details sheet ============================

private data class ExifData(
    val camera: String?,
    val iso: String?,
    val shutter: String?,
    val aperture: String?,
    val flashFired: Boolean?
)

@Composable
private fun DetailsContent(item: MediaItem) {
    val context = LocalContext.current
    val exif by produceState<ExifData?>(null, item.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(item.uri)?.use { s ->
                    val e = ExifInterface(s)
                    val make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    val camera = when {
                        model.isNullOrBlank() -> null
                        !make.isNullOrBlank() && !model.startsWith(make, true) -> "$make $model"
                        else -> model
                    }
                    val t = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    val f = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    val flash = if (e.hasAttribute(ExifInterface.TAG_FLASH)) (e.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) == 1 else null
                    ExifData(
                        camera = camera,
                        iso = e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                        shutter = when { t <= 0.0 -> null; t >= 1.0 -> "${"%.1f".format(t)} s"; else -> "1/${(1.0 / t).roundToInt()} s" },
                        aperture = if (f > 0.0) "f ${"%.1f".format(f)}" else null,
                        flashFired = flash
                    )
                }
            }.getOrNull()
        }
    }

    val megapixels = ((item.width.toLong() * item.height) / 1_000_000.0).roundToInt()
    val format = item.mimeType?.substringAfter('/')?.uppercase() ?: "IMAGE"

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text(item.displayDate.dateTimeLabel(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(item.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))

        SectionDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                exif?.camera ?: "Photo details", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            exif?.flashFired?.let {
                Icon(
                    if (it) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash",
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${megapixels}MP  |  ${item.width} × ${item.height}  |  ${item.size.formatBytes()}  |  $format",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)
        )

        exif?.let { e ->
            if (e.iso != null || e.shutter != null || e.aperture != null) {
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = ChipColor, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ExifCell("ISO", e.iso)
                        ExifCell("Shutter", e.shutter)
                        ExifCell("Aperture", e.aperture)
                    }
                }
            }
        }

        item.bucketName?.let {
            SectionDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhotoLibrary, null, tint = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Album", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Text(it, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ExifCell(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
        Spacer(Modifier.height(2.dp))
        Text(value ?: "–", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ============================ Zoom / pan ============================

private fun clampOffset(o: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

@Composable
private fun ZoomableImage(item: MediaItem, modifier: Modifier = Modifier, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit = {}) {
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offset by remember(item.key) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(item.key) { snapshotFlow { scale > 1.02f }.collect { onZoomChanged(it) } }

    Box(
        modifier
            .fillMaxSize()
            // NOTE: pointerInput is placed BEFORE graphicsLayer on purpose. Modifiers placed after
            // a graphicsLayer receive coordinates in the already-scaled space, which divided every
            // drag by the zoom factor and made panning feel very slow.
            .pointerInput(item.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid != Offset.Unspecified) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val q = centroid - center
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                val ratio = newScale / scale
                                // keep the point under the fingers fixed while scaling
                                val target = q - (q - offset) * ratio + pan
                                scale = newScale
                                offset = if (newScale <= 1f) Offset.Zero else clampOffset(target, newScale, size)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed == 1 && scale > 1f) {
                            val change = event.changes.first { it.pressed }
                            val drag = change.positionChange()
                            val clamped = clampOffset(offset + drag, scale, size)
                            val moved = clamped - offset
                            offset = clamped
                            // At the image edge let the pager take the swipe instead of swallowing it.
                            val consume = if (abs(drag.x) > abs(drag.y)) moved.x != 0f else moved.y != 0f
                            if (consume) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val q = tap - Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clampOffset(q - q * target, target, size) // zoom into the tapped point
                        }
                    }
                )
            }
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
    }
}