package com.acoder.gallery.presentation.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.dateTimeLabel
import com.acoder.gallery.core.util.formatBytes
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.presentation.home.HomeEvent
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(vm: HomeViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(vm.viewerItems.value) }
    val start = vm.viewerIndex.value
    var showInfo by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    BackHandler { nav.popBackStack() }
    if (items.isEmpty()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }

    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex), pageCount = { items.size })
    val current = items.getOrNull(pager.currentPage) ?: items.first()

    val favoriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            items = items.map { if (it.key == current.key) it.copy(isFavorite = !it.isFavorite) else it }
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val remaining = items.filterNot { it.key == current.key }
            items = remaining
            if (remaining.isEmpty()) nav.popBackStack()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.ConfirmFavorite -> favoriteLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is HomeEvent.ConfirmDelete -> deleteLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is HomeEvent.Message -> scope.launch { snackbarHost.showSnackbar(event.text) }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { _ ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                ZoomableImage(items[page])
            }

            TopAppBar(
                title = { Text("${pager.currentPage + 1} / ${items.size}", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) } },
                actions = {
                    IconButton({ vm.toggleFavorite(listOf(current)) }) {
                        Icon(
                            if (current.isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                            "Favorite",
                            tint = if (current.isFavorite) Color(0xFFFFC107) else Color.White
                        )
                    }
                    IconButton({ showInfo = !showInfo }) { Icon(Icons.Default.Info, "Info", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            if (showInfo) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 16.dp, end = 16.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text("${current.displayDate.dateTimeLabel()}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        Text("${current.size.formatBytes()} · ${current.width}\u00d7${current.height}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        current.bucketName?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ViewerAction("Edit", Icons.Default.Edit) { nav.navigate("editor/${android.net.Uri.encode(current.uri.toString())}") }
                ViewerAction("Share", Icons.Default.Share) { MediaShareManager.share(context, listOf(current.uri), current.mimeType ?: "*/*") }
                ViewerAction("Delete", Icons.Default.Delete) { deleteConfirm = true }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Delete this item?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ deleteConfirm = false; vm.requestDelete(listOf(current)) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ViewerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick) { Icon(icon, label) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
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
            .pointerInput(item.key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = newScale
                    if (newScale <= 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                }
            }
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else { scale = 2.5f }
                })
            }
    )
}
