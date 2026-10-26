package com.utaloom.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

internal fun islandSize(expanded: Boolean) = if (expanded) DpSize(640.dp, 340.dp) else DpSize(304.dp, 64.dp)

/** @param phase 0..1 looping phase; offsets the fake-refraction wobble. */
internal fun glassHighlight(phase: Float) = Offset(phase, phase * 0.6f - 0.3f)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun DynamicIsland(onShowMain: () -> Unit, onClose: () -> Unit, onExpandedChange: (Boolean) -> Unit = {}) {
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
    val pressed by interaction.collectIsPressedAsState()
    val expanded = pinnedOpen || hovered
    val targetSize = islandSize(expanded)
    val width by animateDpAsState(targetSize.width, spring(dampingRatio = 0.78f, stiffness = 380f))
    val height by animateDpAsState(targetSize.height, spring(dampingRatio = 0.82f, stiffness = 420f))
    val corner by animateDpAsState(if (expanded) 30.dp else 32.dp, spring(stiffness = 400f))
    val shape = RoundedCornerShape(corner)
    val islandScale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "islandPressScale",
    )
    val glow by animateFloatAsState(if (status == IslandPlaybackStatus.PLAYING) 1f else 0.35f, tween(400))
    val playing = status == IslandPlaybackStatus.PLAYING
    val barPhase = rememberInfiniteTransition(label = "equalizer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "barPhase",
    )
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
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
            color = Color(0xF2050505),
            contentColor = Color.White,
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleX = islandScale
                    scaleY = islandScale
                }
                .drawBehind {
                    // liquid glass: album-tinted sheen + moving specular highlight
                    val radius = corner.toPx()
                    drawRoundRect(brush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x08FFFFFF), Color(0x24FFFFFF))), cornerRadius = CornerRadius(radius))
                    drawRoundRect(
                        brush = Brush.radialGradient(listOf(Color(0x2EFFFFFF), Color(0x00FFFFFF)), center = Offset(size.width * (0.25f + 0.25f * barPhase.value), size.height * 0.08f), radius = size.width * 0.65f),
                        cornerRadius = CornerRadius(radius),
                    )
                }
                .hoverable(interaction)
                .border(1.5.dp, Brush.linearGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF), Color(0x4DFFFFFF))), shape)
                .drawWithContent {
                    drawContent()
                    // soft outer glow when playing
                    val radius = corner.toPx()
                    drawRoundRect(brush = Brush.linearGradient(listOf(Color(0x1EFFFFFF), Color(0x05FFFFFF))), cornerRadius = CornerRadius(radius), alpha = glow * 0.5f)
                }
                .semantics { contentDescription = "Dynamic Island. $description"; role = Role.Button }
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .border(if (focused) 1.dp else 0.dp, Color(0xB3FFFFFF), shape)
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
            Crossfade(targetState = expanded, animationSpec = tween(180), label = "islandContent") { targetExpanded ->
                if (targetExpanded) ExpandedIsland(state, statusText, library.liked.any { it.id == song?.id }, guest, settings, downloads.values.toList(), onShowMain, onClose, barPhase.value)
                else CompactIsland(state, statusText, settings.dynamicIslandLyrics, settings.lyricsProviders, settings.dynamicIslandDownloads, downloads.values.toList(), barPhase.value)
            }
        }
    }
    // Overlay must never steal keyboard focus from the app or other windows; focus arrives on hover/click.
    LaunchedEffect(hovered, pinnedOpen) { if (hovered || pinnedOpen) runCatching { focusRequester.requestFocus() } }
}

