package com.utaloom.ui

import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.playback.PlayerState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

class DynamicIslandTest {
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
            islandTopCenter(Rectangle(100, 20, 1920, 1080), Insets(20, 10, 40, 30), Dimension(500, 200)),
        )
    }

    @Test
    fun settingsDefaultsAndRoundTripKeepIslandEnabled() {
        val defaults = Settings()
        assertTrue(defaults.dynamicIslandEnabled)
        assertTrue(defaults.dynamicIslandAlwaysOnTop)

        val changed = defaults.copy(dynamicIslandEnabled = false, dynamicIslandAlwaysOnTop = false)
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), changed))
        assertFalse(parsed.dynamicIslandEnabled)
        assertFalse(parsed.dynamicIslandAlwaysOnTop)
    }
}
