package com.utaloom.data

import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.Locale

/** Only validated metadata or a YouTube video ID crosses into the matcher, never input URLs. */
data class PlaylistTrack(val title: String, val artist: String = "", val durationSec: Int = 0, val videoId: String? = null)
data class FilePlaylist(val name: String, val tracks: List<PlaylistTrack>, val skipped: Int = 0, val warnings: List<String> = emptyList())

const val MAX_PLAYLIST_FILE_BYTES = 10 * 1024 * 1024
const val MAX_IMPORT_TRACKS = 20_000

fun parseSpotifyCsv(text: String, playlistName: String): FilePlaylist = FilePlaylistImport.parse(text, "$playlistName.csv")
fun parseM3u(text: String, playlistName: String): FilePlaylist = FilePlaylistImport.parse(text, "$playlistName.m3u")
fun parsePlaylistFile(text: String, fileName: String): FilePlaylist = FilePlaylistImport.parse(text, fileName)
internal fun csvRows(text: String): List<List<String>> = FilePlaylistImport.csvRows(text)
internal fun youtubeVideoId(value: String): String? = FilePlaylistImport.videoId(value)

object FilePlaylistImport {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_TRACKS = 20_000
    private const val MAX_FIELD = 8192

    fun parse(text: String, fileName: String): FilePlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && '\u0000' !in text) { "Playlist exceeds 10 MiB or contains a NUL character" }
        val name = clean(fileName.substringBeforeLast('.')).take(200).ifBlank { "Imported playlist" }
        val tracks = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv" -> spotifyCsv(text)
            "m3u", "m3u8" -> m3u(text)
            else -> throw IllegalArgumentException("Choose an Exportify Spotify CSV or YouTube M3U / M3U8 file")
        }
        require(tracks.isNotEmpty() && tracks.size <= MAX_TRACKS) { "Playlist must contain 1 to 20,000 tracks" }
        tracks.forEach(::validateTrack)
        return FilePlaylist(name, tracks)
    }

    /** Staging only: callers must confirm the result before one library update. Cancellation discards it. */
    suspend fun match(
        playlist: FilePlaylist,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        lookup: suspend (PlaylistTrack) -> Song? = ::findTrack,
    ): RemoteImport = withContext(Dispatchers.IO) {
        require(playlist.name.isNotBlank() && playlist.name.length <= 200 && playlist.name.none { it.isISOControl() }) { "Invalid playlist name" }
        require(playlist.tracks.size in 1..MAX_TRACKS) { "Playlist must contain 1 to 20,000 tracks" }
        playlist.tracks.forEach(::validateTrack)
        val songs = mutableListOf<Song>()
        val missing = mutableListOf<String>()
        onProgress(0, playlist.tracks.size)
        playlist.tracks.forEachIndexed { index, track ->
            currentCoroutineContext().ensureActive()
            val song = withTimeout(30_000) { lookup(track) }
            currentCoroutineContext().ensureActive()
            if (song == null) missing += "${track.title}${if (track.artist.isBlank()) "" else " • ${track.artist}"}"
            else {
                require(song.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && song.title.isNotBlank() && song.title.length <= 2000 && song.title.none { it.isISOControl() }) { "Match returned invalid song data" }
                songs += song
            }
            onProgress(index + 1, playlist.tracks.size)
        }
        RemoteImport(LocalPlaylist("", playlist.name, songs.distinctBy { it.id }), missing)
    }

    private suspend fun findTrack(track: PlaylistTrack): Song? {
        if (track.videoId != null) return YouTube.queue(videoIds = listOf(track.videoId)).getOrThrow().firstOrNull { it.id == track.videoId }?.toSong()
        val hits = YouTube.search("${track.title} ${track.artist}", YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
        return bestMatch(hits, track.durationSec, track.artist)?.toSong()
    }

    private fun clean(value: String) = value.trim().replace(Regex("\\s+"), " ")

    private fun validateTrack(track: PlaylistTrack) {
        require(track.title.isNotBlank() && track.title.length <= 2000 && track.title.none { it.isISOControl() }) { "Invalid track title" }
        require(track.artist.length <= 2000 && track.artist.none { it.isISOControl() }) { "Invalid artist metadata" }
        require(track.durationSec in 0..604_800 && (track.videoId == null || Regex("[A-Za-z0-9_-]{11}").matches(track.videoId))) { "Invalid track duration or YouTube ID" }
    }

    /** RFC 4180 quotes, escaped quotes, CRLF and quoted newlines. Truncated/invalid input fails closed. */
    internal fun csvRows(text: String): List<List<String>> {
        val input = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun cell() {
            require(row.size < 200) { "CSV has more than 200 columns" }
            row.add(field.toString())
            field.setLength(0)
            closed = false
        }
        fun record() {
            cell()
            if (row.any { it.isNotBlank() }) {
                require(rows.size <= MAX_TRACKS) { "CSV has more than 20,000 tracks" }
                rows.add(row)
            }
            row = mutableListOf()
        }
        while (i < input.length) {
            val c = input[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < input.length && input[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "Malformed CSV quote" }; quoted = true }
                ',' -> cell()
                '\r', '\n' -> { if (c == '\r' && i < input.length && input[i] == '\n') i++; record() }
                else -> { require(!closed) { "Unexpected characters after CSV quote" }; field.append(c) }
            }
            require(field.length <= MAX_FIELD) { "CSV field exceeds 8192 characters" }
        }
        require(!quoted) { "CSV ends inside a quoted field. Nothing was imported." }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) record()
        return rows
    }

    private fun spotifyCsv(text: String): List<PlaylistTrack> {
        val rows = csvRows(text)
        require(rows.isNotEmpty()) { "CSV is empty" }
        val header = rows.first().map { clean(it).lowercase(Locale.ROOT) }
        fun column(vararg names: String): Int {
            val indices = header.indices.filter { header[it] in names }
            require(indices.size <= 1) { "CSV has ambiguous ${names.first()} columns" }
            return indices.firstOrNull() ?: -1
        }
        val title = column("track name", "track name(s)", "name", "title")
        val artist = column("artist name(s)", "artist names", "artist name", "artists", "artist")
        val duration = column("duration (ms)", "duration_ms", "duration ms")
        require(title >= 0 && artist >= 0) { "CSV needs Track Name and Artist Name(s) headers (Exportify format)" }
        return rows.drop(1).mapIndexed { index, cells ->
            require(cells.size == header.size) { "CSV row ${index + 2} has an incorrect column count. Nothing was imported." }
            val seconds = if (duration < 0 || cells[duration].isBlank()) 0 else {
                val ms = cells[duration].trim().toLongOrNull()
                require(ms != null && ms in 0..604_800_000) { "CSV row ${index + 2} has an invalid Duration (ms)" }
                (ms / 1000).toInt()
            }
            // A Spotify Track URI is not a YouTube ID and is never visited.
            PlaylistTrack(clean(cells[title]), clean(cells[artist]).replace(';', ','), seconds)
        }
    }

    internal fun videoId(value: String): String? {
        if (value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        val id = when (uri.host?.lowercase(Locale.ROOT)) {
            "youtu.be", "www.youtu.be" -> uri.path.removePrefix("/").takeIf { '/' !in it }
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').filter { it.startsWith("v=") }.singleOrNull()?.removePrefix("v=")
                Regex("/(shorts|embed|live)/[A-Za-z0-9_-]{11}").matches(uri.path) -> uri.path.substringAfterLast('/')
                else -> null
            }
            else -> null
        }
        return id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }

    private fun m3u(text: String): List<PlaylistTrack> {
        val tracks = mutableListOf<PlaylistTrack>()
        var metadata: PlaylistTrack? = null
        for ((index, raw) in text.removePrefix("\uFEFF").lineSequence().withIndex()) {
            require(raw.length <= MAX_FIELD) { "M3U line exceeds 8192 characters" }
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", true)) {
                require(metadata == null && ',' in line) { "M3U line ${index + 1} has missing or invalid metadata" }
                val seconds = line.substringAfter(':').substringBefore(',').trim().toIntOrNull()
                require(seconds != null && seconds in -1..604_800) { "M3U line ${index + 1} has an invalid duration" }
                val label = clean(line.substringAfter(','))
                metadata = PlaylistTrack(if (" - " in label) label.substringAfter(" - ") else label, if (" - " in label) label.substringBefore(" - ") else "", seconds.coerceAtLeast(0))
                validateTrack(metadata)
                continue
            }
            if (line.startsWith('#')) continue
            require(tracks.size < MAX_TRACKS) { "M3U has more than 20,000 tracks" }
            val id = videoId(line) ?: throw IllegalArgumentException("M3U line ${index + 1}: only YouTube video URLs are accepted. Local paths and other URLs are never opened.")
            tracks += (metadata ?: PlaylistTrack(id)).copy(videoId = id)
            metadata = null
        }
        require(metadata == null) { "M3U ends with metadata but no video URL. Nothing was imported." }
        return tracks
    }
}
