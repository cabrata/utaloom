package com.utaloom.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
internal fun CanvasArtwork(song: Song?, url: String?, playing: Boolean, modifier: Modifier = Modifier) {
    var failed by remember(song?.id, url) { mutableStateOf(false) }
    var ready by remember(song?.id, url) { mutableStateOf(false) }
    val showCanvas = url != null && !failed
    val alpha by animateFloatAsState(if (ready && showCanvas) 1f else 0f, tween(350), label = "Canvas reveal")
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (showCanvas) 3f / 4f else 1f
        val width = minOf(if (showCanvas) 400.dp else 380.dp, maxWidth, maxHeight * ratio).coerceAtLeast(1.dp)
        val bottomColor = MaterialTheme.colorScheme.background
        Box(Modifier.size(width, width / ratio).clip(RoundedCornerShape(20.dp)).semantics {
            contentDescription = if (ready && showCanvas) "Spotify Canvas for ${song?.title.orEmpty()}" else "Now playing artwork"
        }) {
            Thumb(hiRes(song?.thumbnail), width, RoundedCornerShape(20.dp), Modifier.fillMaxSize())
            if (showCanvas && LocalCanvasActive.current) {
                key(song?.id, url) {
                    CanvasVideo(url, playing, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
                        onReady = { ready = it }, onError = { ready = false; failed = true })
                }
                Box(Modifier.fillMaxWidth().height(64.dp).graphicsLayer { this.alpha = alpha }
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.BottomCenter).graphicsLayer { this.alpha = alpha }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, bottomColor))))
            }
        }
    }
}
