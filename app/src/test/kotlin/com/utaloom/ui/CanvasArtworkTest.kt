package com.utaloom.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.IntSize
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.CanvasVideoTest
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class CanvasArtworkTest {
    @Test
    fun videoRevealsAndTrackChangesAndErrorsRestoreArtwork() {
        assumeTrue("VLC required", NativeLibs.discoverVlc() == null)
        val song = mutableStateOf(Song("canvas-one", "Canvas test", listOf("Utaloom")))
        val url = mutableStateOf<String?>(CanvasVideoTest.fixture().absolutePath)
        Scene(480, 640) { CanvasArtwork(song.value, url.value, true, Modifier.fillMaxSize(), topScrim = true, endFade = true) }.use { ui ->
            ui.await("first Canvas frame") { ui.description("Spotify Canvas for Canvas test") != null }
            ui.frame(30)
            ui.capture("canvas-playing")
            song.value = song.value.copy(id = "canvas-two", title = "Next song")
            url.value = null
            ui.frame()
            assertTrue(ui.description("Now playing artwork") != null)
            assertTrue(ui.description("Spotify Canvas for Canvas test") == null)
            url.value = File("build/canvas-test/missing.mp4").absolutePath
            ui.await("error fallback") {
                val node = ui.description("Now playing artwork")
                node != null && kotlin.math.abs(node.boundsInRoot.width - node.boundsInRoot.height) < 1f
            }
            ui.capture("canvas-fallback")
        }
    }

    @Test
    fun expandedPlayerKeepsTransportInsideSmallWindows() {
        assumeTrue("Layout check uses an uninitialized audio engine", !Player.available)
        val settings = Stores.settings.value
        val previous = Player.state.value
        try {
            Stores.settings.update { it.copy(spotifySpDc = null, lyricsDisabled = it.lyricsProviderOrder.toSet()) }
            Player.playQueue(listOf(Song("canvas-layout", "A long song title for small windows", listOf("Utaloom"))), play = false)
            for ((width, height, compact) in listOf(Triple(900, 540, false), Triple(1280, 820, false), Triple(360, 640, true))) {
                Scene(width, height) { FullPlayer({}, false, {}, compact) }.use { ui ->
                    for (description in listOf("Close player", "Play", "Playback position", "Now playing artwork")) {
                        val bounds = checkNotNull(ui.description(description)) { "Missing $description at $width x $height" }.boundsInRoot
                        assertTrue("$description must fit $width x $height: $bounds",
                            bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                    }
                    ui.capture("player-${width}x$height")
                }
            }
        } finally {
            Player.stop()
            if (previous.queue.isNotEmpty()) Player.playQueue(previous.queue, previous.index, previous.queueTitle, play = false)
            Stores.settings.update { settings }
        }
    }

    @Test
    fun playerTogglePersistsAndArtworkFitsShortAndNarrowLayouts() {
        val original = Stores.settings.value
        try {
            Stores.settings.update { it.copy(spotifyCanvasEnabled = true) }
            Scene(240, 80) {
                val settings by Stores.settings.state.collectAsState()
                CanvasToggle(settings.spotifyCanvasEnabled)
            }.use { ui ->
                val toggle = checkNotNull(ui.description("Turn off Spotify Canvas"))
                assertTrue(checkNotNull(toggle.config[SemanticsActions.OnClick].action).invoke())
                ui.frame()
                assertEquals(false, Stores.settings.value.spotifyCanvasEnabled)
                assertTrue(ui.description("Turn on Spotify Canvas") != null)
            }
            for ((width, height) in listOf(320 to 480, 480 to 240)) {
                Scene(width, height) { CanvasArtwork(null, null, false, Modifier.fillMaxSize()) }.use { ui ->
                    val bounds = checkNotNull(ui.description("Now playing artwork")).boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                    assertEquals(bounds.width, bounds.height, 1f)
                }
            }
        } finally { Stores.settings.update { original } }
    }

    private class Scene(private val width: Int, private val height: Int, content: @Composable () -> Unit) : AutoCloseable {
        private val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        private var owner: SemanticsOwner? = null
        private val context = object : PlatformContext by PlatformContext.Empty() {
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owner = semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owner = null }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
            }
        }
        private val scene = CanvasLayersComposeScene(recomposer, size = IntSize(width, height), platformContext = context)
        private val surface = Surface.makeRasterN32Premul(width, height)
        private var time = 0L
        init { scene.setContent { MaterialTheme { content() } }; frame() }
        fun frame(count: Int = 4) {
            repeat(count) {
                time += 16_000_000
                recomposer.performFrame(time)
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
        }
        fun description(text: String): SemanticsNode? {
            fun find(node: SemanticsNode): SemanticsNode? =
                if (node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(text)) node
                else node.children.firstNotNullOfOrNull(::find)
            return owner?.rootSemanticsNode?.let(::find)
        }
        fun await(message: String, condition: () -> Boolean) {
            val end = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < end) {
                frame()
                if (condition()) return
                Thread.sleep(20)
            }
            check(condition()) { "Timed out: $message" }
        }
        fun capture(name: String) {
            frame()
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)?.use { data ->
                    File("build/canvas-test/$name.png").writeBytes(data.bytes)
                }
            }
        }
        override fun close() { scene.close(); recomposer.close(); surface.close() }
    }
}
