package com.utaloom.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import kotlin.math.PI
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.utaloom.data.Library
import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.lyrics.LyricsRepository
import com.utaloom.platform.artworkBitmap
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val expanded = pinnedOpen || hovered
    val targetSize = islandSize(expanded)
    // iOS / Windhawk Dynamic Island spring: expand is crisp (stiff), collapse settles smoothly
    val width by animateDpAsState(
        targetSize.width,
        spring(dampingRatio = if (expanded) 0.78f else 0.86f, stiffness = if (expanded) 380f else 260f, visibilityThreshold = 0.5.dp),
        label = "islandWidth",
    )
    val height by animateDpAsState(
        targetSize.height,
        spring(dampingRatio = if (expanded) 0.80f else 0.88f, stiffness = if (expanded) 380f else 260f, visibilityThreshold = 0.5.dp),
        label = "islandHeight",
    )
    val corner by animateDpAsState(if (expanded) 30.dp else 32.dp, spring(stiffness = 400f), label = "islandCorner")
    val shape = RoundedCornerShape(corner)
    val hoverLift by animateFloatAsState(if (expanded) 1.015f else 1f, spring(dampingRatio = 0.82f, stiffness = 420f), label = "islandHoverLift")
    val islandScale by animateFloatAsState(
        if (pressed) 0.965f else hoverLift,
        spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "islandPressScale",
    )
    // Sample vibrant accent color from album art (Windhawk Auto Accent + Bloom)
    val ctx = LocalPlatformContext.current
    var rawAccent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(song?.thumbnail, settings.dynamicColor) {
        val url = song?.thumbnail
        rawAccent = if (!settings.dynamicColor || url == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val img = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(hiRes(url, 120)).build()).image
                img?.let(::artworkBitmap)?.let(::extractSeed)
            }.getOrNull()
        }
    }
    val accent by animateColorAsState(islandAccentForSeed(rawAccent), tween(450, easing = FastOutSlowInEasing), label = "islandAccent")
    val playing = status == IslandPlaybackStatus.PLAYING
    // Breathing glow: gentle ping-pong instead of a flat binary value
    val glow = remember { Animatable(0.35f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                glow.animateTo(0.95f, tween(1100, easing = FastOutSlowInEasing))
                glow.animateTo(0.55f, tween(1100, easing = FastOutSlowInEasing))
            }
        } else glow.snapTo(0.35f)
    }
    // Track-change pulse + vertical nudge (iOS & Windhawk bounce when live activity updates)
    val trackPulse = remember { Animatable(1f) }
    val nudgeY = remember { Animatable(0f) }
    val artDrop = remember { Animatable(1f) }
    LaunchedEffect(song?.id) {
        if (song != null) {
            trackPulse.snapTo(0.95f)
            nudgeY.snapTo(-5f)
            artDrop.snapTo(0f)
            trackPulse.animateTo(1f, spring(dampingRatio = 0.52f, stiffness = 420f))
        }
    }
    LaunchedEffect(song?.id) {
        if (song != null) {
            nudgeY.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 340f))
        }
    }
    LaunchedEffect(song?.id) {
        if (song != null) {
            artDrop.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 300f))
        }
    }
    // Equalizer bars run continuously (no Reverse snap-back)
    val barPhase = rememberInfiniteTransition(label = "equalizer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "barPhase",
    )
    val expandedChange by rememberUpdatedState(onExpandedChange)
    LaunchedEffect(expanded) { expandedChange(expanded) }
    val statusText = when (status) {
        IslandPlaybackStatus.EMPTY -> "Nothing playing"
        IslandPlaybackStatus.BUFFERING -> "Buffering"
        IslandPlaybackStatus.PLAYING -> "Playing"
        IslandPlaybackStatus.PAUSED -> "Paused"
        IslandPlaybackStatus.ERROR -> state.error ?: "Playback error"
    }
    val description = if (song == null) statusText else "${song.title} by ${song.artistText}, $statusText"
    // Intro pop when the island window first appears
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        intro.snapTo(0f)
        intro.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 380f))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = Color(0xF608080A), // Windhawk Obsidian palette (#08080A)
            contentColor = Color.White,
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleX = islandScale * trackPulse.value * (0.85f + 0.15f * intro.value)
                    scaleY = islandScale * trackPulse.value * (0.85f + 0.15f * intro.value)
                    translationY = nudgeY.value
                    alpha = intro.value
                }
                .drawBehind {
                    // Windhawk material: radial top accent bloom + downward depth + moving specular sheen
                    val radius = corner.toPx()
                    val highlight = glassHighlight(barPhase.value)
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.24f * glow.value), Color.Transparent),
                            center = Offset(size.width * (0.35f + 0.3f * highlight.x), 0f),
                            radius = size.width * 0.85f,
                        ),
                        cornerRadius = CornerRadius(radius),
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent, Color(0x28000000))),
                        cornerRadius = CornerRadius(radius),
                    )
                }
                .hoverable(interaction)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.42f * glow.value + 0.18f),
                            Color(0x1FFFFFFF),
                            accent.copy(alpha = 0.28f * glow.value + 0.12f),
                        ),
                    ),
                    shape,
                )
                .drawWithContent {
                    drawContent()
                    // soft top specular rim when playing
                    val radius = corner.toPx()
                    drawRoundRect(
                        brush = Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), Color(0x05FFFFFF))),
                        cornerRadius = CornerRadius(radius),
                        alpha = glow.value * 0.55f,
                    )
                }
                .semantics { contentDescription = "Dynamic Island. $description"; role = Role.Button }
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
            // Clip content to the animated pill so morphing never paints outside the rounded window
            Box(Modifier.fillMaxSize().clip(shape)) {
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        // Content morphs: expand fades content in while scale follows the pill growth,
                        // collapse mirrors it so content never blinks
                        (fadeIn(tween(150, delayMillis = 45)) + scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)))
                            .togetherWith(fadeOut(tween(110)) + scaleOut(targetScale = 0.92f, animationSpec = tween(140)))
                    },
                    label = "islandContent",
                ) { targetExpanded ->
                    if (targetExpanded) ExpandedIsland(state, statusText, library.liked.any { it.id == song?.id }, guest, settings, downloads.values.toList(), onShowMain, onClose, barPhase.value, accent, artDrop.value)
                    else CompactIsland(state, statusText, settings.dynamicIslandLyrics, settings.lyricsProviders, settings.dynamicIslandDownloads, downloads.values.toList(), barPhase.value, accent, artDrop.value)
                }
            }
        }
    }
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
    accent: Color,
    artDrop: Float,
) {
    val song = state.current
    val lyricState = rememberIslandLyrics(song, showLyrics, lyricsProviders)
    val lyricLine = (lyricState as? IslandLyrics.Ready)?.lyrics?.currentLine(state.positionMs)
    val download = downloads.firstOrNull().takeIf { showDownloads }
    val titleText = download?.title ?: song?.title ?: "Utaloom"
    val subtitle = when {
        download != null -> "Downloading ${download.title}"
        lyricLine != null && lyricLine != song?.title -> lyricLine
        song == null || state.error != null -> statusText
        else -> song.artistText
    }
    // Smooth the download ring so jumps between progress updates don't snap
    val smoothProgress by animateFloatAsState(
        download?.boundedProgress ?: 0f,
        tween(260, easing = FastOutSlowInEasing),
        label = "compactDownloadProgress",
    )
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer {
                    val s = 0.82f + 0.18f * artDrop
                    scaleX = s
                    scaleY = s
                    rotationZ = -18f * (1f - artDrop)
                }
                .clip(CircleShape)
                .background(Color(0xFF1C1C1E))
                .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (song != null) Thumb(song.thumbnail, 48.dp, shape = CircleShape) else Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD8D8D8))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            AnimatedContent(
                targetState = titleText,
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(170)) { -it / 2 } + fadeOut(tween(130)))
                },
                label = "compactTitle",
            ) { title ->
                Text(title, Modifier.basicMarquee(iterations = Int.MAX_VALUE), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            // iOS-style vertical slide when lyrics/artist/download text changes
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(140)))
                },
                label = "compactSubtitle",
            ) { text ->
                Text(
                    text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF9B9BA5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            download != null -> CircularProgressIndicator(
                progress = { smoothProgress },
                modifier = Modifier.size(22.dp).semantics { contentDescription = download.boundedProgress?.let { "Download ${(it * 100).toInt()} percent" } ?: "Downloading" },
                color = accent,
                trackColor = Color(0xFF323236),
                strokeWidth = 2.2.dp,
            )
            state.isPlaying -> Equalizer(phase, accent, playing = true)
            state.error != null -> Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFFB4AB))
            state.isBuffering -> CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.dp)
            state.current != null -> Equalizer(phase, accent, playing = false)
            else -> Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF9B9BA5))
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
    accent: Color,
    artDrop: Float,
) {
    val song = state.current
    val lyricState = rememberIslandLyrics(song, settings.dynamicIslandLyrics, settings.lyricsProviders)
    val subText = when {
        guest -> "${song?.artistText ?: statusText} · Host controls playback"
        song == null || state.error != null -> statusText
        else -> "${song.artistText} · $statusText"
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(62.dp)
                        .graphicsLayer {
                            val s = 0.86f + 0.14f * artDrop
                            scaleX = s
                            scaleY = s
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x22FFFFFF))
                        .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (song != null) Thumb(song.thumbnail, 62.dp, shape = RoundedCornerShape(16.dp)) else Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD8D8D8))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedContent(
                        targetState = song?.title ?: "Nothing playing",
                        transitionSpec = {
                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(180)))
                                .togetherWith(slideOutVertically(tween(170)) { -it / 2 } + fadeOut(tween(130)))
                        },
                        label = "expandedTitle",
                    ) { title ->
                        Text(title, Modifier.basicMarquee(iterations = Int.MAX_VALUE), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                    AnimatedContent(
                        targetState = subText,
                        transitionSpec = {
                            (slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(170)))
                                .togetherWith(slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(120)))
                        },
                        label = "expandedSubtitle",
                    ) { text ->
                        Text(
                            text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (state.error != null) Color(0xFFFFB4AB) else Color(0xFF9B9BA5),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Equalizer(phase, accent, playing = state.isPlaying)
            }

            val duration = state.durationMs.coerceAtLeast(1)
            var seeking by remember(song?.id) { mutableStateOf(false) }
            var seekFraction by remember(song?.id) { mutableFloatStateOf(0f) }
            val liveFraction = seekFraction(state.positionMs, duration)
            val smoothSeek by animateFloatAsState(
                if (seeking) seekFraction else liveFraction,
                tween(if (seeking) 40 else 180, easing = LinearEasing),
                label = "expandedSeek",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTime(if (seeking) (seekFraction * duration).toLong() else state.positionMs), color = Color(0xFF9B9BA5), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = smoothSeek,
                    onValueChange = { seeking = true; seekFraction = it },
                    onValueChangeFinished = { if (!guest) Player.seekTo((seekFraction * duration).toLong()); seeking = false },
                    enabled = song != null && state.durationMs > 0 && !guest,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color(0x28FFFFFF),
                        disabledThumbColor = Color(0xFF777777),
                        disabledActiveTrackColor = Color(0xFF555555),
                        disabledInactiveTrackColor = Color(0x1EFFFFFF),
                    ),
                )
                Text("-" + formatTime((duration - state.positionMs).coerceAtLeast(0)), color = Color(0xFF9B9BA5), style = MaterialTheme.typography.labelSmall)
            }

            if (settings.dynamicIslandDownloads && downloads.isNotEmpty()) {
                downloads.take(1).forEach { download ->
                    val dlProgress by animateFloatAsState(download.boundedProgress ?: 0f, tween(240), label = "expandedDownloadProgress")
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Download, null, Modifier.size(14.dp), tint = accent)
                            Text(download.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            Text(download.boundedProgress?.let { "${(it * 100).toInt()}%" } ?: "Starting", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.labelSmall)
                        }
                        LinearProgressIndicator(
                            progress = { dlProgress },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = accent,
                            trackColor = Color(0x26FFFFFF),
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }
            }

            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IslandIconButton(if (liked) "Unlike" else "Like", { song?.let(Library::toggleLike) }, song != null) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (liked) accent else Color.White)
                }
                Spacer(Modifier.weight(1f))
                IslandIconButton("Previous", Player::previous, song != null && !guest) { Icon(Icons.Default.SkipPrevious, null) }
                Surface(color = accent.copy(alpha = 0.24f), shape = CircleShape, modifier = Modifier.border(1.dp, accent.copy(alpha = 0.45f), CircleShape)) {
                    IslandIconButton(if (state.isPlaying) "Pause" else "Play", { Player.playPause() }, song != null && !guest) {
                        AnimatedContent(
                            targetState = state.isPlaying,
                            transitionSpec = { (scaleIn(spring(dampingRatio = 0.65f, stiffness = 520f)) + fadeIn(tween(120))).togetherWith(scaleOut(tween(100)) + fadeOut(tween(90))) },
                            label = "playPauseGlyph",
                        ) { isPlaying ->
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        }
                    }
                }
                IslandIconButton("Next", { Player.next() }, state.hasNext && !guest) { Icon(Icons.Default.SkipNext, null) }
                Spacer(Modifier.weight(1f))
                IslandIconButton("Open Utaloom", onShowMain) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                IslandIconButton("Hide Dynamic Island", onClose) { Icon(Icons.Default.Close, null) }
            }
        }

        Box(
            Modifier
                .width(268.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x16FFFFFF))
                .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { IslandLyricsPane(lyricState, song, state.positionMs, accent) }
                if (settings.dynamicIslandQueue) {
                    val upcoming = state.queue.drop(state.index + 1).take(2)
                    if (upcoming.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0x22FFFFFF))
                        Text("Up next", color = Color(0x80FFFFFF), style = MaterialTheme.typography.labelSmall)
                        upcoming.forEachIndexed { offset, queued ->
                            QueueRow(queued, enabled = !guest, accent = accent) { Player.selectQueueIndex(state.index + offset + 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(song: Song, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.7f, stiffness = 620f), label = "queueRowScale")
    val background by animateColorAsState(
        if (hovered) accent.copy(alpha = 0.16f) else Color.Transparent, tween(140), label = "queueRowBg",
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
        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(12.dp), tint = if (hovered) accent else Color(0x99FFFFFF))
        Text(song.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Color(0xE6FFFFFF))
        Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Color(0x80FFFFFF))
    }
}

