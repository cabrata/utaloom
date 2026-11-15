package com.utaloom.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.utaloom.canvas.SpotifyCanvasClient
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.CanvasVideo

internal val LocalCanvasActive = staticCompositionLocalOf { true }

@Composable
internal fun rememberCanvasUrl(song: Song?, enabled: Boolean, spDc: String?): String? {
    var url by remember(song?.id, song?.title, song?.artists, enabled, spDc) { mutableStateOf<String?>(null) }
    LaunchedEffect(song?.id, song?.title, song?.artists, enabled, spDc) {
        if (enabled && song != null && !spDc.isNullOrBlank()) {
            url = SpotifyCanvasClient.fetchCanvas(song.title, song.artists.firstOrNull().orEmpty(), spDc)
        }
    }
    return url
}

@Composable
internal fun CanvasToggle(enabled: Boolean) {
    IconToggleButton(enabled, { value -> Stores.settings.update { it.copy(spotifyCanvasEnabled = value) } }) {
        Icon(Icons.Default.SlowMotionVideo, if (enabled) "Turn off Spotify Canvas" else "Turn on Spotify Canvas",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Artwork slot that turns into a full-bleed Canvas loop, like utaloom-android: no card, scrims blend the
 * video into the player background. [artPadding] only applies to the square artwork shown without a Canvas.
 */
@Composable
internal fun CanvasArtwork(
    song: Song?, url: String?, playing: Boolean, modifier: Modifier = Modifier,
    artPadding: PaddingValues = PaddingValues(0.dp), topScrim: Boolean = false, endFade: Boolean = false,
    onShown: (Boolean) -> Unit = {},
) {
    var failed by remember(song?.id, url) { mutableStateOf(false) }
    var ready by remember(song?.id, url) { mutableStateOf(false) }
    val active = url != null && !failed && LocalCanvasActive.current
    val shown = active && ready
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(350), label = "Canvas reveal")
    val shownCallback by rememberUpdatedState(onShown)
    LaunchedEffect(shown) { shownCallback(shown) }
    Box(modifier) {
        if (active && url != null) key(song?.id, url) {
            val fade = MaterialTheme.colorScheme.background.let { listOf(Color.Transparent, it.copy(alpha = 0.7f), it) }
            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.semantics {
                if (shown) contentDescription = "Spotify Canvas for ${song?.title.orEmpty()}"
            }) {
                CanvasVideo(url, playing, Modifier.fillMaxSize(), onReady = { ready = it }, onError = { ready = false; failed = true })
                if (topScrim) Box(Modifier.fillMaxWidth().height(90.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(fade)))
                if (endFade) Box(Modifier.fillMaxHeight().width(120.dp).align(Alignment.CenterEnd).background(Brush.horizontalGradient(fade)))
            }
        }
        if (alpha < 1f) BoxWithConstraints(Modifier.fillMaxSize().padding(artPadding).graphicsLayer { this.alpha = 1f - alpha },
            contentAlignment = Alignment.Center) {
            val size = minOf(380.dp, maxWidth, maxHeight).coerceAtLeast(1.dp)
            Thumb(hiRes(song?.thumbnail), size, RoundedCornerShape(20.dp), Modifier.semantics { contentDescription = "Now playing artwork" })
        }
    }
}
