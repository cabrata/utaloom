package com.utaloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.utaloom.data.Settings
import com.utaloom.data.Stores
import com.utaloom.lyrics.LyricsTranslation
import com.utaloom.lyrics.Romanization
import com.utaloom.lyrics.TranslationProvider

val Settings.deepLApiKey: String get() = lyricsTranslationKey
val Settings.lyricsTranslateLanguage: String get() = lyricsTargetLanguage

private val ROMANIZATION_OPTIONS = listOf(
    "auto" to "Auto-detect (all enabled languages)",
    "ja" to "Japanese only (Romaji)",
    "ko" to "Korean only (Romaja)",
    "zh" to "Chinese only (Pinyin)",
)

/** Friendly labels for the offline romanization languages, mirroring the Android app. */
private val ROMANIZATION_LANGUAGE_LABELS = mapOf(
    "Japanese" to "Japanese (Romaji)",
    "Korean" to "Korean (Romaja)",
    "Chinese" to "Chinese (Pinyin)",
    "Hindi" to "Hindi (Devanagari)",
    "Punjabi" to "Punjabi (Gurmukhi)",
    "Russian" to "Russian",
    "Ukrainian" to "Ukrainian",
    "Serbian" to "Serbian",
    "Bulgarian" to "Bulgarian",
    "Belarusian" to "Belarusian",
    "Kyrgyz" to "Kyrgyz",
    "Macedonian" to "Macedonian",
)

private val TARGET_LANGUAGE_OPTIONS = listOf(
    "ID" to "Indonesian (ID)",
    "EN" to "English (EN)",
    "JA" to "Japanese (JA)",
    "KO" to "Korean (KO)",
    "ZH" to "Chinese (ZH)",
    "ES" to "Spanish (ES)",
    "FR" to "French (FR)",
    "DE" to "German (DE)",
    "IT" to "Italian (IT)",
    "PT" to "Portuguese (PT)",
    "RU" to "Russian (RU)",
)

@Composable
private fun SettingsRow(title: String, description: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (maxWidth < 500.dp * LocalDensity.current.fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        }
    }
}

@Composable
private fun DropdownSelector(selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) {
            Text(options.firstOrNull { it.first.equals(selected, ignoreCase = true) }?.second ?: selected)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(key)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Per-language enable list, matching the Android Romanization settings screen. The stored value is a
 * comma-separated "Language:true/false" string; an empty value means every supported language is on.
 */
@Composable
private fun RomanizationLanguageList(saved: String, onChange: (String) -> Unit) {
    val languages = Romanization.SUPPORTED_LANGUAGES
    val initial = remember(saved) {
        val savedMap = saved.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) null else parts[0].trim() to parts[1].trim().toBooleanStrictOrNull()
        }.toMap()
        languages.map { it to (savedMap[it] ?: true) }
    }
    val states = remember(initial) { mutableStateListOf(*initial.toTypedArray()) }
    val parentState = when {
        states.all { it.second } -> ToggleableState.On
        states.none { it.second } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Romanization languages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose which languages are romanized offline. Detection is song-aware, so Japanese Kanji lines are not mistaken for Chinese",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TriStateCheckbox(
                state = parentState,
                onClick = {
                    val newState = parentState != ToggleableState.On
                    states.forEachIndexed { index, (language, _) -> states[index] = language to newState }
                    onChange(states.joinToString(",") { (lang, checked) -> "$lang:$checked" })
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        states.forEachIndexed { index, (language, checked) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    ROMANIZATION_LANGUAGE_LABELS[language] ?: language,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Checkbox(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        states[index] = language to isChecked
                        onChange(states.joinToString(",") { (lang, c) -> "$lang:$c" })
                    },
                )
            }
        }
    }
}

/**
 * Dedicated Lyrics settings composable designed for seamless integration into SettingsScreen.
 */
