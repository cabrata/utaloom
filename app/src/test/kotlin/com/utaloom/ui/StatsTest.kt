package com.utaloom.ui

import com.utaloom.data.Library
import com.utaloom.data.LibraryState
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.listeningStats
import org.junit.Assert.*
import org.junit.Test

class StatsTest {
    private val songA = Song("songA123456", "Alpha", listOf("Artist One", "Artist Two"), duration = 200)
    private val songB = Song("songB123456", "Beta", listOf("Artist One"), duration = 150)

    @Test
    fun listeningStatsAggregatesTracksAndArtists() {
        val state = LibraryState(
            liked = listOf(songA, songB),
            playCounts = mapOf(songA.id to 3, songB.id to 5),
            listenedMsBySong = mapOf(songA.id to 600_000L, songB.id to 450_000L),
            songCatalog = mapOf(songA.id to songA, songB.id to songB),
        )
        val stats = listeningStats(state, byTime = true)
        assertEquals(1_050_000L, stats.totalMs)
        assertEquals(8L, stats.plays)
        assertEquals(2, stats.songs.size)
        assertEquals("Alpha", stats.songs[0].song.title)
        assertEquals(600_000L, stats.songs[0].listenedMs)
        // Artist One is credited on both tracks; Artist Two only on Alpha
        val artistOne = stats.artists.first { it.name == "Artist One" }
        assertEquals(1_050_000L, artistOne.listenedMs)
        assertEquals(8L, artistOne.plays)
        val artistTwo = stats.artists.first { it.name == "Artist Two" }
        assertEquals(600_000L, artistTwo.listenedMs)
        assertEquals(3L, artistTwo.plays)
    }

    @Test
    fun listeningStatsSortsByPlaysWhenRequested() {
        val state = LibraryState(
            liked = listOf(songA, songB),
            playCounts = mapOf(songA.id to 2, songB.id to 10),
            listenedMsBySong = mapOf(songA.id to 600_000L, songB.id to 100_000L),
            songCatalog = mapOf(songA.id to songA, songB.id to songB),
        )
        val stats = listeningStats(state, byTime = false)
        assertEquals("Beta", stats.songs[0].song.title)
        assertEquals(10L, stats.songs[0].plays)
    }

    @Test
    fun recordListeningHonorsPauseHistory() {
        Stores.settings.update { it.copy(pauseHistory = true) }
        try {
            val before = Stores.library.value.listenedMsBySong[songA.id] ?: 0L
            Library.recordListening(songA, 50_000L)
            assertEquals(before, Stores.library.value.listenedMsBySong[songA.id] ?: 0L)
        } finally {
            Stores.settings.update { it.copy(pauseHistory = false) }
        }
    }

    @Test
    fun clearListeningHistoryResetsTelemetryAndCatalog() {
        Library.recordListening(songA, 10_000L)
        Library.addHistory(songA)
        assertTrue(Stores.library.value.songCatalog.containsKey(songA.id))
        Library.clearListeningHistory()
        assertTrue(Stores.library.value.history.isEmpty())
        assertTrue(Stores.library.value.playCounts.isEmpty())
        assertTrue(Stores.library.value.listenedMsBySong.isEmpty())
        assertTrue(Stores.library.value.listeningByDay.isEmpty())
        assertTrue(Stores.library.value.songCatalog.isEmpty())
    }
}
