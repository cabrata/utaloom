package com.utaloom.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackupTest {
    private val testSong = Song(
        id = "video-test-1",
        title = "Test Song",
        artists = listOf("Test Artist"),
        duration = 180,
    )

    @Test
    fun roundtripEncodesAndDecodesExcludingSecretsAndDownloadPaths() {
        val library = LibraryState(
            liked = listOf(testSong),
            history = listOf(testSong),
            playlists = listOf(LocalPlaylist("p1", "My Playlist", listOf(testSong))),
            playCounts = mapOf(testSong.id to 5),
            listenedMsBySong = mapOf(testSong.id to 120_000L),
            listeningByDay = mapOf("2026-10-11" to 120_000L),
            songCatalog = mapOf(testSong.id to testSong),
            downloaded = mapOf(testSong.id to "/home/user/secret/music.mp3"),
            downloadedSongs = mapOf(testSong.id to testSong),
        )
        val settings = Settings(
            cookie = "SAPISID=supersecretcookie; HSID=other",
            visitorData = "visitor-secret",
            dataSyncId = "sync-id-123",
            accountName = "Private User",
            proxy = "http://127.0.0.1:8080",
            darkMode = "dark",
            volume = 75,
            hideExplicit = true,
            lyricsRomanize = true,
            lyricsRomanizeList = "Japanese:true,Korean:false",
            lyricsRomanizeAsMain = true,
            lyricsRomanizeCyrillicByLine = true,
        )
        val queue = PersistedQueue(songs = listOf(testSong), index = 0, positionMs = 5000, title = "Queue Title")

        val encoded = Backup.encode(library, settings, queue)

        // Secrets and absolute file paths must NEVER be serialized in backup
        assertFalse(encoded.contains("supersecretcookie"))
        assertFalse(encoded.contains("visitor-secret"))
        assertFalse(encoded.contains("sync-id-123"))
        assertFalse(encoded.contains("127.0.0.1"))
        assertFalse(encoded.contains("/home/user/secret"))

        val decoded = Backup.decode(encoded)
        assertEquals("utaloom-desktop", decoded.format)
        assertEquals(1, decoded.version)
        assertEquals(1, decoded.library.liked.size)
        assertEquals("Test Song", decoded.library.liked.first().title)
        assertEquals(1, decoded.library.playlists.size)
        assertEquals(120_000L, decoded.library.listenedMsBySong[testSong.id])
        assertTrue(decoded.library.downloaded.isEmpty())
        assertTrue(decoded.library.downloadedSongs.isEmpty())

        // Safe settings preserved, unsafe excluded
        assertEquals(JsonPrimitive("dark"), decoded.settings["darkMode"])
        assertEquals(JsonPrimitive(75), decoded.settings["volume"])
        assertEquals(JsonPrimitive("Japanese:true,Korean:false"), decoded.settings["lyricsRomanizeList"])
        assertEquals(JsonPrimitive(true), decoded.settings["lyricsRomanizeAsMain"])
        assertEquals(JsonPrimitive(true), decoded.settings["lyricsRomanizeCyrillicByLine"])
        assertNull(decoded.settings["cookie"])
        assertNull(decoded.settings["visitorData"])
        assertNull(decoded.settings["proxy"])
    }

    @Test
    fun mergePreservesLocalDownloadsAndUsesMaxCounters() {
        val current = LibraryState(
            liked = listOf(testSong),
            downloaded = mapOf(testSong.id to "/local/path/file.opus"),
            downloadedSongs = mapOf(testSong.id to testSong),
            playCounts = mapOf(testSong.id to 10),
            listenedMsBySong = mapOf(testSong.id to 50_000L),
            songCatalog = mapOf(testSong.id to testSong),
        )
        val otherSong = Song("video-test-2", "Other Song", listOf("Other Artist"))
        val incoming = LibraryState(
            liked = listOf(otherSong),
            playCounts = mapOf(testSong.id to 20, otherSong.id to 3),
            listenedMsBySong = mapOf(testSong.id to 30_000L, otherSong.id to 15_000L),
            songCatalog = mapOf(otherSong.id to otherSong),
        )

        val merged = Backup.merge(current, incoming)
        assertEquals(2, merged.liked.size)
        // Counters take maxOf
        assertEquals(20, merged.playCounts[testSong.id])
        assertEquals(50_000L, merged.listenedMsBySong[testSong.id])
        assertEquals(15_000L, merged.listenedMsBySong[otherSong.id])
        // Local downloaded paths are preserved in current
        assertEquals("/local/path/file.opus", merged.downloaded[testSong.id])
    }

    @Test
    fun malformedOrDangerousJsonFailsValidation() {
        // Corrupt json
        assertTrue(runCatching { Backup.decode("not valid json") }.isFailure)
        // Wrong format
        assertTrue(runCatching { Backup.decode("""{"format":"wrong","version":1,"library":{}}""") }.isFailure)
        // Path traversal / malicious ID in song
        val badIdJson = """
            {
              "format": "utaloom-desktop",
              "version": 1,
              "library": {
                "liked": [{"id": "../../../etc/passwd", "title": "bad", "artists": ["bad"]}]
              }
            }
        """.trimIndent()
        assertTrue(runCatching { Backup.decode(badIdJson) }.isFailure)

        // Negative duration or invalid song
        val badDuration = """
            {
              "format": "utaloom-desktop",
              "version": 1,
              "library": {
                "liked": [{"id": "good_id", "title": "bad", "artists": ["bad"], "duration": -50}]
              }
            }
        """.trimIndent()
        assertTrue(runCatching { Backup.decode(badDuration) }.isFailure)
    }

    @Test
    fun restoreWritesRollbackSnapshotAndInvokesHook() {
        var hookCalled = false
        Backup.onRestoreHook = { prefs, queue ->
            hookCalled = true
        }
        val doc = BackupDocument(
            library = LibraryState(liked = listOf(testSong)),
            settings = JsonObject(mapOf("darkMode" to JsonPrimitive("light"))),
        )

        Backup.restore(doc, restorePreferences = false, restoreQueue = false)
        assertTrue(hookCalled)

        val rollbackFile = File(Paths.dir, "backup-rollback.json")
        assertTrue(rollbackFile.exists())
        assertTrue(rollbackFile.length() > 0)
    }
}
