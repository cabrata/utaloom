package com.utaloom.data

import com.utaloom.lyrics.TranslationProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.time.LocalDate

@Serializable
data class BackupDocument(
    val format: String = "utaloom-desktop",
    val version: Int = 1,
    val library: LibraryState,
    val settings: JsonObject = JsonObject(emptyMap()),
    val queue: PersistedQueue = PersistedQueue(),
)

/** Portable JSON only, not Android's database ZIP or an archive of downloaded audio. */
object Backup {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ITEMS = 100_000
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")

    // ponytail: fail-closed preference allowlist. New preferences need review here before export.
    // Cookie/account/visitorData/dataSyncId, all API keys/secrets/tokens/sessions/usernames,
    // proxy and custom endpoints are intentionally never copied, even on restore.
    private val safePreferences = setOf(
        "darkMode", "dynamicColor", "audioQuality", "volume", "normalizeVolume", "smartShuffle",
        "useLoginForBrowse", "contentLanguage", "contentCountry", "hideExplicit", "pauseHistory", "pauseSearchHistory",
        "ltAutoApproveJoins", "ltAutoApproveSuggestions", "ltSyncVolume", "lyricsOrder", "lyricsDisabled", "lyricsTextSize", "minimizeToTray",
        "equalizerEnabled", "equalizerPreset", "equalizerPreamp", "equalizerBands", "crossfadeSeconds", "gaplessPlayback", "skipSilence",
        "playbackSpeed", "playbackPitch", "lastFmEnabled", "listenBrainzEnabled", "discordEnabled",
        "lyricsRomanize", "lyricsRomanizationLanguage", "lyricsTranslateLanguage", "lyricsTranslationProvider",
        "miniPlayerAlwaysOnTop", "globalHotkeysEnabled", "globalHotkeys",
    )

    fun encode(
        library: LibraryState = Stores.library.value,
        settings: Settings = Stores.settings.value,
        queue: PersistedQueue = Stores.queue.value,
    ): String {
        val document = BackupDocument(library = portable(library), settings = safe(settings), queue = queue)
        validate(document)
        val root = json.encodeToJsonElement(BackupDocument.serializer(), document).jsonObject
        val libraryJson = root.getValue("library").jsonObject
        val text = json.encodeToString(JsonObject.serializer(), JsonObject(root + ("library" to JsonObject(libraryJson - "downloaded" - "downloadedSongs"))))
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 20 MiB limit" }
        return text
    }

