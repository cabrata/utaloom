package com.utaloom.ui

import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.playback.ActiveDownload
import com.utaloom.playback.PlayerState
import com.utaloom.playback.downloadProgress
import com.utaloom.platform.ACTIVE_MONITOR
import com.utaloom.platform.PRIMARY_MONITOR
import com.utaloom.platform.selectIslandMonitor
import com.utaloom.platform.IslandMonitor as PlacementMonitor
import com.utaloom.platform.islandTopCenter as placementTopCenter
import com.utaloom.shared.LyricLine
import com.utaloom.shared.Lyrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

class DynamicIslandTest {
    @Test
    fun glassHighlightStaysInsideNormalizedBounds() {
        val center = glassHighlight(0f)
        val edge = glassHighlight(1f)
        assertTrue(center.x >= 0f)
        assertTrue(center.y >= -0.3f)
        assertTrue(edge.x <= 1.1f)
        assertTrue(edge.y <= 0.3f)
    }

    private val song = Song("track", "Track", listOf("Artist"))

    @Test
    fun playbackStatusPrioritizesErrorAndBuffering() {
        assertEquals(IslandPlaybackStatus.EMPTY, islandPlaybackStatus(PlayerState()))
        assertEquals(IslandPlaybackStatus.PAUSED, islandPlaybackStatus(PlayerState(queue = listOf(song), index = 0)))
        assertEquals(IslandPlaybackStatus.PLAYING, islandPlaybackStatus(PlayerState(queue = listOf(song), index = 0, isPlaying = true)))
        assertEquals(IslandPlaybackStatus.BUFFERING, islandPlaybackStatus(PlayerState(queue = listOf(song), index = 0, isPlaying = true, isBuffering = true)))
        assertEquals(IslandPlaybackStatus.ERROR, islandPlaybackStatus(PlayerState(queue = listOf(song), index = 0, isBuffering = true, error = "Unavailable")))
    }

    @Test
    fun sizeAndTopCenterRespectUsableScreen() {
        assertTrue(islandSize(false).width < islandSize(true).width)
        assertTrue(islandSize(false).height < islandSize(true).height)
        assertEquals(
            Point(800, 48),
            com.utaloom.platform.islandTopCenter(Rectangle(100, 20, 1920, 1080), Insets(20, 10, 40, 30), Dimension(500, 200)),
        )
    }