@Composable
private fun IslandLyricsPane(lyricState: IslandLyrics, song: Song?, positionMs: Long, accent: Color) {
    val lyrics = (lyricState as? IslandLyrics.Ready)?.lyrics
    val index = lyrics?.let { currentLineIndex(it.lines, positionMs, it.synced) } ?: -1
    val scrollIndex by animateFloatAsState(
        if (index < 0) 0f else index.toFloat(),
        spring(dampingRatio = 0.84f, stiffness = 360f),
        label = "lyricsScroll",
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        when {
            song == null -> Text("Play something to see lyrics", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.bodySmall)
            lyricState is IslandLyrics.Loading -> Text("Loading lyrics", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Unavailable -> Text("No synced lyrics for this track", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Failed -> Text("Lyrics unavailable", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.bodyMedium)
            lyricState is IslandLyrics.Hidden -> Text("Lyrics hidden in settings", color = Color(0xFF9B9BA5), style = MaterialTheme.typography.bodyMedium)
            lyrics != null -> {
                val lines = lyrics.lines.filter { !it.background }
                val centerIdx = if (index >= 0) index else 0
                val visible = neighboringLines(lines, centerIdx, radius = 1)
                Box(Modifier.fillMaxWidth().graphicsLayer { translationY = -((scrollIndex - centerIdx).coerceIn(-1f, 1f) * 10f) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        visible.forEach { (origIndex, line) ->
                            val isCurrent = origIndex == maxOf(0, index)
                            val currentEnd = lines.getOrNull(origIndex + 1)?.timeMs ?: (line.timeMs + 4_000)
                            val progress = ((positionMs - line.timeMs).toFloat() / (currentEnd - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            val alpha = if (isCurrent) karaokeGlow(progress) else 0.42f
                            val lineScale by animateFloatAsState(if (isCurrent) 1f else 0.94f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "lyricLineScale")
                            val lineColor = if (isCurrent) Color.White.copy(alpha = alpha) else accent.copy(alpha = 0.15f).compositeOverWhite(alpha)
                            Text(
                                line.text,
                                color = lineColor,
                                style = if (isCurrent) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(vertical = 3.dp)
                                    .graphicsLayer {
                                        scaleX = lineScale
                                        scaleY = lineScale
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                    }
                                    .semantics { contentDescription = "Lyric: ${line.text}" },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Color.compositeOverWhite(alpha: Float): Color =
    Color(red * 0.25f + 0.75f, green * 0.25f + 0.75f, blue * 0.25f + 0.75f, alpha)

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
private fun Equalizer(phase: Float, accent: Color, playing: Boolean = true) {
    val activeMix by animateFloatAsState(if (playing) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "eqMix")
    Canvas(Modifier.size(24.dp, 20.dp).semantics { contentDescription = if (playing) "Playing" else "Paused" }) {
        val count = 7
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        val centerY = size.height * 0.5f
        val maxH = size.height * 0.88f
        val values = floatArrayOf(0.32f, 0.78f, 0.52f, 1f, 0.62f, 0.84f, 0.34f)
        values.forEachIndexed { idx, base ->
            // Continuous multi-harmonic wave per bar, center-symmetric like iOS & Windhawk DrawWaveform
            val w1 = 0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * (phase + idx * 0.21f))
            val w2 = 0.5f + 0.5f * kotlin.math.sin(2f * PI.toFloat() * (phase * 2f - idx * 0.33f))
            val amp = (0.35f * base + 0.45f * w1 + 0.20f * w2).coerceIn(0.08f, 1f)
            val h = (barW + (maxH - barW) * amp * activeMix).coerceAtLeast(barW)
            val x = idx * (barW + gap)
            val barColor = accent.copy(alpha = (0.42f + 0.55f * amp * activeMix).coerceIn(0.35f, 1f))
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - h * 0.5f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.5f),
            )
        }
        // ponytail: phase-driven bars, not audio-reactive; add FFT tap when Player exposes samples.
    }
}

@Composable
private fun IslandIconButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.87f
            hovered -> 1.06f
            else -> 1f
        },
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
