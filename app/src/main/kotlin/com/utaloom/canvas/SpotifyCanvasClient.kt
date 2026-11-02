package com.utaloom.canvas

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Looks up a track's Spotify Canvas loop (an https canvaz.scdn.co mp4) using the user's sp_dc cookie. */
object SpotifyCanvasClient {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    // shortcut: Spotify's persisted query is versioned, update the hash from the web player if it changes.
    private const val SEARCH_HASH = "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Pair<String?, Long>>()
    private var session: String? = null
    private var token: String? = null
    private var tokenExpiresAt = 0L
    private var retryAt = 0L

    suspend fun fetchCanvas(title: String, artist: String, spDc: String?): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cookie = normalizeSpDc(spDc)
            if (session != cookie) {
                session = cookie; token = null; tokenExpiresAt = 0L; retryAt = 0L; cache.clear()
            }
            if (cookie == null || title.isBlank() || artist.isBlank()) return@withLock null
            val key = "$title\n$artist"
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { it.second > now }?.let { return@withLock it.first }
            if (now < retryAt) return@withLock null
            try {
                val access = accessToken(cookie)
                val canvas = searchTrackId(title, artist, access)?.let { canvasByTrackId(it, access) }
                cache[key] = canvas to (System.currentTimeMillis() + if (canvas == null) 5 * 60_000L else 30 * 60_000L)
                if (cache.size > 100) cache.remove(cache.keys.first())
                canvas
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A network/auth failure must not be cached as "this song has no Canvas".
                System.err.println("Spotify Canvas lookup failed (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private fun send(req: HttpRequest.Builder): ByteArray {
        val res = http.send(req.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() !in 200..299) {
            if (res.statusCode() == 401 || res.statusCode() == 403) { token = null; tokenExpiresAt = 0L }
            if (res.statusCode() == 429) {
                val s = res.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()?.coerceIn(1L, 3600L) ?: 60L
                retryAt = System.currentTimeMillis() + s * 1000L
            }
            throw IOException("Spotify HTTP ${res.statusCode()}")
        }
        return res.body()
    }

    private fun spotify(url: String, bearer: String? = null) = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .header("Origin", "https://open.spotify.com")
        .header("Referer", "https://open.spotify.com/")
        .apply { bearer?.let { header("Authorization", "Bearer $it") } }

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun accessToken(cookie: String): String {
        token?.takeIf { System.currentTimeMillis() < tokenExpiresAt - 60_000L }?.let { return it }
        // This public rotation-data request must never carry account cookies or bearer tokens.
        val secrets = json(send(HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"))))
        val version = secrets.keys.mapNotNull(String::toIntOrNull).maxOrNull() ?: throw IOException("Missing Spotify authentication version")
        val cipher = secrets.getValue(version.toString()).jsonArray
        if (cipher.size !in 16..128) throw IOException("Invalid Spotify authentication data")
        val key = cipher.withIndex().joinToString("") { (i, v) ->
            val n = v.jsonPrimitive.int
            if (n !in 0..255) throw IOException("Invalid Spotify authentication data")
            (n xor ((i % 33) + 9)).toString()
        }.toByteArray(Charsets.US_ASCII)
        val serverTime = json(send(spotify("https://open.spotify.com/api/server-time"))).getValue("serverTime").jsonPrimitive.long
        if (serverTime <= 0) throw IOException("Invalid Spotify server time")
        // totpServer uses the legacy, already-divided server timestamp.
        val url = "https://open.spotify.com/api/token?reason=transport&productType=web-player" +
            "&totp=${spotifyTotp(key, serverTime)}&totpVer=$version&totpServer=${spotifyTotp(key, serverTime / 30)}"
        val res = json(send(spotify(url).header("Cookie", "sp_dc=$cookie")))
        val access = res["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (access.isBlank() || res["isAnonymous"]?.jsonPrimitive?.booleanOrNull != false) throw IOException("Spotify session is not authenticated")
        token = access
        tokenExpiresAt = res["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 30 * 60_000L)
        return access
    }

    private fun searchTrackId(title: String, artist: String, access: String): String? {
        val payload = buildJsonObject {
            put("operationName", "searchTracks")
            putJsonObject("variables") { put("searchTerm", "${cleanCanvasTitle(title)} $artist"); put("offset", 0); put("limit", 10) }
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", SEARCH_HASH) } }
        }
        val res = json(send(spotify("https://api-partner.spotify.com/pathfinder/v2/query", access)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()))))
        if ((res["errors"] as? JsonArray)?.isNotEmpty() == true) throw IOException("Spotify search failed")
        val items = res["data"]?.jsonObject?.get("searchV2")?.jsonObject?.get("tracksV2")?.jsonObject?.get("items")?.jsonArray
            ?: throw IOException("Missing Spotify track results")
        return matchCanvasTrack(items, title, artist)
    }

    private fun canvasByTrackId(trackId: String, access: String): String? {
        val bytes = send(spotify("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases", access)
            .header("Content-Type", "application/x-protobuf").POST(HttpRequest.BodyPublishers.ofByteArray(canvasRequest(trackId))))
        return decodeCanvasUrl(bytes, "spotify:track:$trackId")
    }
}

internal fun spotifyTotp(key: ByteArray, seconds: Long): String {
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
    val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
    val offset = hash.last().toInt() and 15
    return ((ByteBuffer.wrap(hash, offset, 4).int and Int.MAX_VALUE) % 1_000_000).toString().padStart(6, '0')
}

internal fun cleanCanvasTitle(title: String): String = title
    .replace(Regex("""(?i)\([^)]*(?:official|video|audio|lyric|feat\.|ft\.)[^)]*\)|\[[^]]*(?:official|video|audio|lyric|feat\.|ft\.)[^]]*]"""), "")
    .trim()

private fun normalizeCanvasText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private val trackIdRegex = Regex("[A-Za-z0-9]{22}")

internal fun matchCanvasTrack(tracks: JsonArray, title: String, artist: String): String? {
    val wantTitle = normalizeCanvasText(cleanCanvasTitle(title))
    val wantArtist = normalizeCanvasText(artist)
    for (t in tracks) {
        val track = t.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: continue
        if (normalizeCanvasText(cleanCanvasTitle(track["name"]?.jsonPrimitive?.contentOrNull.orEmpty())) != wantTitle) continue
        val artists = track["artists"]?.jsonObject?.get("items")?.jsonArray ?: continue
        val artistMatches = artists.any { normalizeCanvasText(it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()) == wantArtist }
        val id = track["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (artistMatches && trackIdRegex.matches(id)) return id
    }
    return null
}

internal fun canvasRequest(trackId: String): ByteArray {
    require(trackIdRegex.matches(trackId))
    fun encode(write: CodedOutputStream.() -> Unit) = ByteArrayOutputStream().also { b -> CodedOutputStream.newInstance(b).apply { write(); flush() } }.toByteArray()
    val entity = encode { writeString(1, "spotify:track:$trackId") }
    return encode { writeByteArray(1, entity) }
}

internal fun decodeCanvasUrl(bytes: ByteArray, trackUri: String): String? {
    val input = CodedInputStream.newInstance(bytes)
    while (!input.isAtEnd) {
        val tag = input.readTag()
        if (tag != 10) { if (!input.skipField(tag)) break; continue }
        val canvas = CodedInputStream.newInstance(input.readByteArray())
        var url: String? = null
        var uri: String? = null
        while (!canvas.isAtEnd) {
            when (val f = canvas.readTag()) {
                18 -> url = canvas.readStringRequireUtf8()
                42 -> uri = canvas.readStringRequireUtf8()
                else -> if (!canvas.skipField(f)) break
            }
        }
        val parsed = url?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf { it.scheme == "https" && it.host == "canvaz.scdn.co" && it.path.endsWith(".mp4") }
        if (uri == trackUri && parsed != null) return parsed.toString()
    }
    return null
}

internal fun normalizeSpDc(input: String?): String? {
    val trimmed = input?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: return null
    val cookie = if (trimmed.contains("sp_dc=")) extractSpDc(trimmed) else trimmed
    return cookie?.takeIf { it.length in 16..4096 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '=' } }
}

fun extractSpDc(cookieString: String?): String? = cookieString?.split(';')
    ?.map(String::trim)?.firstOrNull { it.startsWith("sp_dc=") }?.substringAfter("sp_dc=")?.takeIf(String::isNotBlank)
