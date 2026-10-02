@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.acoder.gallery.core.sharing

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.acoder.gallery.core.util.formatDuration
import com.acoder.gallery.presentation.common.MediaBadge
import com.acoder.gallery.presentation.common.MediaThumb
import com.acoder.gallery.presentation.common.SelectionBadge
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The gallery's own picker. Other apps start it with ACTION_GET_CONTENT or ACTION_PICK
 * (see the manifest filters), the user chooses photos / videos in this app's UI, and the
 * chosen content:// URIs are returned to the caller with read permission granted.
 */
class PickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val filters = readMimeFilters(intent)

        setResult(RESULT_CANCELED) // back button / cancel = cancelled

        setContent {
            // Swap this for your app's own theme composable so the picker matches the rest of the app.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                PickerScreen(
                    filters = filters,
                    allowMultiple = allowMultiple,
                    onDone = ::returnResult,
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun returnResult(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val clip = ClipData.newUri(contentResolver, "media", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }

        val result = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = clip                           // multiple results (the grant covers every item)
            if (uris.size == 1) data = uris.first()   // single result, for callers that read data
        }
        setResult(RESULT_OK, result)
        finish()
    }

    // The mime types the caller wants: an image type, a video type, or "anything".
    // (Line comments on purpose: Kotlin block comments nest, so a wildcard type written inside one breaks the file.)
    private fun readMimeFilters(intent: Intent): List<String> {
        val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filter { it.isNotBlank() }
        if (!extra.isNullOrEmpty()) return extra

        val type = intent.type
        if (type != null && !type.startsWith("vnd.android.cursor")) return listOf(type)

        // ACTION_PICK with a MediaStore URI and no explicit type
        val data = intent.data?.toString().orEmpty()
        return when {
            data.contains("/video/") || type?.endsWith("video") == true -> listOf("video/*")
            data.contains("/images/") || type?.endsWith("image") == true -> listOf("image/*")
            else -> listOf("*/*")
        }
    }
}

// =========================================================================
// Model
// =========================================================================

private data class PickItem(
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val bucketId: String,
    val bucketName: String,
    val dateMillis: Long,
    val durationMs: Long
)

private data class AlbumInfo(val bucketId: String, val name: String, val count: Int, val cover: PickItem)

private sealed interface PickRow {
    val key: String

    data class Header(val label: String, override val key: String) : PickRow

    data class Media(val item: PickItem) : PickRow {
        override val key: String get() = item.uri.toString()
    }
}

// =========================================================================
// UI
// =========================================================================

