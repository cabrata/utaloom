package com.utaloom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.utaloom.data.Library
import com.utaloom.data.Stores
import com.utaloom.playback.Player

/** The same player and queue as the main window. Closing this view never stops playback. */
@Composable
fun MiniPlayer(onShowMain: () -> Unit) {
    val s by Player.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val library by Stores.library.state.collectAsState()
    val song = s.current
    val guest = Player.remoteControlled
    val dark = when (settings.darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    UtaloomTheme(DefaultSeed, dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumb(song?.thumbnail, 64.dp)
                    Column(Modifier.weight(1f)) {
                        Text(song?.title ?: "Nothing playing", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(song?.artistText.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onShowMain) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Show Utaloom") }
                }
                var dragging by remember(song?.id) { mutableStateOf(false) }
                var target by remember { mutableFloatStateOf(0f) }
                val duration = s.durationMs.coerceAtLeast(1)
                Slider(
                    if (dragging) target else (s.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    { dragging = true; target = it },
                    onValueChangeFinished = { if (!Player.remoteControlled) Player.seekTo((target * duration).toLong()); dragging = false },
                    enabled = song != null && s.durationMs > 0 && !guest,
                    modifier = Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = "Mini player playback position" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (dragging) (target * duration).toLong() else s.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ song?.let(Library::toggleLike) }, enabled = song != null) {
                        val liked = library.liked.any { it.id == song?.id }
                        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Unlike" else "Like")
                    }
                    IconButton(Player::previous, enabled = song != null && !guest) { Icon(Icons.Default.SkipPrevious, "Previous") }
                    FilledIconButton(Player::playPause, Modifier.size(48.dp), enabled = song != null && !guest) {
                        if (s.isBuffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) "Pause" else "Play")
                    }
                    IconButton({ Player.next() }, enabled = s.hasNext && !guest) { Icon(Icons.Default.SkipNext, "Next") }
                    IconToggleButton(settings.miniPlayerAlwaysOnTop, { pinned -> Stores.settings.update { it.copy(miniPlayerAlwaysOnTop = pinned) } }) {
                        Icon(Icons.Default.PushPin, "Always on top", tint = if (settings.miniPlayerAlwaysOnTop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Player::toggleMute) { Icon(if (s.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (s.muted) "Unmute" else "Mute") }
                    Slider(if (s.muted) 0f else s.volume / 100f, { Player.setVolume((it * 100).toInt()) }, Modifier.weight(1f).semantics { contentDescription = "Mini player volume" })
                }
                if (guest) Text("Host controls playback", style = MaterialTheme.typography.labelSmall)
                s.error?.let { Text(it, maxLines = 2, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
