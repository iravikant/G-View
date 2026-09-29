package com.acoder.gallery.presentation.home

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acoder.gallery.core.permissions.PermissionManager
import com.acoder.gallery.presentation.albums.AlbumsScreen
import com.acoder.gallery.presentation.common.PermissionScreen
import com.acoder.gallery.presentation.editor.PhotoEditorScreen
import com.acoder.gallery.presentation.media.MediaScreen
import com.acoder.gallery.presentation.settings.SettingsScreen
import com.acoder.gallery.presentation.video.VideoPlayerScreen
import com.acoder.gallery.presentation.viewer.MediaViewerScreen

private data class Tab(val route: String, val label: String, val selected: androidx.compose.ui.graphics.vector.ImageVector, val unselected: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("media", "Photos", Icons.Filled.PhotoLibrary, Icons.Outlined.Image),
    Tab("albums", "Albums", Icons.Filled.Collections, Icons.Outlined.Folder),
    Tab("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun GalleryApp(vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(PermissionManager.hasAnyAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = PermissionManager.hasAnyAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val theme by vm.theme.collectAsState()
    val dynamicColor by vm.dynamicColor.collectAsState()

    GalleryTheme(theme, dynamicColor) {
        if (!access) {
            PermissionScreen(context) { access = PermissionManager.hasAnyAccess(context) }
            return@GalleryTheme
        }

        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route

        Scaffold(
            bottomBar = {
                if (route in TABS.map { it.route }) {
                    NavigationBar {
                        TABS.forEach { tab ->
                            val isSelected = route == tab.route
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    if (tab.route == "media") vm.openAlbum(null, null)
                                    nav.navigate(tab.route) {
                                        popUpTo("media") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                icon = { Icon(if (isSelected) tab.selected else tab.unselected, tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = "media", modifier = Modifier.padding(padding)) {
                composable("media") { MediaScreen(vm, nav) }
                composable("albums") { AlbumsScreen(vm, nav) }
                composable("settings") { SettingsScreen(vm) }
                composable("viewer") { MediaViewerScreen(vm, nav) }
                composable("video") { VideoPlayerScreen(vm, nav) }
                composable(
                    "editor/{uri}",
                    arguments = listOf(navArgument("uri") { type = NavType.StringType })
                ) { backStackEntry ->
                    val encoded = backStackEntry.arguments?.getString("uri").orEmpty()
                    PhotoEditorScreen(Uri.parse(encoded), nav)
                }
            }
        }
    }
}
