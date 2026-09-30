package com.acoder.gallery.presentation.home

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SeedPrimary = Color(0xFF3B6939)
private val SeedSecondary = Color(0xFF53634F)
private val SeedTertiary = Color(0xFF386569)

private val LightColors = lightColorScheme(
    primary = SeedPrimary,
    secondary = SeedSecondary,
    tertiary = SeedTertiary
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1D399),
    secondary = Color(0xFFBBCBB4),
    tertiary = Color(0xFFA1CDD0)
)

@Composable
fun GalleryTheme(
    mode: String,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    // Dark mode uses a true-black canvas (OLED friendly, and how photo apps look) while keeping
    // the dynamic-colour accents and raised surfaces.
    val finalScheme = if (dark) colorScheme.copy(background = Color.Black, surface = Color.Black) else colorScheme

    MaterialTheme(
        colorScheme = finalScheme,
        typography = GalleryTypography,
        content = content
    )
}
