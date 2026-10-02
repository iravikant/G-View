
@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.acoder.gallery.core.util

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * "Open from the exact tile you tapped / close back into it" (container transform).
 *
 * The NavHost provides the two scopes below for every route (see ProvideSharedScopes), then the grid
 * tile and the viewer / player both tag their image with the same key -> Compose animates the bounds.
 * If the scopes are not provided the modifier does nothing, so nothing can crash.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun ProvideSharedScopes(
    shared: SharedTransitionScope,
    animated: AnimatedVisibilityScope,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSharedTransitionScope provides shared,
        LocalNavAnimatedScope provides animated,
        content = content
    )
}

/** Tag an image (grid tile / viewer page / player poster) with the media item's key. */
@Composable
fun Modifier.mediaSharedBounds(mediaKey: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@mediaSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "media-$mediaKey"),
            animatedVisibilityScope = animated,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            boundsTransform = { _, _ -> tween<Rect>(durationMillis = 380, easing = FastOutSlowInEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
    }
}