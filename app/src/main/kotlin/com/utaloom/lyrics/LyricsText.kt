package com.utaloom.lyrics

/**
 * Unified lyrics text processor connecting offline romanization (Kuromoji, TinyPinyin, Hangul,
 * Devanagari, Gurmukhi and Cyrillic romanization) and opt-in lyric translation (official DeepL
 * API or free Google Translate).
 */
object LyricsText {
    /** Languages that can be romanized offline, mirroring the Android app. */
    val SUPPORTED_LANGUAGES: List<String> get() = Romanization.SUPPORTED_LANGUAGES

    /** Languages enabled by default (all of them). */
    val DEFAULT_ENABLED_LANGUAGES: List<String> get() = Romanization.DEFAULT_ENABLED_LANGUAGES

    fun isJapanese(text: String): Boolean = Romanization.isJapanese(text)

    fun isKorean(text: String): Boolean = Romanization.isKorean(text)

    fun isChinese(text: String): Boolean = Romanization.isChinese(text)

    fun isHindi(text: String): Boolean = Romanization.isHindi(text)

    fun isPunjabi(text: String): Boolean = Romanization.isPunjabi(text)

    suspend fun romanizeJapanese(text: String): String = Romanization.romanizeJapanese(text)

    suspend fun romanizeKorean(text: String): String = Romanization.romanizeKorean(text)

    suspend fun romanizeChinese(text: String): String = Romanization.romanizeChinese(text)

    suspend fun romanizeHindi(text: String): String = Romanization.romanizeHindi(text)

    suspend fun romanizePunjabi(text: String): String = Romanization.romanizePunjabi(text)

    /** Romanizes a Cyrillic line. Pass a language name to force it, or null to auto-detect. */
    suspend fun romanizeCyrillic(text: String, language: String? = null): String? =
        Romanization.romanizeCyrillic(text, language)

    /** Romanizes a single line using the enabled languages, matching the Android dispatch order. */
    suspend fun romanize(
        text: String,
        line: String,
        enabledLanguages: List<String>,
        romanizeCyrillicByLine: Boolean = false,
    ): String? = Romanization.romanize(text, line, enabledLanguages, romanizeCyrillicByLine)

    suspend fun romanizeSongLines(
        lines: List<String>,
        languageSelector: String = "auto",
        enabledLanguages: List<String> = Romanization.DEFAULT_ENABLED_LANGUAGES,
        romanizeCyrillicByLine: Boolean = false,
    ): List<String?> =
        Romanization.romanizeSongLines(lines, languageSelector, enabledLanguages, romanizeCyrillicByLine) ?: lines.map { null }

    suspend fun translateWithDeepL(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
    ): Result<List<String?>> =
        LyricsTranslation.translateLines(lines, targetLanguage, apiKey, TranslationProvider.DEEPL.id)

    /** Free Google Translate path: no API key required. */
    suspend fun translateWithGoogle(
        lines: List<String>,
        targetLanguage: String,
    ): Result<List<String?>> =
        LyricsTranslation.translateLines(lines, targetLanguage, "", TranslationProvider.GOOGLE.id)

    /** Dispatches to the selected provider; Google ignores [apiKey]. */
    suspend fun translateWithProvider(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        provider: String,
    ): Result<List<String?>> =
        LyricsTranslation.translateLines(lines, targetLanguage, apiKey, provider)

    fun clearCache() {
        LyricsTranslation.clearCache()
    }
}
