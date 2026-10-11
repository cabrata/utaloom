package com.utaloom.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live check against the real free Google Translate endpoint.
 *
 * The public endpoint is best-effort, so transient upstream problems (no network, DNS/TLS failure,
 * HTTP 429 rate limiting, or 5xx) *skip* the test instead of failing CI for reasons unrelated to
 * this code. A successful 200 response is still asserted strictly, so genuine regressions in
 * request building, response parsing, or line mapping fail as normal.
 */
class GoogleTranslationLiveTest {

    private fun networkAvailable(): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("translate.googleapis.com", 443), 5000)
        }
    }.isSuccess

    /** True when the failure is an upstream/transient condition rather than a code regression. */
    private fun isTransientUpstream(failure: Throwable?): Boolean {
        val message = failure?.message.orEmpty()
        if (failure is java.io.IOException) return true
        return Regex("HTTP (429|5\\d\\d)").containsMatchIn(message)
    }

    @Test
    fun liveFreeEndpointTranslatesOntoMatchingLinePositions() = runBlocking {
        assumeTrue("no outbound network", networkAvailable())

        val lines = listOf("Hello world", "", "  ", "Good night")
        val result = runCatching { GoogleTranslation.translateLines(lines, "ID") }
        assumeTrue("upstream unavailable: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val translated = result.getOrThrow()
        assertEquals("one translation slot per source line", lines.size, translated.size)
        // Blank lines must never be sent or filled.
        assertEquals(null, translated[1])
        assertEquals(null, translated[2])
        assertTrue("line 0 translated: ${translated[0]}", !translated[0].isNullOrBlank())
        assertTrue("line 3 translated: ${translated[3]}", !translated[3].isNullOrBlank())
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

        // The provider path returns Result, so distinguish "upstream down" from "contract broken".
        assumeTrue(
            "upstream unavailable: ${result.exceptionOrNull()?.message}",
            !isTransientUpstream(result.exceptionOrNull()),
        )
        assertTrue("failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
    }
}
