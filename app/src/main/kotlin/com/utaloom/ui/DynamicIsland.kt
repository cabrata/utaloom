package com.utaloom.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.utaloom.data.Library
import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.lyrics.LyricsRepository
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerState
import com.utaloom.shared.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class IslandPlaybackStatus { EMPTY, BUFFERING, PLAYING, PAUSED, ERROR }

internal fun islandPlaybackStatus(state: PlayerState): IslandPlaybackStatus = when {
    state.error != null -> IslandPlaybackStatus.ERROR
    state.current == null -> IslandPlaybackStatus.EMPTY
    state.isBuffering -> IslandPlaybackStatus.BUFFERING
    state.isPlaying -> IslandPlaybackStatus.PLAYING
    else -> IslandPlaybackStatus.PAUSED
}

internal fun islandSize(expanded: Boolean) = if (expanded) DpSize(420.dp, 340.dp) else DpSize(304.dp, 64.dp)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun DynamicIsland(onShowMain: () -> Unit, onClose: () -> Unit, onSizeChange: (DpSize) -> Unit = {}) {
    val state by Player.state.collectAsState()
    val library by Stores.library.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val downloads by Downloads.activeDownloads.collectAsState()
    val song = state.current
    val status = islandPlaybackStatus(state)
    val guest = Player.remoteControlled
    var pinnedOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val expanded = pinnedOpen || hovered
    val targetSize = islandSize(expanded)
    val width by animateDpAsState(targetSize.width, tween(220))
    val height by animateDpAsState(targetSize.height, tween(220))
    LaunchedEffect(width, height) { onSizeChange(DpSize(width, height)) }
    val statusText = when (status) {
        IslandPlaybackStatus.EMPTY -> "Nothing playing"
        IslandPlaybackStatus.BUFFERING -> "Buffering"
        IslandPlaybackStatus.PLAYING -> "Playing"
        IslandPlaybackStatus.PAUSED -> "Paused"
        IslandPlaybackStatus.ERROR -> state.error ?: "Playback error"
    }
    val description = if (song == null) statusText else "${song.title} by ${song.artistText}, $statusText"

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = Color(0xFF050505),
            contentColor = Color.White,
            shape = RoundedCornerShape(if (expanded) 30.dp else 32.dp),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(width, height)
                .hoverable(interaction)
                .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(if (expanded) 30.dp else 32.dp))
                .semantics { contentDescription = "Dynamic Island. $description"; role = Role.Button }
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Escape -> { if (expanded) pinnedOpen = false else onClose(); true }
                        Key.Enter -> { pinnedOpen = !pinnedOpen; true }
                        Key.Spacebar -> { if (song != null && !guest) Player.playPause(); true }
                        else -> false
                    }
                }
                .clickable(interactionSource = interaction, indication = null) { pinnedOpen = !pinnedOpen },
        ) {
            if (expanded) ExpandedIsland(state, statusText, library.liked.any { it.id == song?.id }, guest, settings, downloads.values.toList(), onShowMain, onClose)
            else CompactIsland(state, statusText, settings.dynamicIslandLyrics, settings.lyricsProviders, settings.dynamicIslandDownloads, downloads.values.toList())
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun CompactIsland(
    state: PlayerState,
    statusText: String,
    showLyrics: Boolean,
    lyricsProviders: List<String>,
    showDownloads: Boolean,
    downloads: List<com.utaloom.playback.ActiveDownload>,
) {
    val song = state.current
    val lyricState = rememberIslandLyrics(song, showLyrics, lyricsProviders)
    val lyricLine = (lyricState as? IslandLyrics.Ready)?.lyrics?.currentLine(state.positionMs)
    val download = downloads.firstOrNull().takeIf { showDownloads }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF232323)), contentAlignment = Alignment.Center) {
            if (song != null) Thumb(song.thumbnail, 48.dp) else Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD8D8D8))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(download?.title ?: song?.title ?: "Utaloom", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    download != null -> "Downloading ${download.title}"
                    lyricLine != null && lyricLine != song?.title -> lyricLine
                    song == null || state.error != null -> statusText
                    else -> song.artistText
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFFB8B8B8),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            download != null -> CircularProgressIndicator(
                progress = { download.boundedProgress ?: 0f },
                modifier = Modifier.size(22.dp).semantics { contentDescription = download.boundedProgress?.let { "Download ${(it * 100).toInt()} percent" } ?: "Downloading" },
                color = Color.White,
                trackColor = Color(0xFF555555),
                strokeWidth = 2.dp,
            )
            state.error != null -> Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFFB4AB))
            state.isBuffering -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            state.current != null -> Icon(if (state.isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = Color.White)
            else -> Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFFB8B8B8))
        }
    }
}

