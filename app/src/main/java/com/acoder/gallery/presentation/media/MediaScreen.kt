@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.media

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import com.acoder.gallery.core.collage.CollageGenerator
import com.acoder.gallery.core.media.MediaOperationWorker
import com.acoder.gallery.core.pdf.PdfGenerator
import com.acoder.gallery.core.sharing.ExportManager
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.formatDuration
import com.acoder.gallery.domain.model.*
import com.acoder.gallery.presentation.common.EmptyState
import com.acoder.gallery.presentation.common.label
import com.acoder.gallery.presentation.home.HomeEvent
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(vm: HomeViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val state by vm.mediaState.collectAsState()
    val selected by vm.selected.collectAsState()
    val query by vm.query.collectAsState()
    val grid by vm.grid.collectAsState()
    val filter by vm.filter.collectAsState()
    val albumName by vm.albumName.collectAsState()
    val busy by vm.busy.collectAsState()

    var searchOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var pdfConfirm by remember { mutableStateOf(false) }
    var collageConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var moveSheetOpen by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf("copy") }
    var pendingDeleteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    val snapshot = state.items
    val selectedLoaded = remember(snapshot, selected) { if (selected.isEmpty()) emptyList() else snapshot.filter { it.key in selected } }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.onDeleteConfirmed(pendingDeleteKeys) // list updates itself via the MediaStore observer
        }
    }
    val favoriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.notify("Favorites updated")
        }
        vm.clearSelection()
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && selectedLoaded.isNotEmpty()) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val data = workDataOf(
                "mode" to moveMode,
                "dest" to tree.toString(),
                "uris" to selectedLoaded.map { it.uri.toString() }.toTypedArray()
            )
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<MediaOperationWorker>().setInputData(data).build())
            vm.notify(if (moveMode == "move") "Moving ${selectedLoaded.size} item(s)…" else "Copying ${selectedLoaded.size} item(s)…")
            vm.clearSelection()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.Message -> scope.launch { snackbarHost.showSnackbar(event.text) }
                is HomeEvent.ConfirmDelete -> {
                    pendingDeleteKeys = event.pendingKeys
                    deleteLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                }
                is HomeEvent.ConfirmFavorite -> favoriteLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                selected.isNotEmpty() -> "${selected.size} selected"
                                albumName != null -> albumName!!
                                else -> "Gallery"
                            }
                        )
                    },
                    navigationIcon = {
                        if (albumName != null && selected.isEmpty()) {
                            IconButton({ vm.openAlbum(null, null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        }
                    },
                    actions = {
                        if (selected.isEmpty()) {
                            IconButton({ searchOpen = !searchOpen }) { Icon(Icons.Outlined.Search, "Search") }
                            IconButton({ sortOpen = true }) { Icon(Icons.Default.Sort, "Sort") }
                            IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                            SortMenu(sortOpen, vm, onDismiss = { sortOpen = false })
                        } else {
                            IconButton({ vm.selectAll(snapshot.map { it.key }) }) { Icon(Icons.Default.SelectAll, "Select all loaded") }
                            IconButton({ vm.clearSelection() }) { Icon(Icons.Default.Close, "Clear selection") }
                        }
                    }
                )
                AnimatedVisibility(searchOpen && selected.isEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        placeholder = { Text("Search photos and videos") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton({ vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear") } }
                        } else null
                    )
                }
                if (selected.isEmpty()) {
                    FilterRow(filter, onSelect = vm::setFilter)
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(selected.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SelectionActionBar(
                    allFavorite = selectedLoaded.isNotEmpty() && selectedLoaded.all { it.isFavorite },
                    onFavorite = { vm.toggleFavorite(selectedLoaded) },
                    onShare = { MediaShareManager.share(context, selectedLoaded.map { it.uri }, selectedLoaded.firstOrNull()?.mimeType ?: "*/*") },
                    onDelete = { deleteConfirm = true },
                    onPdf = { pdfConfirm = true },
                    onCollage = { collageConfirm = true },
                    onMore = { moreMenuOpen = true }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.items.isEmpty() && query.isNotBlank() ->
                    EmptyState(Icons.Default.SearchOff, "No matches for \u201c$query\u201d", "Try a different search term.")
                state.items.isEmpty() ->
                    EmptyState(Icons.Default.PhotoLibrary, "Nothing here yet", "Photos and videos you add will show up in this view.")
                else -> MediaGrid(state.rows, grid, selected, onTap = { item ->
                    if (selected.isNotEmpty()) {
                        vm.toggle(item.key)
                    } else {
                        vm.openViewer(state.items, state.items.indexOfFirst { it.key == item.key }.coerceAtLeast(0))
                        nav.navigate(if (item.isVideo) "video" else "viewer")
                    }
                }, onLong = { vm.toggle(it.key) })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (pdfConfirm) ConfirmDialog(
        "Create PDF?",
        "${selectedLoaded.size} selected image(s) will be combined into one PDF and shared.",
        onDismiss = { pdfConfirm = false }
    ) {
        pdfConfirm = false
        val chosen = selectedLoaded.filter { !it.isVideo }
        if (chosen.isEmpty()) {
            vm.notify("Select at least one photo to make a PDF")
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    val file = PdfGenerator.generate(context, chosen.map { it.uri })
                    val saved = ExportManager.saveToDownloads(context, file, "application/pdf", "Gallery_${System.currentTimeMillis()}.pdf")
                    withContext(Dispatchers.Main) {
                        if (saved != null) MediaShareManager.share(context, listOf(saved), "application/pdf")
                        else vm.notify("Couldn't save the PDF")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { vm.notify("PDF creation failed: ${e.message ?: "unknown error"}") }
                }
            }
        }
        vm.clearSelection()
    }

    if (collageConfirm) ConfirmDialog(
        "Create collage?",
        "${selectedLoaded.size} selected image(s) will be arranged into a collage and shared.",
        onDismiss = { collageConfirm = false }
    ) {
        collageConfirm = false
        val chosen = selectedLoaded.filter { !it.isVideo }
        if (chosen.isEmpty()) {
            vm.notify("Select at least one photo to make a collage")
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    val file = CollageGenerator.generate(context, chosen.map { it.uri })
                    val saved = ExportManager.saveToDownloads(context, file, "image/jpeg", "Collage_${System.currentTimeMillis()}.jpg")
                    withContext(Dispatchers.Main) {
                        if (saved != null) MediaShareManager.share(context, listOf(saved), "image/jpeg")
                        else vm.notify("Couldn't save the collage")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { vm.notify("Collage creation failed: ${e.message ?: "unknown error"}") }
                }
            }
        }
        vm.clearSelection()
    }

    if (deleteConfirm) ConfirmDialog(
        "Delete ${selectedLoaded.size} item(s)?",
        "This can't be undone. Android may ask you to confirm once more.",
        destructive = true,
        onDismiss = { deleteConfirm = false }
    ) {
        deleteConfirm = false
        vm.requestDelete(selectedLoaded)
    }

    if (moreMenuOpen) AlertDialog(
        onDismissRequest = { moreMenuOpen = false },
        title = { Text("Manage ${selectedLoaded.size} item(s)") },
        text = {
            Column {
                TextButton({ moveMode = "copy"; moreMenuOpen = false; treeLauncher.launch(null) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DriveFileMove, null); Spacer(Modifier.width(8.dp)); Text("Copy to folder")
                }
                TextButton({ moveMode = "move"; moreMenuOpen = false; treeLauncher.launch(null) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DriveFileMove, null); Spacer(Modifier.width(8.dp)); Text("Move to folder")
                }
            }
        },
        confirmButton = { TextButton({ moreMenuOpen = false }) { Text("Close") } }
    )
}

@Composable
private fun FilterRow(current: MediaFilter, onSelect: (MediaFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MediaFilter.values().forEach { filter ->
            FilterChip(
                selected = current == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label()) }
            )
        }
    }
}

@Composable
private fun SortMenu(expanded: Boolean, vm: HomeViewModel, onDismiss: () -> Unit) {
    val current by vm.sort.collectAsState()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortOrder.values().forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label()) },
                leadingIcon = { if (option == current) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                onClick = { vm.setSort(option); onDismiss() }
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    allFavorite: Boolean,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPdf: () -> Unit,
    onCollage: () -> Unit,
    onMore: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionIcon(if (allFavorite) "Unfavorite" else "Favorite", if (allFavorite) Icons.Default.Star else Icons.Outlined.Star, onFavorite)
            ActionIcon("Share", Icons.Default.Share, onShare)
            ActionIcon("PDF", Icons.Default.PictureAsPdf, onPdf)
            ActionIcon("Collage", Icons.Default.GridView, onCollage)
            ActionIcon("Delete", Icons.Default.Delete, onDelete)
            ActionIcon("More", Icons.Default.MoreVert, onMore)
        }
    }
}

