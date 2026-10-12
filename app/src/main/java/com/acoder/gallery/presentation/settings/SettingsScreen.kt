@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.settings

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.acoder.gallery.BuildConfig
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.presentation.common.label
import com.acoder.gallery.presentation.home.HomeViewModel
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(28.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, nav: NavHostController) {
    val theme by vm.theme.collectAsState()
    val grid by vm.grid.collectAsState()
    val autoplay by vm.autoPlay.collectAsState()
    val dynamicColor by vm.dynamicColor.collectAsState()
    val sort by vm.sort.collectAsState()

    val scheme = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var sortExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---------------- Appearance ----------------
            StaggerIn(0) {
                SettingsGroup("Appearance") {
                    ThemeSelector(current = theme, onSelect = vm::setTheme)
                    if (Build.VERSION.SDK_INT >= 31) {
                        RowDivider()
                        SettingRow(
                            icon = Icons.Default.AutoAwesome,
                            tint = scheme.tertiary,
                            title = "Dynamic color",
                            subtitle = "Match the app's palette to your wallpaper",
                            onClick = { vm.setDynamicColor(!dynamicColor) },
                            trailing = { Switch(checked = dynamicColor, onCheckedChange = vm::setDynamicColor) }
                        )
                    }
                }
            }

            // ---------------- Gallery ----------------
            StaggerIn(1) {
                SettingsGroup("Gallery") {
                    GridSizeControl(grid = grid, onChange = { vm.setGrid(it.coerceIn(2, 6)) })
                    RowDivider()
                    SettingRow(
                        icon = Icons.Default.PlayCircle,
                        tint = scheme.secondary,
                        title = "Autoplay videos",
                        subtitle = "Start playing as soon as a video opens",
                        onClick = { vm.setAutoPlay(!autoplay) },
                        trailing = { Switch(checked = autoplay, onCheckedChange = vm::setAutoPlay) }
                    )
                }
            }

            // ---------------- Recycle bin ----------------
            StaggerIn(2) {
                SettingsGroup("Recycle bin") {
                    SettingRow(
                        icon = Icons.Default.DeleteOutline,
                        tint = scheme.error,
                        title = "Open Recycle bin",
                        subtitle = "Deleted photos and videos stay here for 30 days",
                        onClick = { nav.navigate("trash") },
                        trailing = {
                            Icon(Icons.Default.ChevronRight, null, tint = scheme.onSurfaceVariant)
                        }
                    )
                }
            }

            // ---------------- Sort order (expandable) ----------------
            StaggerIn(3) {
                SettingsGroup("Default sort order") {
                    val chevron by animateFloatAsState(
                        if (sortExpanded) 180f else 0f,
                        spring(stiffness = Spring.StiffnessMediumLow),
                        label = "sortChevron"
                    )
                    SettingRow(
                        icon = Icons.Default.Sort,
                        tint = scheme.primary,
                        title = "Sort photos by",
                        subtitle = sort.label(),
                        onClick = { sortExpanded = !sortExpanded },
                        trailing = {
                            Icon(
                                Icons.Default.ExpandMore, null,
                                Modifier.rotate(chevron),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    )
                    AnimatedVisibility(
                        visible = sortExpanded,
                        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            SortOrder.values().forEach { option ->
                                SortOptionRow(option.label(), option == sort) { vm.setSort(option) }
                            }
                        }
                    }
                }
            }

            // ---------------- Privacy + About ----------------
            StaggerIn(4) { AboutCard() }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ======================================================================================
// Building blocks
// ======================================================================================

/** Fades + slides each section in one after another when the screen opens. */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(70L * index)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(380)) + slideInVertically(tween(460)) { it / 5 }
    ) { content() }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        Modifier.padding(start = 72.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

/** Clickable modifier that gently squishes on press. */
@Composable
private fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
}

@Composable
private fun IconChip(icon: ImageVector, tint: Color, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "subtitle"
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

// ---------------- Theme selector ----------------

@Composable
private fun ThemeSelector(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Default.Palette, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how Gallery looks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOption("System", Icons.Default.SettingsBrightness, current == "SYSTEM", Modifier.weight(1f)) { onSelect("SYSTEM") }
            ThemeOption("Light", Icons.Default.LightMode, current == "LIGHT", Modifier.weight(1f)) { onSelect("LIGHT") }
            ThemeOption("Dark", Icons.Default.DarkMode, current == "DARK", Modifier.weight(1f)) { onSelect("DARK") }
        }
    }
}

@Composable
private fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest,
        tween(260), label = "themeBg"
    )
    val fg by animateColorAsState(
        if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        tween(260), label = "themeFg"
    )
    val s by animateFloatAsState(
        if (selected) 1f else 0.95f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "themeScale"
    )
    Column(
        modifier
            .scale(s)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------------- Grid size ----------------

@Composable
private fun GridSizeControl(grid: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Default.GridView, scheme.tertiary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Grid size", style = MaterialTheme.typography.titleMedium)
                Text(
                    "How many photos per row",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Surface(shape = CircleShape, color = scheme.primaryContainer) {
                AnimatedContent(
                    targetState = grid,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                    label = "gridBadge"
                ) { g ->
                    Text(
                        "$g",
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Live miniature of the photo grid.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 180.dp, height = 100.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHighest)
                .padding(8.dp)
        ) {
            AnimatedContent(
                targetState = grid,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "gridPreview"
            ) { g ->
                val gap = 4.dp
                val cell = (164.dp - gap * (g - 1)) / g
                Column(
                    Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    repeat(4) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            repeat(g) { col ->
                                val alpha = 0.22f + ((row + col) % 3) * 0.12f
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(RoundedCornerShape(if (g >= 5) 3.dp else 6.dp))
                                        .background(scheme.primary.copy(alpha = alpha))
                                )
                            }
                        }
                    }
                }
            }
        }

        Slider(
            value = grid.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 2f..6f,
            steps = 3,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Large", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text("Compact", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

// ---------------- Sort options ----------------

@Composable
private fun SortOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (selected) scheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        tween(220), label = "sortBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------------- About ----------------

@Composable
private fun AboutCard() {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .padding(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(scheme.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhotoLibrary, null, tint = scheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Gallery",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer
                    )
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Lock, null,
                    Modifier.size(18.dp).padding(top = 2.dp),
                    tint = scheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Private by design. Media is processed entirely on this device, and Gallery never uploads your photos or videos anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}