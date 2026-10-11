package com.utaloom.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ListeningStatsTest {
    private val songA = Song("song-a", "Song Alpha", listOf("Artist One"), duration = 200)
    private val songB = Song("song-b", "Song Beta", listOf("Artist One", "Artist Two"), duration = 300)

    @Test
    fun listeningStatsAggregatesCorrectlyByTimeAndPlays() {
        val today = LocalDate.now().toString()
        val lib = LibraryState(
            liked = listOf(songA, songB),
            playCounts = mapOf(songA.id to 5, songB.id to 2),
            listenedMsBySong = mapOf(songA.id to 60_000L, songB.id to 180_000L),
            listeningByDay = mapOf(today to 240_000L),
            songCatalog = mapOf(songA.id to songA, songB.id to songB),
        )

        // Sorted by time
        val byTime = listeningStats(lib, byTime = true)
        assertEquals(240_000L, byTime.totalMs)
        assertEquals(7L, byTime.plays)
        assertEquals(2, byTime.songs.size)
        // Song B had 180s, Song A had 60s
        assertEquals("song-b", byTime.songs[0].song.id)
        assertEquals("song-a", byTime.songs[1].song.id)

        // Artists: Artist One played in both songs (60_000 + 180_000 = 240_000 ms, 7 plays)
        val artistOne = byTime.artists.first { it.name == "Artist One" }
        assertEquals(240_000L, artistOne.listenedMs)
        assertEquals(7L, artistOne.plays)

        // Sorted by plays
        val byPlays = listeningStats(lib, byTime = false)
        assertEquals("song-a", byPlays.songs[0].song.id)
        assertEquals(5L, byPlays.songs[0].plays)
    }

    @Test
    fun libraryRecordListeningIncrementsMillisecondsAndClearResets() {
        Stores.settings.update { it.copy(pauseHistory = false) }
        val initialMs = Stores.library.value.listenedMsBySong[songA.id] ?: 0L
        Library.recordListening(songA, 10_000L)
        val after = Stores.library.value
        assertEquals(initialMs + 10_000L, after.listenedMsBySong[songA.id])

        // When paused, recordListening does not increment
        Stores.settings.update { it.copy(pauseHistory = true) }
        Library.recordListening(songA, 50_000L)
        assertEquals(initialMs + 10_000L, Stores.library.value.listenedMsBySong[songA.id])

        // Clear resets listening statistics
        Stores.settings.update { it.copy(pauseHistory = false) }
        Library.clearListeningHistory()
        assertTrue(Stores.library.value.history.isEmpty())
        assertTrue(Stores.library.value.playCounts.isEmpty())
        assertTrue(Stores.library.value.listenedMsBySong.isEmpty())
        assertTrue(Stores.library.value.listeningByDay.isEmpty())
    }

    @Test
    fun listeningStatsLifecycleStartStopIsSafe() {
        ListeningStats.start()
        ListeningStats.start() // idempotent
        ListeningStats.stop()
        ListeningStats.stop() // idempotent
    }
}