    @Test
    fun settingsDefaultsAndRoundTripKeepIslandEnabled() {
        val defaults = Settings()
        assertTrue(defaults.dynamicIslandEnabled)
        assertTrue(defaults.dynamicIslandAlwaysOnTop)
        assertTrue(defaults.dynamicIslandLyrics)
        assertTrue(defaults.dynamicIslandDownloads)
        assertTrue(defaults.dynamicIslandQueue)
        assertEquals("active", defaults.dynamicIslandMonitor)
        assertEquals(8, defaults.dynamicIslandTopOffset)

        val changed = defaults.copy(
            dynamicIslandEnabled = false,
            dynamicIslandAlwaysOnTop = false,
            dynamicIslandLyrics = false,
            dynamicIslandDownloads = false,
            dynamicIslandQueue = false,
            dynamicIslandMonitor = "HDMI-1",
            dynamicIslandTopOffset = 16,
        )
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), changed))
        assertFalse(parsed.dynamicIslandEnabled)
        assertFalse(parsed.dynamicIslandAlwaysOnTop)
        assertFalse(parsed.dynamicIslandLyrics)
        assertFalse(parsed.dynamicIslandDownloads)
        assertFalse(parsed.dynamicIslandQueue)
        assertEquals("HDMI-1", parsed.dynamicIslandMonitor)
        assertEquals(16, parsed.dynamicIslandTopOffset)
    }

    @Test
    fun syncedLyricsFollowPlaybackPosition() {
        val lyrics = Lyrics(
            provider = "test",
            synced = true,
            lines = listOf(
                LyricLine(1_000, "First line"),
                LyricLine(3_000, "   "),
                LyricLine(4_000, "Background voice", background = true),
                LyricLine(5_000, "Second line"),
            ),
            plain = "First line\nSecond line",
        )

        // Before lookahead window (300ms before 1000ms is 700ms)
        assertNull(lyrics.currentLine(0))
        assertNull(lyrics.currentLine(699))
        assertEquals("First line", lyrics.currentLine(700))
        assertEquals("First line", lyrics.currentLine(1_000))

        // Blank line is skipped
        assertNull(lyrics.currentLine(3_000))

        // A blank timed line represents a lyric gap, including while background vocals overlap it
        assertNull(lyrics.currentLine(4_200))

        // Second main line
        assertEquals("Second line", lyrics.currentLine(5_500))

        // Unsynced lyrics always return null
        assertNull(lyrics.copy(synced = false).currentLine(5_500))
    }

    @Test
    fun lyricCacheStoresMissesAndDeduplicatesConcurrentLoads() = kotlinx.coroutines.runBlocking {
        val songId = "cache-${System.nanoTime()}"
        val calls = java.util.concurrent.atomic.AtomicInteger()

        val results = kotlinx.coroutines.coroutineScope {
            List(8) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(kotlinx.coroutines.Dispatchers.Default) {
                    com.utaloom.lyrics.LyricsRepository.cached(songId, listOf("test")) {
                        calls.incrementAndGet()
                        kotlinx.coroutines.delay(25)
                        null
                    }
                }
            }.awaitAll()
        }
        assertTrue(results.all { it == null })
        assertEquals(1, calls.get())

        com.utaloom.lyrics.LyricsRepository.cached(songId, listOf("test")) { error("cached miss fetched twice") }
        com.utaloom.lyrics.LyricsRepository.cached(songId, listOf("other")) { calls.incrementAndGet(); null }
        assertEquals(2, calls.get())
        com.utaloom.lyrics.LyricsRepository.invalidate(songId)
    }

    @Test
    fun downloadCompactPriorityLogic() {
        val active = mapOf("dl-1" to ActiveDownload(song, 0.65f))

        fun resolveCompactSubtitle(
            downloadsEnabled: Boolean,
            downloads: Map<String, ActiveDownload>,
            lyricLine: String?,
        ): String {
            if (downloadsEnabled && downloads.isNotEmpty()) {
                val dl = downloads.values.first()
                val pct = dl.progress?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
                return "Downloading ${dl.title}$pct"
            }
            return lyricLine ?: song.artistText
        }

        assertEquals("Downloading Track (65%)", resolveCompactSubtitle(true, active, "Active Lyric"))
        assertEquals("Active Lyric", resolveCompactSubtitle(false, active, "Active Lyric"))
        assertEquals("Artist", resolveCompactSubtitle(true, emptyMap(), null))
        assertNull(downloadProgress(10, null))
        assertNull(downloadProgress(10, 0))
        assertEquals(0.5f, downloadProgress(50, 100)!!, 0f)
        assertEquals(1f, downloadProgress(120, 100)!!, 0f)
    }

    @Test
    fun queuePreviewNextThreeSelection() {
        val queue = (1..6).map { Song("q-$it", "Track $it", listOf("Artist")) }

        fun previewNextThree(items: List<Song>, currentIndex: Int): List<IndexedValue<Song>> {
            if (currentIndex !in items.indices) return emptyList()
            return items.withIndex().drop(currentIndex + 1).take(3)
        }

        val firstThree = previewNextThree(queue, 0)
        assertEquals(listOf(1, 2, 3), firstThree.map { it.index })
        assertEquals(listOf("Track 2", "Track 3", "Track 4"), firstThree.map { it.value.title })

        val lastOne = previewNextThree(queue, 4)
        assertEquals(listOf(5), lastOne.map { it.index })

        assertTrue(previewNextThree(queue, 5).isEmpty())
        assertTrue(previewNextThree(queue, -1).isEmpty())
        assertTrue(previewNextThree(queue, 99).isEmpty())
        assertTrue(previewNextThree(emptyList(), 0).isEmpty())
    }

    @Test
    fun monitorPlacementSettingsAndFallback() {
        val monitorA = PlacementMonitor("mon-a", Rectangle(0, 0, 1920, 1080), Insets(10, 0, 0, 0))
        val monitorB = PlacementMonitor("mon-b", Rectangle(1920, 0, 1920, 1080), Insets(20, 0, 0, 0))
        val monitors = listOf(monitorA, monitorB)

        assertEquals(monitorA, selectIslandMonitor(monitors, ACTIVE_MONITOR, "mon-a", "mon-b"))
        assertEquals(monitorB, selectIslandMonitor(monitors, ACTIVE_MONITOR, "missing", "mon-b"))
        assertEquals(monitorB, selectIslandMonitor(monitors, PRIMARY_MONITOR, "mon-a", "mon-b"))
        assertEquals(monitorA, selectIslandMonitor(monitors, "mon-a", null, null))
        assertEquals(monitorB, selectIslandMonitor(monitors, "disconnected", null, "mon-b"))

        val topPoint = placementTopCenter(monitorA, Dimension(300, 60), topOffset = 16)
        assertEquals(Point(810, 26), topPoint)
    }
}
