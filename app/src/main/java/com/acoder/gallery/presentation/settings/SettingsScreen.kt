@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.acoder.gallery.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acoder.gallery.BuildConfig
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.presentation.common.label
import com.acoder.gallery.presentation.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, nav: NavHostController) {
    val theme by vm.theme.collectAsState()
    val grid by vm.grid.collectAsState()
    val autoplay by vm.autoPlay.collectAsState()
    val dynamicColor by vm.dynamicColor.collectAsState()
    val sort by vm.sort.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun managesMedia() = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
    var canManage by remember { mutableStateOf(managesMedia()) }
    DisposableEffect(lifecycleOwner) {
        // The user flips this in system settings, so re-read it whenever we come back.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canManage = managesMedia() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Appearance")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (value, label) ->
                    FilterChip(selected = theme == value, onClick = { vm.setTheme(value) }, label = { Text(label) }, modifier = Modifier.weight(1f))
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Dynamic color", "Match the app's palette to your wallpaper", dynamicColor, vm::setDynamicColor)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Gallery")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Grid size — $grid columns", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = grid.toFloat(),
                    onValueChange = { vm.setGrid(it.toInt().coerceIn(2, 6)) },
                    valueRange = 2f..6f,
                    steps = 3
                )
            }
            SwitchRow("Autoplay videos", "Start playing as soon as a video opens", autoplay, vm::setAutoPlay)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Recycle bin")
            ListItem(
                headlineContent = { Text("Open Recycle bin") },
                supportingContent = { Text("Deleted photos and videos stay here for 30 days") },
                leadingContent = { Icon(Icons.Default.Delete, null) },
                modifier = Modifier.clickableListItem { nav.navigate("trash") }
            )
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow(
                    "Delete without extra prompts",
                    if (canManage) "Gallery can move photos to the Recycle bin without asking Android each time."
                    else "Android asks you to approve every delete. Allow media management to skip that prompt.",
                    canManage
                ) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData(Uri.parse("package:${context.packageName}"))
                        )
                    } catch (_: Exception) {
                        vm.notify("Couldn't open the system setting")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Default sort order")
            Column {
                SortOrder.values().forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.label()) },
                        leadingContent = {
                            Icon(
                                if (sort == option) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (sort == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickableListItem { vm.setSort(option) }
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Privacy")
            Text(
                "Media is processed entirely on this device. Gallery never uploads your photos or videos anywhere.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            SectionHeader("About")
            Text("Gallery ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
