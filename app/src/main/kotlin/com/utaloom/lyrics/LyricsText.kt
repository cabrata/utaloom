package com.utaloom.lyrics

/**
 * Unified lyrics text processor connecting offline romanization (Kuromoji, TinyPinyin, Hangul)
 * and official DeepL API lyric translation.
 */
object LyricsText {
    fun isJapanese(text: String): Boolean = text.any {
        (it in '\u3040'..'\u309F') || (it in '\u30A0'..'\u30FF') || (it in '\u4E00'..'\u9FFF')
    }

    fun isKorean(text: String): Boolean = text.any { it in '\uAC00'..'\uD7A3' }

    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cjkCount = text.count { it in '\u4E00'..'\u9FFF' }
        val kanaCount = text.count { (it in '\u3040'..'\u309F') || (it in '\u30A0'..'\u30FF') }
        return cjkCount > 0 && (kanaCount.toDouble() / text.length.toDouble()) < 0.1
    }

    suspend fun romanizeJapanese(text: String): String = Romanization.romanizeJapanese(text)

    suspend fun romanizeKorean(text: String): String = Romanization.romanizeKorean(text)

    suspend fun romanizeChinese(text: String): String = Romanization.romanizeChinese(text)

    suspend fun romanizeSongLines(lines: List<String>, languageSelector: String = "auto"): List<String?> =
        Romanization.romanizeSongLines(lines, languageSelector) ?: lines.map { null }

    suspend fun translateWithDeepL(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
    ): Result<List<String?>> =
        LyricsTranslation.translateLines(lines, targetLanguage, apiKey)

    fun clearCache() {
        LyricsTranslation.clearCache()
    }
}
