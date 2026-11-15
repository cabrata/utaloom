package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.playback.AddonAuthRequiredException
import com.utaloom.playback.MusicAddon
import com.utaloom.playback.MusicAddons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val WorkingGreen = Color(0xFF2E9E5B)

/** BitChord-compatible add-ons, tried top to bottom before YouTube Music. */
@Composable
fun MusicSourcesSettings() {
    val s by Stores.settings.state.collectAsState()
    val addons = s.musicAddons
    fun save(transform: (List<MusicAddon>) -> List<MusicAddon>) = Stores.settings.update { it.copy(musicAddons = transform(it.musicAddons)) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    // Per add-on URL: absent while checking, "" when working, otherwise what failed.
    val statuses = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(addons.map { it.url }) {
        val refreshed = addons.mapNotNull { addon ->
            try {
                MusicAddons.check(addon).also { statuses[addon.url] = "" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statuses[addon.url] = e.message ?: e.javaClass.simpleName
                null
            }
        }.associateBy { it.url }
        // Merge onto the current list so a switch or quality changed during the check survives.
        save { current ->
            current.map { c ->
                refreshed[c.url]?.let { fresh ->
                    fresh.copy(enabled = c.enabled, params = fresh.params + c.params.filterKeys { it == MusicAddons.QUALITY })
                } ?: c
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add-ons are tried from top to bottom. A song is matched by title, artist and length; when no add-on has it, YouTube Music plays it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        addons.forEachIndexed { i, addon ->
            fun move(to: Int) = save { it.toMutableList().apply { add(to, removeAt(i)) } }
            val status = statuses[addon.url]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(addon.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(addon.version.takeIf { it.isNotBlank() }?.let { "v$it" }, if (addon.downloadsAllowed) "Downloads allowed" else "Streaming only").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when { status == null -> "Checking…"; status.isEmpty() -> "Working"; else -> "Error: $status" }, style = MaterialTheme.typography.bodySmall,
                        color = when { status == null -> MaterialTheme.colorScheme.onSurfaceVariant; status.isEmpty() -> WorkingGreen; else -> MaterialTheme.colorScheme.error })
                }
                if (addon.qualities.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton({ open = true }) { Text(addon.qualities.firstOrNull { it.value == addon.quality }?.label ?: addon.quality.orEmpty()); Icon(Icons.Default.ArrowDropDown, null) }
                        DropdownMenu(open, { open = false }) {
                            addon.qualities.forEach { q ->
                                DropdownMenuItem({ Text(q.label) }, {
                                    save { list -> list.map { if (it.url == addon.url) it.copy(params = it.params + (MusicAddons.QUALITY to q.value)) else it } }
                                    open = false
                                })
                            }
                        }
                    }
                }
                IconButton({ move(i - 1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move ${addon.name} up") }
                IconButton({ move(i + 1) }, enabled = i < addons.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move ${addon.name} down") }
                IconButton({ save { list -> list.filter { it.url != addon.url } } }) { Icon(Icons.Default.Delete, "Remove ${addon.name}") }
                Switch(addon.enabled, { on -> save { list -> list.map { if (it.url == addon.url) it.copy(enabled = on) else it } } })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("YouTube Music", style = MaterialTheme.typography.titleMedium)
                Text("Lossy · Full catalogue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Always on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(link, { link = it; addError = null }, Modifier.weight(1f), label = { Text("Add-on link") },
                placeholder = { Text("https://…/manifest.json") }, singleLine = true, isError = addError != null)
            OutlinedButton({
                adding = true
                scope.launch {
                    try {
                        val addon = MusicAddons.add(link)
                        save { list -> list.filter { it.url != addon.url } + addon }
                        link = ""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addError = if (e is AddonAuthRequiredException) e.message else "Couldn't add this add-on. Check the link and try again."
                    } finally {
                        adding = false
                    }
                }
            }, enabled = !adding && MusicAddons.normalizeUrl(link) != null) { Text(if (adding) "Adding…" else "Add") }
        }
        Text(addError ?: "Paste the HTTPS link of a BitChord-compatible add-on server. Links often contain an access token; keep them private.",
            style = MaterialTheme.typography.bodySmall, color = if (addError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}