@Composable
private fun CompactIsland(
    state: PlayerState,
    statusText: String,
    showLyrics: Boolean,
    lyricsProviders: List<String>,
    showDownloads: Boolean,
    downloads: List<com.utaloom.playback.ActiveDownload>,
    phase: Float,
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
            state.isPlaying -> Equalizer(phase)
            state.error != null -> Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFFB4AB))
            state.isBuffering -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            state.current != null -> Icon(if (state.isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = Color.White)
            else -> Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFFB8B8B8))
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ExpandedIsland(
    state: PlayerState,
    statusText: String,
    liked: Boolean,
    guest: Boolean,
    settings: Settings,
    downloads: List<com.utaloom.playback.ActiveDownload>,
    onShowMain: () -> Unit,
    onClose: () -> Unit,
    phase: Float,
) {
    val song = state.current
    val lyricState = rememberIslandLyrics(song, settings.dynamicIslandLyrics, settings.lyricsProviders)
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                    if (song != null) Thumb(song.thumbnail, 58.dp) else Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD8D8D8))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(song?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            guest -> "${song?.artistText ?: statusText} · Host controls playback"
                            song == null || state.error != null -> statusText
                            else -> "${song.artistText} · $statusText"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) Color(0xFFFFB4AB) else Color(0xFFB8B8B8),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (state.isPlaying) Equalizer(phase)
            }

            val duration = state.durationMs.coerceAtLeast(1)
            var seeking by remember(song?.id) { mutableStateOf(false) }
            var seekFraction by remember(song?.id) { mutableFloatStateOf(0f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTime(if (seeking) (seekFraction * duration).toLong() else state.positionMs), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (seeking) seekFraction else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { seeking = true; seekFraction = it },
                    onValueChangeFinished = { if (!guest) Player.seekTo((seekFraction * duration).toLong()); seeking = false },
                    enabled = song != null && state.durationMs > 0 && !guest,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color(0x59FFFFFF),
                        disabledThumbColor = Color(0xFF777777),
                        disabledActiveTrackColor = Color(0xFF777777),
                        disabledInactiveTrackColor = Color(0x33FFFFFF),
                    ),
                )
                Text("-" + formatTime((duration - state.positionMs).coerceAtLeast(0)), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
            }

            if (settings.dynamicIslandDownloads && downloads.isNotEmpty()) {
                downloads.take(1).forEach { download ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(14.dp), tint = Color(0xFFB8B8B8))
                        Text(download.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        Text(download.boundedProgress?.let { "${(it * 100).toInt()}%" } ?: "Starting", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IslandIconButton(if (liked) "Unlike" else "Like", { song?.let(Library::toggleLike) }, song != null) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                }
                Spacer(Modifier.weight(1f))
                IslandIconButton("Previous", Player::previous, song != null && !guest) { Icon(Icons.Default.SkipPrevious, null) }
                Surface(color = Color(0x26FFFFFF), shape = CircleShape) {
                    IslandIconButton(if (state.isPlaying) "Pause" else "Play", { Player.playPause() }, song != null && !guest) {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    }
                }
                IslandIconButton("Next", { Player.next() }, state.hasNext && !guest) { Icon(Icons.Default.SkipNext, null) }
                Spacer(Modifier.weight(1f))
                IslandIconButton("Open Utaloom", onShowMain) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                IslandIconButton("Hide Dynamic Island", onClose) { Icon(Icons.Default.Close, null) }
            }
        }

        Box(Modifier.width(268.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { IslandLyricsPane(lyricState, song, state.positionMs) }
                if (settings.dynamicIslandQueue) {
                    val upcoming = state.queue.drop(state.index + 1).take(2)
                    if (upcoming.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0x22FFFFFF))
                        Text("Up next", color = Color(0x80FFFFFF), style = MaterialTheme.typography.labelSmall)
                        upcoming.forEachIndexed { offset, queued ->
                            QueueRow(queued, enabled = !guest) { Player.selectQueueIndex(state.index + offset + 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(song: Song, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.7f, stiffness = 620f), label = "queueRowScale")
    val background by androidx.compose.animation.animateColorAsState(
        if (hovered) Color(0x1FFFFFFF) else Color.Transparent, tween(140), label = "queueRowBg",
    )
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp)).background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Play next: ${song.title} by ${song.artistText}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.QueueMusic, null, Modifier.size(12.dp), tint = Color(0x99FFFFFF))
        Text(song.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Color(0xE6FFFFFF))
        Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Color(0x80FFFFFF))
    }
}

@Composable
private fun IslandLyricsPane(lyricState: IslandLyrics, song: Song?, positionMs: Long) {
    val lyrics = (lyricState as? IslandLyrics.Ready)?.lyrics
    val index = lyrics?.let { currentLineIndex(it.lines, positionMs, it.synced) } ?: -1
    val scrollIndex by animateFloatAsState(
        if (index < 0) 0f else index.toFloat(),
        spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "lyricsScroll",
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        when {
            song == null -> Text("Play something to see lyrics", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall)
            lyricState is IslandLyrics.Loading -> Text("Loading lyrics", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Unavailable -> Text("No synced lyrics for this track", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Failed -> Text("Lyrics unavailable", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Hidden -> Text("Lyrics hidden in settings", color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodyMedium)
            lyrics != null -> {
                val lines = lyrics.lines.filter { !it.background }
                val centerIdx = if (index >= 0) index else 0
                val visible = neighboringLines(lines, centerIdx, radius = 1)
                val basePad = 8
                Box(Modifier.fillMaxWidth().graphicsLayer { translationY = -((scrollIndex - centerIdx).coerceIn(-1f, 1f) * 6) }) {
                    Column {
                        visible.forEach { (origIndex, line) ->
                            val isCurrent = origIndex == maxOf(0, index)
                            val currentEnd = lines.getOrNull(origIndex + 1)?.timeMs ?: (line.timeMs + 4_000)
                            val progress = ((positionMs - line.timeMs).toFloat() / (currentEnd - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            val alpha = if (isCurrent) karaokeGlow(progress) else 0.45f
                            Text(
                                line.text,
                                color = Color.White.copy(alpha = alpha),
                                style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 3.dp).semantics { contentDescription = "Lyric: ${line.text}" },
                            )
                        }
                    }
                }
            }
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
private fun Equalizer(phase: Float) {
    Canvas(Modifier.size(22.dp).semantics { contentDescription = "Playing" }) {
        val barW = size.width / 7f
        val values = floatArrayOf(0.35f, 0.8f, 0.5f, 1f, 0.6f, 0.85f, 0.3f)
        values.forEachIndexed { idx, base ->
            val h = size.height * (0.25f + 0.75f * ((phase + idx * 0.13f) % 1f))
            drawRoundRect(Color.White, topLeft = Offset(idx * barW, size.height - h), size = androidx.compose.ui.geometry.Size(barW * 0.6f, h), cornerRadius = CornerRadius(barW / 2f))
        }
        // ponytail: phase-driven bars, not audio-reactive; add FFT tap when Player exposes samples.
    }
}

@Composable
private fun IslandIconButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.68f, stiffness = 700f),
        label = "iconPressScale",
    )
    IconButton(
        onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { contentDescription = description },
        content = content,
    )
}