@Composable
private fun ExpandedIsland(
    state: PlayerState,
    statusText: String,
    liked: Boolean,
    guest: Boolean,
    settings: Settings,
    downloads: List<com.utaloom.playback.ActiveDownload>,
    onShowMain: () -> Unit,
    onClose: () -> Unit,
) {
    val song = state.current
    val lyricState = rememberIslandLyrics(song, settings.dynamicIslandLyrics, settings.lyricsProviders)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF232323)), contentAlignment = Alignment.Center) {
                if (song != null) Thumb(song.thumbnail, 58.dp) else Icon(Icons.Default.MusicNote, "No artwork", tint = Color(0xFFD8D8D8))
            }
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        state.error != null || state.isBuffering || song == null -> statusText
                        guest -> "${song.artistText} · $statusText · Host controls playback"
                        else -> "${song.artistText} · $statusText"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state.error != null) Color(0xFFFFB4AB) else Color(0xFFB8B8B8),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IslandIconButton("Open Utaloom", onShowMain) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
            IslandIconButton("Hide Dynamic Island", onClose) { Icon(Icons.Default.Close, null) }
        }

        if (settings.dynamicIslandLyrics && song != null) {
            val lyricText = when (lyricState) {
                IslandLyrics.Loading -> "Loading lyrics"
                IslandLyrics.Unavailable -> "Synced lyrics unavailable"
                IslandLyrics.Failed -> "Lyrics unavailable"
                is IslandLyrics.Ready -> lyricState.lyrics.currentLine(state.positionMs) ?: "Lyrics start soon"
                IslandLyrics.Hidden -> ""
            }
            Text(lyricText, Modifier.fillMaxWidth().semantics { contentDescription = "Lyrics: $lyricText" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (lyricState is IslandLyrics.Ready) Color.White else Color(0xFFB8B8B8))
        }

        if (settings.dynamicIslandDownloads && downloads.isNotEmpty()) {
            downloads.take(2).forEach { download ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(16.dp), tint = Color(0xFFB8B8B8))
                    Text(download.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(download.boundedProgress?.let { "${(it * 100).toInt()}%" } ?: "Starting", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (settings.dynamicIslandQueue) {
            state.queue.drop(state.index + 1).take(3).forEachIndexed { offset, queued ->
                TextButton(
                    onClick = { Player.selectQueueIndex(state.index + offset + 1) },
                    enabled = !guest,
                    modifier = Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = "Play next: ${queued.title} by ${queued.artistText}" },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(Icons.Default.QueueMusic, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(queued.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(queued.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        val duration = state.durationMs.coerceAtLeast(1)
        var seeking by remember(song?.id) { mutableStateOf(false) }
        var seekFraction by remember(song?.id) { mutableFloatStateOf(0f) }
        Slider(
            value = if (seeking) seekFraction else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { seeking = true; seekFraction = it },
            onValueChangeFinished = { if (!guest) Player.seekTo((seekFraction * duration).toLong()); seeking = false },
            enabled = song != null && state.durationMs > 0 && !guest,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF555555),
                disabledThumbColor = Color(0xFF777777),
                disabledActiveTrackColor = Color(0xFF777777),
                disabledInactiveTrackColor = Color(0xFF3A3A3A),
            ),
            modifier = Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = "Playback position" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (seeking) (seekFraction * duration).toLong() else state.positionMs), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(state.durationMs), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IslandIconButton(if (liked) "Unlike" else "Like", { song?.let(Library::toggleLike) }, song != null) {
                Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (liked) Color(0xFFFF6B81) else Color.White)
            }
            IslandIconButton("Previous", Player::previous, song != null && !guest) { Icon(Icons.Default.SkipPrevious, null) }
            FilledIconButton(
                onClick = Player::playPause,
                enabled = song != null && !guest,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.size(44.dp).semantics { contentDescription = if (state.isPlaying) "Pause" else "Play" },
            ) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            IslandIconButton("Next", { Player.next() }, state.hasNext && !guest) { Icon(Icons.Default.SkipNext, null) }
            IslandIconButton("Open main window", onShowMain) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
        }
    }
}

private sealed interface IslandLyrics {
    data object Hidden : IslandLyrics
    data object Loading : IslandLyrics
    data object Unavailable : IslandLyrics
    data object Failed : IslandLyrics
    data class Ready(val lyrics: Lyrics) : IslandLyrics
}

internal fun Lyrics.currentLine(positionMs: Long): String? {
    if (!synced) return null
    return lines.getOrNull(currentIndex(positionMs))?.text?.trim()?.takeIf(String::isNotEmpty)
}

@Composable
private fun rememberIslandLyrics(song: Song?, enabled: Boolean, providers: List<String> = Stores.settings.value.lyricsProviders): IslandLyrics {
    val result by produceState<IslandLyrics>(if (enabled && song != null) IslandLyrics.Loading else IslandLyrics.Hidden, song?.id, enabled, providers) {
        if (!enabled || song == null) {
            value = IslandLyrics.Hidden
            return@produceState
        }
        value = IslandLyrics.Loading
        value = try {
            val lyrics = withContext(Dispatchers.IO) { LyricsRepository.get(song, providers) }
            if (lyrics?.synced == true) IslandLyrics.Ready(lyrics) else IslandLyrics.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IslandLyrics.Failed
        }
    }
    return result
}

@Composable
private fun IslandIconButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(36.dp).semantics { contentDescription = description }, content = content)
}
