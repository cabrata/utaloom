package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.utaloom.data.Library
import com.utaloom.data.LibraryState
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.data.SongListeningStat
import com.utaloom.data.ArtistListeningStat
import com.utaloom.data.AggregatedListeningStats
import com.utaloom.data.listeningStats
import java.time.LocalDate
import java.util.Locale

typealias ListeningStats = AggregatedListeningStats

private fun hours(ms: Long) = String.format(Locale.getDefault(), "%.2f h", ms / 3_600_000.0)

@Composable
fun StatsScreen() {
    val library by Stores.library.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val player by Player.state.collectAsState()
    var byTime by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    val stats = remember(library, byTime) { listeningStats(library, byTime) }
    val topSongs = stats.songs.filter { !settings.hideExplicit || !it.song.explicit }.take(50)
    val days = library.listeningByDay.entries.sortedByDescending { it.key }.take(30)
    VScrollColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle("Listening statistics") }
        item {
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column { Text(hours(stats.totalMs), style = MaterialTheme.typography.headlineMedium); Text("Actual listening time") }
                Column { Text("${stats.plays}", style = MaterialTheme.typography.headlineMedium); Text("Recorded plays") }
                Column { Text("${stats.songs.size}", style = MaterialTheme.typography.headlineMedium); Text("Songs heard") }
            }
            Text("Hours count only measured active playback since tracking was enabled. Older play counts do not add estimated listening time. Each credited artist receives a song's full time, so artist totals overlap.",
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.pauseHistory) Text("Listening history is paused. No new listening time or plays are recorded.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(byTime, { byTime = true }, { Text("Listening time") })
                FilterChip(!byTime, { byTime = false }, { Text("Play count") })
                TextButton({ confirmClear = true }, enabled = stats.songs.isNotEmpty() || library.listeningByDay.isNotEmpty()) { Text("Clear listening history") }
            }
        }
        item { SectionTitle("Top songs • all time") }
        if (topSongs.isEmpty()) item { Text("Play music with history enabled to see your top songs.", Modifier.padding(24.dp)) }
        itemsIndexed(topSongs, key = { _, stat -> "stats-song-${stat.song.id}" }) { index, stat ->
            SongRow(stat.song, { Player.playQueue(topSongs.map { it.song }, index, "Top songs") }, index = index + 1, playing = player.current?.id == stat.song.id,
                trailing = { Column { Text(hours(stat.listenedMs), style = MaterialTheme.typography.bodySmall); Text("${stat.plays} plays", style = MaterialTheme.typography.bodySmall) } })
        }
        item { SectionTitle("Top artists • all time") }
        if (stats.artists.isEmpty()) item { Text("No artist statistics yet.", Modifier.padding(24.dp)) }
        itemsIndexed(stats.artists.take(50), key = { _, artist -> "stats-artist-${artist.name}" }) { index, artist ->
            ListItem(headlineContent = { Text("${index + 1}. ${artist.name}") }, supportingContent = { Text("${artist.plays} plays") }, trailingContent = { Text(hours(artist.listenedMs)) }, modifier = Modifier.padding(horizontal = 12.dp))
        }
        item { SectionTitle("Recent listening days") }
        if (days.isEmpty()) item { Text("No measured listening time yet.", Modifier.padding(24.dp)) }
        items(days, key = { "stats-day-${it.key}" }) { day ->
            ListItem(headlineContent = { Text(if (day.key == LocalDate.now().toString()) "Today (${day.key})" else day.key) }, trailingContent = { Text(hours(day.value)) }, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear listening history and statistics?") }, text = {
        Text("All local listening history, play counts, listening time and artist statistics will be removed. Liked songs, playlists and downloads are kept.")
    }, confirmButton = { TextButton({ Library.clearListeningHistory(); confirmClear = false }) { Text("Clear", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
fun StatisticsScreen() = StatsScreen()
