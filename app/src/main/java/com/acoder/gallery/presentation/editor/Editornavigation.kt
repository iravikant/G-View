package com.acoder.gallery.presentation.editor

import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.acoder.gallery.presentation.viewer.ViewerTransitions

/** Key under which the editor reports the Uri of the photo it saved to the screen that opened it. */
const val EDITOR_RESULT_KEY = "editor_saved_uri"

object EditorRoutes {
    const val EDITOR = "editor/{uri}"
    const val ARG_URI = "uri"

    /**
     * Route for editing [uri]. A content Uri contains '/', ':' and sometimes '%', which clash with how
     * the navigation library encodes and decodes route arguments, so it is carried as URL-safe Base64
     * (letters, digits, '-' and '_' only) and restored by [decode].
     */
    fun editor(uri: Uri): String = "editor/" + encode(uri)

    /** Same, for models that keep the Uri as a String (`item.uri.toString()` works for either). */
    fun editor(uri: String): String = editor(Uri.parse(uri))

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(uri: Uri): String =
        Base64.encodeToString(uri.toString().toByteArray(Charsets.UTF_8), FLAGS)

    fun decode(arg: String?): Uri? = try {
        if (arg.isNullOrEmpty()) null else Uri.parse(
            String(
                Base64.decode(arg, FLAGS),
                Charsets.UTF_8
            )
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Opens the photo editor for [uri]. Call this from the media / viewer screen. */
fun NavHostController.navigateToEditor(uri: Uri) {
    navigate(EditorRoutes.editor(uri)) { launchSingleTop = true }
}

fun NavHostController.navigateToEditor(uri: String) = navigateToEditor(Uri.parse(uri))

/** Register the editor in your NavHost: `NavHost(...) { ...; photoEditorDestination(navController) }`. */
fun NavGraphBuilder.photoEditorDestination(nav: NavHostController) {
    composable(
        route = EditorRoutes.EDITOR,
        arguments = listOf(navArgument(EditorRoutes.ARG_URI) { type = NavType.StringType }),
        // same fades as the photo viewer / video player so opening the editor feels consistent
        enterTransition = { ViewerTransitions.enter },
        exitTransition = { ViewerTransitions.exit },
        popEnterTransition = { ViewerTransitions.popEnter },
        popExitTransition = { ViewerTransitions.popExit }
    ) { entry ->
        val uri = EditorRoutes.decode(entry.arguments?.getString(EditorRoutes.ARG_URI))
        if (uri != null) {
            PhotoEditorScreen(uri = uri, nav = nav)
        } else {
            // A malformed route must not crash the app: leave immediately.
            LaunchedEffect(Unit) { nav.popBackStack() }
            Box(Modifier
                .fillMaxSize()
                .background(Color.Black), Alignment.Center) {
                Text("Photo unavailable", color = Color.White)
            }
        }
    }
}

/**
 * Call from the screen that launched the editor. [onSaved] runs once, with the Uri of the new photo,
 * after the editor saved and closed (use it to refresh the list or select the new photo).
 *
 *   ObserveEditorResult(backStackEntry) { newUri -> viewModel.refresh() }
 */
@Composable
fun ObserveEditorResult(entry: NavBackStackEntry, onSaved: (Uri) -> Unit) {
    val handle = entry.savedStateHandle
    val saved by handle.getStateFlow<String?>(EDITOR_RESULT_KEY, null).collectAsState()
    LaunchedEffect(saved) {
        val value = saved ?: return@LaunchedEffect
        handle[EDITOR_RESULT_KEY] = null          // consume it so it fires only once
        onSaved(Uri.parse(value))
    }
}

/**
 * Same as above for screens that do not receive their [NavBackStackEntry] (the viewer is opened by route,
 * e.g. `ObserveEditorResult(nav, ViewerRoutes.IMAGE) { ... }`).
 */
@Composable
fun ObserveEditorResult(nav: NavHostController, route: String, onSaved: (Uri) -> Unit) {
    val entry = remember(nav) { runCatching { nav.getBackStackEntry(route) }.getOrNull() } ?: return
    ObserveEditorResult(entry, onSaved)
}