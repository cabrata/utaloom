package com.utaloom.platform

import com.utaloom.data.Song
import com.utaloom.playback.PlayerState
import com.utaloom.playback.RepeatMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IntegrationTests {

    @Test
    fun testLastFmSignature() {
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "test_key",
            "artist" to "Cher",
            "track" to "Believe",
            "timestamp" to "1234567890"
        )
        val secret = "my_secret"
        val sig1 = lastFmSignature(params, secret)
        val sig2 = lastFmSignature(params, secret)
        assertEquals(sig1, sig2)
        assertFalse(sig1.isBlank())
        assertEquals(32, sig1.length)

        // format and api_sig must be excluded from hashing
        val paramsWithFormat = params + mapOf("format" to "json", "api_sig" to "ignored")
        assertEquals(sig1, lastFmSignature(paramsWithFormat, secret))
    }

    @Test
    fun testScrobbleThreshold() {
        // Less than or equal to 0 duration -> null
        assertNull(scrobbleThreshold(0))
        assertNull(scrobbleThreshold(-100))

        // Normal song: half duration
        assertEquals(30_000L, scrobbleThreshold(60_000L))
        assertEquals(90_000L, scrobbleThreshold(180_000L))

        // Long song: capped at 4 minutes (240_000 ms)
        assertEquals(240_000L, scrobbleThreshold(600_000L))
        assertEquals(240_000L, scrobbleThreshold(480_000L))
    }

    @Test
    fun testListenTrackerEligibilityAndSeeks() {
        val tracker = ListenTracker(minimumDurationMs = 30_000)
        val song = Song(id = "song1", title = "Test Song", artists = listOf("Artist"), duration = 200)

        // 1. Initial play -> nowPlaying = true, not eligible yet
        var p = tracker.sample(
            PlayerState(queue = listOf(song), index = 0, isPlaying = true, positionMs = 0, durationMs = 200_000),
            monotonicMs = 1000,
            epochSeconds = 100
        )
        assertNotNull(p)
        assertTrue(p!!.nowPlaying)
        assertFalse(p.eligible)

        // 2. Play for 10 seconds monotonically -> still not eligible
        p = tracker.sample(
            PlayerState(queue = listOf(song), index = 0, isPlaying = true, positionMs = 10_000, durationMs = 200_000),
            monotonicMs = 11_000,
            epochSeconds = 110
        )
        assertNotNull(p)
        assertFalse(p!!.eligible)

        // 3. User seeks from 10s forward to 150s (skipping forward)
        // Seek must not award listening time
        p = tracker.sample(
            PlayerState(queue = listOf(song), index = 0, isPlaying = true, positionMs = 150_000, durationMs = 200_000),
            monotonicMs = 12_000,
            epochSeconds = 111
        )
        assertNotNull(p)
        assertFalse(p!!.eligible)

        // 4. Paused state -> elapsed wall time while paused should NOT count
        p = tracker.sample(
            PlayerState(queue = listOf(song), index = 0, isPlaying = false, positionMs = 150_000, durationMs = 200_000),
            monotonicMs = 20_000,
            epochSeconds = 119
        )
        assertNotNull(p)
        assertFalse(p!!.eligible)
    }

    @Test
    fun testDiscordIpcFrameEncodingAndParsing() {
        val payload = """{"v":1,"client_id":"12345"}"""
        val encoded = encodeDiscordFrame(DiscordOpcode.HANDSHAKE, payload)

        assertEquals(8 + payload.toByteArray(Charsets.UTF_8).size, encoded.remaining())
        val opcodeInt = encoded.getInt()
        val length = encoded.getInt()
        assertEquals(DiscordOpcode.HANDSHAKE.code, opcodeInt)
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, length)

        val body = ByteArray(length)
        encoded.get(body)
        assertEquals(payload, String(body, Charsets.UTF_8))

        // Test frame parser
        val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.putInt(DiscordOpcode.FRAME.code)
        headerBuf.putInt(length)
        headerBuf.flip()

        val parsed = parseDiscordFrame(headerBuf, body)
        assertEquals(DiscordOpcode.FRAME, parsed.opcode)
        assertArrayEquals(body, parsed.payload)
    }
}