@Composable
private fun MediaGrid(
    rows: List<GridRow>,
    columns: Int,
    selected: Set<String>,
    onTap: (MediaItem) -> Unit,
    onLong: (MediaItem) -> Unit
) {
    // Stable keys keep the scroll position anchored when the list updates underneath the user.
    val gridState = rememberLazyGridState()
    val selectionActive = selected.isNotEmpty()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceIn(2, 5)),
        contentPadding = PaddingValues(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = rows,
            key = { it.key },
            span = { row -> if (row is GridRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { row -> if (row is GridRow.Header) "header" else (row as GridRow.Media).item.type }
        ) { row ->
            when (row) {
                is GridRow.Header -> Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
                is GridRow.Media -> MediaTile(
                    item = row.item,
                    selectionActive = selectionActive,
                    isSelected = row.item.key in selected,
                    onTap = { onTap(row.item) },
                    onLong = { onLong(row.item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(item: MediaItem, selectionActive: Boolean, isSelected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLong)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.uri).videoFrameMillis(1000).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.isVideo) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (selectionActive && isSelected) 0.35f else 0f)))
            Icon(
                Icons.Default.PlayCircle, null,
                Modifier.align(Alignment.Center).size(28.dp),
                tint = Color.White.copy(alpha = 0.92f)
            )
            if (item.duration > 0) {
                Text(
                    item.duration.formatDuration(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        if (item.isFavorite) {
            Icon(
                Icons.Default.Star, "Favorite",
                Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp),
                tint = Color.White
            )
        }
        if (selectionActive) {
            Box(Modifier.fillMaxSize().background(if (isSelected) Color.Black.copy(alpha = 0.25f) else Color.Transparent))
            Surface(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape),
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                border = if (!isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)) else null
            ) {
                if (isSelected) Icon(Icons.Default.Check, null, Modifier.padding(2.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun ActionIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onConfirm) {
                Text("Confirm", color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