@Composable
fun LyricsSettings() {
    val s by Stores.settings.state.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "Lyrics Romanization & Translation",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        SettingsRow(
            title = "Romanize lyrics",
            description = "Show an offline romanized secondary line for Japanese, Korean, Chinese, Hindi, Punjabi and Cyrillic-script languages",
        ) {
            Switch(
                checked = s.lyricsRomanize,
                onCheckedChange = { enabled ->
                    Stores.settings.update { it.copy(lyricsRomanize = enabled) }
                },
            )
        }

        if (s.lyricsRomanize) {
            SettingsRow(
                title = "Romanization mode",
                description = "Auto-detect romanizes any enabled language per song; the CJK options force a single language",
            ) {
                DropdownSelector(
                    selected = s.lyricsRomanizationLanguage,
                    options = ROMANIZATION_OPTIONS,
                    onSelect = { lang ->
                        Stores.settings.update { it.copy(lyricsRomanizationLanguage = lang) }
                    },
                )
            }

            RomanizationLanguageList(
                saved = s.lyricsRomanizeList,
                onChange = { encoded -> Stores.settings.update { it.copy(lyricsRomanizeList = encoded) } },
            )

            SettingsRow(
                title = "Romanization as main line",
                description = "Show the romanized text as the primary line and the original script as the secondary line",
            ) {
                Switch(
                    checked = s.lyricsRomanizeAsMain,
                    onCheckedChange = { enabled ->
                        Stores.settings.update { it.copy(lyricsRomanizeAsMain = enabled) }
                    },
                )
            }

            SettingsRow(
                title = "Detect Cyrillic per line",
                description = "Detect the Cyrillic language line-by-line instead of using the whole song, for mixed-script lyrics",
            ) {
                Switch(
                    checked = s.lyricsRomanizeCyrillicByLine,
                    onCheckedChange = { enabled ->
                        Stores.settings.update { it.copy(lyricsRomanizeCyrillicByLine = enabled) }
                    },
                )
            }
        }

        SettingsRow(
            title = "Translate lyrics",
            description = "Opt-in: enable on-demand translation of lyrics using an official translation service",
        ) {
            Switch(
                checked = s.lyricsTranslate,
                onCheckedChange = { enabled ->
                    Stores.settings.update { it.copy(lyricsTranslate = enabled) }
                },
            )
        }

        if (s.lyricsTranslate) {
            SettingsRow(
                title = "Translation provider",
                description = "DeepL needs your own API key; Google Translate is free and needs no key",
            ) {
                DropdownSelector(
                    selected = s.lyricsTranslationProvider,
                    options = TranslationProvider.entries.map { it.id to it.label },
                    onSelect = { prov ->
                        Stores.settings.update { it.copy(lyricsTranslationProvider = prov) }
                    },
                )
            }

            SettingsRow(
                title = "Translation target language",
                description = "Language used when requesting lyrics translation",
            ) {
                DropdownSelector(
                    selected = s.lyricsTargetLanguage,
                    options = TARGET_LANGUAGE_OPTIONS,
                    onSelect = { target ->
                        Stores.settings.update { it.copy(lyricsTargetLanguage = target) }
                    },
                )
            }

            if (TranslationProvider.fromId(s.lyricsTranslationProvider).requiresApiKey) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DeepL API Key", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Key ending with ':fx' automatically routes to api-free.deepl.com; otherwise api.deepl.com. Masked and stored locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = s.lyricsTranslationKey,
                            onValueChange = { key ->
                                Stores.settings.update { it.copy(lyricsTranslationKey = key.trim()) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            placeholder = { Text("Enter DeepL Auth Key") },
                            trailingIcon = {
                                IconButton({ showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide key" else "Show key",
                                    )
                                }
                            },
                        )
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(
                            "Google Translate uses the public translate.googleapis.com endpoint. No API key is needed, but lyrics are sent to Google in plain text and the free endpoint has no uptime or quota guarantee.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    "Lyrics are translated strictly on demand when you click 'Translate' in the player lyrics view. Translation is never sent automatically on startup. DeepL uses your personal quota; Google Translate is free but sends lyrics to Google's public endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsRow(
            title = "Translation cache",
            description = "Clear in-memory cached translations for current session",
        ) {
            OutlinedButton(onClick = {
                LyricsTranslation.clearCache()
                cacheCleared = true
            }) {
                Text(if (cacheCleared) "Cache cleared" else "Clear cache")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
