package com.utaloom.playback

import com.utaloom.data.Library
import com.utaloom.data.PersistedQueue
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.WatchEndpoint
import com.metrolist.innertubex.extraction.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 80,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queueTitle: String? = null,
    val error: String? = null,
    val sleepTimerEndsAt: Long? = null,
    val speed: Float = 1f,
    val pitch: Int = 0,
) {
    val current: Song? get() = queue.getOrNull(index)
    val hasNext get() = index < queue.lastIndex || repeat == RepeatMode.ALL
}

/** Listener used by Listen Together to observe local user actions (only fired for user-initiated changes). */
interface PlayerHooks {
    fun onTrackChanged(song: Song, queue: List<Song>, index: Int) {}
    /** Fired whenever audio actually starts/resumes playing. */
    fun onPlaying(positionMs: Long) {}
    fun onPause(positionMs: Long) {}
    fun onSeek(positionMs: Long) {}
    fun onQueueChanged(queue: List<Song>, index: Int) {}
    fun onBufferReady(songId: String) {}
    fun onVolume(volume: Int) {}
}

object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile var hooks: PlayerHooks? = null
    /** When true, playback controls are driven remotely (Listen Together guest). */
    @Volatile var remoteControlled: Boolean = false

    @Volatile private var mp: AudioEngine? = null
    private var loadJob: Job? = null
    private var retryJob: Job? = null
    private var radioJob: Job? = null
    private var persistJob: Job? = null
    private val generation = AtomicLong()
    @Volatile private var loadedGeneration = -1L
    private var events: AudioEngine.Listener? = null
    private var sleepJob: Job? = null
    private var originalOrder: List<Song>? = null
    private var smartShuffleJob: Job? = null
    private var smartShuffleRevision = 0L
    @Volatile private var smartShuffleIds = emptySet<String>()
    @Volatile private var gain = 1.0
    @Volatile private var pendingSeek: Long? = null
    @Volatile private var playWhenReady = true
    private var retried = false
    @Volatile private var loadedId: String? = null
    private var radioEndpoint: WatchEndpoint? = null
    private var radioContinuation: String? = null
    data class PreparedTrack(val song: Song, val engine: AudioEngine, val gen: Long)
    @Volatile private var prepared: PreparedTrack? = null
    @Volatile private var fading: AudioEngine? = null
    @Volatile private var crossfading = false
    private var audioJob: Job? = null
    private var backendPitch = 0
    private var listeningSong: Song? = null
    private var listeningTick = 0L
    private var listeningActive = false
    private var listenedPending = 0L

    val available: Boolean get() = mp != null

    @Synchronized fun init(): Boolean {
        if (mp != null) return true
        return runCatching {
            val s = Stores.settings.value
            backendPitch = s.playbackPitch.coerceIn(-12, 12)
            mp = createAudioEngine(backendPitch)
            _state.update { it.copy(volume = s.volume, speed = validSpeed(s.playbackSpeed), pitch = backendPitch) }
            restoreQueue()
            persistJob = scope.launch { positionPersistLoop() }
            audioJob = scope.launch { audioLoop() }
            true
        }.getOrElse {
            System.err.println("Audio backend unavailable: ${it.message}")
            _state.update { s -> s.copy(error = "Audio playback is unavailable.") }
            false
        }
    }

    fun release() {
        val old: AudioEngine?
        synchronized(this) {
            persist()
            finishListening()
            cancelTransitions()
            cancelSmartShuffle()
            cancelLoading()
            sleepJob?.cancel(); persistJob?.cancel(); audioJob?.cancel()
            old = mp
            mp = null; events = null
        }
        // release may wait for backend event dispatch, so never hold Player's monitor here.
        old?.release()
    }

    private fun cancelLoading() {
        generation.incrementAndGet()
        loadJob?.cancel(); retryJob?.cancel(); radioJob?.cancel()
        loadedGeneration = -1
        loadedId = null
        loadingId = null
        pendingSeek = null
    }

    // region queue

    @Synchronized fun playQueue(songs: List<Song>, startIndex: Int = 0, title: String? = null, radio: WatchEndpoint? = null, fromRemote: Boolean = false, play: Boolean = true, startAt: Long = 0, shuffle: Boolean = false) {
        if (songs.isEmpty() || remoteControlled && !fromRemote) return
        cancelSmartShuffle()
        smartShuffleIds = emptySet()
        originalOrder = null
        radioEndpoint = radio
        radioContinuation = null
        _state.update { it.copy(queue = songs, index = startIndex.coerceIn(songs.indices), queueTitle = title, shuffle = false) }
        load(play = play, notify = !fromRemote, startAt = startAt)
        if (shuffle && !fromRemote) toggleShuffle()
    }

    /** Play a single song and fill the queue with a YouTube Music radio for it. */
    fun playRadio(song: Song) {
        if (remoteControlled) return
        playQueue(listOf(song), 0, title = "Radio: ${song.title}", radio = WatchEndpoint(videoId = song.id))
        radioJob = scope.launch { extendRadio() }
    }

    @Synchronized fun startRadio(endpoint: WatchEndpoint, title: String?) {
        if (remoteControlled) return
        removeSmartShuffleRecommendations()
        radioJob?.cancel()
        val gen = generation.get()
        radioJob = scope.launch {
            YouTube.next(endpoint).onSuccess { r ->
                currentCoroutineContext().ensureActive()
                val songs = r.items.map { it.toSong() }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (remoteControlled || gen != generation.get()) return@withContext
                    playQueue(songs, r.currentIndex ?: 0, title ?: r.title, radio = r.endpoint)
                    radioContinuation = r.continuation
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (gen == generation.get()) _state.update { it.copy(error = e.message?.takeIf { message -> message.isNotBlank() } ?: "Couldn't load radio.") }
            }
        }
    }

    private suspend fun extendRadio() {
        if (remoteControlled) return
        val ep = radioEndpoint ?: return
        val gen = generation.get()
        YouTube.next(ep, radioContinuation).onSuccess { r ->
            currentCoroutineContext().ensureActive()
            if (remoteControlled || gen != generation.get()) return@onSuccess
            radioContinuation = r.continuation
            val have = _state.value.queue.map { it.id }.toSet()
            val add = r.items.map { it.toSong() }.filter { it.id !in have }
            if (add.isNotEmpty()) {
                _state.update { it.copy(queue = it.queue + add) }
                notifyQueue()
            }
            if (r.continuation == null) radioEndpoint = null
        }
    }

    fun playNext(songs: List<Song>) = mutateQueue { q, i ->
        val clean = songs.filter { s -> q.none { it.id == s.id } || q.getOrNull(i)?.id == s.id }.filter { it.id != q.getOrNull(i)?.id }
        val rest = q.toMutableList().apply { removeAll { s -> clean.any { it.id == s.id } } }
        val ci = rest.indexOfFirst { it.id == q.getOrNull(i)?.id }.coerceAtLeast(0)
        rest.addAll(if (rest.isEmpty()) 0 else ci + 1, clean)
        rest to ci
    }

    fun addToQueue(songs: List<Song>) = mutateQueue { q, i ->
        (q + songs.filter { s -> q.none { it.id == s.id } }) to i
    }

    @Synchronized fun removeFromQueue(pos: Int) {
        if (remoteControlled) return
        val s = _state.value
        if (pos !in s.queue.indices) return
        if (pos == s.index) {
            if (s.queue.size == 1) return stop()
            _state.update { it.copy(queue = it.queue.toMutableList().apply { removeAt(pos) }, index = pos.coerceAtMost(it.queue.size - 2)) }
            load(play = playWhenReady)
        } else {
            mutateQueue { q, i -> q.toMutableList().apply { removeAt(pos) } to if (pos < i) i - 1 else i }
        }
    }

    fun moveInQueue(from: Int, to: Int) = mutateQueue { q, i ->
        if (from !in q.indices || i !in q.indices) return@mutateQueue q to i
        val list = q.toMutableList()
        val cur = list[i]
        list.add(to.coerceIn(0, list.lastIndex), list.removeAt(from))
        list to list.indexOf(cur)
    }

    @Synchronized fun clearQueue() {
        if (remoteControlled) return
        cancelSmartShuffle()
        mutateQueue { q, i -> listOfNotNull(q.getOrNull(i)) to 0 }
    }

    /** Replace queue keeping the current song (used for Listen Together guest syncing). */
    @Synchronized fun setQueueFromRemote(songs: List<Song>, currentId: String?) {
        cancelSmartShuffle()
        smartShuffleIds = emptySet()
        originalOrder = null
        val idx = songs.indexOfFirst { it.id == currentId }
        _state.update { it.copy(queue = songs, index = if (idx >= 0) idx else it.index.coerceAtMost(songs.lastIndex), shuffle = false) }
    }

    @Synchronized private fun mutateQueue(f: (List<Song>, Int) -> Pair<List<Song>, Int>) {
        if (remoteControlled) return
        _state.update { s -> f(s.queue, s.index).let { (q, i) -> s.copy(queue = q, index = i) } }
        notifyQueue()
    }

    private fun notifyQueue() {
        persist()
        if (!remoteControlled) _state.value.let { hooks?.onQueueChanged(it.queue, it.index) }
    }

    @Synchronized fun toggleShuffle() {
        if (remoteControlled) return
        val s = _state.value
        if (s.queue.isEmpty()) return
        val cur = s.current ?: return
        if (!s.shuffle) {
            originalOrder = s.queue
            val rest = s.queue.filterIndexed { i, _ -> i != s.index }.shuffled()
            _state.update { it.copy(queue = listOf(cur) + rest, index = 0, shuffle = true) }
            startSmartShuffle()
        } else {
            removeSmartShuffleRecommendations()
            val queue = _state.value.queue
            val orig = originalOrder ?: s.queue
            val merged = orig.filter { o -> queue.any { it.id == o.id } } + queue.filter { q -> orig.none { it.id == q.id } }
            _state.update { it.copy(queue = merged, index = merged.indexOfFirst { m -> m.id == cur.id }.coerceAtLeast(0), shuffle = false) }
            originalOrder = null
        }
        notifyQueue()
    }

    @Synchronized fun setSmartShuffle(enabled: Boolean) {
        Stores.settings.update { it.copy(smartShuffle = enabled) }
        if (remoteControlled) return
        if (enabled) startSmartShuffle() else removeSmartShuffleRecommendations()
    }

    private fun cancelSmartShuffle() {
        smartShuffleRevision++
        smartShuffleJob?.cancel()
        smartShuffleJob = null
    }

    private fun removeSmartShuffleRecommendations() {
        cancelSmartShuffle()
        if (smartShuffleIds.isEmpty()) return
        val s = _state.value
        val queue = s.queue.filter { it.id !in smartShuffleIds || it.id == s.current?.id }
        _state.update { it.copy(queue = queue, index = queue.indexOfFirst { song -> song.id == s.current?.id }) }
        smartShuffleIds = smartShuffleIds.intersect(setOfNotNull(s.current?.id))
        notifyQueue()
    }

    // ponytail: one bounded radio batch per finite queue, paginate only if continuous discovery is needed.
    private fun startSmartShuffle() {
        val s = _state.value
        if (remoteControlled || !Stores.settings.value.smartShuffle || !s.shuffle || radioEndpoint != null ||
            smartShuffleJob?.isActive == true || s.queue.any { it.id in smartShuffleIds }
        ) return
        val seed = s.current ?: return
        val revision = smartShuffleRevision
        val count = (s.queue.size / 3).coerceIn(1, 30)
        smartShuffleJob = scope.launch {
            try {
                val result = withTimeout(30_000) {
                    YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")).getOrThrow()
                }
                currentCoroutineContext().ensureActive()
                synchronized(Player) {
                    val live = _state.value
                    if (revision != smartShuffleRevision || remoteControlled || !live.shuffle ||
                        !Stores.settings.value.smartShuffle || live.current == null || radioEndpoint != null
                    ) return@synchronized
                    val have = live.queue.map { it.id }.toSet()
                    val additions = result.items.map { it.toSong() }
                        .filter { it.id !in have && (!Stores.settings.value.hideExplicit || !it.explicit) }
                        .distinctBy { it.id }.take(count)
                    if (additions.isEmpty()) return@synchronized
                    val queue = live.queue.take(live.index + 1).toMutableList()
                    val recommendations = additions.iterator()
                    live.queue.drop(live.index + 1).forEachIndexed { i, song ->
                        queue += song
                        if ((i + 1) % 3 == 0 && recommendations.hasNext()) queue += recommendations.next()
                    }
                    recommendations.forEachRemaining { queue += it }
                    smartShuffleIds = additions.map { it.id }.toSet()
                    _state.update { it.copy(queue = queue) }
                    notifyQueue()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Smart shuffle unavailable, keeping normal shuffle: ${e.message}")
            }
        }
    }

    fun cycleRepeat() {
        if (!remoteControlled) _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
    }

    // endregion

    // region transport

    @Synchronized fun playPause() {
        if (remoteControlled) return
        val s = _state.value
        if (s.current == null) return
        if (s.isPlaying || s.isBuffering && playWhenReady) pause() else play()
    }

    @Synchronized fun play(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        playWhenReady = true
        val cur = _state.value.current ?: return
        if (loadedId != cur.id) {
            // Still resolving this song: it will start playing once ready.
            if (loadJob?.isActive == true && loadingId == cur.id) return
            return load(play = true, notify = false, startAt = _state.value.positionMs)
        }
        val gen = generation.get()
        p.submit { if (gen == generation.get() && p === mp) p.play() }
    }

    @Synchronized fun pause(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        playWhenReady = false
        val gen = generation.get()
        p.submit { if (gen == generation.get() && p === mp) p.setPause(true) }
        _state.update { it.copy(isPlaying = false) }
        if (!fromRemote) hooks?.onPause(_state.value.positionMs)
    }

    @Synchronized fun seekTo(ms: Long, fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        val target = ms.coerceAtLeast(0)
        _state.update { it.copy(positionMs = target) }
        if (loadedId != _state.value.current?.id || !p.isSeekable) pendingSeek = target
        else {
            val gen = generation.get()
            p.submit { if (gen == generation.get() && p === mp) p.seek(target) }
        }
        if (!fromRemote) hooks?.onSeek(target)
    }

    @Synchronized fun next(auto: Boolean = false) {
        if (remoteControlled) return
        val s = _state.value
        when {
            auto && s.repeat == RepeatMode.ONE -> { seekTo(0, fromRemote = true); play(fromRemote = true); return }
            s.index < s.queue.lastIndex -> _state.update { it.copy(index = it.index + 1) }
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> _state.update { it.copy(index = 0) }
            else -> {
                if (auto) { pause(fromRemote = true); seekTo(0, fromRemote = true) }
                return
            }
        }
        load(play = true)
        if (_state.value.queue.size - _state.value.index < 4) radioJob = scope.launch { extendRadio() }
    }

    @Synchronized fun previous() {
        if (remoteControlled) return
        val s = _state.value
        if (s.current == null) return
        if (s.positionMs > 3000 || s.index == 0) { seekTo(0); return }
        _state.update { it.copy(index = it.index - 1) }
        load(play = true)
    }

    @Synchronized fun selectQueueIndex(index: Int): Boolean {
        if (remoteControlled || index !in _state.value.queue.indices || index == _state.value.index) return false
        skipTo(index)
        return true
    }

    @Synchronized fun skipTo(index: Int, fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        load(play = !fromRemote || playWhenReady, notify = !fromRemote)
        if (_state.value.queue.size - _state.value.index < 4) radioJob = scope.launch { extendRadio() }
    }

    @Synchronized fun stop(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        cancelSmartShuffle()
        smartShuffleIds = emptySet()
        originalOrder = null
        finishListening()
        cancelTransitions()
        cancelLoading()
        sleepJob?.cancel()
        playWhenReady = false
        radioEndpoint = null; radioContinuation = null
        val gen = generation.get()
        mp?.let { p -> p.submit { if (gen == generation.get() && p === mp) p.stop() } }
        loadedId = null
        _state.update { PlayerState(volume = it.volume, speed = it.speed, pitch = it.pitch) }
        persist()
    }

    fun setVolume(v: Int, fromRemote: Boolean = false) {
        val vol = v.coerceIn(0, 100)
        _state.update { it.copy(volume = vol, muted = false) }
        applyVolume()
        Stores.settings.update { it.copy(volume = vol) }
        if (!fromRemote) hooks?.onVolume(vol)
    }

    fun toggleMute() {
        _state.update { it.copy(muted = !it.muted) }
        applyVolume()
    }

    private fun validSpeed(value: Float) = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f

    fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..2f) { "Speed must be between 0.5x and 2x" }
        if (remoteControlled || hooks != null) return
        Stores.settings.update { it.copy(playbackSpeed = speed) }
        _state.update { it.copy(speed = speed) }
        updateAudioEffects()
    }

    fun setPitch(semitones: Int) {
        require(semitones in -12..12) { "Pitch must be between -12 and +12 semitones" }
        if (remoteControlled || hooks != null) return
        Stores.settings.update { it.copy(playbackPitch = semitones) }
        // VLC's pitch filter is a factory option. The audio loop rebuilds at the live seek position.
    }

    fun updateAudioEffects() {
        val settings = Stores.settings.value
        val speed = if (remoteControlled || hooks != null) 1f else validSpeed(settings.playbackSpeed)
        _state.update { it.copy(speed = speed) }
        listOfNotNull(mp, prepared?.engine, fading).forEach { engine ->
            engine.submit {
                if (engine === mp || engine === prepared?.engine || engine === fading) {
                    engine.setEqualizer(settings.equalizerEnabled,
                        settings.equalizerPreamp.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f,
                        List(10) { settings.equalizerBands.getOrNull(it)?.takeIf { n -> n.isFinite() }?.coerceIn(-20f, 20f) ?: 0f })
                    engine.setRate(speed)
                }
            }
        }
    }

    private fun applyVolume() {
        val p = mp ?: return
        val s = _state.value
        val v = if (s.muted) 0 else (s.volume * gain).toInt().coerceIn(0, 150)
        p.submit { p.setVolume(v) }
    }

    fun setSleepTimer(minutes: Int?) {
        if (remoteControlled && minutes != null) return
        require(minutes == null || minutes in 1..1440) { "Sleep timer must be between 1 minute and 24 hours" }
        sleepJob?.cancel()
        if (minutes == null) { _state.update { it.copy(sleepTimerEndsAt = null) }; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndsAt = end) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepTimerEndsAt = null) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // endregion

    // region loading

    @Synchronized private fun load(play: Boolean, notify: Boolean = true, startAt: Long = 0, countHistory: Boolean = true) {
        val song = _state.value.current ?: return
        val p = mp ?: return
        if (countHistory) finishListening() else accountListening()
        cancelTransitions()
        loadJob?.cancel()
        retryJob?.cancel()
        val gen = generation.incrementAndGet()
        playWhenReady = play
        retried = false
        loadedId = null
        loadedGeneration = -1
        loadingId = song.id
        readyFor = null
        pendingSeek = startAt.takeIf { it > 0 }
        _state.update { it.copy(isPlaying = false, isBuffering = true, positionMs = startAt, durationMs = (song.duration ?: 0) * 1000L, error = null) }
        if (countHistory) Library.addHistory(song)
        if (notify && !remoteControlled) _state.value.let { hooks?.onTrackChanged(song, it.queue, it.index) }
        persist()
        loadJob = scope.launch { startMedia(p, song, gen) }
    }

    private fun currentLoad(p: AudioEngine, songId: String, gen: Long): Boolean =
        p === mp && gen == generation.get() && _state.value.current?.id == songId

    private suspend fun startMedia(p: AudioEngine, song: Song, gen: Long) {
        val local = Stores.library.value.downloaded[song.id]?.let(::File)?.takeIf { it.exists() }
        val (mrl, headers) = if (local != null) {
            gain = 1.0
            local.absolutePath to emptyMap()
        } else {
            val quality = runCatching { AudioQuality.valueOf(Stores.settings.value.audioQuality) }.getOrDefault(AudioQuality.AUTO)
            val stream = runCatching { StreamResolver.resolve(song.id, quality, allowBoundedRange = false) }.getOrElse { e ->
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                if (!currentLoad(p, song.id, gen)) return
                _state.update { it.copy(isBuffering = false, error = "Can't play \"${song.title}\": ${e.message}") }
                delay(1500)
                if (currentLoad(p, song.id, gen)) next(auto = true)
                return
            }
            currentCoroutineContext().ensureActive()
            if (!currentLoad(p, song.id, gen)) return
            gain = if (Stores.settings.value.normalizeVolume) stream.loudnessDb?.let { 10.0.pow(-it / 20.0).coerceAtMost(1.0) } ?: 1.0 else 1.0
            lastClient = stream.clientName
            stream.audioUrl to stream.headers
        }
        currentCoroutineContext().ensureActive()
        if (!currentLoad(p, song.id, gen)) return
        p.submit {
            synchronized(Player) {
                if (!currentLoad(p, song.id, gen)) return@submit
                events?.let { p.removeListener(it) }
                val listener = Events(p, song.id, gen)
                events = listener
                p.addListener(listener)
                loadedId = song.id
                loadedGeneration = gen
                // start-paused avoids a burst of audio while a guest waits for the host's play message.
                val accepted = p.load(mrl, headers, startPaused = !playWhenReady)
                if (!accepted && currentLoad(p, song.id, gen)) onError(gen)
                if (currentLoad(p, song.id, gen)) {
                    applyVolume()
                    updateAudioEffects()
                    analyzeSilence(mrl, headers, song, gen)
                }
            }
        }
    }

    private var lastClient: String? = null
    @Volatile private var readyFor: String? = null
    @Volatile private var loadingId: String? = null

    @Synchronized private fun onError(gen: Long) {
        if (gen != generation.get()) return
        val song = _state.value.current ?: return
        lastClient?.let { StreamResolver.markFailed(song.id, it) }
        if (!retried) {
            retried = true
            val resume = _state.value.positionMs
            retryJob = scope.launch {
                StreamResolver.refreshAfterRejection()
                currentCoroutineContext().ensureActive()
                if (gen != generation.get()) return@launch
                pendingSeek = resume
                mp?.let { startMedia(it, song, gen) }
            }
        } else {
            _state.update { it.copy(isBuffering = false, error = "Playback failed for \"${song.title}\"") }
            retryJob = scope.launch { delay(1500); if (gen == generation.get()) next(auto = true) }
        }
    }

    private class Events(private val p: AudioEngine, private val songId: String, private val gen: Long) : AudioEngine.Listener {
        private fun active() = currentLoad(p, songId, gen) && loadedGeneration == gen && loadedId == songId

        /** A paused/prebuffered guest must signal ready even though it has never played. */
        private fun ready() {
            if (!active()) return
            val seek: Long?
            synchronized(Player) {
                if (!active() || readyFor == songId) return
                readyFor = songId
                seek = pendingSeek
                pendingSeek = null
            }
            _state.update { if (active()) it.copy(isBuffering = false) else it }
            if (seek != null) p.submit { if (active()) p.seek(seek) }
            hooks?.onBufferReady(songId)
        }

        override fun playing() {
            if (!active()) return
            val seek = pendingSeek
            _state.update { it.copy(isPlaying = playWhenReady, isBuffering = false) }
            ready()
            applyVolume()
            updateAudioEffects()
            if (playWhenReady) {
                synchronized(Player) { beginListening(_state.value.current) }
                if (!remoteControlled) hooks?.onPlaying(seek ?: p.time)
            } else {
                p.submit { if (active() && !playWhenReady) p.setPause(true) }
            }
        }

        override fun paused() {
            if (!active()) return
            synchronized(Player) { accountListening() }
            _state.update { it.copy(isPlaying = false) }
            ready()
        }
        override fun stopped() {
            if (active()) _state.update { it.copy(isPlaying = false) }
        }
        override fun buffering(percent: Float) {
            if (!active()) return
            _state.update { it.copy(isBuffering = percent < 100f) }
            if (percent >= 100f) ready()
        }
        override fun timeChanged(timeMs: Long) {
            if (active()) _state.update { it.copy(positionMs = timeMs) }
        }
        override fun lengthChanged(lengthMs: Long) {
            if (active() && lengthMs > 0) _state.update { it.copy(durationMs = lengthMs) }
        }
        override fun finished() {
            if (!active()) return
            synchronized(Player) { accountListening() }
            _state.update { it.copy(isPlaying = false) }
            scope.launch { if (active() && !usePrepared(crossfadeMs = 0)) next(auto = true) }
        }
        override fun error() { if (active()) onError(gen) }
    }

    // endregion

    // region persistence

    private fun persist() {
        val s = _state.value
        val songs = s.queue.filter { it.id !in smartShuffleIds || it.id == s.current?.id }.take(500)
        Stores.queue.update { PersistedQueue(songs, songs.indexOfFirst { it.id == s.current?.id }.coerceAtLeast(0), s.positionMs, s.queueTitle) }
    }

    private suspend fun positionPersistLoop() {
        while (scope.isActive) {
            delay(5000)
            if (_state.value.isPlaying) persist()
        }
    }

    @Synchronized private fun beginListening(song: Song?) {
        if (song == null) return
        if (listeningSong == null) {
            listeningSong = song
            listenedPending = 0
        }
        listeningTick = System.nanoTime()
        listeningActive = true
    }

    /** Wall-clock audio time, not seek position. Buffering and paused intervals never count. */
    @Synchronized private fun accountListening() {
        val now = System.nanoTime()
        val s = _state.value
        val active = s.isPlaying && !s.isBuffering && listeningSong != null
        if (listeningActive && active && listeningTick != 0L) {
            listenedPending += ((now - listeningTick) / 1_000_000).coerceIn(0, 2_000)
        }
        listeningTick = now
        listeningActive = active
        if (listenedPending >= 1000) flushListening()
    }

    private fun flushListening() {
        val song = listeningSong ?: return
        if (listenedPending <= 0) return
        Library.recordListening(song, listenedPending)
        listenedPending = 0
    }

    @Synchronized private fun finishListening() {
        accountListening()
        flushListening()
        listeningSong = null
        listeningActive = false
        listeningTick = 0
    }

    private suspend fun audioLoop() {
        var previousEffects: Triple<Boolean, Float, List<Float>>? = null
        var previousSpeed = Float.NaN
        while (scope.isActive && mp != null) {
            delay(100)
            accountListening()
            val settings = Stores.settings.value
            val room = remoteControlled || hooks != null
            val speed = if (room) 1f else validSpeed(settings.playbackSpeed)
            val effects = Triple(settings.equalizerEnabled, settings.equalizerPreamp, settings.equalizerBands)
            if (speed != previousSpeed || effects != previousEffects) {
                previousSpeed = speed; previousEffects = effects
                updateAudioEffects()
            }
            val pitch = if (room) 0 else settings.playbackPitch.coerceIn(-12, 12)
            if (pitch != backendPitch) rebuildAudio(pitch)
            advanceAudio()
        }
    }

    private suspend fun rebuildAudio(pitch: Int) {
        val old = mp ?: return
        val replacement = runCatching { createAudioEngine(pitch) }.getOrElse {
            _state.update { s -> s.copy(error = "Couldn't change pitch. Current audio was kept.") }
            Stores.settings.update { it.copy(playbackPitch = backendPitch) }
            return
        }
        val swapped = synchronized(Player) {
            val desired = if (remoteControlled || hooks != null) 0 else Stores.settings.value.playbackPitch.coerceIn(-12, 12)
            if (old !== mp || pitch != desired) false else {
                events?.let { old.removeListener(it) }
                events = null
                mp = replacement
                backendPitch = pitch
                _state.update { it.copy(pitch = pitch) }
                load(playWhenReady, notify = false, startAt = _state.value.positionMs, countHistory = false)
                true
            }
        }
        if (swapped) old.release() else replacement.release()
    }

    @Synchronized fun restoreAfterBackup() {
        stop(fromRemote = true)
        restoreQueue()
        Stores.settings.value.let { s ->
            setVolume(s.volume)
            updateAudioEffects()
        }
    }

    private fun cancelTransitions() {
        prepared?.engine?.release()
        prepared = null
        fading?.release()
        fading = null
        crossfading = false
    }

    private fun analyzeSilence(mrl: String, headers: Map<String, String>, song: Song, gen: Long) {
        if (!Stores.settings.value.skipSilence) return
    }

    private fun usePrepared(crossfadeMs: Long): Boolean {
        return false
    }

    private fun advanceAudio() {
        val s = _state.value
        val settings = Stores.settings.value
        if (remoteControlled || hooks != null || !s.isPlaying || s.durationMs <= 0) return
        val remaining = s.durationMs - s.positionMs
        if (settings.crossfadeSeconds > 0 && remaining in 300..((settings.crossfadeSeconds * 1000L).coerceAtLeast(1000L))) {
            if (!crossfading && s.hasNext) {
                crossfading = true
                scope.launch {
                    next(auto = true)
                    crossfading = false
                }
            }
        }
    }

    private fun restoreQueue() {
        val q = Stores.queue.value
        if (q.songs.isEmpty()) return
        _state.update { it.copy(queue = q.songs, index = q.index.coerceIn(q.songs.indices), positionMs = q.positionMs, queueTitle = q.title) }
        pendingSeek = q.positionMs
        playWhenReady = false
    }

    // endregion
}
