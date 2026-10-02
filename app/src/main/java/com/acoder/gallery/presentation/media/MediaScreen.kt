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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.acoder.gallery.core.collage.CollageGenerator
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
import kotlinx.coroutines.isActive
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
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                   /*********************************************************************************************
                    *************** when line exceed more than 1  fontSize will be changed to 24sp  *************
                    *********************************************************************************************/

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
                    else -> MediaGrid(
                        rows = state.rows,
                        columns = grid,
                        selected = selected,
                        onTap = { item ->
                            if (selecting) {
                                vm.toggle(item.key)
                            } else {
                                vm.openViewer(state.items, state.items.indexOfFirst { it.key == item.key }.coerceAtLeast(0))
                                nav.navigate(if (item.isVideo) "video" else "viewer")
                            }
                        },
                        // Used by long-press + drag multi-select. Same call the tap handler uses.
                        onToggle = { key -> vm.toggle(key) },
                        // Pinch gesture: if your ViewModel's setter has another name, change it here.
                        onColumnsChange = { vm.setGrid(it) }
                    )
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

// =========================================================================
// Grid with gestures
// =========================================================================

private enum class TouchMode { TAP, SCROLL, PINCH, LONG_PRESS }

/** Bookkeeping for one long-press + drag selection. Only touched from the gesture and the auto-scroll loop. */
private class DragSelection {
    var active = false
    var anchor = -1
    var lastIndex = -1
    /** true = the drag selects items, false = it deselects them. */
    var selectMode = true
    var original: Set<String> = emptySet()
    /** Keys this drag has flipped so far, so they can be flipped back when the range shrinks. */
    val toggled = HashSet<String>()
    var pointer = Offset.Zero
}

private fun autoScrollStep(depth: Float, edge: Float): Float = (depth / edge).coerceIn(0.1f, 1.5f) * 28f

/**
 * Gestures on the media grid:
 *  - Tap: handled by the tiles.
 *  - Pinch (two fingers): fewer / more columns.
 *  - Long-press, then drag: select (or deselect) every item between the pressed item and the finger.
 *    Dragging near the top or bottom edge scrolls the grid.
 * Everything runs in one pointer handler (Initial pass) so the gestures never fight each other.
 * Events are consumed only while pinching or drag-selecting, so normal scrolling is untouched.
 */
