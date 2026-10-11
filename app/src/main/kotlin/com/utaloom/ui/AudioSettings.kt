package com.utaloom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.playback.AudioEffects
import com.utaloom.playback.Player
import com.utaloom.together.Connection
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import kotlin.math.roundToInt

@Composable
fun AudioSettings() {
    val settings by Stores.settings.state.collectAsState()
    val together by ListenTogether.state.collectAsState()
    val inRoom = (together.connection == Connection.CONNECTED && together.role != Role.NONE) ||
        Player.remoteControlled || Player.hooks != null

    val speed = AudioEffects.sanitizeSpeed(settings.playbackSpeed)
    val pitch = AudioEffects.sanitizePitch(settings.playbackPitch)
    val eqEnabled = settings.equalizerEnabled
    val preamp = AudioEffects.sanitizePreamp(settings.equalizerPreamp)
    val bands = AudioEffects.sanitizeBands(settings.equalizerBands)
    val preset = settings.equalizerPreset

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Section: Speed and Pitch
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playback speed and pitch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (inRoom) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        "Speed and pitch adjustments are disabled while connected to a Listen Together room.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Speed slider
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.bodyMedium)
                        Text(String.format(java.util.Locale.US, "%.2fx", speed), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = speed,
                        onValueChange = { newSpeed ->
                            if (!inRoom) {
                                Player.setSpeed(AudioEffects.sanitizeSpeed(newSpeed))
                            }
                        },
                        valueRange = 0.5f..2.0f,
                        enabled = !inRoom,
                    )
                }
                if (!inRoom && speed != 1f) {
                    IconButton(onClick = { Player.setSpeed(1f) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset speed")
                    }
                }
            }

            // Pitch slider
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pitch", style = MaterialTheme.typography.bodyMedium)
                        val pitchText = when {
                            pitch > 0 -> "+$pitch semitones"
                            pitch < 0 -> "$pitch semitones"
                            else -> "Natural (0)"
                        }
                        Text(pitchText, style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = pitch.toFloat(),
                        onValueChange = { newPitch ->
                            if (!inRoom) {
                                Player.setPitch(AudioEffects.sanitizePitch(newPitch.roundToInt()))
                            }
                        },
                        valueRange = -12f..12f,
                        steps = 23,
                        enabled = !inRoom,
                    )
                }
                if (!inRoom && pitch != 0) {
                    IconButton(onClick = { Player.setPitch(0) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset pitch")
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 24.dp))

        // Section: Transitions (Crossfade, Gapless, Skip Silence)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Track transitions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (inRoom) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        "Crossfade, gapless playback, and silence skipping are automatically bypassed in Listen Together rooms to preserve synchronization.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Crossfade
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Crossfade", style = MaterialTheme.typography.bodyMedium)
                        Text("Smoothly fade out the current track while fading in the next.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (settings.crossfadeSeconds > 0) "${settings.crossfadeSeconds}s" else "Off", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = settings.crossfadeSeconds.toFloat(),
                    onValueChange = { v ->
                        val secs = v.roundToInt().coerceIn(0, 12)
                        Stores.settings.update { it.copy(crossfadeSeconds = secs) }
                    },
                    valueRange = 0f..12f,
                    steps = 11,
                )
            }

            // Gapless
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Gapless playback", style = MaterialTheme.typography.bodyMedium)
                    Text("Play continuous tracks without pauses between songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.gaplessPlayback,
                    onCheckedChange = { v -> Stores.settings.update { it.copy(gaplessPlayback = v) } },
                )
            }

            // Skip silence
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Skip silence", style = MaterialTheme.typography.bodyMedium)
                    Text("Skip leading and trailing quiet intervals automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.skipSilence,
                    onCheckedChange = { v -> Stores.settings.update { it.copy(skipSilence = v) } },
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 24.dp))

        // Section: Equalizer
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Shape frequency balance with preamp and 10-band controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { enabled ->
                        Stores.settings.update { it.copy(equalizerEnabled = enabled) }
                        Player.updateAudioEffects()
                    },
                )
            }

            AnimatedVisibility(visible = eqEnabled) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Preset selector
                    var menuExpanded by remember { mutableStateOf(false) }
                    val presets = remember { AudioEffects.presets() }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Preset", style = MaterialTheme.typography.bodyMedium)
                        Box {
                            OutlinedButton(onClick = { menuExpanded = true }) {
                                Text(preset)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                presets.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            menuExpanded = false
                                            Stores.settings.update {
                                                it.copy(
                                                    equalizerPreset = p.name,
                                                    equalizerPreamp = p.preamp,
                                                    equalizerBands = p.bands,
                                                )
                                            }
                                            Player.updateAudioEffects()
                                        },
                                    )
                                }
                                if (presets.none { it.name == "Custom" }) {
                                    DropdownMenuItem(
                                        text = { Text("Custom") },
                                        onClick = {
                                            menuExpanded = false
                                            Stores.settings.update { it.copy(equalizerPreset = "Custom") }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Preamp slider
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Preamp", style = MaterialTheme.typography.bodyMedium)
                            Text(String.format(java.util.Locale.US, "%+.1f dB", preamp), style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(
                            value = preamp,
                            onValueChange = { newPreamp ->
                                val bounded = AudioEffects.sanitizePreamp(newPreamp)
                                Stores.settings.update { it.copy(equalizerPreamp = bounded, equalizerPreset = "Custom") }
                                Player.updateAudioEffects()
                            },
                            valueRange = -20f..20f,
                        )
                    }

                    // 10 bands
                    val freqs = AudioEffects.bandFreqs
                    Text("Bands", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 10) {
                            val freqLabel = formatBandFreq(freqs.getOrNull(i) ?: 0f)
                            val gain = bands.getOrNull(i) ?: 0f
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(freqLabel, Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    modifier = Modifier.weight(1f),
                                    value = gain,
                                    onValueChange = { newGain ->
                                        val bounded = AudioEffects.sanitizePreamp(newGain)
                                        val newBands = bands.toMutableList()
                                        while (newBands.size < 10) newBands.add(0f)
                                        newBands[i] = bounded
                                        Stores.settings.update {
                                            it.copy(
                                                equalizerBands = newBands,
                                                equalizerPreset = "Custom",
                                            )
                                        }
                                        Player.updateAudioEffects()
                                    },
                                    valueRange = -20f..20f,
                                )
                                Text(
                                    String.format(java.util.Locale.US, "%+.1f dB", gain),
                                    Modifier.width(65.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    // Reset EQ button
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            val flat = presets.firstOrNull { it.name == "Flat" }
                            val flatBands = flat?.bands ?: List(10) { 0f }
                            val flatPreamp = flat?.preamp ?: 0f
                            Stores.settings.update {
                                it.copy(
                                    equalizerPreset = "Flat",
                                    equalizerPreamp = flatPreamp,
                                    equalizerBands = flatBands,
                                )
                            }
                            Player.updateAudioEffects()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset Equalizer")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBandFreq(hz: Float): String = when {
    hz >= 1000f -> "${(hz / 1000f).roundToInt()} kHz"
    hz > 0f -> "${hz.roundToInt()} Hz"
    else -> "Hz"
}
