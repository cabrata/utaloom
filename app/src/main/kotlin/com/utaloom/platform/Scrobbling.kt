package com.utaloom.platform

import com.utaloom.data.JsonStore
import com.utaloom.data.Paths
import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerState
import com.utaloom.playback.RepeatMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.min

internal fun lastFmSignature(parameters: Map<String, String>, secret: String): String =
    MessageDigest.getInstance("MD5").digest((parameters.filterKeys { it !in setOf("format", "callback", "api_sig") }
        .toSortedMap().entries.joinToString("") { it.key + it.value } + secret).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun scrobbleThreshold(durationMs: Long): Long? =
    durationMs.takeIf { it > 0 }?.let { min(it / 2, 240_000L) }

internal data class ListenProgress(val song: Song, val startedAt: Long, val durationMs: Long, val listenedMs: Long, val nowPlaying: Boolean, val eligible: Boolean)

/** Counts monotonic playing time, never the seek position. Each consent/credential change gets a fresh tracker. */
internal class ListenTracker(private val minimumDurationMs: Long = 30_000) {
    private var previous: PlayerState? = null
    private var previousTime = 0L
    private var startedAt = 0L
    private var listenedMs = 0L
    private var submitted = false
    private var naturalEnd = false
    private var lastProgressAt = 0L

    fun sample(state: PlayerState, monotonicMs: Long, epochSeconds: Long): ListenProgress? {
        val song = state.current
        val old = previous
        val duration = state.durationMs.takeIf { it > 0 } ?: (song?.duration ?: 0) * 1000L
        val elapsed = (monotonicMs - previousTime).coerceAtLeast(0)
        val playing = state.isPlaying && !state.isBuffering && state.error == null
        val oldPlaying = old?.let { it.isPlaying && !it.isBuffering && it.error == null } == true
        // ponytail: without a playback generation, only a naturally reached end + repeat can prove a same-song restart.
        val restarted = old?.current?.id == song?.id && naturalEnd && state.repeat != RepeatMode.OFF &&
            state.positionMs <= 1500 && (old?.positionMs ?: 0) > 3000 && playing
        val changed = old?.current?.id != song?.id || old?.index != state.index || restarted
        if (changed || song == null) {
            listenedMs = 0
            startedAt = 0
            submitted = false
            naturalEnd = false
            lastProgressAt = monotonicMs
        }
        val nowPlaying = song != null && playing && startedAt == 0L
        if (nowPlaying) startedAt = epochSeconds.coerceAtLeast(1)
        if (!changed && old != null && song != null) {
            val positionDelta = state.positionMs - old.positionMs
            val seek = positionDelta < 0 || positionDelta > elapsed + 1500
            if (positionDelta > 0 && !seek) lastProgressAt = monotonicMs
            // Suspend/sleep, pauses, buffering, stalls and seek jumps do not buy listening time.
            if (playing && oldPlaying && !seek && elapsed <= 1500 && monotonicMs - lastProgressAt <= 2000) {
                listenedMs += elapsed
                if (duration > 0 && state.positionMs >= duration - 1500 && positionDelta > 0) naturalEnd = true
            }
            if (seek && !restarted) naturalEnd = false
        }
        previous = state
        previousTime = monotonicMs
        if (song == null || startedAt == 0L) return null
        val threshold = scrobbleThreshold(duration)
        val eligible = !submitted && duration > minimumDurationMs && threshold != null && listenedMs >= threshold &&
            song.title.isNotBlank() && song.artistText.isNotBlank()
        if (eligible) submitted = true
        return ListenProgress(song, startedAt, duration, listenedMs, nowPlaying, eligible)
    }
}

@Serializable
internal data class PendingScrobble(
    val id: String, val service: String, val account: String, val song: Song, val startedAt: Long,
    val durationMs: Long, val attempts: Int = 0, val retryAt: Long = 0,
)

internal class ScrobbleFailure(val retryable: Boolean, val authentication: Boolean = false, message: String) : Exception(message)

internal fun integrationCredentialValid(value: String): Boolean = value.isNotBlank() && value.length <= 512 && value.all { it.code in 33..126 }
internal fun lastFmConfigured(s: Settings): Boolean = integrationCredentialValid(s.lastFmApiKey) &&
    integrationCredentialValid(s.lastFmSecret) && integrationCredentialValid(s.lastFmSessionKey)

internal fun listenBrainzPayload(song: Song, durationMs: Long, startedAt: Long? = null): JsonObject = buildJsonObject {
    put("listen_type", if (startedAt == null) "playing_now" else "single")
    putJsonArray("payload") {
        add(buildJsonObject {
            if (startedAt != null) put("listened_at", startedAt)
            putJsonObject("track_metadata") {
                put("artist_name", song.artistText.take(1024))
                put("track_name", song.title.take(1024))
                song.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it.take(1024)) }
                putJsonObject("additional_info") {
                    put("submission_client", "Utaloom Desktop")
                    if (durationMs > 0) put("duration_ms", durationMs)
                }
            }
        })
    }
}

