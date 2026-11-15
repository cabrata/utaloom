package com.utaloom.platform

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.skiaCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
internal fun CanvasVideo(url: String, playing: Boolean, modifier: Modifier = Modifier, onReady: (Boolean) -> Unit = {}, onError: () -> Unit = {}) {
    val player = remember(url) { CanvasVideoPlayer(url, playing) }
    val readyCallback by rememberUpdatedState(onReady)
    val errorCallback by rememberUpdatedState(onError)
    var revision by remember(player) { mutableLongStateOf(0) }
    DisposableEffect(player) {
        readyCallback(false)
        onDispose { player.close() }
    }
    SideEffect { player.setPlaying(playing) }
    LaunchedEffect(player) {
        var ready = false
        val startedAt = System.nanoTime()
        while (isActive) {
            if (player.failed || !ready && System.nanoTime() - startedAt > 15_000_000_000L) {
                readyCallback(false)
                errorCallback()
                break
            }
            val latest = player.revision.get()
            if (latest != revision) {
                revision = latest
                if (!ready) { ready = true; readyCallback(true) }
            }
            delay(33)
        }
    }
    Canvas(modifier.clipToBounds()) {
        if (revision > 0) player.draw(drawContext.canvas.skiaCanvas, size.width, size.height)
    }
}

/** Owns one silent decoder. All native control and teardown work stays on its serial worker. */
internal class CanvasVideoPlayer(private val url: String, playing: Boolean) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "utaloom-canvas").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val frameLock = Any()
    private var pixels = ByteArray(0)
    private var info: ImageInfo? = null
    private var pitch = 0
    private var lastFrameAt = 0L
    private var factory: MediaPlayerFactory? = null
    private var player: EmbeddedMediaPlayer? = null
    @Volatile private var shouldPlay = playing
    @Volatile var failed = false
        private set
    @Volatile var paused = !playing
        private set
    val revision = AtomicLong()
    val released = CompletableFuture<Unit>()

    init {
        worker.execute {
            try {
                if (closed.get()) return@execute
                check(NativeLibs.discoverVlc() == null) { "VLC unavailable" }
                val nativeFactory = MediaPlayerFactory("--no-audio", "--no-osd", "--no-video-title-show", "--no-sub-autodetect-file", "--avcodec-hw=none",
                    if (System.getenv("UTALOOM_DEBUG") == null) "--quiet" else "--verbose=1")
                factory = nativeFactory
                val nativePlayer = nativeFactory.mediaPlayers().newEmbeddedMediaPlayer()
                player = nativePlayer
                nativePlayer.videoSurface().set(nativeFactory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                        val scale = minOf(1.0, 720.0 / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1))
                        return RV32BufferFormat((sourceWidth * scale).toInt().coerceAtLeast(1), (sourceHeight * scale).toInt().coerceAtLeast(1))
                    }
                    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {}
                    override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
                }, object : RenderCallback {
                    override fun lock(mediaPlayer: MediaPlayer) {}
                    override fun unlock(mediaPlayer: MediaPlayer) {}
                    override fun display(mediaPlayer: MediaPlayer, buffers: Array<ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
                        if (closed.get() || failed || !shouldPlay && revision.get() > 0) return
                        synchronized(frameLock) {
                            val now = System.nanoTime()
                            if (now - lastFrameAt < 30_000_000L) return
                            lastFrameAt = now
                            pitch = format.pitches[0]
                            val count = pitch * format.height
                            if (pixels.size != count) pixels = ByteArray(count)
                            buffers[0].duplicate().apply { clear(); get(pixels) }
                            // RV32 is BGRX on our little-endian desktop targets. Its fourth byte is not alpha.
                            info = ImageInfo(format.width, format.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                            // A paused song still gets one Canvas frame, then the decoder pauses.
                            if (revision.incrementAndGet() == 1L && !shouldPlay) syncPlayback()
                        }
                    }
                }, true))
                nativePlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun playing(mediaPlayer: MediaPlayer) { paused = false; if (shouldPlay || revision.get() > 0) syncPlayback() }
                    override fun paused(mediaPlayer: MediaPlayer) { paused = true }
                    override fun error(mediaPlayer: MediaPlayer) { failed = true }
                })
                nativePlayer.controls().setRepeat(true)
                if (!closed.get()) check(nativePlayer.media().play(url, ":no-audio", ":input-repeat=65535", ":network-caching=1000")) { "Canvas could not start" }
            } catch (failure: Exception) {
                System.err.println("Canvas decoder unavailable (${failure.javaClass.simpleName})")
                if (System.getenv("UTALOOM_DEBUG") != null) failure.printStackTrace()
                failed = true
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        if (closed.get() || shouldPlay == playing) return
        shouldPlay = playing
        syncPlayback()
    }

    private fun syncPlayback() {
        // VLC events may arrive during release. Never enqueue native calls after teardown starts.
        synchronized(worker) {
            if (!closed.get()) worker.execute {
                if (!closed.get() && !failed) runCatching { player?.controls()?.setPause(!shouldPlay) }.onFailure { failed = true }
            }
        }
    }

    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        synchronized(frameLock) {
            val imageInfo = info ?: return
            if (closed.get() || failed) return
            val scale = maxOf(width / imageInfo.width, height / imageInfo.height)
            val w = imageInfo.width * scale
            val h = imageInfo.height * scale
            Image.makeRaster(imageInfo, pixels, pitch).use { image ->
                canvas.drawImageRect(image, Rect.makeXYWH((width - w) / 2, (height - h) / 2, w, h))
            }
        }
    }

    override fun close() {
        synchronized(worker) {
            if (!closed.compareAndSet(false, true)) return
            worker.execute {
                try {
                    try { player?.release() } finally { factory?.release() }
                    synchronized(frameLock) { pixels = ByteArray(0); info = null }
                    released.complete(Unit)
                } catch (failure: Exception) {
                    released.completeExceptionally(failure)
                } finally {
                    worker.shutdown()
                }
            }
        }
    }
}
