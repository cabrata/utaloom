package com.utaloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.platform.DiscordPresence
import com.utaloom.platform.Scrobbling
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

@Composable
fun IntegrationSettings() {
    val s by Stores.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lastFmStatus by Scrobbling.lastFmStatus.collectAsState()
    val listenBrainzStatus by Scrobbling.listenBrainzStatus.collectAsState()
    val discordStatus by DiscordPresence.status.collectAsState()

    var showSecrets by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("External integrations", style = MaterialTheme.typography.titleLarge)
        Text(
            "Scrobbling and Discord presence are strictly opt-in, disabled by default, and store no passwords.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show sensitive credentials", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = showSecrets, onCheckedChange = { showSecrets = it })
        }

        HorizontalDivider()

        // region Last.fm
        Text("Last.fm", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable Last.fm scrobbling", style = MaterialTheme.typography.bodyMedium)
                Text("Status: $lastFmStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = s.lastFmEnabled,
                onCheckedChange = { enabled -> Stores.settings.update { it.copy(lastFmEnabled = enabled) } }
            )
        }

        OutlinedTextField(
            value = s.lastFmApiKey,
            onValueChange = { value -> Stores.settings.update { it.copy(lastFmApiKey = value.trim()) } },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
        )

        OutlinedTextField(
            value = s.lastFmSecret,
            onValueChange = { value -> Stores.settings.update { it.copy(lastFmSecret = value.trim()) } },
            label = { Text("Shared Secret") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
        )

        if (s.lastFmUsername.isNotBlank()) {
            Text("Signed in as: ${s.lastFmUsername}", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val authUrlResult = Scrobbling.beginLastFmAuth()
                        authUrlResult.onSuccess { url ->
                            runCatching {
                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                    Desktop.getDesktop().browse(URI(url))
                                }
                            }
                        }
                    }
                },
                enabled = s.lastFmApiKey.isNotBlank() && s.lastFmSecret.isNotBlank()
            ) {
                Text("1. Authorize in browser")
            }

            OutlinedButton(
                onClick = { scope.launch { Scrobbling.finishLastFmAuth() } },
                enabled = s.lastFmApiKey.isNotBlank() && s.lastFmSecret.isNotBlank()
            ) {
                Text("2. Complete sign in")
            }

            if (s.lastFmSessionKey.isNotBlank()) {
                TextButton(onClick = {
                    Stores.settings.update { it.copy(lastFmSessionKey = "", lastFmUsername = "") }
                }) {
                    Text("Disconnect")
                }
            }
        }
        // endregion

        HorizontalDivider()

        // region ListenBrainz
        Text("ListenBrainz", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable ListenBrainz scrobbling", style = MaterialTheme.typography.bodyMedium)
                Text("Status: $listenBrainzStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = s.listenBrainzEnabled,
                onCheckedChange = { enabled -> Stores.settings.update { it.copy(listenBrainzEnabled = enabled) } }
            )
        }

        OutlinedTextField(
            value = s.listenBrainzToken,
            onValueChange = { token -> Stores.settings.update { it.copy(listenBrainzToken = token.trim()) } },
            label = { Text("User Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { Scrobbling.validateListenBrainzToken() } },
                enabled = s.listenBrainzToken.isNotBlank()
            ) {
                Text("Validate token")
            }

            if (s.listenBrainzToken.isNotBlank()) {
                TextButton(onClick = {
                    Stores.settings.update { it.copy(listenBrainzToken = "") }
                }) {
                    Text("Clear token")
                }
            }
        }
        // endregion

        HorizontalDivider()

        // region Discord Rich Presence
        Text("Discord Rich Presence", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable Discord presence", style = MaterialTheme.typography.bodyMedium)
                Text("Status: $discordStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = s.discordEnabled,
                onCheckedChange = { enabled -> Stores.settings.update { it.copy(discordEnabled = enabled) } }
            )
        }

        OutlinedTextField(
            value = s.discordApplicationId,
            onValueChange = { id -> Stores.settings.update { it.copy(discordApplicationId = id.trim()) } },
            label = { Text("Discord Application ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        // endregion
    }
}
