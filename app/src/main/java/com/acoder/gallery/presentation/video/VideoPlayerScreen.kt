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
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import coil3.compose.AsyncImage
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.dateLabel
import com.acoder.gallery.core.util.dateTimeLabel
import com.acoder.gallery.core.util.formatBytes
import com.acoder.gallery.core.util.formatDuration
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
import com.acoder.gallery.presentation.viewer.MediaThumbnailStrip
import com.acoder.gallery.presentation.viewer.PlaybackRequest
import com.acoder.gallery.presentation.viewer.edgeBleedGradient
import com.acoder.gallery.presentation.viewer.isVideoMedia
import com.acoder.gallery.presentation.viewer.openMediaFromViewer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val PillColor = Color(0xE62A2A2C)
private val SheetColor = Color(0xFF121214)
private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private val AccentGradient = Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFFFF4081)))
private val BrightnessTint = Color(0xFFFFC857)
private val VolumeTint = Color(0xFF6EC6FF)
private val FavoriteTint = Color(0xFFFF5A5F)

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/** Aspect-ratio modes cycled by the top-bar button. [ratio] != null forces a fixed frame ratio. */
private enum class AspectMode(val label: String, val ratio: Float?, val resize: Int) {
    FIT("Fit", null, AspectRatioFrameLayout.RESIZE_MODE_FIT), FILL(
        "Fill screen",
        null,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    ),
    STRETCH("Stretch", null, AspectRatioFrameLayout.RESIZE_MODE_FILL), R16_9(
        "16:9",
        16f / 9f,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    ),
    R4_3("4:3", 4f / 3f, AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

private data class Hud(
    val icon: ImageVector, val label: String, val progress: Float?, val tint: Color = Color.White
)

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

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }; return
    }
    val current = items.getOrNull(index) ?: items.first()
    val currentState by rememberUpdatedState(current)

    // Close the player if the current video gets trashed/deleted (also after an OS confirmation prompt).
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            if (e is HomeEvent.ActionDone && (e.action == MediaAction.TRASH || e.action == MediaAction.DELETE_FOREVER) && currentState.key in e.keys) nav.popBackStack()
        }
    }

    // Survive an activity re-creation caused by rotating (position + play state are saved).
    var resumePos by rememberSaveable(current.key) { mutableStateOf(0L) }
    var resumePlay by rememberSaveable(current.key) { mutableStateOf(autoPlay) }

    // The autoplay request is "sticky": it stays valid until playback really starts, so a second composition
    // during the navigation transition can no longer swallow it (that was the "first time needs a tap" bug).
    val wantAutoPlay = remember(current.key) {
        val oneShot = PlaybackRequest.consume()
        oneShot || PlaybackRequest.autoPlayKey == current.key
    }
    var autoStarted by remember(current.key) { mutableStateOf(false) }

    val player = remember(current.key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoItem.fromUri(current.uri))
            if (resumePos > 0L) seekTo(resumePos)
            // prepare() + playWhenReady are applied in the effect below, after the open animation
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // The expensive part (codec + surface start-up) used to run in the middle of the open transition and dropped
    // frames - that was the visible glitch. Show the poster first, start the decoder once the animation is over.
    var surfaceReady by remember(current.key) { mutableStateOf(false) }
    LaunchedEffect(player) {
        if (resumePos == 0L) delay(340) // roughly the fade / shared-element duration
        player.playWhenReady = resumePlay || wantAutoPlay
        player.prepare()
        surfaceReady = true
    }
    ForceLightSystemBarIcons()

    // ---- state ----
    var isPlaying by remember(current.key) { mutableStateOf(autoPlay) }
    var ended by remember(current.key) { mutableStateOf(false) }
    var buffering by remember(current.key) { mutableStateOf(false) }
    var position by remember(current.key) { mutableLongStateOf(0L) }
    var duration by remember(current.key) { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }   // dragging the seek bar itself
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var boosting by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    var firstFrame by remember(current.key) { mutableStateOf(false) }
    BackHandler { closing = true }
    var aspectIdx by rememberSaveable { mutableStateOf(0) }
    val aspect = AspectMode.values()[aspectIdx]
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var favOverride by remember(current.key) { mutableStateOf<Boolean?>(null) }
    val isFavorite = favOverride ?: current.isFavorite

    // gesture HUD (hud keeps its last value so it can animate out)
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hudVisible by remember { mutableStateOf(false) }
    var hudTick by remember { mutableIntStateOf(0) }
    var flashSide by remember { mutableIntStateOf(1) } // -1 left, 1 right (last used side)
    var flashVisible by remember { mutableStateOf(false) }
    var flashAmount by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }

    fun showHud(h: Hud) {
        hud = h
        hudVisible = true
        hudTick++
    }

    // brightness + volume
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember {
        mutableFloatStateOf(
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
        )
    }
    var brightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
            ?: 0.5f)
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
                w.attributes = w.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // ---- immersive: system bars (time / network / battery) follow the player controls ----
    val insetsController = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }
    DisposableEffect(Unit) {
        val w = activity?.window
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= 28 && w != null) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28 && w != null) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
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
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) PlaybackRequest.autoPlayKey = null // request fulfilled
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                resumePlay = playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                buffering = state == Player.STATE_BUFFERING
                // Safety net: the first time the video is ready, make sure an autoplay request really starts it.
                if (state == Player.STATE_READY && wantAutoPlay && !autoStarted) {
                    autoStarted = true
                    if (!player.isPlaying) player.play()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    DisposableEffect(player, lifecycle) {
        val obs =
            LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
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
        if (chrome && isPlaying && !seeking && !menuOpen) {
            delay(3500); chrome = false
        }
    }
    LaunchedEffect(hudTick) {
        if (hudTick > 0) {
            delay(900); hudVisible = false
        }
    }
    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            delay(700); flashVisible = false
        }
    }

    fun skip(dir: Int) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + dir * 10_000L).coerceIn(0L, dur))
        flashAmount = if (flashVisible && flashSide == dir) flashAmount + 10 else 10
        flashSide = dir
        flashVisible = true
        flashTick++
    }

    fun togglePlay() {
        if (ended) {
            player.seekTo(0); player.play()
        } else if (player.isPlaying) player.pause() else player.play()
    }
    // Close = show the poster for a frame (a live video surface can't morph back into the tile), then pop.
    LaunchedEffect(closing) {
        if (closing) {
            withFrameNanos { }; withFrameNanos { }; nav.popBackStack()
        }
    }
    fun cycleAspect() {
        val modes = AspectMode.values()
        aspectIdx = (aspectIdx + 1) % modes.size
        showHud(Hud(Icons.Default.AspectRatio, modes[aspectIdx].label, null))
        interaction++
    }

    fun rotate() {
        val act = activity ?: return
        act.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        interaction++
    }

    // Videos switch inside this screen; a photo is opened in the photo viewer instead.
    fun openMedia(i: Int) {
        if (i == index) return
        if (items.getOrNull(i)?.isVideoMedia == true) vm.viewerIndex.value = i
        else openMediaFromViewer(vm, nav, items, i)
    }

    // Pause, then open the video editor for this video.
    fun openEditor() {
        player.pause()
        nav.navigate("video_editor/" + Uri.encode(current.uri.toString()))
    }

    // Smooth hairline progress shown while the controls are hidden.
    val hairline by animateFloatAsState(
        targetValue = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(250, easing = LinearEasing),
        label = "hairline"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .mediaSharedBounds(current.key),
            contentAlignment = Alignment.Center
        ) {
            if (surfaceReady) {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = {
                        it.player = player
                        it.keepScreenOn = isPlaying
                        it.resizeMode = aspect.resize
                    },
                    modifier = aspect.ratio?.let { r -> Modifier.aspectRatio(r) }
                        ?: Modifier.fillMaxSize())
            }
            // poster = the tile's picture; it is what flies in / out, then fades once the first frame is drawn
            AnimatedVisibility(
                visible = !firstFrame || closing,
                enter = EnterTransition.None,
                exit = fadeOut(tween(200))
            ) {
                AsyncImage(
                    model = current.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ---------- gesture layer ----------
        Box(Modifier
            .fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { pos ->
                    val third = size.width / 3f
                    when {
                        pos.x < third -> skip(-1)
                        pos.x > third * 2 -> skip(1)
                        else -> togglePlay()
                    }
                }, onPress = {
                    tryAwaitRelease()
                    if (boosting) {
                        boosting = false; player.setPlaybackSpeed(speed)
                    }
                }, onLongPress = {
                    if (player.isPlaying) {
                        boosting = true; player.setPlaybackSpeed(2f)
                    }
                })
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
                                    target = (startPos + totalX / size.width * 120_000f).toLong()
                                        .coerceIn(0L, dur)
                                    position = target
                                    showHud(
                                        Hud(
                                            if (totalX >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                                            "${target.formatDuration()} / ${dur.formatDuration()}",
                                            target.toFloat() / dur
                                        )
                                    )
                                }
                            }

                            DragMode.BRIGHTNESS -> {
                                brightness =
                                    (startBrightness - totalY / size.height * 1.5f).coerceIn(
                                        0.02f, 1f
                                    )
                                applyBrightness(brightness)
                                showHud(
                                    Hud(
                                        Icons.Default.BrightnessMedium,
                                        "${(brightness * 100).roundToInt()}%",
                                        brightness,
                                        BrightnessTint
                                    )
                                )
                            }

                            DragMode.VOLUME -> {
                                volume =
                                    (startVolume - totalY / size.height * 1.5f).coerceIn(0f, 1f)
                                applyVolume(volume)
                                if (volume > 0f) muted = false
                                showHud(
                                    Hud(
                                        when {
                                            volume == 0f -> Icons.Default.VolumeOff; volume < 0.5f -> Icons.Default.VolumeDown; else -> Icons.Default.VolumeUp
                                        }, "${(volume * 100).roundToInt()}%", volume, VolumeTint
                                    )
                                )
                            }

                            DragMode.NONE -> Unit
                        }
                    })
            })

        // ---------- double-tap seek flash ----------
        AnimatedVisibility(
            visible = flashVisible,
            modifier = Modifier
                .align(if (flashSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 28.dp),
            enter = scaleIn(
                spring<Float>(Spring.DampingRatioMediumBouncy), initialScale = 0.6f
            ) + fadeIn(tween(100)),
            exit = scaleOut(tween(180), targetScale = 0.85f) + fadeOut(tween(180))
        ) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (flashSide < 0) Icons.Default.Replay10 else Icons.Default.Forward10,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        "$flashAmount sec",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ---------- gesture HUD (brightness / volume / seek / aspect) ----------
        val shownHud = hud
        AnimatedVisibility(
            visible = hudVisible && shownHud != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 96.dp),
            enter = scaleIn(tween(160), initialScale = 0.85f) + fadeIn(tween(160)),
            exit = scaleOut(tween(200), targetScale = 0.92f) + fadeOut(tween(200))
        ) {
            if (shownHud != null) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color.White.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        Modifier
                            .width(170.dp)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            shownHud.icon,
                            null,
                            tint = shownHud.tint,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            shownHud.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        shownHud.progress?.let {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { it.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = shownHud.tint,
                                trackColor = Color.White.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
            }
        }

        // ---------- 2x boost chip ----------
        AnimatedVisibility(
            visible = boosting,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
            enter = scaleIn(
                spring<Float>(Spring.DampingRatioMediumBouncy), initialScale = 0.6f
            ) + fadeIn(),
            exit = scaleOut(targetScale = 0.8f) + fadeOut()
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f)) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoostArrows()
                    Spacer(Modifier.width(6.dp))
                    Text("2x speed", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        if (buffering && !ended) {
            CircularProgressIndicator(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }

        // ---------- top bar ----------
        AnimatedVisibility(
            chrome,
            Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .edgeBleedGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), top = true
                    )
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ closing = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                ) {
                    Text(
                        current.bucketName ?: current.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        "${current.displayDate.dateLabel()} at ${
                            current.displayDate.timeLabel().lowercase()
                        }  ·  ${index + 1}/${items.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
                // Speed chip pops in whenever playback speed is changed; tap to reset.
                AnimatedVisibility(
                    visible = speed != 1f, enter = scaleIn(
                        spring<Float>(Spring.DampingRatioMediumBouncy), initialScale = 0.5f
                    ) + fadeIn(), exit = scaleOut(targetScale = 0.5f) + fadeOut()
                ) {
                    Row(
                        Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { speed = 1f; interaction++ }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Speed,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            speedLabel(speed),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                IconButton({ cycleAspect() }) {
                    Icon(
                        Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White
                    )
                }
                IconButton({ rotate() }) {
                    Icon(
                        Icons.Default.ScreenRotation, "Rotate", tint = Color.White
                    )
                }
                Box {
                    IconButton({ menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert, "More", tint = Color.White
                        )
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit video", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                            onClick = { menuOpen = false; openEditor() })
                        HorizontalDivider()
                        SPEEDS.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                Text(
                                    "Speed  ${speedLabel(s)}",
                                    fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                                leadingIcon = { Icon(Icons.Default.Speed, null) },
                                trailingIcon = { if (s == speed) Icon(Icons.Default.Check, null) },
                                onClick = { speed = s; menuOpen = false })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menuOpen = false; showInfo = true })
                    }
                }
            }
        }

        // ---------- centre transport controls ----------
        AnimatedVisibility(
            chrome,
            Modifier.align(Alignment.Center),
            enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.85f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundControl(Icons.Default.SkipPrevious, "Previous", 48.dp, enabled = index > 0) {
                    if (index > 0) openMedia(index - 1)
                }
                PlayButton(
                    icon = when {
                        ended -> Icons.Default.Replay; isPlaying -> Icons.Default.Pause; else -> Icons.Default.PlayArrow
                    }, pulse = !isPlaying
                ) { togglePlay(); interaction++ }
                RoundControl(
                    Icons.Default.SkipNext, "Next", 48.dp, enabled = index < items.lastIndex
                ) {
                    if (index < items.lastIndex) openMedia(index + 1)
                }
            }
        }

        // ---------- bottom: seek bar + actions ----------
        AnimatedVisibility(
            chrome,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .edgeBleedGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), top = false
                    )
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

                // time bubble that floats above the seek bar while it is dragged
                AnimatedVisibility(
                    visible = scrubbing,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.8f),
                    exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f)
                ) {
                    Surface(shape = RoundedCornerShape(50), color = PillColor) {
                        Text(
                            "${position.formatDuration()} / ${duration.formatDuration()}",
                            Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                val thumbSize by animateDpAsState(
                    targetValue = if (scrubbing) 20.dp else 12.dp,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                    label = "seekThumb"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(position.formatDuration(), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration).coerceIn(
                        0f, 1f
                    ) else 0f, onValueChange = {
                        seeking = true; scrubbing = true; position =
                        (it * duration).toLong(); interaction++
                    }, onValueChangeFinished = {
                        player.seekTo(position); seeking = false; scrubbing = false
                    }, colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ), thumb = {
                        Box(
                            Modifier
                                .size(thumbSize)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }, modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(duration.formatDuration(), color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleAction(Icons.Default.Share, "Share") {
                        MediaShareManager.share(
                            context, listOf(current.uri), current.mimeType ?: "*/*"
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = PillColor,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Color.White.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val heartTint by animateColorAsState(
                                if (isFavorite) FavoriteTint else Color.White, label = "heart"
                            )
                            ActionIcon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                "Favourite",
                                heartTint,
                                bouncy = true
                            ) {
                                favOverride = !isFavorite
                                vm.toggleFavorite(listOf(current))
                            }
                            ActionIcon(
                                if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                "Mute",
                                Color.White
                            ) { muted = !muted }
                            EditButton { openEditor() }
                            ActionIcon(
                                Icons.Default.Delete, "Delete", Color.White
                            ) { deleteConfirm = true }
                        }
                    }
                    CircleAction(Icons.Default.Info, "Details") { showInfo = true }
                }
            }
        }

        // ---------- hairline progress while the controls are hidden ----------
        AnimatedVisibility(
            visible = !chrome && duration > 0,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(150))
        ) {
            LinearProgressIndicator(
                progress = { hairline },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color.White.copy(alpha = 0.85f),
                trackColor = Color.White.copy(alpha = 0.18f)
            )
        }

        if (busy) LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )
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
            onConfirm = { deleteConfirm = false; vm.moveToTrash(listOf(current)) })
    }
}

