@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.acoder.gallery.presentation.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.dateLabel
import com.acoder.gallery.core.util.dateTimeLabel
import com.acoder.gallery.core.util.formatBytes
import com.acoder.gallery.core.util.formatDuration
import com.acoder.gallery.core.util.timeLabel
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.presentation.common.ForceLightSystemBarIcons
import com.acoder.gallery.presentation.common.GalleryDialog
import com.acoder.gallery.presentation.home.HomeEvent
import com.acoder.gallery.presentation.home.HomeViewModel
import com.acoder.gallery.presentation.home.MediaAction
import com.acoder.gallery.presentation.media.deleteMessage
import com.acoder.gallery.presentation.media.deleteTitle
import com.acoder.gallery.presentation.viewer.MediaThumbnailStrip
import com.acoder.gallery.presentation.viewer.PlaybackRequest
import com.acoder.gallery.presentation.viewer.isVideoMedia
import com.acoder.gallery.presentation.viewer.openMediaFromViewer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val PillColor = Color(0xFF2A2A2C)
private val SheetColor = Color(0xFF121214)
private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/** Aspect-ratio modes cycled by the top-bar button. [ratio] != null forces a fixed frame ratio. */
private enum class AspectMode(val label: String, val ratio: Float?, val resize: Int) {
    FIT("Fit", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill screen", null, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", null, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    R16_9("16:9", 16f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    R4_3("4:3", 4f / 3f, AspectRatioFrameLayout.RESIZE_MODE_FILL)
}
private data class Hud(val icon: ImageVector, val label: String, val progress: Float?)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun speedLabel(s: Float) = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

@Composable
fun VideoPlayerScreen(vm: HomeViewModel, nav: NavHostController) {
    val items by vm.viewerItems.collectAsState()
    val index by vm.viewerIndex.collectAsState()
    val autoPlay by vm.autoPlay.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (items.isEmpty()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val current = items.getOrNull(index) ?: items.first()
    val currentState by rememberUpdatedState(current)

    // Close the player if the current video gets trashed/deleted (also after an OS confirmation prompt).
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            if (e is HomeEvent.ActionDone &&
                (e.action == MediaAction.TRASH || e.action == MediaAction.DELETE_FOREVER) &&
                currentState.key in e.keys
            ) nav.popBackStack()
        }
    }

    // Survive an activity re-creation caused by rotating (position + play state are saved).
    var resumePos by rememberSaveable(current.key) { mutableStateOf(0L) }
    var resumePlay by rememberSaveable(current.key) { mutableStateOf(autoPlay) }

    val player = remember(current.key) {
        val forcedPlay = PlaybackRequest.consume()
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoItem.fromUri(current.uri))
            if (resumePos > 0L) seekTo(resumePos)
            prepare()
            playWhenReady = resumePlay || forcedPlay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    BackHandler { nav.popBackStack() }
    ForceLightSystemBarIcons()

    // ---- state ----
    var isPlaying by remember(current.key) { mutableStateOf(autoPlay) }
    var ended by remember(current.key) { mutableStateOf(false) }
    var buffering by remember(current.key) { mutableStateOf(false) }
    var position by remember(current.key) { mutableLongStateOf(0L) }
    var duration by remember(current.key) { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var boosting by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var aspectIdx by rememberSaveable { mutableStateOf(0) }
    val aspect = AspectMode.values()[aspectIdx]
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var favOverride by remember(current.key) { mutableStateOf<Boolean?>(null) }
    val isFavorite = favOverride ?: current.isFavorite

    // gesture HUD
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var flashSide by remember { mutableIntStateOf(0) } // -1 left, 1 right
    var flashAmount by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }

    // brightness + volume
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var brightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f)
    }
    fun applyBrightness(v: Float) {
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v } }
    }
    fun applyVolume(v: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt(), 0)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }

    // ---- immersive: system bars (time / network / battery) follow the player controls ----
    val insetsController = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }
    DisposableEffect(Unit) {
        val w = activity?.window
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= 28 && w != null) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28 && w != null) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }
    LaunchedEffect(chrome, insetsController) {
        if (chrome) insetsController?.show(WindowInsetsCompat.Type.systemBars())
        else insetsController?.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- player wiring ----
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { resumePlay = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                buffering = state == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    DisposableEffect(player, lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle?.addObserver(obs)
        onDispose { lifecycle?.removeObserver(obs) }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(player) {
        while (true) {
            if (!seeking) {
                position = player.currentPosition.coerceAtLeast(0)
                resumePos = position
                duration = player.duration.coerceAtLeast(0)
            }
            delay(250)
        }
    }

    // auto-hide controls while playing
    LaunchedEffect(chrome, isPlaying, interaction, seeking, menuOpen) {
        if (chrome && isPlaying && !seeking && !menuOpen) { delay(3500); chrome = false }
    }
    LaunchedEffect(hudTick) { if (hudTick > 0) { delay(900); hud = null } }
    LaunchedEffect(flashTick) { if (flashTick > 0) { delay(700); flashSide = 0; flashAmount = 0 } }

    fun skip(dir: Int) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + dir * 10_000L).coerceIn(0L, dur))
        flashAmount = if (flashSide == dir) flashAmount + 10 else 10
        flashSide = dir
        flashTick++
    }
    fun togglePlay() {
        if (ended) { player.seekTo(0); player.play() }
        else if (player.isPlaying) player.pause() else player.play()
    }
    fun cycleAspect() {
        val modes = AspectMode.values()
        aspectIdx = (aspectIdx + 1) % modes.size
        hud = Hud(Icons.Default.AspectRatio, modes[aspectIdx].label, null)
        hudTick++
        interaction++
    }
    fun rotate() {
        val act = activity ?: return
        act.requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        interaction++
    }
    // Videos switch inside this screen; a photo is opened in the photo viewer instead.
    fun openMedia(i: Int) {
        if (i == index) return
        if (items.getOrNull(i)?.isVideoMedia == true) vm.viewerIndex.value = i
        else openMediaFromViewer(vm, nav, items, i)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false } },
                update = {
                    it.player = player
                    it.keepScreenOn = isPlaying
                    it.resizeMode = aspect.resize
                },
                modifier = aspect.ratio?.let { r -> Modifier.aspectRatio(r) } ?: Modifier.fillMaxSize()
            )
        }

        // ---------- gesture layer ----------
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { pos ->
                            val third = size.width / 3f
                            when {
                                pos.x < third -> skip(-1)
                                pos.x > third * 2 -> skip(1)
                                else -> togglePlay()
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (boosting) { boosting = false; player.setPlaybackSpeed(speed) }
                        },
                        onLongPress = {
                            if (player.isPlaying) { boosting = true; player.setPlaybackSpeed(2f) }
                        }
                    )
                }
                .pointerInput(player) {
                    var mode = DragMode.NONE
                    var totalX = 0f
                    var totalY = 0f
                    var startPos = 0L
                    var target = 0L
                    var startBrightness = 0f
                    var startVolume = 0f
                    var startedLeft = true
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = DragMode.NONE; totalX = 0f; totalY = 0f
                            startedLeft = o.x < size.width / 2f
                            startPos = player.currentPosition
                            target = startPos
                            startBrightness = brightness
                            startVolume = volume
                        },
                        onDragEnd = {
                            if (mode == DragMode.SEEK) player.seekTo(target)
                            seeking = false; mode = DragMode.NONE
                        },
                        onDragCancel = { seeking = false; mode = DragMode.NONE },
                        onDrag = { change, amount ->
                            change.consume()
                            totalX += amount.x; totalY += amount.y
                            if (mode == DragMode.NONE && (abs(totalX) > 24f || abs(totalY) > 24f)) {
                                mode = when {
                                    abs(totalX) > abs(totalY) -> DragMode.SEEK
                                    startedLeft -> DragMode.BRIGHTNESS
                                    else -> DragMode.VOLUME
                                }
                                if (mode == DragMode.SEEK) seeking = true
                            }
                            when (mode) {
                                DragMode.SEEK -> {
                                    val dur = player.duration.coerceAtLeast(0)
                                    if (dur > 0) {
                                        // full screen width == 2 minutes of video
                                        target = (startPos + totalX / size.width * 120_000f).toLong().coerceIn(0L, dur)
                                        position = target
                                        hud = Hud(
                                            if (totalX >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                                            "${target.formatDuration()} / ${dur.formatDuration()}",
                                            target.toFloat() / dur
                                        )
                                        hudTick++
                                    }
                                }
                                DragMode.BRIGHTNESS -> {
                                    brightness = (startBrightness - totalY / size.height * 1.5f).coerceIn(0.02f, 1f)
                                    applyBrightness(brightness)
                                    hud = Hud(Icons.Default.BrightnessMedium, "${(brightness * 100).roundToInt()}%", brightness)
                                    hudTick++
                                }
                                DragMode.VOLUME -> {
                                    volume = (startVolume - totalY / size.height * 1.5f).coerceIn(0f, 1f)
                                    applyVolume(volume)
                                    if (volume > 0f) muted = false
                                    hud = Hud(
                                        when { volume == 0f -> Icons.Default.VolumeOff; volume < 0.5f -> Icons.Default.VolumeDown; else -> Icons.Default.VolumeUp },
                                        "${(volume * 100).roundToInt()}%", volume
                                    )
                                    hudTick++
                                }
                                DragMode.NONE -> Unit
                            }
                        }
                    )
                }
        )

        // ---------- double-tap seek flash ----------
        if (flashSide != 0) {
            Box(
                Modifier
                    .align(if (flashSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 28.dp)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (flashSide < 0) Icons.Default.Replay10 else Icons.Default.Forward10,
                        null, tint = Color.White, modifier = Modifier.size(34.dp)
                    )
                    Text("$flashAmount sec", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ---------- gesture HUD (brightness / volume / seek) ----------
        hud?.let { h ->
            Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Column(Modifier.width(170.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(h.icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(h.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    h.progress?.let {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { it.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color.White, trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        if (boosting) {
            Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
                shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.65f)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("2x speed", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        if (buffering && !ended) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White, strokeWidth = 3.dp)
        }

        // ---------- top bar ----------
        androidx.compose.animation.AnimatedVisibility(
            chrome, Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { -it / 2 }, exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { -it / 2 }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        current.bucketName ?: current.name,
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    Text(
                        "${current.displayDate.dateLabel()} at ${current.displayDate.timeLabel().lowercase()}  ·  ${index + 1}/${items.size}",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1
                    )
                }
                IconButton({ cycleAspect() }) { Icon(Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White) }
                IconButton({ rotate() }) { Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White) }
                Box {
                    IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        SPEEDS.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Speed  ${speedLabel(s)}",
                                        fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = { speed = s; menuOpen = false }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Details") }, onClick = { menuOpen = false; showInfo = true })
                    }
                }
            }
        }

        // ---------- centre transport controls ----------
        androidx.compose.animation.AnimatedVisibility(
            chrome, Modifier.align(Alignment.Center),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(240), initialScale = 0.85f), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(160), targetScale = 0.9f)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundControl(Icons.Default.SkipPrevious, "Previous", 48.dp, enabled = index > 0) {
                    if (index > 0) openMedia(index - 1)
                }
                RoundControl(
                    when { ended -> Icons.Default.Replay; isPlaying -> Icons.Default.Pause; else -> Icons.Default.PlayArrow },
                    "Play/Pause", 72.dp, iconSize = 38.dp
                ) { togglePlay(); interaction++ }
                RoundControl(Icons.Default.SkipNext, "Next", 48.dp, enabled = index < items.lastIndex) {
                    if (index < items.lastIndex) openMedia(index + 1)
                }
            }
        }

        // ---------- bottom: seek bar + actions ----------
        androidx.compose.animation.AnimatedVisibility(
            chrome, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 2 }, exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { it / 2 }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 28.dp, bottom = 12.dp)
            ) {
                // not enough height for the strip in landscape - keep the video area clear
                if (!isLandscape) {
                    MediaThumbnailStrip(items, index, onScrollActive = { interaction++ }) { i ->
                        openMedia(i)
                        interaction++
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(position.formatDuration(), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seeking = true; position = (it * duration).toLong(); interaction++ },
                        onValueChangeFinished = { player.seekTo(position); seeking = false },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(duration.formatDuration(), color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
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
                            IconButton({
                                favOverride = !isFavorite
                                vm.toggleFavorite(listOf(current))
                            }) {
                                Icon(
                                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                                    tint = if (isFavorite) Color(0xFFFF5A5F) else Color.White
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton({ muted = !muted }) {
                                Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Mute", tint = Color.White)
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
        ) { VideoDetails(current, duration) }
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
private fun RoundControl(
    icon: ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = PillColor, modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun VideoDetails(item: MediaItem, durationMs: Long) {
    val format = item.mimeType?.substringAfter('/')?.uppercase() ?: "VIDEO"
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text(item.displayDate.dateTimeLabel(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(item.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))

        Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Color.White.copy(alpha = 0.12f)); Spacer(Modifier.height(16.dp))

        Text("Video details", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "${durationMs.formatDuration()}  |  ${item.width} × ${item.height}  |  ${item.size.formatBytes()}  |  $format",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)
        )

        item.bucketName?.let {
            Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Color.White.copy(alpha = 0.12f)); Spacer(Modifier.height(16.dp))
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