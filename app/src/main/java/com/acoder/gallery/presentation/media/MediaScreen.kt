@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.media

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.acoder.gallery.core.collage.CollageConfig
import com.acoder.gallery.core.collage.CollageFrame
import com.acoder.gallery.core.collage.CollageGenerator
import com.acoder.gallery.core.collage.CollageLayout
import com.acoder.gallery.core.collage.CollagePreviewDialog
import com.acoder.gallery.core.media.MediaOperationWorker
import com.acoder.gallery.core.sharing.ExportManager
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.core.util.formatDuration
import com.acoder.gallery.domain.model.GridRow
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.PdfPageSize
import com.acoder.gallery.domain.model.PdfQuality
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.presentation.common.BarAction
import com.acoder.gallery.presentation.common.CircleButton
import com.acoder.gallery.presentation.common.EmptyState
import com.acoder.gallery.presentation.common.FloatingActionPill
import com.acoder.gallery.presentation.common.FloatingBarClearance
import com.acoder.gallery.presentation.common.GalleryDialog
import com.acoder.gallery.presentation.common.LargeTitle
import com.acoder.gallery.presentation.common.MediaBadge
import com.acoder.gallery.presentation.common.MediaThumb
import com.acoder.gallery.presentation.common.SelectionBadge
import com.acoder.gallery.presentation.common.label
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Delete this photo?" / "Delete this video?" / "Delete 5 items?" */
fun deleteTitle(items: List<MediaItem>): String = when {
    items.size == 1 && items[0].isVideo -> "Delete this video?"
    items.size == 1 -> "Delete this photo?"
    items.all { it.isVideo } -> "Delete ${items.size} videos?"
    items.none { it.isVideo } -> "Delete ${items.size} photos?"
    else -> "Delete ${items.size} items?"
}

fun deleteMessage(items: List<MediaItem>, trashSupported: Boolean): String {
    val what = when {
        items.size == 1 && items[0].isVideo -> "This video"
        items.size == 1 -> "This photo"
        else -> "These ${items.size} items"
    }
    return if (trashSupported) {
        "$what will be moved to the Recycle bin and deleted permanently after 30 days. You can restore ${if (items.size == 1) "it" else "them"} until then."
    } else {
        "$what will be deleted from this device. This can't be undone."
    }
}

