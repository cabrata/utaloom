package com.utaloom.data

import com.utaloom.lyrics.Romanization
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsTest {
    @Test
    fun romanizeLanguageListDefaultsAndParsing() {
        val supported = Romanization.SUPPORTED_LANGUAGES

        // Blank preference enables every supported language (the default).
        assertEquals(supported, Settings().lyricsRomanizeLanguages)

        // Unknown entries are ignored and the canonical order is preserved.
        val mixed = Settings(lyricsRomanizeList = "Korean:false,NotALanguage:true,Japanese:true")
        assertEquals(supported - "Korean", mixed.lyricsRomanizeLanguages)

        // A fully disabled list yields no languages, and garbage falls back to all.
        assertEquals(emptyList<String>(), Settings(lyricsRomanizeList = supported.joinToString(",") { "$it:false" }).lyricsRomanizeLanguages)
        assertEquals(supported, Settings(lyricsRomanizeList = "garbage").lyricsRomanizeLanguages)

        // The new fields survive a JSON round-trip like the rest of the settings.
        val original = Settings(
            lyricsRomanize = true,
            lyricsRomanizeList = "Japanese:true,Korean:false",
            lyricsRomanizeAsMain = true,
            lyricsRomanizeCyrillicByLine = true,
        )
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), original))
        assertEquals(original, parsed)
        // Like Android, a language missing from the saved list stays enabled (default true).
        assertEquals(supported - "Korean", parsed.lyricsRomanizeLanguages)
    }

    @Test
    fun onlyExactLegacyDefaultIsMigrated() {
        val legacy = "wss://metrolist.caliph.dev/ws"
        val current = "wss://utaloom.caliph.dev/ws"
        val missing = Json.decodeFromString(Settings.serializer(), "{}")
        assertEquals(current, missing.ltServerUrl)
        assertSame(missing, missing.normalizeLegacyLtServerUrl())

        val retained = Settings(
            darkMode = "dark",
            volume = 37,
            cookie = "SAPISID=test; legacy=$legacy",
            accountName = legacy,
            proxy = "https://proxy.example/?target=$legacy",
            ltUsername = "listener",
            lyricsOrder = listOf("LRCLib", "LyricsPlus"),
            minimizeToTray = false,
        )
        listOf(
            legacy to current,
            current to current,
            " \t$legacy\n" to current,
            " \t$current\n" to " \t$current\n",
            " wss://custom.example/ws " to " wss://custom.example/ws ",
            "$legacy/custom" to "$legacy/custom",
            "$legacy?room=custom" to "$legacy?room=custom",
            "wss://custom.example/ws?fallback=$legacy" to "wss://custom.example/ws?fallback=$legacy",
        ).forEach { (url, expected) ->
            val persisted = Json.encodeToString(Settings.serializer(), retained.copy(ltServerUrl = url))
            val parsed = Json.decodeFromString(Settings.serializer(), persisted)
            val normalized = parsed.normalizeLegacyLtServerUrl()
            assertEquals("All other settings must be retained for $url", retained.copy(ltServerUrl = expected), normalized)
            if (url == expected) assertSame(parsed, normalized)
            assertSame(normalized, normalized.normalizeLegacyLtServerUrl())
        }
    }
}