// =========================================================================
// Building blocks
// =========================================================================

/** Click with a springy "squish" on press. */
@Composable
private fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "bounce"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = source, indication = null, enabled = enabled, onClick = onClick
        )
}

/** An icon that pops / cross-scales when the vector changes (play <-> pause, heart, mute...). */
@Composable
private fun SwapIcon(
    icon: ImageVector, desc: String?, tint: Color, size: Dp = 24.dp, bouncy: Boolean = false
) {
    AnimatedContent(
        targetState = icon, transitionSpec = {
            val enterSpec = if (bouncy) spring<Float>(
                Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium
            ) else tween<Float>(180)
            (scaleIn(enterSpec, initialScale = 0.4f) + fadeIn(tween(120))) togetherWith (scaleOut(
                tween(120),
                targetScale = 0.4f
            ) + fadeOut(tween(100)))
        }, label = "swapIcon"
    ) { vector -> Icon(vector, desc, Modifier.size(size), tint = tint) }
}

@Composable
private fun ActionIcon(
    icon: ImageVector, desc: String, tint: Color, bouncy: Boolean = false, onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .bounceClick(onClick = onClick), contentAlignment = Alignment.Center
    ) {
        SwapIcon(icon, desc, tint, 24.dp, bouncy)
    }
}

/** The "Edit video" button: a gradient circle with a soft breathing glow. */
@Composable
private fun EditButton(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "editGlow")
    val glow by t.animateFloat(
        initialValue = 0.15f, targetValue = 0.55f, animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "glow"
    )
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(Modifier
            .size(46.dp)
            .graphicsLayer { alpha = glow; scaleX = 1.2f; scaleY = 1.2f }
            .background(AccentGradient, CircleShape))
        Box(
            Modifier
                .size(42.dp)
                .bounceClick(onClick = onClick)
                .clip(CircleShape)
                .background(AccentGradient), contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ContentCut,
                "Edit video",
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

/** Big centre play button. While paused a ring ripples out of it. */
@Composable
private fun PlayButton(icon: ImageVector, pulse: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (pulse) PulseRing(72.dp)
        Box(
            Modifier
                .size(72.dp)
                .bounceClick(onClick = onClick)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xCC3A3A3F), Color(0xCC1C1C20))))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            SwapIcon(icon, "Play/Pause", Color.White, 38.dp)
        }
    }
}

