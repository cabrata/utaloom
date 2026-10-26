package com.utaloom.playback

import com.utaloom.data.Library
import com.utaloom.data.Song
import com.utaloom.data.Stores
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class PlayerQueueSelectionTest {
    @Test
    fun selectsOnlyValidLocalQueueIndexesThroughNormalTrackLoading() = runBlocking {
        val oldQueue = Stores.queue.value
        val oldLibrary = Stores.library.value
        val oldHooks = Player.hooks
        val oldRemoteControlled = Player.remoteControlled
        val media = Files.createTempFile("utaloom-queue-selection", ".audio").toFile()
        val engine = RecordingAudioEngine()
        val songs = listOf(
            Song("queue-select-1", "First", listOf("Artist")),
            Song("queue-select-2", "Second", listOf("Artist")),
        )

        try {
            Player.remoteControlled = false
            Player.hooks = null
            Player.release()
            Player.stop(fromRemote = true)
            Player::class.java.getDeclaredField("mp").apply { isAccessible = true }.set(null, engine)
            songs.forEach { Library.setDownloaded(it.id, media.absolutePath, it) }
            Player.playQueue(songs, play = false)
            withTimeout(2_000) { while (engine.loads.isEmpty()) delay(10) }

            val selected = mutableListOf<String>()
            Player.hooks = object : PlayerHooks {
                override fun onTrackChanged(song: Song, queue: List<Song>, index: Int) {
                    selected += song.id
                }
            }

            assertFalse(Player.selectQueueIndex(-1))
            assertFalse(Player.selectQueueIndex(songs.size))
            assertFalse(Player.selectQueueIndex(0))
            Player.remoteControlled = true
            assertFalse(Player.selectQueueIndex(1))
            assertEquals(0, Player.state.value.index)
            assertEquals(0, Stores.queue.value.index)
            assertTrue(selected.isEmpty())

            Player.remoteControlled = false
            assertTrue(Player.selectQueueIndex(1))
            withTimeout(2_000) { while (engine.loads.none { it.url == media.absolutePath && !it.startPaused }) delay(10) }
            assertEquals(songs[1], Player.state.value.current)
            assertEquals(listOf(songs[1].id), selected)
            assertEquals(songs[1].id, Stores.queue.value.songs[Stores.queue.value.index].id)

            val radioJobField = Player::class.java.getDeclaredField("radioJob").apply { isAccessible = true }
            val radioEndpointField = Player::class.java.getDeclaredField("radioEndpoint").apply { isAccessible = true }
            radioEndpointField.set(Player, com.utaloom.innertube.models.WatchEndpoint(videoId = "dummy-radio"))
            assertTrue(Player.selectQueueIndex(0))
            withTimeout(2_000) { while (radioJobField.get(Player) == null) delay(10) }
            val job = radioJobField.get(Player) as? kotlinx.coroutines.Job
            assertTrue(job != null)
        } finally {
            Player.remoteControlled = false
            Player.hooks = null
            Player.stop(fromRemote = true)
            Player.release()
            Stores.library.update { oldLibrary }
            Stores.queue.update { oldQueue }
            Stores.flushAll()
            Player.hooks = oldHooks
            Player.remoteControlled = oldRemoteControlled
            media.delete()
        }
    }

    private class RecordingAudioEngine : AudioEngine {
        data class Load(val url: String, val startPaused: Boolean)

        val loads = CopyOnWriteArrayList<Load>()

        override fun addListener(listener: AudioEngine.Listener) = Unit
        override fun removeListener(listener: AudioEngine.Listener) = Unit
        override fun submit(task: () -> Unit) = task()
        override fun play() = Unit
        override fun setPause(paused: Boolean) = Unit
        override fun seek(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun setVolume(volume: Int) = Unit
        override fun load(url: String, headers: Map<String, String>, startPaused: Boolean): Boolean {
            loads += Load(url, startPaused)
            return true
        }
        override val time = 0L
        override val isSeekable = true
        override fun release() = Unit
    }
}
