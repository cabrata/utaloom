package com.utaloom.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsToolsTest {

    @Test
    fun testSongAwareLanguageDetection() {
        // Song with Kanji-only first line, but Kana in the chorus: whole song is Japanese
        val japaneseSong = listOf(
            "青空", // Kanji only line
            "きみが笑うなら", // Hiragana line
            "心の中で",
        )
        val detectedJa = Romanization.detectSongLanguage(japaneseSong.joinToString("\n"))
        assertEquals("ja", detectedJa)

        // Song with purely CJK Hanzi and no Kana: detected as Chinese
        val chineseSong = listOf(
            "海阔天空",
            "今天我寒夜里看雪飘过",
        )
        val detectedZh = Romanization.detectSongLanguage(chineseSong.joinToString("\n"))
        assertEquals("zh", detectedZh)

        // Song with Hangul: detected as Korean
        val koreanSong = listOf(
            "보고 싶다",
            "이렇게 말하니까 더 보고 싶다",
        )
        val detectedKo = Romanization.detectSongLanguage(koreanSong.joinToString("\n"))
        assertEquals("ko", detectedKo)

        // Latin / English song: detected as null (no romanization)
        val englishSong = listOf("Hello world", "I love music")
        assertNull(Romanization.detectSongLanguage(englishSong.joinToString("\n")))
    }

    @Test
    fun testRomanizationLineMappingPreservesLineCount() = runBlocking {
        val mixedSong = listOf(
            "青空",
            "",
            "きみが笑うなら",
            "Hello world",
        )
        val result = Romanization.romanizeSongLines(mixedSong, "auto")
        assertNotNull(result)
        assertEquals(mixedSong.size, result!!.size)

        // Line 0 is Kanji, romanized using Japanese reading
        assertNotNull(result[0])
        assertTrue(result[0]!!.contains("aozora"))

        // Blank line produces null
        assertNull(result[1])

        // Line 2 is Hiragana, romanized
        assertNotNull(result[2])

        // Line 3 is English, produces null (no redundant identical secondary line)
        assertNull(result[3])
    }

    @Test
    fun testDeepLEndpointAndCacheKey() {
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            LyricsTranslation.selectEndpoint("12345678-abcd-1234-abcd-1234567890ab:fx"),
        )
        assertEquals(
            "https://api.deepl.com/v2/translate",
            LyricsTranslation.selectEndpoint("12345678-abcd-1234-abcd-1234567890ab"),
        )

        val key = LyricsTranslation.computeCacheKey(
            provider = "deepl",
            targetLang = "ID",
            apiKey = "secret-api-key:fx",
            fullText = "Line 1\nLine 2",
        )
        assertTrue(key.startsWith("deepl:ID:"))
        assertTrue(key.contains("#"))
    }

    @Test
    fun testDeepLResponseParsingAndLineCountMismatch() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // Valid response with matching 2 lines
        val validJson = """
            {
                "translations": [
                    {"text": "Langit biru"},
                    {"text": "Jika kamu tersenyum"}
                ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString(DeepLTranslationResponse.serializer(), validJson)
        val translations = parsed.translations!!.map { it.text }
        assertEquals(2, translations.size)
        assertEquals("Langit biru", translations[0])

        // Mapping 2 translations back onto a 4-line song where lines 1 & 3 were blank
        val originalLines = listOf("青空", "", "きみが笑うなら", "   ")
        val nonEmptyIndices = originalLines.mapIndexedNotNull { i, t -> if (t.isNotBlank()) i to t else null }
        assertEquals(2, nonEmptyIndices.size)

        val mapped = ArrayList<String?>(originalLines.size)
        for (i in originalLines.indices) mapped.add(null)
        nonEmptyIndices.forEachIndexed { i, (origIdx, _) ->
            mapped[origIdx] = translations[i]
        }
        assertEquals("Langit biru", mapped[0])
        assertNull(mapped[1])
        assertEquals("Jika kamu tersenyum", mapped[2])
        assertNull(mapped[3])

        // Mismatched count detection (received 1 line for 2 requested lines)
        val mismatchedJson = """{"translations": [{"text": "Langit biru"}]}"""
        val parsedMismatched = json.decodeFromString(DeepLTranslationResponse.serializer(), mismatchedJson)
        val mismatchedCount = parsedMismatched.translations!!.size
        val expectedCount = nonEmptyIndices.size
        assertTrue(mismatchedCount != expectedCount)
    }
}