@Composable
private fun PulseRing(size: Dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "pulseP"
    )
    Box(Modifier
        .size(size)
        .graphicsLayer {
            val s = 1f + 0.4f * p
            scaleX = s; scaleY = s
            alpha = (1f - p) * 0.5f
        }
        .border(2.dp, Color.White, CircleShape))
}

/** The little "fast-forward" icon in the 2x chip nudges to the right in a loop. */
@Composable
private fun BoostArrows() {
    val t = rememberInfiniteTransition(label = "boost")
    val dx by t.animateFloat(
        initialValue = -2f, targetValue = 3f, animationSpec = infiniteRepeatable(
            tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "boostDx"
    )
    Icon(
        Icons.Default.FastForward,
        null,
        tint = Color.White,
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer { translationX = dx * density })
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    label: String,
    size: Dp,
    iconSize: Dp = 26.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .bounceClick(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            label,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .bounceClick(onClick = onClick)
            .clip(CircleShape)
            .background(PillColor)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

// =========================================================================
// Details sheet
// =========================================================================

@Composable
private fun VideoDetails(item: MediaItem, durationMs: Long) {
    val format = item.mimeType?.substringAfter('/')?.uppercase() ?: "VIDEO"
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(item.displayDate.dateTimeLabel(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(item.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)

        Spacer(Modifier.height(18.dp))
        Text("Video details", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile(
                Icons.Default.Schedule, "Duration", durationMs.formatDuration(), Modifier.weight(1f)
            )
            InfoTile(
                Icons.Default.AspectRatio,
                "Resolution",
                "${item.width} × ${item.height}",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile(Icons.Default.Storage, "Size", item.size.formatBytes(), Modifier.weight(1f))
            InfoTile(Icons.Default.Movie, "Format", format, Modifier.weight(1f))
        }

        item.bucketName?.let {
            Spacer(Modifier.height(12.dp))
            InfoTile(Icons.Default.PhotoLibrary, "Album", it, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoTile(
    icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AccentGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}