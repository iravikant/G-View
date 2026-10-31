package com.acoder.gallery.presentation.home

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Brand palette (used when dynamic colour is off or unavailable). Calm indigo-slate accent on
// neutral surfaces so photos, not chrome, carry the colour.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondary = Color(0xFF595E72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF75546F),
    tertiaryContainer = Color(0xFFFFD6F6),
    onTertiaryContainer = Color(0xFF2C122A),
    background = Color(0xFFFAF8FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF8FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F3F8),
    surfaceContainer = Color(0xFFEFEDF2),
    surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E1E6),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF042878),
    primaryContainer = Color(0xFF264190),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFC1C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659),
    onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFE4BAD9),
    tertiaryContainer = Color(0xFF5C3D57),
    onTertiaryContainer = Color(0xFFFFD6F6),
    background = Color.Black,
    onBackground = Color(0xFFE4E1E6),
    surface = Color.Black,
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111114),
    surfaceContainer = Color(0xFF16161A),
    surfaceContainerHigh = Color(0xFF212125),
    surfaceContainerHighest = Color(0xFF2B2B30),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB)
)

/** One shape scale for the whole app so cards, sheets, menus and dialogs feel related. */
val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
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

    // Dark mode keeps a true-black canvas (OLED friendly, how photo apps look) while the raised
    // surfaces stay distinguishable from it.
    val finalScheme = if (dark) colorScheme.copy(background = Color.Black, surface = Color.Black) else colorScheme

    MaterialTheme(
        colorScheme = finalScheme,
        typography = GalleryTypography,
        shapes = GalleryShapes,
        content = content
    )
}
