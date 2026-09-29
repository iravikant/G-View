package com.acoder.gallery.presentation.video

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.acoder.gallery.core.util.formatDuration
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(vm: HomeViewModel, nav: NavHostController) {
    val items by vm.viewerItems.collectAsState()
    val index by vm.viewerIndex.collectAsState()
    val autoPlay by vm.autoPlay.collectAsState()
    val context = LocalContext.current

    if (items.isEmpty()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val current = items.getOrNull(index) ?: items.first()

    val player = remember(current.key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoItem.fromUri(current.uri))
            prepare()
            playWhenReady = autoPlay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    BackHandler { nav.popBackStack() }

    var isPlaying by remember(current.key) { mutableStateOf(autoPlay) }
    var position by remember(current.key) { mutableLongStateOf(0L) }
    var duration by remember(current.key) { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(1f) }
    var brightness by remember { mutableFloatStateOf(0.5f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!seeking) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
            }
            delay(300)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { seeking = true; position = (it * duration).toLong() },
                onValueChangeFinished = { player.seekTo(position); seeking = false },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(position.formatDuration(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(duration.formatDuration(), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton({ if (index > 0) vm.viewerIndex.value = index - 1 }, enabled = index > 0) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                }
                IconButton({ player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White)
                }
                FilledIconButton({ if (isPlaying) player.pause() else player.play() }, Modifier.size(56.dp)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                }
                IconButton({ player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }) {
                    Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White)
                }
                IconButton({ if (index < items.lastIndex) vm.viewerIndex.value = index + 1 }, enabled = index < items.lastIndex) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ muted = !muted; player.volume = if (muted) 0f else volume }) {
                    Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Mute", tint = Color.White)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it; muted = false; player.volume = it },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(88.dp))
                Slider(
                    value = brightness,
                    onValueChange = {
                        brightness = it
                        (context as? Activity)?.window?.attributes = (context as? Activity)?.window?.attributes?.apply {
                            screenBrightness = brightness.coerceIn(0.02f, 1f)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        IconButton({ nav.popBackStack() }, Modifier.padding(top = 24.dp, start = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
        }
    }
}
