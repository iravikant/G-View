@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.dateLabel
import com.acoder.gallery.core.util.dateTimeLabel
import com.acoder.gallery.core.util.formatBytes
import com.acoder.gallery.core.util.timeLabel
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.presentation.common.ForceLightSystemBarIcons
import com.acoder.gallery.presentation.common.GalleryDialog
import com.acoder.gallery.presentation.home.HomeEvent
import com.acoder.gallery.presentation.home.HomeViewModel
import com.acoder.gallery.presentation.home.MediaAction
import com.acoder.gallery.presentation.media.deleteMessage
import com.acoder.gallery.presentation.media.deleteTitle

@Composable
fun MediaViewerScreen(vm: HomeViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(vm.viewerItems.value) }
    val start = vm.viewerIndex.value
    var showInfo by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()

    BackHandler { nav.popBackStack() }
    ForceLightSystemBarIcons()

    // Keep this screen in sync with what happens elsewhere (trash / favourite finished, even after an OS prompt).
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            items.getOrNull(page)?.let { ZoomableImage(it, onTap = { chrome = !chrome }) }
        }

        // ---- top: back + "Today / 4:49 pm" + info ----
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(current.displayDate.dateLabel(), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(
                        "${current.displayDate.timeLabel().lowercase()}  ·  ${pager.currentPage + 1}/${items.size}",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp
                    )
                }
                IconButton({ showInfo = !showInfo }) { Icon(Icons.Default.Info, "Info", tint = Color.White) }
            }
        }

        if (showInfo && chrome) {
            Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = 16.dp, end = 16.dp),
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(current.displayDate.dateTimeLabel(), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    Text("${current.size.formatBytes()} · ${current.width}×${current.height}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    current.bucketName?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // ---- bottom: Share / Favourite / Edit / Delete ----
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding()
                    .padding(top = 28.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ViewerAction("Share", Icons.Default.Share) { MediaShareManager.share(context, listOf(current.uri), current.mimeType ?: "*/*") }
                ViewerAction(
                    if (current.isFavorite) "Favourited" else "Favourite",
                    if (current.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    tint = if (current.isFavorite) Color(0xFFFFC107) else Color.White
                ) { vm.toggleFavorite(listOf(current)) }
                ViewerAction("Edit", Icons.Default.Edit) { nav.navigate("editor/${android.net.Uri.encode(current.uri.toString())}") }
                ViewerAction("Delete", Icons.Default.Delete) { deleteConfirm = true }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding())
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

@Composable
private fun ViewerAction(label: String, icon: ImageVector, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, Modifier.size(26.dp), tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit) {
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.key) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.key) { mutableFloatStateOf(0f) }

    AsyncImage(
        model = item.uri,
        contentDescription = item.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offsetX; translationY = offsetY
            }
            // Custom gesture handling instead of detectTransformGestures: that detector consumes
            // every single-finger drag unconditionally, which starved the parent HorizontalPager
            // of any pointer events and made swiping to the next photo impossible even at 1x zoom.
            // Here a pinch (2+ pointers) always zooms, but a one-finger drag is only consumed (to
            // pan the photo) once it's actually zoomed in — at 1x the drag is left untouched so the
            // pager can swipe pages with it.
            .pointerInput(item.key) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size
                        if (pointerCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            scale = newScale
                            if (newScale <= 1f) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                offsetX += panChange.x; offsetY += panChange.y
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1 && scale > 1f) {
                            val change = event.changes.first()
                            val drag = change.positionChange()
                            if (drag != androidx.compose.ui.geometry.Offset.Zero) {
                                offsetX += drag.x
                                offsetY += drag.y
                                change.consume()
                            }
                        }
                        // pointerCount == 1 && scale == 1f: leave unconsumed so HorizontalPager can swipe.
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.key) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else { scale = 2.5f }
                })
            }
    )
}
