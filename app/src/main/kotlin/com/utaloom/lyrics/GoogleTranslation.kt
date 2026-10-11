package com.utaloom.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Free Google Translate provider using the public `translate_a/single` (gtx) endpoint.
 *
 * No API key is required. Each lyric line is sent as a separate request, and when a single
 * line returns multiple sentence fragments they are joined back together so the caller always
 * receives exactly one translation per requested line.
 */
object GoogleTranslation {
    const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Google caps the whole `q` parameter; keep requests well below it. */
    private const val MAX_QUERY_CHARS = 2000

    /** The public gtx endpoint serves plain HTTP clients a "Sorry" bot page, so present a browser UA. */
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(UserAgent) { agent = USER_AGENT }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses the nested gtx response `[[["translated","source",...],...],...]` into a flat string.
     * Sentence fragments are appended without separators because Google splits them mid-sentence,
     * while the original newlines inside a fragment are preserved.
     */
    fun parseResponse(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return ""
        val segments = root.firstOrNull() as? JsonArray ?: return ""
        return buildString {
            for (segment in segments) {
                val fragment = (segment as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: continue
                append(fragment)
            }
        }.trim()
    }

    /**
     * Normalizes a lyric-settings language code for the gtx `tl` parameter.
     * Google expects lowercase tags such as `id`, `en`, `zh-CN` or `pt-BR`.
     */
    fun normalizeLanguage(lang: String): String {
        val trimmed = lang.trim()
        if (trimmed.isEmpty()) return "en"
        return when (trimmed.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant" -> "zh-TW"
            "pt", "pt-pt" -> "pt-PT"
            "pt-br" -> "pt-BR"
            "en", "en-us" -> "en"
            "en-gb" -> "en-GB"
            else -> {
                val parts = trimmed.replace('_', '-').split('-')
                val base = parts.first().lowercase()
                val rest = parts.drop(1).map { it.uppercase() }
                (listOf(base) + rest).joinToString("-")
            }
        }
    }

    /** Translates a single line. Returns an empty string when Google has no translation for it. */
    suspend fun translateLine(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        coroutineContext.ensureActive()

        val response = client.get(ENDPOINT) {
            parameter("client", "gtx")
            parameter("sl", "auto")
            parameter("dt", "t")
            parameter("tl", normalizeLanguage(targetLang))
            parameter("q", text.take(MAX_QUERY_CHARS))
        }
        coroutineContext.ensureActive()

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Google Translate failed: HTTP ${response.status.value} ${response.status.description}")
        }
        parseResponse(body)
    }

    /**
     * Translates every non-blank line, preserving positions so results map 1:1 onto the source.
     * Blank source lines stay null and are never sent over the network.
     */
    suspend fun translateLines(lines: List<String>, targetLang: String): List<String?> {
        if (lines.isEmpty()) return emptyList()

        val results = ArrayList<String?>(lines.size)
        for (line in lines) {
            coroutineContext.ensureActive()
            results.add(null)
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val translated = translateLine(trimmed, targetLang)
            results[results.size - 1] = translated.takeIf { it.isNotBlank() }
        }
        return results
    }

    internal fun maxQueryChars(): Int = MAX_QUERY_CHARS
}
