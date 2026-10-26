package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.utaloom.data.Settings
import java.awt.GraphicsEnvironment

typealias IslandMonitorDescriptor = IslandMonitorOption

data class IslandMonitorOption(val id: String, val label: String)

/** Discovers available display devices, always including the virtual active monitor. */
fun defaultIslandMonitorOptions(): List<IslandMonitorOption> {
    val active = IslandMonitorOption("active", "Active monitor")
    if (GraphicsEnvironment.isHeadless()) return listOf(active)
    return runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screens = ge.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            val deviceId = device.getIDstring()
            val name = deviceId.ifBlank { "Display ${index + 1}" }
            IslandMonitorOption(deviceId, "$name (${bounds.width}x${bounds.height})")
        }
        listOf(active) + screens.filter { it.id != "active" }.distinctBy { it.id }
    }.getOrDefault(listOf(active))
}

/**
 * Compact settings section for Dynamic Island overlay options.
 *
 * Takes current [Settings] and an [onUpdateSettings] callback, along with
 * monitor descriptors and an optional queue toggle callback.
 */
@Composable
fun IslandSettings(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    monitors: List<IslandMonitorOption> = defaultIslandMonitorOptions(),
    onSelectMonitor: ((String) -> Unit)? = null,
    onMonitorSelected: ((IslandMonitorOption) -> Unit)? = null,
    queueEnabled: Boolean = false,
    onQueueChange: (Boolean) -> Unit = {},
) {
    IslandSettings(
        settings = settings,
        monitors = monitors,
        queueEnabled = settings.dynamicIslandQueue,
        onLyricsChange = { onUpdateSettings(settings.copy(dynamicIslandLyrics = it)) },
        onDownloadsChange = { onUpdateSettings(settings.copy(dynamicIslandDownloads = it)) },
        onQueueChange = { enabled ->
            onQueueChange(enabled)
            onUpdateSettings(settings.copy(dynamicIslandQueue = enabled))
        },
        onAlwaysOnTopChange = { onUpdateSettings(settings.copy(dynamicIslandAlwaysOnTop = it)) },
        onMonitorChange = { id ->
            onSelectMonitor?.invoke(id)
            val descriptor = monitors.firstOrNull { it.id == id } ?: IslandMonitorOption(id, id)
            onMonitorSelected?.invoke(descriptor)
            onUpdateSettings(settings.copy(dynamicIslandMonitor = id))
        },
        onTopOffsetChange = { onUpdateSettings(settings.copy(dynamicIslandTopOffset = it.coerceIn(0, 64))) },
        modifier = modifier,
    )
}

// ponytail: monitor list uses AWT screen devices without hotplug listeners; upgrade when OS display events are added.
@Composable
fun IslandSettings(
    settings: Settings,
    monitors: List<IslandMonitorOption>,
    queueEnabled: Boolean,
    onLyricsChange: (Boolean) -> Unit,
    onDownloadsChange: (Boolean) -> Unit,
    onQueueChange: (Boolean) -> Unit,
    onAlwaysOnTopChange: (Boolean) -> Unit,
    onMonitorChange: (String) -> Unit,
    onTopOffsetChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Dynamic Island",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        IslandSettingToggle(
            "Lyrics",
            "Show the current synced line when lyrics are available.",
            settings.dynamicIslandLyrics,
            onLyricsChange,
        )
        IslandSettingToggle(
            "Downloads",
            "Show download progress in the overlay.",
            settings.dynamicIslandDownloads,
            onDownloadsChange,
        )
        IslandSettingToggle(
            "Queue",
            "Show upcoming tracks when the overlay is expanded.",
            queueEnabled,
            onQueueChange,
        )
        IslandSettingToggle(
            "Always on top",
            "Keep the overlay visible above other windows.",
            settings.dynamicIslandAlwaysOnTop,
            onAlwaysOnTopChange,
        )
        MonitorSetting(settings.dynamicIslandMonitor, monitors, onMonitorChange)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val offset = settings.dynamicIslandTopOffset.coerceIn(0, 64)
            Text("Top offset: $offset px", style = MaterialTheme.typography.titleMedium)
            Text(
                "Move the overlay down from the top edge of its monitor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = offset.toFloat(),
                onValueChange = { onTopOffsetChange(it.toInt().coerceIn(0, 64)) },
                valueRange = 0f..64f,
                steps = 63,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Dynamic Island top offset, currently $offset pixels"
                },
            )
        }
    }
}

@Composable
private fun IslandSettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun MonitorSetting(
    selectedId: String,
    monitors: List<IslandMonitorOption>,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = remember(monitors) {
        listOf(IslandMonitorOption("active", "Active monitor")) + monitors.filter { it.id != "active" }.distinctBy { it.id }
    }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label ?: "Unavailable monitor"

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Monitor", style = MaterialTheme.typography.titleMedium)
            Text(
                if (selectedId == "active") "Follow the window currently in use." else "Choose where the overlay appears.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.semantics { contentDescription = "Monitor: $selectedLabel" },
            ) {
                Text(selectedLabel)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { monitor ->
                    DropdownMenuItem(
                        text = { Text(monitor.label) },
                        onClick = {
                            onChange(monitor.id)
                            open = false
                        },
                        modifier = Modifier.semantics { selected = monitor.id == selectedId },
                    )
                }
            }
        }
    }
}

internal fun islandSettingsSelfCheck() {
    val monitors = defaultIslandMonitorOptions()
    check(monitors.isNotEmpty()) { "Monitors should never be empty" }
    check(monitors.any { it.id == "active" }) { "Active monitor must be present" }

    val settings = Settings(dynamicIslandTopOffset = 100)
    val boundedOffset = settings.dynamicIslandTopOffset.coerceIn(0, 64)
    check(boundedOffset == 64) { "Offset above 64 must clamp to 64" }

    val negativeSettings = Settings(dynamicIslandTopOffset = -5)
    val boundedNegative = negativeSettings.dynamicIslandTopOffset.coerceIn(0, 64)
    check(boundedNegative == 0) { "Negative offset must clamp to 0" }
}
