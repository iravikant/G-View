package com.acoder.gallery.presentation.home

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.acoder.gallery.presentation.common.FloatingBottomBar
import com.acoder.gallery.presentation.common.ForceLightSystemBarIcons
import com.acoder.gallery.presentation.common.PermissionScreen
import com.acoder.gallery.presentation.editor.PhotoEditorScreen
import com.acoder.gallery.presentation.media.MediaScreen
import com.acoder.gallery.presentation.settings.SettingsScreen
import com.acoder.gallery.presentation.trash.TrashScreen
import com.acoder.gallery.presentation.video.VideoPlayerScreen
import com.acoder.gallery.presentation.viewer.MediaViewerScreen
import kotlinx.coroutines.launch

private val TAB_ROUTES = listOf("media", "albums")
private val TAB_LABELS = listOf("Photos", "Albums")

@Composable
fun GalleryApp(vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(PermissionManager.hasAnyAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = PermissionManager.hasAnyAccess(context)
                // Cheap background re-query; covers partial-access changes. Unchanged data = no UI update.
                if (access) vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Start loading the library in the ViewModel as soon as access exists.
    LaunchedEffect(access) { vm.setAccess(access) }

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
        val selected by vm.selected.collectAsState()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }

        // --- One place handles the OS confirmation dialogs and snackbars for every screen. ---
        var pending by remember { mutableStateOf<HomeEvent.ConfirmAction?>(null) }
        val actionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pending
            pending = null
            if (action != null && result.resultCode == Activity.RESULT_OK) {
                vm.onActionConfirmed(action.action, action.keys, action.flag)
            }
        }
        LaunchedEffect(Unit) {
            vm.events.collect { event ->
                when (event) {
                    is HomeEvent.ConfirmAction -> {
                        pending = event
                        actionLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    }
                    is HomeEvent.Message -> {
                        snackbar.currentSnackbarData?.dismiss()
                        scope.launch { snackbar.showSnackbar(event.text) }
                    }
                    is HomeEvent.ActionDone -> Unit // screens that care (the viewer) listen themselves
                }
            }
        }

        val onTabRoute = route in TAB_ROUTES
        val showBar = onTabRoute && !(route == "media" && selected.isNotEmpty())

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavHost(nav, startDestination = "media", modifier = Modifier.fillMaxSize()) {
                composable("media") {
                    LaunchedEffect(Unit) { vm.clearAlbum() }
                    MediaScreen(vm, nav)
                }
                composable("album") { MediaScreen(vm, nav, isAlbum = true) }
                composable("albums") { AlbumsScreen(vm, nav) }
                composable("trash") { TrashScreen(vm, nav) }
                composable("settings") { SettingsScreen(vm, nav) }
                composable("viewer") { MediaViewerScreen(vm, nav) }
                composable("video") {
                    ForceLightSystemBarIcons()
                    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) { VideoPlayerScreen(vm, nav) }
                }
                composable(
                    "editor/{uri}",
                    arguments = listOf(navArgument("uri") { type = NavType.StringType })
                ) { backStackEntry ->
                    val encoded = backStackEntry.arguments?.getString("uri").orEmpty()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                        PhotoEditorScreen(Uri.parse(encoded), nav)
                    }
                }
            }

            AnimatedVisibility(
                showBar,
                Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val onPhotos = route == "media"
                FloatingBottomBar(
                    tabs = TAB_LABELS,
                    selected = if (onPhotos) 0 else 1,
                    onSelect = { index ->
                        val target = TAB_ROUTES[index]
                        if (target != route) {
                            if (target == "media") vm.clearAlbum()
                            nav.navigate(target) {
                                popUpTo("media") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    leftIcon = if (onPhotos) Icons.Default.GridView else Icons.Default.Settings,
                    leftDescription = if (onPhotos) "Change grid size" else "Settings",
                    onLeft = { if (onPhotos) vm.cycleGrid() else nav.navigate("settings") },
                    rightIcon = if (onPhotos) Icons.Default.FilterList else Icons.Default.Delete,
                    rightDescription = if (onPhotos) "Sort and filter" else "Recycle bin",
                    onRight = { if (onPhotos) vm.sortSheetOpen.value = true else nav.navigate("trash") }
                )
            }

            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (showBar) 92.dp else 12.dp)
            )
        }
    }
}
