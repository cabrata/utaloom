package com.utaloom.playback

import com.utaloom.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsStateTest {
    private val song = Song("abcdefghijk", "Download title", listOf("Artist"))

    @Test
    fun activeDownloadKeepsSongMetadataAndExposesBoundedProgress() {
        val active = ActiveDownload(song)
        assertEquals(song, active.song)
        assertEquals("abcdefghijk", active.id)
        assertEquals("Download title", active.title)
        assertNull(active.progress)
        assertNull(active.boundedProgress)

        val inFlight = ActiveDownload(song, 0.45f)
        assertEquals(0.45f, inFlight.progress!!, 0.001f)
        assertEquals(0.45f, inFlight.boundedProgress!!, 0.001f)

        val overflow = ActiveDownload(song, 1.5f)
        assertEquals(1.5f, overflow.progress!!, 0.001f)
        assertEquals(1.0f, overflow.boundedProgress!!, 0.001f)

        val underflow = ActiveDownload(song, -0.2f)
        assertEquals(-0.2f, underflow.progress!!, 0.001f)
        assertEquals(0.0f, underflow.boundedProgress!!, 0.001f)
    }

    @Test
    fun progressIsOnlyReportedWhenTotalSizeIsKnownAndAlwaysBounded() {
        assertNull(downloadProgress(50, null))
        assertNull(downloadProgress(50, 0))
        assertNull(downloadProgress(50, -100))
        assertEquals(0f, downloadProgress(0, 100)!!, 0f)
        assertEquals(0.5f, downloadProgress(50, 100)!!, 0f)
        assertEquals(1f, downloadProgress(100, 100)!!, 0f)
        assertEquals(1f, downloadProgress(150, 100)!!, 0f)
        assertEquals(0f, downloadProgress(-20, 100)!!, 0f)
    }

    @Test
    fun downloadRejectsInvalidIdsWithoutTouchingActiveState() {
        val initialDownloads = Downloads.activeDownloads.value
        val initialSongs = Downloads.activeSongs.value

        Downloads.download(listOf(Song("too-short", "Bad ID", listOf("Artist"))))

        assertEquals(initialDownloads, Downloads.activeDownloads.value)
        assertEquals(initialSongs, Downloads.activeSongs.value)
    }

    @Test
    fun downloadsStateExposesInitialFlows() {
        assertTrue(Downloads.activeDownloads.value.isEmpty())
        assertTrue(Downloads.activeSongs.value.isEmpty())
        assertTrue(Downloads.progress.value.isEmpty())
        assertTrue(Downloads.errors.value.isEmpty())
    }
}