@Composable
private fun PickerScreen(
    filters: List<String>,
    allowMultiple: Boolean,
    onDone: (List<Uri>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    // ----- permission -----
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun hasAccess() = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasAccess()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAccess()
    }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(permissions) }

    // ----- data -----
    var items by remember { mutableStateOf<List<PickItem>?>(null) }
    LaunchedEffect(granted) {
        if (granted) items = withContext(Dispatchers.IO) { loadMedia(context, filters) }
    }

    var tab by remember { mutableStateOf(0) }                  // 0 = Photos, 1 = Albums
    var albumId by remember { mutableStateOf<String?>(null) }  // set when an album was opened
    val selected = remember { mutableStateListOf<Uri>() }      // keeps the order the user tapped

    val all = items
    val albums = remember(all) {
        all.orEmpty().groupBy { it.bucketId }
            .map { (id, list) -> AlbumInfo(id, list.first().bucketName, list.size, list.first()) }
    }
    val visible = remember(all, albumId) {
        if (albumId == null) all.orEmpty() else all.orEmpty().filter { it.bucketId == albumId }
    }
    val rows = remember(visible) { buildRows(visible) }

    BackHandler(enabled = albumId != null) { albumId = null }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Black behind, sheet with rounded top corners on top, like a system picker.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {

                // ---------------- Top bar: Cancel | Photos / Albums | Done ----------------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        TextButton(onClick = onCancel) {
                            Text("Cancel", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    SegmentedPill(listOf("Photos", "Albums"), tab) { tab = it }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (allowMultiple) {
                            Button(onClick = { onDone(selected.toList()) }, enabled = selected.isNotEmpty()) {
                                Text(if (selected.isEmpty()) "Done" else "Done (${selected.size})")
                            }
                        }
                    }
                }

                // ---------------- Content ----------------
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !granted -> Message(
                            "Allow access to your photos and videos to choose from them.",
                            action = "Allow access"
                        ) { permissionLauncher.launch(permissions) }

                        all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        tab == 1 -> {
                            if (albums.isEmpty()) Message("No albums.")
                            else AlbumGrid(albums, bottomInset) { album ->
                                albumId = album.bucketId
                                tab = 0
                            }
                        }

                        else -> Column(Modifier.fillMaxSize()) {
                            val openedAlbum = albums.firstOrNull { it.bucketId == albumId }
                            if (openedAlbum != null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { albumId = null }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all photos")
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        openedAlbum.name,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (rows.isEmpty()) {
                                Message("Nothing to choose from here.")
                            } else {
                                PhotoGrid(rows, allowMultiple, selected, bottomInset) { item ->
                                    if (!allowMultiple) onDone(listOf(item.uri))
                                    else if (item.uri in selected) selected.remove(item.uri)
                                    else selected.add(item.uri)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The rounded "Photos | Albums" switch from the top bar. */
@Composable
private fun SegmentedPill(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    label,
                    fontSize = 18.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** Photos grouped by day ("Today", "Yesterday", "30 Sept", ...), three per row with thin gaps. */
@Composable
private fun PhotoGrid(
    rows: List<PickRow>,
    allowMultiple: Boolean,
    selected: List<Uri>,
    bottomInset: androidx.compose.ui.unit.Dp,
    onPick: (PickItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = bottomInset),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = rows,
            key = { it.key },
            span = { row -> if (row is PickRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { row -> if (row is PickRow.Header) "header" else "media" }
        ) { row ->
            when (row) {
                is PickRow.Header -> Text(
                    row.label,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                is PickRow.Media -> {
                    val item = row.item
                    val isSelected = item.uri in selected
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clickable { onPick(item) }
                    ) {
                        MediaThumb(item.uri, Modifier.fillMaxSize(), item.name)
                        if (item.isVideo) {
                            if (item.durationMs > 0) {
                                MediaBadge(
                                    item.durationMs.formatDuration(),
                                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow, null,
                                    Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        if (allowMultiple) {
                            if (isSelected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                            SelectionBadge(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Albums tab: two columns, cover photo with the album name and item count underneath. */
@Composable
private fun AlbumGrid(
    albums: List<AlbumInfo>,
    bottomInset: androidx.compose.ui.unit.Dp,
    onOpen: (AlbumInfo) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums, key = { it.bucketId }) { album ->
            Column(Modifier.clickable { onOpen(album) }) {
                MediaThumb(
                    album.cover.uri,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)),
                    album.name
                )
                Text(
                    album.name,
                    Modifier.padding(top = 8.dp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${album.count}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

// =========================================================================
// Data
// =========================================================================

private const val DAY_MS = 24L * 60L * 60L * 1000L

private fun dayKey(millis: Long): Long = (millis + TimeZone.getDefault().getOffset(millis)) / DAY_MS

/** Inserts a day header ("Today", "Yesterday", "30 Sept") before each new day. Items must be newest first. */
private fun buildRows(items: List<PickItem>): List<PickRow> {
    val today = dayKey(System.currentTimeMillis())
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val sameYear = SimpleDateFormat("d MMM", Locale.getDefault())
    val otherYear = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    val rows = ArrayList<PickRow>(items.size + 32)
    var lastKey = Long.MIN_VALUE
    for (item in items) {
        val key = dayKey(item.dateMillis)
        if (key != lastKey) {
            lastKey = key
            val label = when (key) {
                today -> "Today"
                today - 1 -> "Yesterday"
                else -> {
                    val year = Calendar.getInstance().apply { timeInMillis = item.dateMillis }.get(Calendar.YEAR)
                    (if (year == currentYear) sameYear else otherYear).format(Date(item.dateMillis))
                }
            }
            rows += PickRow.Header(label, "h:$key")
        }
        rows += PickRow.Media(item)
    }
    return rows
}

private fun mimeMatches(mime: String?, filters: List<String>): Boolean {
    val m = mime ?: return false
    return filters.any { f -> f == "*/*" || f == m || (f.endsWith("/*") && m.startsWith(f.dropLast(1))) }
}

/** Newest first. Only items whose type matches what the calling app asked for. */
private fun loadMedia(context: Context, filters: List<String>, extended: Boolean = true): List<PickItem> {
    val wantImages = filters.any { it == "*/*" || it.startsWith("image/") }
    val wantVideos = filters.any { it == "*/*" || it.startsWith("video/") }
    val mediaTypes = buildList {
        if (wantImages) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        if (wantVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
    }
    if (mediaTypes.isEmpty()) return emptyList()

    val projection = if (extended) {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.Video.VideoColumns.DURATION
        )
    } else {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
    }

    val out = ArrayList<PickItem>()
    try {
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString(",")})",
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                if (!mimeMatches(c.getString(2), filters)) continue
                val id = c.getLong(0)
                val isVideo = c.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val modifiedMs = c.getLong(6) * 1000L
                val takenMs = if (extended) c.getLong(7) else 0L
                val durationMs = if (extended && isVideo) c.getLong(8) else 0L

                out += PickItem(
                    uri = ContentUris.withAppendedId(base, id),
                    isVideo = isVideo,
                    name = c.getString(1) ?: "",
                    bucketId = c.getString(4) ?: "",
                    bucketName = c.getString(5) ?: "Other",
                    dateMillis = if (takenMs > 0) takenMs else modifiedMs,
                    durationMs = durationMs
                )
            }
        }
    } catch (_: SecurityException) {
        // No media permission: the screen shows the permission prompt instead.
    } catch (e: IllegalArgumentException) {
        // A column isn't available on this Android version: retry with the basic columns.
        if (extended) return loadMedia(context, filters, extended = false)
    }

    out.sortByDescending { it.dateMillis }
    return out
}