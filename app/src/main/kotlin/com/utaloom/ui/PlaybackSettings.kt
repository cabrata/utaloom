package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import java.util.Locale

val equalizerPresets = listOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Classical" to listOf(4.8f, 3.6f, 2.4f, 2.4f, -1.2f, -1.2f, 0f, 2.4f, 3.6f, 4.0f),
    "Club" to listOf(0f, 0f, 2f, 3.6f, 3.6f, 3.6f, 2f, 0f, 0f, 0f),
    "Dance" to listOf(7.2f, 5.6f, 2f, 0f, 0f, -3.2f, -4.8f, -4.8f, 0f, 0f),
    "Full bass" to listOf(7.2f, 7.2f, 7.2f, 4.8f, 1.6f, -3.2f, -6.4f, -8f, -8.8f, -8.8f),
    "Full bass and treble" to listOf(5.6f, 4.4f, 0f, -4.8f, -3.2f, 1.6f, 6.4f, 8f, 8.8f, 9.6f),
    "Full treble" to listOf(-7.2f, -7.2f, -7.2f, -3.2f, 2.4f, 8f, 11.2f, 12f, 12f, 12.8f),
    "Headphones" to listOf(3.6f, 8.8f, 4.4f, -2.4f, -1.6f, 1.6f, 3.6f, 7.2f, 9.6f, 10.4f),
    "Live" to listOf(-3.2f, 0f, 2.4f, 3.6f, 4f, 4f, 2.4f, 1.6f, 1.6f, 1.6f),
    "Party" to listOf(5.6f, 5.6f, 0f, 0f, 0f, 0f, 0f, 0f, 5.6f, 5.6f),
    "Pop" to listOf(-1.6f, 1.6f, 5.6f, 6.4f, 4f, -1.6f, -2.4f, -2.4f, -1.6f, -1.6f),
    "Reggae" to listOf(0f, 0f, -0.8f, -4.4f, 0f, 4.8f, 4.8f, 0f, 0f, 0f),
    "Rock" to listOf(6.4f, 4f, -3.6f, -6.4f, -2.4f, 2.4f, 6.8f, 8.8f, 9f, 9.6f),
    "Soft" to listOf(3.6f, 1.6f, 0f, -1.6f, 0f, 3.2f, 6.4f, 7.2f, 8f, 8.8f),
    "Techno" to listOf(6.4f, 4.8f, 0f, -4.4f, -3.6f, 0f, 6.4f, 7.2f, 7.2f, 6.4f),
)

private val bandLabels = listOf("60 Hz", "170 Hz", "310 Hz", "600 Hz", "1 kHz", "3 kHz", "6 kHz", "12 kHz", "14 kHz", "16 kHz")

@Composable
fun PlaybackSettings() {
    val s by Stores.settings.state.collectAsState()
    var presetMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        SectionTitle("Equalizer")

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable equalizer", style = MaterialTheme.typography.titleMedium)
                Text("Native 10-band audio equalizer powered by libVLC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.equalizerEnabled, onCheckedChange = { enabled ->
                Stores.settings.update { it.copy(equalizerEnabled = enabled) }
                Player.updateAudioEffects()
            })
        }

        if (s.equalizerEnabled) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Preset: ${s.equalizerPreset}", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { presetMenuOpen = true }) {
                        Text(s.equalizerPreset)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        equalizerPresets.forEach { (name, bands) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                presetMenuOpen = false
                                Stores.settings.update { it.copy(equalizerPreset = name, equalizerBands = bands) }
                                Player.updateAudioEffects()
                            })
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Preamp", style = MaterialTheme.typography.bodyMedium)
                    Text(String.format(Locale.ROOT, "%.1f dB", s.equalizerPreamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = s.equalizerPreamp,
                    onValueChange = { preamp ->
                        Stores.settings.update { it.copy(equalizerPreamp = preamp) }
                        Player.updateAudioEffects()
                    },
                    valueRange = -20f..20f,
                )
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Bands (-20 dB to +20 dB)", style = MaterialTheme.typography.titleSmall)
                val bands = s.equalizerBands.ifEmpty { List(10) { 0f } }
                bandLabels.forEachIndexed { i, label ->
                    val gain = bands.getOrElse(i) { 0f }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = gain,
                            onValueChange = { newGain ->
                                val updated = bands.toMutableList().apply {
                                    while (size <= i) add(0f)
                                    set(i, newGain)
                                }
                                Stores.settings.update { it.copy(equalizerPreset = "Custom", equalizerBands = updated) }
                                Player.updateAudioEffects()
                            },
                            valueRange = -20f..20f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(String.format(Locale.ROOT, "%.1f", gain), Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        SectionTitle("Transitions & audio processing")

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Crossfade", style = MaterialTheme.typography.titleMedium)
                Text(if (s.crossfadeSeconds == 0) "Off" else "${s.crossfadeSeconds}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Fade out the end of the song while fading in the next one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = s.crossfadeSeconds.toFloat(),
                onValueChange = { sec -> Stores.settings.update { it.copy(crossfadeSeconds = sec.toInt()) } },
                valueRange = 0f..12f,
                steps = 11,
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gapless playback", style = MaterialTheme.typography.titleMedium)
                Text("Preload and transition to the next track with minimal delay between songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.gaplessPlayback, onCheckedChange = { gapless -> Stores.settings.update { it.copy(gaplessPlayback = gapless) } })
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Skip silence", style = MaterialTheme.typography.titleMedium)
                Text("Skip unvoiced and silent sections at the beginning of songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.skipSilence, onCheckedChange = { skip -> Stores.settings.update { it.copy(skipSilence = skip) } })
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Playback speed: ${String.format(Locale.ROOT, "%.2f", s.playbackSpeed)}x", style = MaterialTheme.typography.titleMedium)
                if (s.playbackSpeed != 1f) {
                    TextButton(onClick = { Player.setSpeed(1f) }) { Text("Reset") }
                }
            }
            Slider(
                value = s.playbackSpeed,
                onValueChange = { Player.setSpeed(it) },
                valueRange = 0.5f..2.0f,
            )
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pitch shift: ${if (s.playbackPitch > 0) "+${s.playbackPitch}" else "${s.playbackPitch}"} semitones", style = MaterialTheme.typography.titleMedium)
                if (s.playbackPitch != 0) {
                    TextButton(onClick = { Player.setPitch(0) }) { Text("Reset") }
                }
            }
            Slider(
                value = s.playbackPitch.toFloat(),
                onValueChange = { Player.setPitch(it.toInt()) },
                valueRange = -12f..12f,
                steps = 23,
            )
        }
    }
}
