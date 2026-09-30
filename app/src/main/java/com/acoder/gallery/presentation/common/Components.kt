@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.common

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis

/** Space the grids leave at the bottom so content scrolls *under* the floating bar. */
val FloatingBarClearance = 120.dp

// ---------------------------------------------------------------------------------------------
// Buttons & bars
// ---------------------------------------------------------------------------------------------

/** Round, softly raised icon button used in headers and beside the floating tab bar. */
@Composable
fun CircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    raised: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .then(if (raised) Modifier.shadow(6.dp, CircleShape) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
    ) {
        Icon(icon, description, Modifier.size(if (size > 50.dp) 26.dp else 22.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The floating "Photos | Albums" pill with a round action button on each side.
 * Sits over the content instead of reserving a strip of screen like a NavigationBar.
 */
@Composable
fun FloatingBottomBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    leftIcon: ImageVector,
    leftDescription: String,
    onLeft: () -> Unit,
    rightIcon: ImageVector,
    rightDescription: String,
    onRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CircleButton(leftIcon, leftDescription, onLeft, size = 56.dp, raised = true)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp
        ) {
            Row(Modifier.padding(6.dp)) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onSelect(index) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 17.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        CircleButton(rightIcon, rightDescription, onRight, size = 56.dp, raised = true)
    }
}

data class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit, val destructive: Boolean = false)

/** Floating pill of icon+label actions shown while photos are selected. */
@Composable
fun FloatingActionPill(actions: List<BarAction>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                actions.forEach { action ->
                    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(onClick = action.onClick)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(action.icon, action.label, Modifier.size(24.dp), tint = tint)
                        Spacer(Modifier.height(2.dp))
                        Text(action.label, fontSize = 11.sp, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------------------------

/**
 * Centered confirmation dialog: bold title, plain message, and a Cancel | Confirm row split by a
 * hairline — the same layout as the system gallery's "Delete this photo?" prompt.
 */
@Composable
fun GalleryDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelLabel: String = "Cancel",
    destructive: Boolean = true
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column {
                Text(
                    title,
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    message,
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 22.dp),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    TextButton(onDismiss, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(0.dp)) {
                        Text(cancelLabel, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    TextButton(onConfirm, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(0.dp)) {
                        Text(
                            confirmLabel,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Media bits
// ---------------------------------------------------------------------------------------------

/** Thumbnail for a photo or a video (first-second frame). */
@Composable
fun MediaThumb(uri: Uri?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val request = remember(uri) { ImageRequest.Builder(context).data(uri).videoFrameMillis(1000).build() }
    AsyncImage(
        model = request,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/** Small dark pill with text, e.g. a video's length or "12 days". */
@Composable
fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

/** Round check marker in a tile's corner while selecting. */
@Composable
fun SelectionBadge(isSelected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier.size(22.dp),
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
        border = if (!isSelected) BorderStroke(1.5.dp, Color.White.copy(alpha = 0.9f)) else null
    ) {
        if (isSelected) Icon(Icons.Default.Check, null, Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Big left-aligned screen title. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** Full-width section heading ("Pinned", "All albums"…). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = 18.dp, bottom = 10.dp),
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** Keeps status/navigation bar icons white while a black, full-bleed screen (viewer, video) is showing. */
@Composable
fun ForceLightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
            if (oldNav != null) controller.isAppearanceLightNavigationBars = oldNav
        }
    }
}