/** Opt-in observer independent of Listen Together's single Player.hooks slot. */
object Scrobbling {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lastFmStatus = MutableStateFlow("Disabled")
    private val _listenBrainzStatus = MutableStateFlow("Disabled")
    val lastFmStatus: StateFlow<String> = _lastFmStatus.asStateFlow()
    val listenBrainzStatus: StateFlow<String> = _listenBrainzStatus.asStateFlow()
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val pending by lazy { JsonStore(Paths.dir.resolve("scrobbles.json"), { emptyList<PendingScrobble>() }, kotlinx.serialization.builtins.ListSerializer(PendingScrobble.serializer())) }
    private var client: HttpClient? = null
    private val blocked = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Synchronized private fun http(): HttpClient = client ?: HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
    }.also { client = it }

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                supervisorScope {
                    launch { observe() }
                    launch { retryLoop() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _lastFmStatus.value = "Integration unavailable"; _listenBrainzStatus.value = "Integration unavailable" }
        }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        client?.close()
        client = null
        _lastFmStatus.value = "Stopped"
        _listenBrainzStatus.value = "Stopped"
    }

    /** Returns the authorization URL. The UI opens it, then calls finishLastFmAuth after approval. */
    suspend fun beginLastFmAuth(): Result<String> = safeResult {
        val s = Stores.settings.value
        requireCredentials(s.lastFmApiKey, s.lastFmSecret)
        val response = lastFmRequest("auth.getToken", s, emptyMap(), session = false)
        val token = response["token"]?.jsonPrimitive?.content?.takeIf(::integrationCredentialValid)
            ?: throw ScrobbleFailure(false, message = "Last.fm returned no authentication token")
        synchronized(this) { authToken = token; authApiKey = s.lastFmApiKey; authSecret = s.lastFmSecret }
        _lastFmStatus.value = "Approve in your browser, then finish sign in"
        "https://www.last.fm/api/auth/?api_key=${encode(s.lastFmApiKey)}&token=${encode(token)}"
    }.also { result -> result.exceptionOrNull()?.let { _lastFmStatus.value = publicError(it) } }

    private var authToken: String? = null
    private var authApiKey = ""
    private var authSecret = ""

    suspend fun finishLastFmAuth(): Result<String> = safeResult {
        val s = Stores.settings.value
        val token = synchronized(this) {
            authToken?.takeIf { authApiKey == s.lastFmApiKey && authSecret == s.lastFmSecret }
        } ?: throw ScrobbleFailure(false, message = "Start browser sign in first")
        val session = lastFmRequest("auth.getSession", s, mapOf("token" to token), session = false)["session"]?.jsonObject
            ?: throw ScrobbleFailure(false, message = "Last.fm returned no session")
        val key = session["key"]?.jsonPrimitive?.content.orEmpty()
        val name = session["name"]?.jsonPrimitive?.content.orEmpty()
        requireCredentials(key)
        if (name.isBlank() || name.length > 256) throw ScrobbleFailure(false, message = "Last.fm returned no username")
        currentCoroutineContext().ensureActive()
        if (Stores.settings.value.lastFmApiKey != s.lastFmApiKey || Stores.settings.value.lastFmSecret != s.lastFmSecret)
            throw ScrobbleFailure(false, message = "Credentials changed, restart sign in")
        Stores.settings.update { it.copy(lastFmSessionKey = key, lastFmUsername = name) }
        synchronized(this) { authToken = null; authSecret = ""; authApiKey = "" }
        _lastFmStatus.value = "Signed in. Enable scrobbling to share listens"
        name
    }.also { result -> result.exceptionOrNull()?.let { _lastFmStatus.value = publicError(it) } }

    suspend fun validateListenBrainzToken(token: String = Stores.settings.value.listenBrainzToken): Result<String> = safeResult {
        requireCredentials(token)
        val response = http().get("https://api.listenbrainz.org/1/validate-token") { header("Authorization", "Token $token") }
        val body = parseResponse(response)
        if (body["valid"]?.jsonPrimitive?.booleanOrNull != true) throw ScrobbleFailure(false, true, "Invalid ListenBrainz token")
        val name = body["user_name"]?.jsonPrimitive?.content?.take(256).orEmpty()
        _listenBrainzStatus.value = "Token valid. Enable scrobbling to share listens"
        name
    }.also { result -> result.exceptionOrNull()?.let { _listenBrainzStatus.value = publicError(it) } }

    private suspend fun observe() = supervisorScope {
        var lastFmAccount = ""
        var brainzAccount = ""
        var fmTracker = ListenTracker()
        var brainzTracker = ListenTracker(0)
        while (isActive) {
            val s = Stores.settings.value
            val fm = if (s.lastFmEnabled && lastFmConfigured(s)) account("lastfm", s) else ""
            val lb = if (s.listenBrainzEnabled && integrationCredentialValid(s.listenBrainzToken)) account("listenbrainz", s) else ""
            if (fm != lastFmAccount) { fmTracker = ListenTracker(); lastFmAccount = fm; _lastFmStatus.value = if (fm.isEmpty()) if (s.lastFmEnabled) "API key, secret and session required" else "Disabled" else "Ready" }
            if (lb != brainzAccount) { brainzTracker = ListenTracker(0); brainzAccount = lb; _listenBrainzStatus.value = if (lb.isEmpty()) if (s.listenBrainzEnabled) "Token required" else "Disabled" else "Ready" }
            val state = Player.state.value
            val now = System.currentTimeMillis() / 1000
            val tick = System.nanoTime() / 1_000_000
            listOf("lastfm" to fm, "listenbrainz" to lb).forEach { (service, identity) ->
                if (identity.isNotEmpty()) {
                    val progress = (if (service == "lastfm") fmTracker else brainzTracker).sample(state, tick, now)
                    if (progress?.nowPlaying == true) launch {
                        safeResult { send(service, s, progress, nowPlaying = true) }.exceptionOrNull()?.let { status(service).value = publicError(it) }
                    }
                    if (progress?.eligible == true) {
                        try {
                            val entry = PendingScrobble(java.util.UUID.randomUUID().toString(), service, identity, progress.song, progress.startedAt, progress.durationMs)
                            pending.update { (it + entry).takeLast(500) }
                            pending.flush()
                            status(service).value = "Listen queued"
                        } catch (_: Exception) { status(service).value = "Could not save pending listen" }
                    }
                } else status(service).value = if (service == "lastfm" && s.lastFmEnabled || service == "listenbrainz" && s.listenBrainzEnabled) "Credentials required" else "Disabled"
            }
            delay(250)
        }
    }

    private suspend fun retryLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val s = Stores.settings.value
                val now = System.currentTimeMillis() / 1000
                val before = pending.value
                // Revoking consent or switching accounts must never send the previous account's queue.
                val retained = before.filter { it.attempts < 6 && now - it.startedAt <= 7 * 24 * 3600 &&
                    (if (it.service == "lastfm") s.lastFmEnabled && lastFmConfigured(s) else s.listenBrainzEnabled && integrationCredentialValid(s.listenBrainzToken)) && it.account == account(it.service, s) }
                if (before != retained) { pending.update { retained }; pending.flush() }
                for (service in listOf("lastfm", "listenbrainz")) {
                    val entry = pending.value.firstOrNull { it.service == service } ?: continue
                    if (entry.retryAt > now || blocked[service] == entry.account) continue
                    val live = Stores.settings.value
                    if (account(service, live) != entry.account || !(if (service == "lastfm") live.lastFmEnabled else live.listenBrainzEnabled)) continue
                    val result = safeResult { send(service, live, ListenProgress(entry.song, entry.startedAt, entry.durationMs, 0, false, true), false) }
                    val failure = result.exceptionOrNull()
                    if (failure == null) {
                        pending.update { it.filterNot { item -> item.id == entry.id } }
                        status(service).value = "Listen submitted"
                    } else {
                        status(service).value = publicError(failure)
                        if (failure is ScrobbleFailure && failure.authentication) blocked[service] = entry.account
                        val retry = failure !is ScrobbleFailure || failure.retryable || failure.authentication
                        pending.update { list -> list.mapNotNull { item ->
                            if (item.id != entry.id) item else if (!retry) null else item.copy(attempts = item.attempts + 1, retryAt = now + min(3600L, 30L * (1L shl item.attempts)))
                        } }
                    }
                    pending.flush()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _lastFmStatus.value = "Pending listen storage unavailable"; _listenBrainzStatus.value = "Pending listen storage unavailable" }
            delay(1000)
        }
    }

    private suspend fun send(service: String, s: Settings, progress: ListenProgress, nowPlaying: Boolean) {
        if (service == "lastfm") {
            if (!s.lastFmEnabled || !lastFmConfigured(s)) return
            val fields = buildMap {
                put("artist", progress.song.artistText.take(1024))
                put("track", progress.song.title.take(1024))
                progress.song.album?.takeIf { it.isNotBlank() }?.let { put("album", it.take(1024)) }
                if (progress.durationMs > 0) put("duration", (progress.durationMs / 1000).toString())
                if (!nowPlaying) put("timestamp", progress.startedAt.toString())
            }
            val response = lastFmRequest(if (nowPlaying) "track.updateNowPlaying" else "track.scrobble", s, fields)
            if (!nowPlaying && response["scrobbles"]?.jsonObject?.get("@attr")?.jsonObject?.get("accepted")?.jsonPrimitive?.content == "0")
                throw ScrobbleFailure(false, message = "Last.fm ignored this listen")
        } else {
            if (!s.listenBrainzEnabled || !integrationCredentialValid(s.listenBrainzToken)) return
            val response = http().post("https://api.listenbrainz.org/1/submit-listens") {
                header("Authorization", "Token ${s.listenBrainzToken}")
                contentType(ContentType.Application.Json)
                setBody(listenBrainzPayload(progress.song, progress.durationMs, if (nowPlaying) null else progress.startedAt).toString())
            }
            val body = parseResponse(response)
            if (body["status"]?.jsonPrimitive?.content != "ok") throw ScrobbleFailure(false, message = "ListenBrainz did not accept this listen")
        }
        if (nowPlaying) status(service).value = "Now playing shared"
    }

    private suspend fun lastFmRequest(method: String, s: Settings, extra: Map<String, String>, session: Boolean = true): JsonObject {
        requireCredentials(s.lastFmApiKey, s.lastFmSecret)
        if (session) requireCredentials(s.lastFmSessionKey)
        val parameters = buildMap { put("method", method); put("api_key", s.lastFmApiKey); if (session) put("sk", s.lastFmSessionKey); putAll(extra) }
        val body = (parameters + mapOf("api_sig" to lastFmSignature(parameters, s.lastFmSecret), "format" to "json")).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = http().post("https://ws.audioscrobbler.com/2.0/") { contentType(ContentType.Application.FormUrlEncoded); setBody(body) }
        return parseResponse(response, lastFm = true)
    }

    private suspend fun parseResponse(response: io.ktor.client.statement.HttpResponse, lastFm: Boolean = false): JsonObject {
        val text = response.bodyAsText()
        if (text.toByteArray(Charsets.UTF_8).size > 65_536) throw ScrobbleFailure(true, message = "Service response too large")
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = if (lastFm) body?.get("error")?.jsonPrimitive?.intOrNull else null
        if (code != null) {
            val message = when (code) { 9 -> "Last.fm session expired, sign in again"; 10, 26 -> "Last.fm API key rejected"; 13 -> "Last.fm signature rejected, check API secret"; 14 -> "Approve Last.fm access in the browser first"; 15 -> "Last.fm token expired, restart sign in"; 29 -> "Last.fm rate limited, retry scheduled"; else -> "Last.fm request failed (code $code)" }
            throw ScrobbleFailure(code in setOf(8, 11, 16, 29), code in setOf(4, 9, 10, 13, 26), message)
        }
        val status = response.status.value
        if (status !in 200..299) throw ScrobbleFailure(status == 429 || status >= 500, status in setOf(401, 403), "${if (lastFm) "Last.fm" else "ListenBrainz"} request failed (HTTP $status)")
        return body ?: throw ScrobbleFailure(true, message = "Invalid service response")
    }

    private fun account(service: String, s: Settings): String {
        val value = if (service == "lastfm") "${s.lastFmApiKey}\u0000${s.lastFmSessionKey}\u0000${s.lastFmSecret}" else s.listenBrainzToken
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun status(service: String) = if (service == "lastfm") _lastFmStatus else _listenBrainzStatus
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private fun requireCredentials(vararg values: String) { if (values.any { !integrationCredentialValid(it) }) throw ScrobbleFailure(false, message = "Valid credentials required") }
    private fun publicError(error: Throwable) = (error as? ScrobbleFailure)?.message ?: "Could not reach service, please try again"
    private suspend fun <T> safeResult(block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
