package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.data.defaultGlobalHotkeys
import com.utaloom.platform.GlobalHotkeys
import com.utaloom.platform.HotkeyBackendType

@Composable
fun HotkeySettings() {
    val s by Stores.settings.state.collectAsState()
    val status by GlobalHotkeys.status.collectAsState()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Enable global shortcuts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Control playback system-wide when Utaloom is minimized or in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = s.globalHotkeysEnabled,
                onCheckedChange = { enabled ->
                    Stores.settings.update { it.copy(globalHotkeysEnabled = enabled) }
                },
            )
        }

        // Backend Status Banner
        val statusText = when {
            !s.globalHotkeysEnabled -> "Disabled"
            status.active -> when (status.backend) {
                HotkeyBackendType.WINDOWS -> "Active (Windows native)"
                HotkeyBackendType.X11 -> "Active (X11 native)"
                HotkeyBackendType.WAYLAND_PORTAL -> "Active (Wayland portal)"
                HotkeyBackendType.NONE -> "Active"
            }
            status.generalError != null -> "Failed: ${status.generalError}"
            status.warning != null -> "Unavailable"
            else -> "Idle"
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val statusColor = if (status.active) MaterialTheme.colorScheme.primary
            else if (status.warning != null || status.generalError != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant

            Text(
                "Status: $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )

            if (s.globalHotkeysEnabled) {
                TextButton(onClick = {
                    Stores.settings.update { it.copy(globalHotkeys = defaultGlobalHotkeys) }
                }) {
                    Text("Reset all defaults")
                }
            }
        }

        // Warning card (e.g. Wayland portal absent or no display)
        if (s.globalHotkeysEnabled && status.warning != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = status.warning.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Editable hotkey rows
        if (s.globalHotkeysEnabled) {
            val currentBindings = s.globalHotkeys
            val bindingStatusMap = status.bindings.associateBy { it.action }

            GlobalHotkeys.actionDescriptions.forEach { (action, label) ->
                val boundValue = currentBindings[action] ?: defaultGlobalHotkeys[action].orEmpty()
                var textValue by remember(boundValue) { mutableStateOf(boundValue) }
                val validationError = GlobalHotkeys.validateCombo(action, textValue, currentBindings)
                val nativeStatus = bindingStatusMap[action]
                val nativeError = if (nativeStatus?.registered == false) nativeStatus.error else null

                val effectiveError = validationError ?: nativeError
                val defaultCombo = defaultGlobalHotkeys[action].orEmpty()

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { newVal ->
                                textValue = newVal
                                val check = GlobalHotkeys.validateCombo(action, newVal, currentBindings)
                                if (check == null) {
                                    val normalized = GlobalHotkeys.normalizeCombo(newVal)
                                    Stores.settings.update {
                                        it.copy(globalHotkeys = it.globalHotkeys + mapOf(action to normalized))
                                    }
                                }
                            },
                            modifier = Modifier.width(200.dp),
                            singleLine = true,
                            isError = effectiveError != null,
                            placeholder = { Text(defaultCombo) },
                        )

                        IconButton(
                            onClick = {
                                textValue = defaultCombo
                                Stores.settings.update {
                                    it.copy(globalHotkeys = it.globalHotkeys + mapOf(action to defaultCombo))
                                }
                            },
                            enabled = textValue != defaultCombo,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset $label to default",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    if (effectiveError != null) {
                        Text(
                            text = effectiveError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
