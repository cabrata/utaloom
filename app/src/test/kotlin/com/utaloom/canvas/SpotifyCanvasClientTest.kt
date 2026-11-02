package com.utaloom.canvas

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCanvasClientTest {
    private val id = "0123456789abcdefghijAB"

    private fun response(url: String, uri: String): ByteArray {
        val canvas = ByteArrayOutputStream().also { CodedOutputStream.newInstance(it).apply { writeString(2, url); writeString(5, uri); flush() } }.toByteArray()
        return ByteArrayOutputStream().also { CodedOutputStream.newInstance(it).apply { writeByteArray(1, canvas); flush() } }.toByteArray()
    }

    @Test fun decodesOnlyTrustedCanvasUrls() {
        val ok = "https://canvaz.scdn.co/a/b.mp4"
        assertEquals(ok, decodeCanvasUrl(response(ok, "spotify:track:$id"), "spotify:track:$id"))
        assertNull(decodeCanvasUrl(response("https://evil.example/b.mp4", "spotify:track:$id"), "spotify:track:$id"))
        assertNull(decodeCanvasUrl(response(ok, "spotify:track:other"), "spotify:track:$id"))
    }

    @Test fun normalizesSpDc() {
        assertEquals("a".repeat(20), normalizeSpDc("foo=1; sp_dc=${"a".repeat(20)}; x=2"))
        assertNull(normalizeSpDc("short"))
    }
}
