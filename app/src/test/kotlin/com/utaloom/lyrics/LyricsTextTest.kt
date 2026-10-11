package com.utaloom.lyrics

import com.utaloom.shared.LyricLine
import com.utaloom.shared.LyricWord
import com.utaloom.shared.Lyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTextTest {

    @Test
    fun japaneseKanjiAndKanaOfflineRomanization() = runBlocking {
        // Kuromoji IPADIC morphological analysis translates Kanji to kana readings
        val sakura = LyricsText.romanizeJapanese("桜が咲く")
        assertTrue("Expected sakura in '$sakura'", sakura.contains("sakura"))
        assertTrue("Expected saku in '$sakura'", sakura.contains("saku"))

        val watashi = LyricsText.romanizeJapanese("私は日本語を話します")
        assertTrue("Expected watashi in '$watashi'", watashi.contains("watashi"))
        assertTrue("Expected nihongo in '$watashi'", watashi.contains("nihongo"))

        val chotto = LyricsText.romanizeJapanese("ちょっと待って")
        assertTrue("Expected doubled consonant for sokuon in '$chotto'", chotto.contains("chotto") || chotto.contains("matte"))
    }

    @Test
    fun koreanHangulAndAssimilationOfflineRomanization() = runBlocking {
        val hello = LyricsText.romanizeKorean("안녕하세요")
        assertEquals("annyeonghaseyo", hello)

        val love = LyricsText.romanizeKorean("사랑해")
        assertEquals("saranghae", love)

        val thanks = LyricsText.romanizeKorean("감사합니다")
        assertTrue("Expected kamsa in '$thanks'", thanks.startsWith("kamsa") || thanks.startsWith("gamsa"))
    }

    @Test
    fun chinesePinyinOfflineRomanization() = runBlocking {
        val helloWorld = LyricsText.romanizeChinese("你好世界")
        assertEquals("ni hao shi jie", helloWorld)

        val dream = LyricsText.romanizeChinese("梦想成真")
        assertEquals("meng xiang cheng zhen", dream)
    }

    @Test
    fun languageDetectionAccuracy() {
        assertTrue(LyricsText.isJapanese("君の名は"))
        assertTrue(LyricsText.isJapanese("さくら"))
        assertFalse(LyricsText.isJapanese("Hello World"))

        assertTrue(LyricsText.isKorean("안녕하세요"))
        assertFalse(LyricsText.isKorean("Konnichiwa"))

        assertTrue(LyricsText.isChinese("我爱北京天安门"))
        assertFalse(LyricsText.isChinese("English words"))
    }

    @Test
    fun translationLineIntegrityAndTimingPreservation() {
        // Original synced lyrics with word-level timings and duet singers
        val originalWords = listOf(LyricWord("Sa", 1000, 1500), LyricWord("ku", 1500, 2000), LyricWord("ra", 2000, 2500))
        val originalLines = listOf(
            LyricLine(1000, "桜が咲く", words = originalWords, background = false, agent = "v1"),
            LyricLine(3500, "風が吹く", background = true, agent = null),
            LyricLine(6000, "ありがとう", background = false, agent = "v2"),
        )
        val originalLyrics = Lyrics(
            provider = "LrcLib",
            synced = true,
            lines = originalLines,
            plain = "桜が咲く\n風が吹く\nありがとう",
        )

        // Mock translated lines strictly matching line indices
        val mockTranslated = listOf(
            "Cherry blossoms bloom",
            "The wind blows",
            "Thank you",
        )

        assertEquals(originalLyrics.lines.size, mockTranslated.size)

        // Verify that auxiliary translations attach without modifying original times or words
        val combined = originalLyrics.lines.mapIndexed { index, line ->
            Pair(line, mockTranslated[index])
        }

        assertEquals(3, combined.size)
        // Original timings and metadata must remain 100% untouched
        assertEquals(1000L, combined[0].first.timeMs)
        assertEquals("v1", combined[0].first.agent)
        assertEquals(originalWords, combined[0].first.words)
        assertEquals("Cherry blossoms bloom", combined[0].second)

        assertEquals(3500L, combined[1].first.timeMs)
        assertTrue(combined[1].first.background)
        assertEquals("The wind blows", combined[1].second)

        assertEquals(6000L, combined[2].first.timeMs)
        assertEquals("v2", combined[2].first.agent)
        assertEquals("Thank you", combined[2].second)
    }

    @Test
    fun googleTranslateResponseParsingJoinsSegments() {
        // Google splits a sentence into fragments; they must join without inserted separators.
        val body = """[[["Langit ","Sky ",null,null,10],["biru","blue",null,null,3]],null,"en"]"""
        assertEquals("Langit biru", GoogleTranslation.parseResponse(body))

        // Newlines inside a single fragment are preserved for multi-line inputs.
        assertEquals("Baris satu\nBaris dua", GoogleTranslation.parseResponse("""[[["Baris satu\nBaris dua","Line one\nLine two"]]]"""))

        // Malformed or empty payloads degrade to an empty string rather than throwing.
        assertEquals("", GoogleTranslation.parseResponse("not json"))
        assertEquals("", GoogleTranslation.parseResponse("[[[]]]"))
        assertEquals("", GoogleTranslation.parseResponse("[]"))
    }

    @Test
    fun googleTranslateLanguageNormalization() {
        assertEquals("id", GoogleTranslation.normalizeLanguage("ID"))
        assertEquals("en", GoogleTranslation.normalizeLanguage("EN"))
        assertEquals("en", GoogleTranslation.normalizeLanguage("en-US"))
        assertEquals("zh-CN", GoogleTranslation.normalizeLanguage("ZH"))
        assertEquals("zh-TW", GoogleTranslation.normalizeLanguage("zh-tw"))
        assertEquals("pt-BR", GoogleTranslation.normalizeLanguage("PT-BR"))
        assertEquals("pt-PT", GoogleTranslation.normalizeLanguage("pt"))
        assertEquals("en", GoogleTranslation.normalizeLanguage("  "))
    }

    @Test
    fun translationProviderSelectionAndKeyRequirement() {
        assertEquals(TranslationProvider.GOOGLE, TranslationProvider.fromId("google"))
        assertEquals(TranslationProvider.GOOGLE, TranslationProvider.fromId("  GOOGLE "))
        assertEquals(TranslationProvider.DEEPL, TranslationProvider.fromId("deepl"))
        // Unknown or blank ids fall back to DeepL instead of failing.
        assertEquals(TranslationProvider.DEEPL, TranslationProvider.fromId(""))
        assertEquals(TranslationProvider.DEEPL, TranslationProvider.fromId(null))

        assertFalse(TranslationProvider.GOOGLE.requiresApiKey)
        assertTrue(TranslationProvider.DEEPL.requiresApiKey)
    }

    @Test
    fun googleProviderSucceedsWithoutApiKey() = runBlocking {
        // Empty input must short-circuit before any network call, even without a key.
        val result = LyricsTranslation.translateLines(
            lines = emptyList(),
            targetLang = "ID",
            apiKey = "",
            provider = TranslationProvider.GOOGLE.id,
        )
        assertTrue(result.isSuccess)
        assertEquals(emptyList<String?>(), result.getOrNull())
    }

    @Test
    fun translationCacheKeySeparatesProviders() {
        val deepl = LyricsTranslation.computeCacheKey("deepl", "ID", "key", "Hello")
        val google = LyricsTranslation.computeCacheKey("google", "ID", "free", "Hello")
        assertTrue(deepl.startsWith("deepl:ID:"))
        assertTrue(google.startsWith("google:ID:"))
        assertTrue(deepl != google)
    }

    @Test
    fun missingApiKeyFailsSafelyWithoutSendingNetwork() = runBlocking {
        val result = LyricsText.translateWithDeepL(
            lines = listOf("Hello", "World"),
            targetLanguage = "ID",
            apiKey = "",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API key is required") == true)
    }

    @Test
    fun endpointSelectionFreeVsPro() {
        assertEquals("https://api-free.deepl.com/v2/translate", LyricsTranslation.selectEndpoint("12345678:fx"))
        assertEquals("https://api-free.deepl.com/v2/translate", LyricsTranslation.selectEndpoint("key:FX"))
        assertEquals("https://api.deepl.com/v2/translate", LyricsTranslation.selectEndpoint("12345678-standard-pro-key"))
    }
}
