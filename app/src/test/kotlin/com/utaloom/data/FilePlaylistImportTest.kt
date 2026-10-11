package com.utaloom.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FilePlaylistImportTest {
    @Test
    fun parseSpotifyCsvHandlesExportifyQuotingAndDurations() {
        val csv = "\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"Stay\",\"The Kid LAROI, Justin Bieber\",\"F*CK LOVE 3\",\"141800\"\n" +
            "\"Hello, World\",\"Louie \"\"The King\"\"\",\"Single\",\"200000\""
        val parsed = FilePlaylistImport.parse(csv, "Favorites.csv")
        assertEquals("Favorites", parsed.name)
        assertEquals(2, parsed.tracks.size)
        assertEquals("Stay", parsed.tracks[0].title)
        assertEquals("The Kid LAROI, Justin Bieber", parsed.tracks[0].artist)
        assertEquals(141, parsed.tracks[0].durationSec)
        assertEquals("Hello, World", parsed.tracks[1].title)
        assertEquals("Louie \"The King\"", parsed.tracks[1].artist)
        assertEquals(200, parsed.tracks[1].durationSec)
    }

    @Test
    fun parseSpotifyCsvRejectsMalformedQuotes() {
        val bad = "\"Track Name\",\"Artist Name(s)\"\n\"Unclosed quote,Artist"
        assertTrue(runCatching { FilePlaylistImport.parse(bad, "bad.csv") }.isFailure)
    }

    @Test
    fun parseM3uExtractsExtinfAndYouTubeUrlsOnly() {
        val m3u = """
            #EXTM3U
            #EXTINF:180,Dua Lipa - Levitating
            https://music.youtube.com/watch?v=TUVcZfQe-Kw
            #EXTINF:210,Olivia Rodrigo - Drivers License
            https://youtu.be/ZmDBbnmKqqQ
        """.trimIndent()
        val parsed = FilePlaylistImport.parse(m3u, "Pop.m3u")
        assertEquals(2, parsed.tracks.size)
        assertEquals("Levitating", parsed.tracks[0].title)
        assertEquals("Dua Lipa", parsed.tracks[0].artist)
        assertEquals("TUVcZfQe-Kw", parsed.tracks[0].videoId)
        assertEquals(180, parsed.tracks[0].durationSec)
        assertEquals("Drivers License", parsed.tracks[1].title)
        assertEquals("Olivia Rodrigo", parsed.tracks[1].artist)
        assertEquals("ZmDBbnmKqqQ", parsed.tracks[1].videoId)
        assertEquals(210, parsed.tracks[1].durationSec)
    }

    @Test
    fun parseM3uRejectsArbitraryWebOrLocalFileUrls() {
        val evil = """
            #EXTM3U
            #EXTINF:180,Evil Track
            https://evil.com/audio.mp3
        """.trimIndent()
        assertTrue(runCatching { FilePlaylistImport.parse(evil, "evil.m3u") }.isFailure)

        val local = """
            #EXTM3U
            #EXTINF:180,Local File
            file:///etc/passwd
        """.trimIndent()
        assertTrue(runCatching { FilePlaylistImport.parse(local, "local.m3u") }.isFailure)
    }

    @Test
    fun matchingDiscardsOnFailureWithoutSaving() = runBlocking {
        val playlist = FilePlaylist("Staged", listOf(PlaylistTrack("Test Song", "Test Artist", 180)))
        val before = Stores.library.state.value.playlists
        val result = FilePlaylistImport.match(playlist, lookup = { null })
        assertEquals(0, result.playlist.songs.size)
        assertEquals(1, result.missing.size)
        assertEquals(before, Stores.library.state.value.playlists)
    }
}
