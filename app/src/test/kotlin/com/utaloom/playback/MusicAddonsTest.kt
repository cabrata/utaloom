package com.utaloom.playback

import com.utaloom.data.Settings
import com.utaloom.playback.MusicAddons.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicAddonsTest {
    private fun pick(vararg tracks: Track, title: String = "Get Lucky (feat. Pharrell Williams)", duration: Int? = 369) =
        MusicAddons.pickMatch(tracks.toList(), title, listOf("Daft Punk", "Pharrell Williams"), duration)?.id

    @Test
    fun picksSameRecordingClosestLength() {
        assertEquals("b", pick(Track("a", "Get Lucky", "Daft Punk", 371.0), Track("b", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk", 369.2)))
    }

    @Test
    fun rejectsOtherVersionsArtistsAndLengths() {
        assertNull(pick(Track("a", "Get Lucky (Radio Edit)", "Daft Punk", 369.0)))
        assertNull(pick(Track("a", "Get Lucky - Live", "Daft Punk", 369.0)))
        assertNull(pick(Track("a", "Get Lucky", "Some Cover Band", 369.0)))
        assertNull(pick(Track("a", "Get Lucky", "Daft Punk", 248.0)))
        assertNull(pick(Track("a", "Lucky", "Daft Punk", 369.0)))
        assertNull(pick(Track("", "Get Lucky", "Daft Punk", 369.0)))
    }

    @Test
    fun keepsVersionIgnoresAccentsAndNeedsLengthWithoutArtist() {
        assertEquals("a", pick(Track("a", "Déjà Vu [Live]", "DAFT PUNK", 200.0), title = "Deja Vu (Live)", duration = 201))
        assertEquals("a", pick(Track("a", "Get Lucky", "", 369.0)))
        assertNull(pick(Track("a", "Get Lucky", "", null)))
    }

    @Test
    fun queryUrlAndSettingsRoundTrip() {
        assertEquals("Get Lucky Daft Punk", MusicAddons.searchQuery("Get Lucky (feat. Pharrell Williams)", listOf("Daft Punk")))
        assertEquals("https://example.com/a", MusicAddons.normalizeUrl(" https://example.com/a/manifest.json "))
        assertNull(MusicAddons.normalizeUrl("http://example.com/a"))
        assertNull(MusicAddons.normalizeUrl("file:///tmp/a"))
        val s = Settings(musicAddons = listOf(MusicAddon("https://example.com/a", "k", enabled = false, params = mapOf("quality" to "LOSSLESS"))))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s.musicAddons, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), s)).musicAddons)
        // Settings saved before add-ons existed still load.
        assertEquals(emptyList<MusicAddon>(), json.decodeFromString(Settings.serializer(), "{}").musicAddons)
    }
}
