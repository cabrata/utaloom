package com.utaloom.data

import com.utaloom.playback.Player
import com.utaloom.playback.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

data class SongListeningStat(val song: Song, val plays: Long, val listenedMs: Long)
data class ArtistListeningStat(val name: String, val plays: Long, val listenedMs: Long)
data class AggregatedListeningStats(val totalMs: Long, val plays: Long, val songs: List<SongListeningStat>, val artists: List<ArtistListeningStat>)

private fun sumTimes(values: Iterable<Long>): Long = values.fold(0L) { a, b ->
    val positive = b.coerceAtLeast(0)
    if (a > Long.MAX_VALUE - positive) Long.MAX_VALUE else a + positive
}

fun listeningStats(library: LibraryState, byTime: Boolean = true): AggregatedListeningStats {
    val catalog = (library.history + library.liked + library.playlists.flatMap { it.songs } + library.downloadedSongs.values + library.songCatalog.values).associateBy { it.id }
    val songs = (library.playCounts.keys + library.listenedMsBySong.keys).map { id ->
        SongListeningStat(catalog[id] ?: Song(id, "Unknown song ($id)", emptyList()), (library.playCounts[id] ?: 0).toLong().coerceAtLeast(0), (library.listenedMsBySong[id] ?: 0).coerceAtLeast(0))
    }.filter { it.plays > 0 || it.listenedMs > 0 }.sortedWith(
        compareByDescending<SongListeningStat> { if (byTime) it.listenedMs else it.plays }.thenByDescending { if (byTime) it.plays else it.listenedMs }.thenBy { it.song.title.lowercase(Locale.ROOT) },
    )
    val artistSongs = mutableMapOf<String, MutableList<SongListeningStat>>()
    songs.forEach { stat -> stat.song.artists.filter { it.isNotBlank() }.distinctBy { it.trim().lowercase(Locale.ROOT) }.forEach { name ->
        artistSongs.getOrPut(name.trim().lowercase(Locale.ROOT)) { mutableListOf() }.add(stat)
    } }
    val artists = artistSongs.map { (key, tracks) ->
        ArtistListeningStat(tracks.first().song.artists.first { it.trim().lowercase(Locale.ROOT) == key }.trim(), sumTimes(tracks.map { it.plays }), sumTimes(tracks.map { it.listenedMs }))
    }.sortedWith(compareByDescending<ArtistListeningStat> { if (byTime) it.listenedMs else it.plays }.thenByDescending { if (byTime) it.plays else it.listenedMs }.thenBy { it.name.lowercase(Locale.ROOT) })
    return AggregatedListeningStats(sumTimes(library.listenedMsBySong.values), sumTimes(library.playCounts.values.map { it.toLong() }), songs, artists)
}

/**
 * Independent listener that credits measured wall-clock playback to Library.recordListening.
 * Polls Player.state every second while isPlaying and not isBuffering.
 * Does NOT use Player.hooks (reserved for Listen Together).
 * Obeys pauseHistory through Library.recordListening.
 * Periodic flush every 15 seconds so a crash loses at most 15 seconds.
 */
object ListeningStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch { collect() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun collect() {
        var lastSong: Song? = null
        var accumulatedMs = 0L
        var lastTick = 0L

        fun flush() {
            val song = lastSong
            if (song != null && accumulatedMs > 0) {
                Library.recordListening(song, accumulatedMs)
                accumulatedMs = 0
            }
        }

        // Re-collect on every state change; poll inside isPlaying windows.
        Player.state.collectLatest { state ->
            val song = state.current
            if (song == null || !state.isPlaying || state.isBuffering) {
                flush()
                lastSong = song
                lastTick = 0
                return@collectLatest // suspends until next state change
            }
            // Track changed while playing
            if (song.id != lastSong?.id) {
                flush()
                lastSong = song
                accumulatedMs = 0
            }
            lastTick = System.currentTimeMillis()
            // Poll every second while playing
            try {
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTick
                    lastTick = now
                    // Clamp to 2 seconds to avoid crediting sleep/suspend
                    val credited = elapsed.coerceIn(0, 2000)
                    accumulatedMs += credited
                    // Periodic persist every 15 seconds
                    if (accumulatedMs >= 15_000) flush()
                }
            } finally {
                flush()
            }
        }
    }
}
