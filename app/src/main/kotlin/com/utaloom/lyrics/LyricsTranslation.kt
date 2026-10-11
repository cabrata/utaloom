package com.utaloom.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

@Serializable
data class DeepLTranslationRequest(
    val text: List<String>,
    @SerialName("target_lang") val targetLang: String,
    @SerialName("preserve_formatting") val preserveFormatting: Boolean = true,
)

@Serializable
data class DeepLTranslationItem(
    val text: String,
    @SerialName("detected_source_language") val detectedSourceLanguage: String? = null,
)

@Serializable
data class DeepLTranslationResponse(
    val translations: List<DeepLTranslationItem>? = null,
    val message: String? = null,
)

sealed interface TranslationState {
    data object Idle : TranslationState
    data class Translating(val songId: String) : TranslationState
    data class Success(val songId: String, val targetLang: String, val lines: List<String?>) : TranslationState
    data class Error(val songId: String, val message: String) : TranslationState
}

/** Translation backends supported by the lyrics view. */
enum class TranslationProvider(val id: String, val label: String, val requiresApiKey: Boolean) {
    DEEPL("deepl", "DeepL API (Official Free / Pro)", true),
    GOOGLE("google", "Google Translate (Free, no key)", false);

    companion object {
        fun fromId(id: String?): TranslationProvider =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEEPL
    }
}

/**
 * Opt-in lyric translation with line mapping preservation, response count enforcement,
 * caching, and explicit opt-in execution. Backed by DeepL (API key) or the free
 * Google Translate endpoint.
 */
object LyricsTranslation {
    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val memoryCache = ConcurrentHashMap<String, List<String?>>()
    private val _state = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val state: StateFlow<TranslationState> = _state.asStateFlow()

    private var activeJob: Job? = null

    /** Selects official DeepL free vs pro endpoint based on the ':fx' suffix. */
    fun selectEndpoint(apiKey: String): String =
        if (apiKey.trim().endsWith(":fx", ignoreCase = true)) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }

    /** Creates a non-reversible fingerprint of the API key for cache isolation. */
    fun computeKeyFingerprint(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return "empty"
        val hash = trimmed.hashCode().toString(16)
        return if (trimmed.length > 8) "${trimmed.take(3)}..${trimmed.takeLast(4)}#$hash" else hash
    }

    /** Computes cache key including provider, target language, key fingerprint, and text hash. */
    fun computeCacheKey(provider: String, targetLang: String, apiKey: String, fullText: String): String {
        val keyFp = computeKeyFingerprint(apiKey)
        val textFp = fullText.hashCode().toString(16)
        return "$provider:$targetLang:$keyFp:$textFp"
    }

    /** Normalizes language code for DeepL (e.g. ID, EN-US, ZH). */
    fun normalizeTargetLanguage(lang: String): String = when (lang.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "ZH"
        "zh-tw", "zh-hant" -> "ZH"
        "en", "en-us" -> "EN-US"
        "en-gb" -> "EN-GB"
        "pt", "pt-pt" -> "PT-PT"
        "pt-br" -> "PT-BR"
        else -> lang.trim().uppercase().take(2)
    }

    fun clearCache() {
        memoryCache.clear()
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        if (_state.value is TranslationState.Translating) {
            _state.value = TranslationState.Idle
        }
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        _state.value = TranslationState.Idle
    }

    /**
     * Executes translation for a list of lyric lines, preserving line positions
     * and strictly enforcing response line counts before returning.
     * The free Google provider ignores [apiKey] and never requires one.
     */
    suspend fun translateLines(
        lines: List<String>,
        targetLang: String,
        apiKey: String,
        provider: String = TranslationProvider.DEEPL.id,
    ): Result<List<String?>> = withContext(Dispatchers.IO) {
        val selected = TranslationProvider.fromId(provider)
        if (selected == TranslationProvider.GOOGLE) {
            return@withContext translateWithGoogle(lines, targetLang)
        }

        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("DeepL API key is required"))
        }
        if (lines.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val indexedLines = lines.mapIndexedNotNull { index, text ->
            val trimmed = text.trim()
            if (trimmed.isNotBlank()) index to trimmed else null
        }
        if (indexedLines.isEmpty()) {
            return@withContext Result.success(List(lines.size) { null })
        }

        val fullText = lines.joinToString("\n")
        val cacheKey = computeCacheKey("deepl", targetLang, trimmedKey, fullText)
        memoryCache[cacheKey]?.let { return@withContext Result.success(it) }

        val endpoint = selectEndpoint(trimmedKey)
        val normalizedLang = normalizeTargetLanguage(targetLang)
        val textsToSend = indexedLines.map { it.second }

        try {
            coroutineContext.ensureActive()
            val payload = DeepLTranslationRequest(
                text = textsToSend,
                targetLang = normalizedLang,
                preserveFormatting = true,
            )
            val response = client.post(endpoint) {
                header("Authorization", "DeepL-Auth-Key $trimmedKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DeepLTranslationRequest.serializer(), payload))
            }

            coroutineContext.ensureActive()
            val responseText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val errorMsg = runCatching {
                    json.decodeFromString(DeepLTranslationResponse.serializer(), responseText).message
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${response.status.value}: ${response.status.description}"
                return@withContext Result.failure(IllegalStateException("DeepL translation failed: $errorMsg"))
            }

            val parsed = json.decodeFromString(DeepLTranslationResponse.serializer(), responseText)
            val translations = parsed.translations?.map { it.text }
                ?: return@withContext Result.failure(IllegalStateException("DeepL response contained no translations"))

            // Enforce response count before applying
            if (translations.size != textsToSend.size) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Translation response count mismatch: expected ${textsToSend.size}, received ${translations.size}"
                    )
                )
            }

            val mapped = ArrayList<String?>(lines.size)
            for (i in lines.indices) mapped.add(null)
            indexedLines.forEachIndexed { i, (origIdx, _) ->
                mapped[origIdx] = translations[i].trim().takeIf { it.isNotBlank() }
            }

            memoryCache[cacheKey] = mapped
            Result.success(mapped)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Free Google Translate path: per-line requests, line positions preserved, cached by provider.
     */
    private suspend fun translateWithGoogle(lines: List<String>, targetLang: String): Result<List<String?>> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext Result.success(emptyList<String?>())

        val fullText = lines.joinToString("\n")
        val cacheKey = computeCacheKey(TranslationProvider.GOOGLE.id, targetLang, "free", fullText)
        memoryCache[cacheKey]?.let { return@withContext Result.success(it) }

        return@withContext try {
            coroutineContext.ensureActive()
            val translated = GoogleTranslation.translateLines(lines, targetLang)
            coroutineContext.ensureActive()
            if (translated.size != lines.size) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Translation response count mismatch: expected ${lines.size}, received ${translated.size}"
                    )
                )
            }
            memoryCache[cacheKey] = translated
            Result.success(translated)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Triggers translation in response to an explicit user click.
     * Honors cancellation and surfaces errors.
     */
    fun translate(
        songId: String,
        lines: List<String>,
        targetLang: String,
        apiKey: String,
        scope: CoroutineScope,
        provider: String = TranslationProvider.DEEPL.id,
    ) {
        cancel()
        _state.value = TranslationState.Translating(songId)

        activeJob = scope.launch {
            val result = translateLines(lines, targetLang, apiKey, provider)
            result.fold(
                onSuccess = { translated ->
                    _state.value = TranslationState.Success(songId, targetLang, translated)
                },
                onFailure = { err ->
                    _state.value = TranslationState.Error(
                        songId,
                        err.message ?: "Failed to translate lyrics"
                    )
                }
            )
        }
    }
}
