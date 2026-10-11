package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.utaloom.data.Backup
import com.utaloom.data.BackupDocument
import com.utaloom.platform.readBackupText
import com.utaloom.platform.writeBackupText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drop this into a SettingsScreen lazy-column item. No account data ever enters an export. */
@Composable
fun BackupSettings() {
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupDocument?>(null) }
    var preferences by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Backup and restore")
        Text("Portable desktop JSON includes your library, listening statistics, safe preferences and saved queue. Cookies, account metadata, API keys, sessions, downloaded paths and audio are excluded automatically.",
            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ exporting = true }, enabled = !busy) { Text("Export backup") }
            OutlinedButton({
                busy = true
                scope.launch {
                    try {
                        val text = readBackupText(Backup.MAX_BYTES)
                        if (text != null) {
                            preview = withContext(Dispatchers.Default) { Backup.decode(text) }
                            preferences = false
                            queue = false
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "Restore failed. No data was changed. ${(e as? IllegalArgumentException)?.message ?: "Choose a valid Utaloom desktop backup."}" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("Restore backup") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    }
    if (exporting) AlertDialog(onDismissRequest = { exporting = false }, title = { Text("Export desktop backup?") }, text = {
        Text("The backup still contains your playlists and listening/search history. Keep it somewhere private. Account credentials and downloaded audio are never included.")
    }, confirmButton = { TextButton({
        exporting = false
        busy = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) { Backup.encode() }
                if (writeBackupText(text, Backup.MAX_BYTES)) message = "Backup exported successfully."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "Export failed. The previous destination file was kept. ${(e as? IllegalArgumentException)?.message ?: "Choose a writable local filesystem supporting atomic replacement."}" }
            finally { busy = false }
        }
    }) { Text("Choose destination") } }, dismissButton = { TextButton({ exporting = false }) { Text("Cancel") } })
    preview?.let { backup -> AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Merge desktop backup?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${backup.library.liked.size} liked songs, ${backup.library.playlists.size} playlists, ${backup.library.history.size} history entries.")
            Text("Existing songs, playlists and downloads are kept. Matching playlists gain missing songs. Larger play/listening counters win so restoring twice does not double your statistics.")
            Text("Sign-ins are never replaced. Android database ZIP backups are not supported.", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(preferences, { preferences = it }, enabled = !busy); Text("Restore safe preferences", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(queue, { queue = it }, enabled = !busy); Text("Restore saved queue for next launch", Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton({
        val includePreferences = preferences
        val includeQueue = queue
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Backup.restore(backup, includePreferences, includeQueue) }
                if (includePreferences) applySession()
                if (includeQueue) {
                    runCatching {
                        com.utaloom.playback.Player::class.java.getMethod("restoreAfterBackup").invoke(com.utaloom.playback.Player)
                    }
                }
                preview = null
                message = "Backup merged successfully. Existing library data and sign-ins were kept."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Restore could not be saved. Previous data was restored where possible. Check storage space and permissions before retrying." }
            finally { busy = false }
        }
    }, enabled = !busy) { Text("Merge backup") } }, dismissButton = { TextButton({ preview = null }, enabled = !busy) { Text("Cancel") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton({ message = null }) { Text("OK") } }) }
}
