@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.albums

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.usecase.MediaListBuilder
import com.acoder.gallery.presentation.common.CircleButton
import com.acoder.gallery.presentation.common.EmptyState
import com.acoder.gallery.presentation.common.FloatingBarClearance
import com.acoder.gallery.presentation.common.MediaThumb
import com.acoder.gallery.presentation.common.SectionTitle
import com.acoder.gallery.presentation.home.HomeViewModel

/**
 * Pinned albums, in the order they should appear:
 * 0 Recent, 1 Camera, 2 Videos, 3 Screenshots, 4 Download, 5 WhatsApp Images.
 * Returns -1 when the album is not pinned.
 */
private fun pinnedRank(album: Album): Int {
    val name = album.name.trim().lowercase()
    val id = album.id.toString().lowercase()
    return when {
        name == "recent" || name == "recents" || id.contains("recent") -> 0
        name == "camera" -> 1
        name == "videos" || name == "video" || (id.contains("video") && !name.contains("whatsapp")) -> 2
        name == "screenshots" -> 3
        name == "download" || name == "downloads" -> 4
        name.contains("whatsapp") && name.contains("image") -> 5
        // Any other smart album (Favourites, etc.) goes after the six
        album.id in MediaListBuilder.SMART_IDS -> 6
        else -> -1
    }
}

@Composable
fun AlbumsScreen(vm: HomeViewModel, nav: NavHostController) {
    val albums by vm.albums.collectAsState()
    val loading by vm.albumsLoading.collectAsState()
    val trash by vm.trashItems.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var othersExpanded by rememberSaveable { mutableStateOf(true) }

    val pinned = albums.filter { pinnedRank(it) >= 0 }.sortedBy { pinnedRank(it) }
    val others = albums - pinned.toSet()
    val bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun open(album: Album) {
        vm.openAlbum(album.id, album.name)
        nav.navigate("album")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Albums",
                modifier = Modifier.weight(1f),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Box {
                CircleButton(Icons.Default.MoreVert, "More", { menuOpen = true })
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Recycle bin") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; nav.navigate("trash") }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { menuOpen = false; nav.navigate("settings") }
                    )
                }
            }
        }

        when {
            loading && albums.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            albums.isEmpty() -> EmptyState(Icons.Default.Collections, "No albums yet", "Albums appear here once your device has photos or videos.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "h-pinned", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Pinned") }
                    items(pinned, key = { "a-${it.id}" }) { AlbumTile(it) { open(it) } }
                }

                if (others.isNotEmpty()) {
                    // Collapsible header
                    item(key = "h-all", span = { GridItemSpan(maxLineSpan) }) {
                        val rotation by animateFloatAsState(if (othersExpanded) 180f else 0f, label = "allAlbumsArrow")
                        Row(
                            Modifier.fillMaxWidth().clickable { othersExpanded = !othersExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SectionTitle("All albums")
                            Icon(
                                Icons.Default.ExpandMore,
                                if (othersExpanded) "Collapse" else "Expand",
                                Modifier.padding(end = 4.dp).rotate(rotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Horizontally scrolling albums
                    item(key = "all-albums-row", span = { GridItemSpan(maxLineSpan) }) {
                        AnimatedVisibility(
                            visible = othersExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(others, key = { "a-${it.id}" }) { album ->
                                    Box(Modifier.width(110.dp)) { AlbumTile(album) { open(album) } }
                                }
                            }
                        }
                    }
                }

                if (vm.trashSupported) {
                    item(key = "h-utilities", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Utilities") }
                    item(key = "trash") { RecycleBinTile(trash.size, trash.firstOrNull()?.uri) { nav.navigate("trash") } }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaThumb(
            album.coverUri,
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
            album.name
        )
        AlbumCaption(album.name, "%,d".format(album.count))
    }
}

@Composable
private fun RecycleBinTile(count: Int, cover: android.net.Uri?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) MediaThumb(cover, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    if (cover != null) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)
                    else androidx.compose.ui.graphics.Color.Transparent
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete, null, Modifier.size(40.dp),
                    tint = if (cover != null) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AlbumCaption("Recycle bin", "%,d".format(count))
    }
}

@Composable
private fun AlbumCaption(name: String, count: String) {
    Spacer(Modifier.height(5.dp))
    Text(
        name,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(count, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}