@Composable
private fun MediaGrid(
    rows: List<GridRow>,
    columns: Int,
    selected: Set<String>,
    onTap: (MediaItem) -> Unit,
    onToggle: (String) -> Unit,
    onColumnsChange: (Int) -> Unit
) {
    // Stable keys keep the scroll position anchored when the list updates underneath the user.
    val gridState = rememberLazyGridState()
    val selectionActive = selected.isNotEmpty()
    val bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Latest values for the long-lived pointer-input block below.
    val currentRows by rememberUpdatedState(rows)
    val currentSelected by rememberUpdatedState(selected)
    val currentColumns by rememberUpdatedState(columns.coerceIn(2, 6))
    val currentToggle by rememberUpdatedState(onToggle)
    val currentOnColumns by rememberUpdatedState(onColumnsChange)
    val haptics = LocalHapticFeedback.current

    val drag = remember { DragSelection() }
    var autoScroll by remember { mutableStateOf(0f) }   // px per frame; negative = up, positive = down
    val autoScrolling by remember { derivedStateOf { autoScroll != 0f } }

    fun indexAt(p: Offset): Int =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            p.x >= info.offset.x && p.x < info.offset.x + info.size.width &&
                    p.y >= info.offset.y && p.y < info.offset.y + info.size.height
        }?.index ?: -1

    fun mediaKeyAt(index: Int): String? = (currentRows.getOrNull(index) as? GridRow.Media)?.item?.key

    /** Selects / deselects everything between the anchor and [toIndex], undoing items that left the range. */
    fun applyRange(toIndex: Int) {
        if (!drag.active || drag.anchor < 0) return
        val lo = minOf(drag.anchor, toIndex)
        val hi = maxOf(drag.anchor, toIndex)

        val want = HashSet<String>()
        for (i in lo..hi) {
            val key = mediaKeyAt(i) ?: continue
            if ((key in drag.original) != drag.selectMode) want += key
        }
        want.forEach { if (it !in drag.toggled) currentToggle(it) }
        drag.toggled.forEach { if (it !in want) currentToggle(it) }
        drag.toggled.clear()
        drag.toggled.addAll(want)
    }

    fun updatePointer(p: Offset) {
        drag.pointer = p
        val idx = indexAt(p)
        if (idx >= 0 && mediaKeyAt(idx) != null) drag.lastIndex = idx
        if (drag.lastIndex >= 0) applyRange(drag.lastIndex)
    }

    fun beginDrag(p: Offset): Boolean {
        val idx = indexAt(p)
        val key = mediaKeyAt(idx) ?: return false
        drag.active = true
        drag.anchor = idx
        drag.lastIndex = idx
        drag.pointer = p
        drag.original = currentSelected
        drag.toggled.clear()
        // Starting on an already selected item turns the drag into "deselect".
        drag.selectMode = key !in drag.original
        applyRange(idx)
        return true
    }

    fun endDrag() {
        drag.active = false
        autoScroll = 0f
    }

    // Keeps scrolling while the finger rests near the top or bottom edge during a drag selection.
    LaunchedEffect(autoScrolling) {
        if (!autoScrolling) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (!drag.active) break
            gridState.scrollBy(autoScroll)
            updatePointer(drag.pointer)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        contentPadding = PaddingValues(bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

                    // Phase 1: is this a long press, a pinch, a scroll or a plain tap?
                    var mode = TouchMode.TAP
                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                mode = TouchMode.PINCH
                                return@withTimeoutOrNull
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                mode = TouchMode.TAP
                                return@withTimeoutOrNull
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                mode = TouchMode.SCROLL
                                return@withTimeoutOrNull
                            }
                        }
                    } == null
                    if (timedOut) mode = TouchMode.LONG_PRESS

                    // Phase 2a: long press -> drag selection
                    if (mode == TouchMode.LONG_PRESS) {
                        if (beginDrag(down.position)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val edge = 88.dp.toPx()
                            val viewportH = size.height.toFloat()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                // Eat everything (including the final "up") so the grid doesn't scroll
                                // and the tile underneath doesn't register a click.
                                event.changes.forEach { it.consume() }
                                if (change == null || !change.pressed) break

                                val p = change.position
                                autoScroll = when {
                                    p.y < edge -> -autoScrollStep(edge - p.y, edge)
                                    p.y > viewportH - edge -> autoScrollStep(p.y - (viewportH - edge), edge)
                                    else -> 0f
                                }
                                updatePointer(p)
                            }
                            endDrag()
                            return@awaitEachGesture
                        }
                        // Long press on a header or in a gap: nothing to select.
                        mode = TouchMode.SCROLL
                    }

                    // Phase 2b: keep watching for a pinch (also when a scroll has already started)
                    var stillDown = mode != TouchMode.TAP
                    var scale = 1f
                    while (stillDown) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        stillDown = event.changes.any { it.pressed }
                        if (event.changes.count { it.pressed } >= 2) {
                            scale *= event.calculateZoom()

                            val stepOut = scale > 1.25f   // spread: bigger photos, fewer columns
                            val stepIn = scale < 0.8f     // pinch: smaller photos, more columns
                            if (stepOut || stepIn) {
                                val target = (if (stepOut) currentColumns - 1 else currentColumns + 1).coerceIn(2, 6)
                                scale = 1f
                                if (target != currentColumns) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnColumns(target)
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                }
            }
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
                    Modifier.padding(start = 18.dp, end = 20.dp, top = 15.dp, bottom = 15.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                is GridRow.Media -> MediaTile(
                    item = row.item,
                    selectionActive = selectionActive,
                    isSelected = row.item.key in selected,
                    onTap = { onTap(row.item) }
                )
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem, selectionActive: Boolean, isSelected: Boolean, onTap: () -> Unit) {
    // Long press is handled by the grid (long-press + drag selection), so the tile only handles taps.
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap)
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