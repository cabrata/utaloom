package com.utaloom.platform

import com.utaloom.data.Song
import com.utaloom.playback.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

class IntegrationBehaviorTest {

    @Test
    fun testScrobbleThresholdCriteria() {
        // Less than 30 seconds -> never scrobble
        val thresholdUnder30 = scrobbleThreshold(25_000)
        assertNotNull(thresholdUnder30)
        // With tracker: duration <= 30_000 does not scrobble
        val tracker = ListenTracker(minimumDurationMs = 30_000)
        val shortSong = Song(id = "s1", title = "Short", artists = listOf("A"), duration = 25)
        val shortState = PlayerState(queue = listOf(shortSong), index = 0, isPlaying = true, positionMs = 20_000, durationMs = 25_000)
        val progress = tracker.sample(shortState, monotonicMs = 20_000, epochSeconds = 1000)
        assertFalse("Under 30s duration should not be eligible to scrobble", progress?.eligible == true)

        // Normal song: 200s (200,000ms), 50% is 100,000ms
        assertEquals(100_000L, scrobbleThreshold(200_000))

        // Long song: 600s (600,000ms, 10 minutes), min(50%, 4 min) = 4 min = 240,000ms
        assertEquals(240_000L, scrobbleThreshold(600_000))
    }

    @Test
    fun testLastFmApiSignature() {
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "test_key",
            "artist[0]" to "Artist",
            "track[0]" to "Track",
            "timestamp[0]" to "12345678"
        )
        val secret = "my_secret"
        val sig1 = lastFmSignature(params, secret)
        val sig2 = lastFmSignature(params, secret)
        assertEquals("Signature should be deterministic", sig1, sig2)
        assertEquals("MD5 hex length must be 32 chars", 32, sig1.length)

        // Changing parameter alters signature
        val sigOther = lastFmSignature(params + ("extra" to "1"), secret)
        assertTrue("Different parameters should yield different signature", sig1 != sigOther)
    }

    @Test
    fun testDiscordPresenceActivityPayload() {
        val song = Song(
            id = "test_song_123",
            title = "Awesome Track",
            artists = listOf("Main Artist", "Featured"),
            album = "Best Album",
            duration = 180,
            thumbnail = "https://example.com/thumb.jpg"
        )

        // When paused or stopped -> activity is JsonNull
        val pausedState = PlayerState(queue = listOf(song), index = 0, isPlaying = false, positionMs = 10_000, durationMs = 180_000)
        val pausedPayload = discordActivityPayload("app_1", song, pausedState, epochNow = 1000)
        assertTrue("Activity must be null when not playing", pausedPayload.contains(""""activity":null"""))

        // When playing -> full activity payload
        val playingState = PlayerState(queue = listOf(song), index = 0, isPlaying = true, positionMs = 30_000, durationMs = 180_000)
        val playingPayload = discordActivityPayload("app_1", song, playingState, epochNow = 1000)
        assertTrue("Contains song title", playingPayload.contains("Awesome Track"))
        assertTrue("Contains artists", playingPayload.contains("Main Artist, Featured"))
        assertTrue("Contains album", playingPayload.contains("Best Album"))
        assertTrue("Contains command SET_ACTIVITY", playingPayload.contains(""""cmd":"SET_ACTIVITY""""))

        // Frame encoding and parsing round-trip
        val encoded = encodeDiscordFrame(DiscordOpcode.FRAME, playingPayload)
        assertEquals(DiscordOpcode.FRAME.code, encoded.order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        val bodyLen = encoded.getInt(4)
        val body = ByteArray(bodyLen)
        encoded.position(8)
        encoded.get(body)
        val parsed = parseDiscordFrame(encoded, body)
        assertEquals(DiscordOpcode.FRAME, parsed.opcode)
        assertEquals(playingPayload, String(parsed.payload, Charsets.UTF_8))
    }
}
