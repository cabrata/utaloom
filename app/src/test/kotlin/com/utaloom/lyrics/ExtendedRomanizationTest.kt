package com.utaloom.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the romanization languages ported from the Android app: Hindi, Punjabi and the
 * Cyrillic-script languages, plus the per-language enable list behaviour.
 */
class ExtendedRomanizationTest {

    @Test
    fun `supported languages mirror the android list`() {
        assertEquals(
            listOf(
                "Japanese", "Korean", "Chinese", "Hindi", "Punjabi",
                "Russian", "Ukrainian", "Serbian", "Bulgarian", "Belarusian", "Kyrgyz", "Macedonian",
            ),
            Romanization.SUPPORTED_LANGUAGES,
        )
    }

    @Test
    fun hindiDevanagariOfflineRomanization() = runBlocking {
        // Same Devanagari map as Android, which keeps inherent vowels (no schwa deletion).
        val result = LyricsText.romanizeHindi("नमस्ते दुनिया")
        assertEquals("nmste duniyaa", result)
    }

    @Test
    fun punjabiGurmukhiOfflineRomanization() = runBlocking {
        // Same Gurmukhi map as Android.
        val result = LyricsText.romanizePunjabi("ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ")
        assertEquals("sti sree akaal", result)
    }

    @Test
    fun cyrillicLanguageDetection() {
        assertTrue(Romanization.isRussian("Привет мир"))
        assertTrue(Romanization.isUkrainian("Привіт світе"))
        assertTrue(Romanization.isSerbian("Здраво свете"))
        assertTrue(Romanization.isBulgarian("Здравей свят"))
        assertTrue(Romanization.isBelarusian("Прывітанне свет"))
        assertTrue(Romanization.isKyrgyz("Салам дүйнө"))
        assertTrue(Romanization.isMacedonian("Здраво свету"))
        assertFalse(Romanization.isRussian("Hello world"))
    }

    @Test
    fun russianCyrillicRomanization() = runBlocking {
        val result = LyricsText.romanizeCyrillic("Привет мир", "Russian")
        assertNotNull(result)
        assertTrue("expected Privet in '$result'", result!!.contains("Privet"))
    }

    /**
     * Macedonian writes stressed "e"/"i" with a grave accent, and reads ц as "c" (not "ts") and
     * ж as "zh" (not "ž"). This is the same expectation as the Android MacedonianRomanizationTest.
     */
    @Test
    fun macedonianGraveAccentsAndAlphabet() = runBlocking {
        val lineWithGraveE = "Сѐ што имам и куќа и нива"
        assertTrue(Romanization.isMacedonian(lineWithGraveE))
        assertEquals("Se shto imam i kukja i niva", Romanization.romanizeCyrillic(lineWithGraveE, "Macedonian"))
    }

    @Test
    fun macedonianSongUsesOneAlphabetForEveryLine() = runBlocking {
        val song = listOf(
            "Една цреша што мене ме чека",
            "Сѐ што имам и куќа и нива",
            "Но црешата да ја најдам жива",
            "Што е тоа во луѓето тажно",
        )
        val lyrics = song.joinToString("\n")
        val romanized = song.map {
            Romanization.romanize(lyrics, it, listOf("Macedonian"), romanizeCyrillicByLine = false)
        }
        romanized.forEachIndexed { index, value ->
            assertNotNull("line ${index + 1} was left unromanised", value)
        }
        assertTrue("got ${romanized[0]}", romanized[0]!!.contains("cresha"))
        assertTrue("got ${romanized[2]}", romanized[2]!!.contains("zhiva"))
        assertTrue("got ${romanized[3]}", romanized[3]!!.contains("tazhno"))
    }

    @Test
    fun songLinesRespectEnabledLanguageList() = runBlocking {
        val song = listOf("Привет мир", "Здравствуй")

        // No matching language enabled -> every line is null, but the list shape is preserved.
        val disabled = Romanization.romanizeSongLines(song, "auto", listOf("Japanese"))
        assertNotNull(disabled)
        assertEquals(listOf(null, null), disabled)

        // Russian enabled -> every line romanized and line count preserved.
        val withRussian = Romanization.romanizeSongLines(song, "auto", listOf("Russian"))
        assertNotNull(withRussian)
        assertEquals(song.size, withRussian!!.size)
        assertTrue(withRussian[0]!!.contains("Privet"))

        // An empty enabled list short-circuits to null rather than returning blank lines.
        assertNull(Romanization.romanizeSongLines(song, "auto", emptyList()))
    }

    @Test
    fun forcedCjkSelectorStillWinsOverEnabledList() = runBlocking {
        val song = listOf("桜が咲く")
        val result = Romanization.romanizeSongLines(song, "ja", enabledLanguages = listOf("Korean"))
        assertNotNull(result)
        assertTrue(result!![0]!!.contains("sakura"))
    }
}