    fun decode(text: String): BackupDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 20 MiB limit" }
        val root = try { json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject }
        catch (e: Exception) { throw IllegalArgumentException("Invalid Utaloom desktop backup JSON", e) }
        require(root["format"] == JsonPrimitive("utaloom-desktop") && root["version"] == JsonPrimitive(1) && root["library"] is JsonObject) { "Unsupported backup format or version" }
        val document = try { json.decodeFromJsonElement(BackupDocument.serializer(), root) }
        catch (e: Exception) { throw IllegalArgumentException("Invalid Utaloom desktop backup JSON", e) }
        validate(document)
        // Importing an edited/older backup can never import credentials or download locations.
        return document.copy(library = portable(document.library), settings = JsonObject(document.settings.filterKeys { it in safePreferences }))
    }

    fun read(file: File): BackupDocument {
        require(file.isFile && file.length() <= MAX_BYTES) { "Choose a desktop backup smaller than 20 MiB" }
        val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "Backup exceeds the 20 MiB limit" }
        return decode(bytes.toString(Charsets.UTF_8))
    }

    /** Repeated merges are idempotent: retain local ordering/metadata and the larger counters. */
    fun merge(current: LibraryState, incoming: LibraryState): LibraryState {
        fun songs(a: List<Song>, b: List<Song>) = (a + b).distinctBy { it.id }
        fun refs(a: List<SavedRef>, b: List<SavedRef>) = (a + b).distinctBy { it.id }
        fun <T : Comparable<T>> counters(a: Map<String, T>, b: Map<String, T>) =
            a + b.mapValues { (id, value) -> a[id]?.let { maxOf(it, value) } ?: value }
        val existing = current.playlists.associateBy { it.id }
        val imported = incoming.playlists.associateBy { it.id }
        return current.copy(
            liked = songs(current.liked, incoming.liked),
            history = songs(current.history, incoming.history),
            playlists = current.playlists.map { p ->
                imported[p.id]?.let { p.copy(songs = songs(p.songs, it.songs)) } ?: p
            } + incoming.playlists.filter { it.id !in existing },
            savedAlbums = refs(current.savedAlbums, incoming.savedAlbums),
            savedArtists = refs(current.savedArtists, incoming.savedArtists),
            savedPlaylists = refs(current.savedPlaylists, incoming.savedPlaylists),
            searchHistory = (current.searchHistory + incoming.searchHistory).distinct(),
            playCounts = counters(current.playCounts, incoming.playCounts),
            listenedMsBySong = counters(current.listenedMsBySong, incoming.listenedMsBySong),
            listeningByDay = counters(current.listeningByDay, incoming.listeningByDay),
            songCatalog = incoming.songCatalog + current.songCatalog,
        )
    }

    var onRestoreHook: ((restorePreferences: Boolean, restoreQueue: Boolean) -> Unit)? = null

    /** Merge only. The active queue, sign-ins and preferences are untouched unless explicitly opted in. */
    @Synchronized
    fun restore(document: BackupDocument, restorePreferences: Boolean = false, restoreQueue: Boolean = false) {
        validate(document)
        val oldLibrary = Stores.library.value
        val oldSettings = Stores.settings.value
        val oldQueue = Stores.queue.value
        val library = merge(oldLibrary, portable(document.library))
        val settings = if (restorePreferences) applySafeSettings(oldSettings, document.settings) else oldSettings
        val queue = if (restoreQueue) document.queue else oldQueue
        // Validate all proposed states before the first mutation, including preference overlays.
        validate(BackupDocument(library = portable(library), settings = safe(settings), queue = queue))
        // Automatic rollback snapshot on disk before replacing existing state.
        val rollbackFile = File(Paths.dir, "backup-rollback.json")
        com.utaloom.platform.writeTextAtomically(rollbackFile, encode(oldLibrary, oldSettings, oldQueue), MAX_BYTES)
        ListeningStats.stop()
        try {
            Stores.library.update { merge(it, portable(document.library)) }
            if (restorePreferences) Stores.settings.update { applySafeSettings(it, document.settings) }
            if (restoreQueue) Stores.queue.update { queue }
            Stores.library.flush()
            if (restorePreferences) Stores.settings.flush()
            if (restoreQueue) Stores.queue.flush()
            onRestoreHook?.invoke(restorePreferences, restoreQueue)
        } catch (failure: Exception) {
            Stores.library.update { oldLibrary }
            if (restorePreferences) Stores.settings.update { oldSettings }
            if (restoreQueue) Stores.queue.update { oldQueue }
            runCatching { Stores.library.flush() }.exceptionOrNull()?.let(failure::addSuppressed)
            if (restorePreferences) runCatching { Stores.settings.flush() }.exceptionOrNull()?.let(failure::addSuppressed)
            if (restoreQueue) runCatching { Stores.queue.flush() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            ListeningStats.start()
        }
    }

    private fun portable(library: LibraryState) = library.copy(
        downloaded = emptyMap(), downloadedSongs = emptyMap(),
        songCatalog = library.downloadedSongs + library.songCatalog,
    )

    private fun safe(settings: Settings) = JsonObject(json.encodeToJsonElement(Settings.serializer(), settings).jsonObject.filterKeys { it in safePreferences })

    private fun applySafeSettings(current: Settings, values: JsonObject): Settings {
        val reviewed = values.filterKeys { it in safePreferences }
        val root = json.encodeToJsonElement(Settings.serializer(), current).jsonObject
        return json.decodeFromJsonElement(Settings.serializer(), JsonObject(root + reviewed))
    }

    private fun text(value: String, max: Int, label: String, blank: Boolean = true) {
        require(value.length <= max && value.none { it.isISOControl() } && (blank || value.isNotBlank())) { "Invalid $label" }
    }

    private fun id(value: String) { require(idPattern.matches(value)) { "Invalid song or library ID" } }

    private fun thumbnail(value: String?) {
        if (value.isNullOrEmpty()) return
        text(value, 4096, "thumbnail URL")
        val url = runCatching { URI(value) }.getOrNull()
        require(url != null && url.scheme in listOf("http", "https") && !url.host.isNullOrBlank() && url.userInfo == null) { "Invalid thumbnail URL" }
    }

    private fun song(song: Song) {
        id(song.id)
        text(song.title, 2000, "song title", blank = false)
        require(song.artists.size <= 100 && song.artistIds.size <= 100) { "Too many artists" }
        song.artists.forEach { text(it, 500, "artist") }
        song.artistIds.filterNotNull().forEach(::id)
        song.album?.let { text(it, 2000, "album") }
        song.albumId?.let(::id)
        require(song.duration == null || song.duration in 0..604_800) { "Invalid song duration" }
        thumbnail(song.thumbnail)
    }

    private fun validate(document: BackupDocument) {
        require(document.format == "utaloom-desktop" && document.version == 1) { "Unsupported backup format or version" }
        val lib = document.library
        val lists = listOf(lib.liked, lib.history, lib.playlists.flatMap { it.songs }, lib.downloadedSongs.values.toList(), lib.songCatalog.values.toList(), document.queue.songs)
        require(lists.sumOf { it.size.toLong() } <= MAX_ITEMS && lib.playlists.size <= 20_000) { "Backup contains too many songs or playlists" }
        lists.flatten().forEach(::song)
        listOf(lib.liked, lib.history).forEach { songs ->
            require(songs.distinctBy { it.id }.size == songs.size) { "Duplicate library songs" }
        }
        require(lib.playlists.distinctBy { it.id }.size == lib.playlists.size) { "Duplicate playlist IDs" }
        lib.playlists.forEach { p ->
            id(p.id)
            text(p.name, 200, "playlist name", blank = false)
            require(p.songs.size <= 20_000 && p.songs.distinctBy { it.id }.size == p.songs.size) { "Invalid or duplicate playlist songs" }
        }
        listOf(lib.savedAlbums, lib.savedArtists, lib.savedPlaylists).forEach { refs ->
            require(refs.size <= MAX_ITEMS && refs.distinctBy { it.id }.size == refs.size) { "Invalid saved collection" }
            refs.forEach { r -> id(r.id); text(r.title, 2000, "saved title", blank = false); r.subtitle?.let { text(it, 2000, "subtitle") }; thumbnail(r.thumbnail); r.playlistId?.let(::id) }
        }
        require(lib.searchHistory.size <= MAX_ITEMS) { "Too many search entries" }
        lib.searchHistory.forEach { text(it, 2000, "search query") }
        require(listOf(lib.playCounts.size, lib.listenedMsBySong.size, lib.listeningByDay.size, lib.downloaded.size, lib.songCatalog.size).all { it <= MAX_ITEMS }) { "Backup contains too many statistics" }
        lib.playCounts.forEach { (key, count) -> id(key); require(count >= 0) { "Negative play count" } }
        lib.listenedMsBySong.forEach { (key, ms) -> id(key); require(ms >= 0) { "Negative listening time" } }
        lib.listeningByDay.forEach { (day, ms) ->
            require(runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false) && ms >= 0) { "Invalid daily listening time" }
        }
        lib.songCatalog.forEach { (key, value) -> require(key == value.id) { "Mismatched catalog ID" } }
        lib.downloadedSongs.forEach { (key, value) -> require(key == value.id) { "Mismatched download ID" } }
        require(document.queue.positionMs >= 0 && (if (document.queue.songs.isEmpty()) document.queue.index == 0 else document.queue.index in document.queue.songs.indices)) { "Invalid saved queue position" }
        document.queue.title?.let { text(it, 2000, "queue title") }
        val settings = applySafeSettings(Settings(), document.settings)
        require(settings.darkMode in listOf("system", "dark", "light") && settings.audioQuality in listOf("AUTO", "HIGH", "LOW") && settings.volume in 0..100) { "Invalid backup preferences" }
        require(settings.lyricsTextSize in 8..100 && settings.lyricsOrder.size <= 100 && settings.lyricsDisabled.size <= 100) { "Invalid lyrics preferences" }
        (settings.lyricsOrder + settings.lyricsDisabled).forEach { text(it, 100, "lyrics provider") }
        text(settings.contentLanguage, 32, "content language", blank = false)
        text(settings.contentCountry, 16, "content country", blank = false)
        // Validate optional newer fields without depending on their addition timing in Settings.
        val prefs = document.settings.filterKeys { it in safePreferences }
        fun number(key: String, range: ClosedFloatingPointRange<Double>) {
            prefs[key]?.let { value -> require(value.jsonPrimitive.doubleOrNull?.let { it.isFinite() && it in range } == true) { "Invalid $key preference" } }
        }
        number("equalizerPreamp", -20.0..20.0)
        number("crossfadeSeconds", 0.0..30.0)
        number("playbackSpeed", 0.25..4.0)
        number("playbackPitch", -12.0..12.0)
        prefs["equalizerBands"]?.let { value -> require(value.jsonArray.size == 10 && value.jsonArray.all { it.jsonPrimitive.doubleOrNull?.let { n -> n.isFinite() && n in -20.0..20.0 } == true }) { "Invalid equalizer bands" } }
        prefs["lyricsTranslationProvider"]?.let { value ->
            val id = value.jsonPrimitive.content
            require(TranslationProvider.entries.any { it.id == id }) { "Invalid lyrics translation provider" }
        }
        listOf("equalizerPreset", "lyricsRomanizationLanguage", "lyricsTranslateLanguage").forEach { key -> prefs[key]?.let { text(it.jsonPrimitive.content, 100, key) } }
        prefs["globalHotkeys"]?.let { value ->
            require(value.jsonObject.size <= 32) { "Too many global hotkeys" }
            value.jsonObject.forEach { (key, binding) -> text(key, 100, "hotkey action"); text(binding.jsonPrimitive.content, 100, "hotkey binding") }
        }
    }
}
