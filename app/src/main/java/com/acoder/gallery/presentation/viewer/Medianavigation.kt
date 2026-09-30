@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.viewer

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs

/**
 * !! Set these two to the route names used in your NavHost !!
 * (the same strings you pass to composable("...") for the photo viewer and the video player)
 */
object ViewerRoutes {
    const val IMAGE = "viewer"
    const val VIDEO = "video"
}

/**
 * Open / close animations for the photo viewer + video player routes.
 * Use them in the NavHost:
 *   composable(ViewerRoutes.IMAGE,
 *       enterTransition = { ViewerTransitions.enter }, exitTransition = { ViewerTransitions.exit },
 *       popEnterTransition = { ViewerTransitions.popEnter }, popExitTransition = { ViewerTransitions.popExit }
 *   ) { ... }
 */
object ViewerTransitions {
    val enter: EnterTransition =
        fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.92f)
    val exit: ExitTransition =
        fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 0.96f)
    val popEnter: EnterTransition = fadeIn(tween(200, easing = FastOutSlowInEasing))
    val popExit: ExitTransition =
        fadeOut(tween(200, easing = FastOutSlowInEasing)) +
                scaleOut(tween(260, easing = FastOutSlowInEasing), targetScale = 0.90f)
}

val MediaItem.isVideoMedia: Boolean
    get() = mimeType?.startsWith("video", ignoreCase = true) == true

/** One-shot flag: "start playing as soon as the video player opens" (vm.autoPlay is read-only). */
object PlaybackRequest {
    @Volatile var autoPlayNext = false
    fun consume(): Boolean { val v = autoPlayNext; autoPlayNext = false; return v }
}

/**
 * Opens [index] of [items] in the right screen for its type (photo viewer / video player),
 * replacing the screen we are currently on so Back still returns to the gallery grid.
 */
fun openMediaFromViewer(vm: HomeViewModel, nav: NavHostController, items: List<MediaItem>, index: Int) {
    val item = items.getOrNull(index) ?: return
    vm.viewerIndex.value = index
    if (item.isVideoMedia) PlaybackRequest.autoPlayNext = true
    val currentId = nav.currentDestination?.id
    nav.navigate(if (item.isVideoMedia) ViewerRoutes.VIDEO else ViewerRoutes.IMAGE) {
        if (currentId != null) popUpTo(currentId) { inclusive = true }
        launchSingleTop = true
    }
}

private val ThumbWidth = 56.dp
private val ThumbSpacing = 4.dp

/** Index of the item whose centre is closest to the centre of the list viewport. */
private fun LazyListState.centerIndex(): Int? {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
}

/**
 * Thumbnail strip shared by the photo viewer and the video player.
 *  - the selected thumbnail is pinned to the centre; the strip snaps to the nearest item
 *  - [onLiveScrub] fires (with a haptic tick) every time a new item passes the centre while the user
 *    scrolls - the photo viewer uses it to follow the strip live
 *  - [onSelect] fires when the scroll settles on a different item (or a thumbnail is tapped)
 *  - when the previewer changes on its own, the strip glides to keep it centred
 */
@Composable
fun MediaThumbnailStrip(
    items: List<MediaItem>,
    currentIndex: Int,
    onScrollActive: () -> Unit = {},
    onLiveScrub: (Int) -> Unit = {},
    onSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    )
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestActive by rememberUpdatedState(onScrollActive)
    val latestLive by rememberUpdatedState(onLiveScrub)
    var programmatic by remember { mutableStateOf(false) }

    // previewer -> strip: keep the current item centred (never fight the user's finger)
    LaunchedEffect(currentIndex) {
        if (listState.isScrollInProgress && !programmatic) return@LaunchedEffect
        if (listState.centerIndex() != currentIndex) {
            programmatic = true
            try { listState.animateScrollToItem(currentIndex) } finally { programmatic = false }
        }
    }
    // user scrolling: haptic tick + live preview each time a new item reaches the centre
    LaunchedEffect(listState) {
        snapshotFlow { listState.centerIndex() }
            .distinctUntilChanged()
            .collect { c ->
                if (c != null && listState.isScrollInProgress && !programmatic) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestLive(c)
                }
            }
    }
    // scroll settled: open the centred item
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (!programmatic) {
                    val c = listState.centerIndex()
                    if (c != null && c != latestIndex) latestSelect(c)
                }
            }
    }
    // keep player controls visible while the user is scrolling the strip
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect { if (!programmatic) latestActive() }
    }

    // while dragging, highlight whatever is under the centre; otherwise the current item
    val liveCenter by remember { derivedStateOf { listState.centerIndex() } }
    val highlighted = if (listState.isScrollInProgress && !programmatic) liveCenter ?: currentIndex else currentIndex

    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val sidePadding = ((maxWidth - ThumbWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(ThumbSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                val selected = index == highlighted
                val height by animateDpAsState(if (selected) 60.dp else 50.dp, tween(160), label = "thumbH")
                val alpha by animateFloatAsState(if (selected) 1f else 0.72f, tween(160), label = "thumbA")
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .width(ThumbWidth)
                        .height(height)
                        .alpha(alpha)
                        .clip(shape)
                        .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
                        .clickable { if (index != currentIndex) latestSelect(index) }
                ) {
                    // small decode size = much smoother scrolling
                    val request = remember(item.uri) {
                        ImageRequest.Builder(context).data(item.uri).size(180).crossfade(true).build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.isVideoMedia) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(3.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}