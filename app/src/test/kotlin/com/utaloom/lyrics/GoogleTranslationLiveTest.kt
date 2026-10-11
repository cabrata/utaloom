package com.utaloom.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live check against the real free Google Translate endpoint.
 * Skipped automatically when the sandbox/CI has no outbound network.
 */
class GoogleTranslationLiveTest {

    private fun networkAvailable(): Boolean = runCatching {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress("translate.googleapis.com", 443), 5000)
        socket.close()
    }.isSuccess

    @Test
    fun liveFreeEndpointTranslatesOntoMatchingLinePositions() = runBlocking {
        assumeTrue("no outbound network", networkAvailable())

        val lines = listOf("Hello world", "", "  ", "Good night")
        val result = GoogleTranslation.translateLines(lines, "ID")

        assertEquals("one translation slot per source line", lines.size, result.size)
        // Blank lines must never be sent or filled.
        assertEquals(null, result[1])
        assertEquals(null, result[2])
        assertTrue("line 0 translated: ${result[0]}", !result[0].isNullOrBlank())
        assertTrue("line 3 translated: ${result[3]}", !result[3].isNullOrBlank())
    }

    @Test
    fun liveProviderPathThroughLyricsTranslation() = runBlocking {
        assumeTrue("no outbound network", networkAvailable())

        val result = LyricsTranslation.translateLines(
            lines = listOf("Good morning"),
            targetLang = "ID",
            apiKey = "",
            provider = TranslationProvider.GOOGLE.id,
        )
        assertTrue("failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
    }
}
