package com.utaloom.data

import org.junit.Assert.*
import org.junit.Test

class PlaylistFileParserTest {
    @Test
    fun quotedExportifyHandlesCommasNewlinesAndEscapedQuotes() {
        val csv = "\uFEFF\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Duration (ms)\"\r\n" +
            "\"spotify:track:123\",\"Hello, \"\"world\"\"\nagain\",\"Artist One;Artist Two\",201999\r\n"
        val parsed = FilePlaylistImport.parse(csv, "Mix.csv")
        assertEquals(1, parsed.tracks.size)
        assertEquals("Hello, \"world\" again", parsed.tracks.single().title)
        assertEquals("Artist One,Artist Two", parsed.tracks.single().artist)
        assertEquals(201, parsed.tracks.single().durationSec)
        assertNull(parsed.tracks.single().videoId)
    }

    @Test
    fun malformedAndTruncatedCsvFailClosed() {
        listOf(
            "\"a\",\"unfinished",
            "\"a\",\"b\"\"c",
            "\"Track Name\",\"Artist Name(s)\"\n,",
            "\"Unknown\",\"Header\"\n\"a\",\"b\"",
        ).forEach { text ->
            assertTrue(text, runCatching { FilePlaylistImport.parse(text, "Mix.csv") }.isFailure)
        }
        assertTrue(runCatching { FilePlaylistImport.parse("x".repeat(8193), "Mix.csv") }.isFailure)
        assertTrue(runCatching { FilePlaylistImport.parse("x\u0000", "Mix.csv") }.isFailure)
        assertTrue(runCatching { FilePlaylistImport.parse("x".repeat(FilePlaylistImport.MAX_BYTES + 1), "Mix.csv") }.isFailure)
    }

    @Test
    fun m3uAcceptsYouTubeMusicAndShortsLinks() {
        val m3u = """
            #EXTM3U
            #EXTINF:205,Artist - Title
            https://music.youtube.com/watch?v=abcdefghijk
            #EXTINF:42,Shorts Video
            https://youtube.com/shorts/12345678901
        """.trimIndent()
        val parsed = FilePlaylistImport.parse(m3u, "Mix.m3u8")
        assertEquals(2, parsed.tracks.size)
        assertEquals("abcdefghijk", parsed.tracks[0].videoId)
        assertEquals("Title", parsed.tracks[0].title)
        assertEquals("12345678901", parsed.tracks[1].videoId)
    }

    @Test
    fun untrustedRemoteM3uIsRejected() {
        listOf(
            "https://example.org/a.mp3",
            "https://youtube.com.evil.org/watch?v=abcdefghijk",
            "file:///etc/passwd",
            "https://user:pass@youtube.com/watch?v=abcdefghijk",
        ).forEach { url ->
            val m3u = "#EXTINF:5,Title\n$url"
            assertTrue(url, runCatching { FilePlaylistImport.parse(m3u, "Mix.m3u") }.isFailure)
        }
    }

    @Test
    fun unsupportedExtensionsFail() {
        assertTrue(runCatching { FilePlaylistImport.parse("#EXTM3U", "unsafe.txt") }.isFailure)
        assertTrue(runCatching { FilePlaylistImport.parse("#EXTM3U", "unsafe.exe") }.isFailure)
    }
}