@Composable
fun MediaScreen(vm: HomeViewModel, nav: NavHostController, isAlbum: Boolean = false) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val state by vm.mediaState.collectAsState()
    val selected by vm.selected.collectAsState()
    val query by vm.query.collectAsState()
    val grid by vm.grid.collectAsState()
    val filter by vm.filter.collectAsState()
    val albumName by vm.albumName.collectAsState()
    val busy by vm.busy.collectAsState()
    val sortSheetOpen by vm.sortSheetOpen.collectAsState()

    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pdfConfirm by remember { mutableStateOf(false) }
    var collageConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf("copy") }

    val snapshot = state.items
    // In the order the user tapped them — that is the page order for PDFs and collages.
    val selectedItems = remember(snapshot, selected) {
        if (selected.isEmpty()) emptyList() else {
            val byKey = snapshot.associateBy { it.key }
            selected.mapNotNull { byKey[it] }
        }
    }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && searchOpen) { searchOpen = false; vm.setQuery("") }

    // A process restore can leave us on the album route with no album chosen.
    LaunchedEffect(isAlbum, albumName) { if (isAlbum && albumName == null) nav.popBackStack() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && selectedItems.isNotEmpty()) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val data = workDataOf(
                "mode" to moveMode,
                "dest" to tree.toString(),
                "uris" to selectedItems.map { it.uri.toString() }.toTypedArray()
            )
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<MediaOperationWorker>().setInputData(data).build())
            vm.notify(if (moveMode == "move") "Moving ${selectedItems.size} item(s)…" else "Copying ${selectedItems.size} item(s)…")
            vm.clearSelection()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- Header ----------------
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleButton(Icons.Default.Close, "Clear selection", { vm.clearSelection() })
                    Text(
                        "${selected.size} selected",
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    CircleButton(Icons.Default.SelectAll, "Select all", { vm.selectAll(snapshot.map { it.key }) })
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isAlbum) {
                        CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { nav.popBackStack() })
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        if (isAlbum) albumName.orEmpty() else "Photos",
                        modifier = Modifier.weight(1f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    // when line exceed more than 1  fontSize will be changed to 24sp
                   /* val title = if (isAlbum) albumName.orEmpty() else "Photos"
                   // Reset whenever the title changes
                    var fontSize by remember(title) { mutableStateOf(28.sp) }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        onTextLayout = { result ->
                            if (result.lineCount > 1 && fontSize != 24.sp) {
                                fontSize = 24.sp
                            }
                        }
                    )*/
                    CircleButton(Icons.Default.Search, "Search", { searchOpen = !searchOpen; if (!searchOpen) vm.setQuery("") })
                    Spacer(Modifier.width(10.dp))
                    Box {
                        CircleButton(Icons.Default.MoreVert, "More", { menuOpen = true })
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                onClick = { menuOpen = false; vm.selectAll(snapshot.map { it.key }) }
                            )
                            DropdownMenuItem(
                                text = { Text("Recycle bin") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menuOpen = false; nav.navigate("trash") }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.GridView, null) },
                                onClick = { menuOpen = false; nav.navigate("settings") }
                            )
                        }
                    }
                }
                if (searchOpen) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus),
                        singleLine = true,
                        shape = CircleShape,
                        placeholder = { Text("Search photos and videos") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { CircleButton(Icons.Default.Close, "Clear", { vm.setQuery("") }, size = 36.dp) }
                        } else null,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
                if (filter != MediaFilter.ALL) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        InputChip(
                            selected = true,
                            onClick = { vm.setFilter(MediaFilter.ALL) },
                            label = { Text(filter.label()) },
                            trailingIcon = { Icon(Icons.Default.Close, "Clear filter", Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            // ---------------- Content ----------------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.items.isEmpty() && query.isNotBlank() ->
                        EmptyState(Icons.Default.SearchOff, "No matches for \u201c$query\u201d", "Try a different search term.")
                    state.items.isEmpty() ->
                        EmptyState(Icons.Default.PhotoLibrary, "Nothing here yet", "Photos and videos you add will show up in this view.")
                    else -> MediaGrid(state.rows, grid, selected, onTap = { item ->
                        if (selecting) {
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

        // ---------------- Selection actions ----------------
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            val allFavorite = selectedItems.isNotEmpty() && selectedItems.all { it.isFavorite }
            FloatingActionPill(
                listOf(
                    BarAction(Icons.Default.Share, "Share", {
                        MediaShareManager.share(context, selectedItems.map { it.uri }, selectedItems.firstOrNull()?.mimeType ?: "*/*")
                    }),
                    BarAction(if (allFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favourite", { vm.toggleFavorite(selectedItems) }),
                    BarAction(Icons.Default.PictureAsPdf, "PDF", { pdfConfirm = true }),
                    BarAction(Icons.Default.Delete, "Delete", { deleteConfirm = true }),
                    BarAction(Icons.Default.MoreHoriz, "More", { moreOpen = true })
                )
            )
        }
    }

    // ---------------- Dialogs & sheets ----------------

    if (deleteConfirm && selectedItems.isNotEmpty()) {
        GalleryDialog(
            title = deleteTitle(selectedItems),
            message = deleteMessage(selectedItems, vm.trashSupported),
            confirmLabel = "Delete",
            onDismiss = { deleteConfirm = false },
            onConfirm = { deleteConfirm = false; vm.moveToTrash(selectedItems) }
        )
    }

    if (pdfConfirm) {
        var pageSize by remember { mutableStateOf(PdfPageSize.A4) }
        var pdfQuality by remember { mutableStateOf(PdfQuality.HIGH) }
        AlertDialog(
            onDismissRequest = { pdfConfirm = false },
            title = { Text("Create PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${selectedItems.count { !it.isVideo }} selected photo(s) will be combined into one PDF, saved to Downloads and shared.")
                    Text("Page size", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfPageSize.entries.forEach { size ->
                            FilterChip(selected = pageSize == size, onClick = { pageSize = size }, label = { Text(size.label) })
                        }
                    }
                    Text("Quality", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfQuality.entries.forEach { q ->
                            FilterChip(selected = pdfQuality == q, onClick = { pdfQuality = q }, label = { Text(q.label) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pdfConfirm = false
                    val chosen = selectedItems.filter { !it.isVideo }
                    if (chosen.isEmpty()) vm.notify("Select at least one photo to make a PDF")
                    else vm.createPdf(context, chosen, pageSize, pdfQuality)
                }) { Text("Create") }
            },
            dismissButton = { TextButton({ pdfConfirm = false }) { Text("Cancel") } }
        )
    }

    if (collageConfirm) {
        val photos = selectedItems.filter { !it.isVideo }
        if (photos.isEmpty()) {
            LaunchedEffect(Unit) {
                vm.notify("Select at least one photo to make a collage")
                collageConfirm = false
            }
        } else {
            val uris = remember { photos.map { it.uri } }
            CollagePreviewDialog(
                uris = uris,
                onDismiss = { collageConfirm = false },
                onCreate = { config ->
                    collageConfirm = false
                    vm.notify("Creating collage…")
                    scope.launch(Dispatchers.IO) {
                        try {
                            val file = CollageGenerator.generate(context, uris, config)
                            val saved = ExportManager.saveToDownloads(
                                context, file, "image/jpeg", "Collage_${System.currentTimeMillis()}.jpg"
                            )
                            withContext(Dispatchers.Main) {
                                if (saved != null) MediaShareManager.share(context, listOf(saved), "image/jpeg")
                                else vm.notify("Couldn't save the collage")
                            }
                        } catch (t: Throwable) {
                            Log.e("Collage", "Collage creation failed", t)
                            withContext(Dispatchers.Main) {
                                vm.notify("Collage creation failed: ${t.message ?: t::class.java.simpleName}")
                            }
                        }
                    }
                    vm.clearSelection()
                }
            )
        }
    }
    if (moreOpen) AlertDialog(
        onDismissRequest = { moreOpen = false },
        title = { Text("${selectedItems.size} selected") },
        text = {
            Column {
                TextButton({ moreOpen = false; collageConfirm = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.GridView, null); Spacer(Modifier.width(8.dp)); Text("Create collage", Modifier.weight(1f))
                }
                TextButton({ moveMode = "copy"; moreOpen = false; treeLauncher.launch(null) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DriveFileMove, null); Spacer(Modifier.width(8.dp)); Text("Copy to folder", Modifier.weight(1f))
                }
                TextButton({ moveMode = "move"; moreOpen = false; treeLauncher.launch(null) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DriveFileMove, null); Spacer(Modifier.width(8.dp)); Text("Move to folder", Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton({ moreOpen = false }) { Text("Close") } }
    )

    if (sortSheetOpen) SortFilterSheet(vm, onDismiss = { vm.sortSheetOpen.value = false })
}

@Composable
private fun SortFilterSheet(vm: HomeViewModel, onDismiss: () -> Unit) {
    val sort by vm.sort.collectAsState()
    val filter by vm.filter.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Show", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { vm.setFilter(option) }, label = { Text(option.label()) })
                }
            }
            Text("Sort by", Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            SortOrder.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { vm.setSort(option) }).padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = sort == option, onClick = { vm.setSort(option) })
                    Text(option.label(), fontSize = 16.sp)
                }
            }
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
    val bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        contentPadding = PaddingValues(bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
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

@Composable
private fun MediaTile(item: MediaItem, selectionActive: Boolean, isSelected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLong)
    ) {
        MediaThumb(item.uri, Modifier.fillMaxSize(), item.name)
        if (item.isVideo) {
            if (item.duration > 0) {
                MediaBadge(item.duration.formatDuration(), Modifier.align(Alignment.BottomStart).padding(6.dp))
            } else {
                Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp), tint = Color.White)
            }
        }
        if (item.isFavorite) {
            Icon(Icons.Default.Star, "Favourite", Modifier.align(Alignment.BottomEnd).padding(6.dp).size(16.dp), tint = Color.White)
        }
        if (selectionActive) {
            if (isSelected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            SelectionBadge(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}
