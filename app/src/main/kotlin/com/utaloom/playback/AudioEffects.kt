package com.utaloom.playback

import com.utaloom.data.Settings
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/** Equalizer preset with name, preamp (-20..+20 dB), and 10 band gains (-20..+20 dB). */
data class EqualizerPreset(
    val name: String,
    val preamp: Float,
    val bands: List<Float>,
)

object AudioEffects {
    val defaultBandFreqs: List<Float> = listOf(
        31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f,
    )

    @Volatile private var cachedPresets: List<EqualizerPreset>? = null
    @Volatile private var cachedBands: List<Float>? = null

    val bandFreqs: List<Float>
        get() {
            cachedBands?.let { return it }
            val loaded = queryNativeBands() ?: defaultBandFreqs
            cachedBands = loaded
            return loaded
        }

    fun presets(): List<EqualizerPreset> {
        cachedPresets?.let { return it }
        val loaded = queryNativePresets() ?: fallbackPresets
        cachedPresets = loaded
        return loaded
    }

    internal fun populateFromFactory(factory: MediaPlayerFactory) {
        if (cachedPresets != null && cachedBands != null) return
        runCatching {
            val eqApi = factory.equalizer()
            val bands = eqApi.bands()
            if (bands != null && bands.isNotEmpty()) cachedBands = bands
            val names = eqApi.presets().orEmpty()
            if (names.isNotEmpty()) {
                val list = names.mapNotNull { name ->
                    runCatching {
                        val eq = eqApi.newEqualizer(name)
                        EqualizerPreset(
                            name = name,
                            preamp = sanitizePreamp(eq.preamp()),
                            bands = sanitizeBands(eq.amps().toList()),
                        )
                    }.getOrNull()
                }
                if (list.isNotEmpty()) cachedPresets = list
            }
        }
    }

    private fun queryNativeBands(): List<Float>? = runCatching {
        val factory = MediaPlayerFactory("--no-video", "--quiet")
        try {
            factory.equalizer().bands()?.takeIf { it.isNotEmpty() }
        } finally {
            factory.release()
        }
    }.getOrNull()

    private fun queryNativePresets(): List<EqualizerPreset>? = runCatching {
        val factory = MediaPlayerFactory("--no-video", "--quiet")
        try {
            val eqApi = factory.equalizer()
            eqApi.presets().mapNotNull { name ->
                val eq = eqApi.newEqualizer(name)
                EqualizerPreset(
                    name = name,
                    preamp = sanitizePreamp(eq.preamp()),
                    bands = sanitizeBands(eq.amps().toList()),
                )
            }.takeIf { it.isNotEmpty() }
        } finally {
            factory.release()
        }
    }.getOrNull()

    fun sanitizePreamp(preamp: Float): Float =
        if (preamp.isFinite()) preamp.coerceIn(-20f, 20f) else 0f

    fun sanitizeBands(bands: List<Float>?): List<Float> {
        val raw = bands.orEmpty()
        return List(10) { i ->
            val v = raw.getOrNull(i)
            if (v != null && v.isFinite()) v.coerceIn(-20f, 20f) else 0f
        }
    }

    fun sanitizeSpeed(speed: Float): Float =
        if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f

    fun sanitizePitch(pitch: Int): Int =
        pitch.coerceIn(-12, 12)

    fun sanitized(settings: Settings): Settings = settings.copy(
        equalizerPreamp = sanitizePreamp(settings.equalizerPreamp),
        equalizerBands = sanitizeBands(settings.equalizerBands),
        playbackSpeed = sanitizeSpeed(settings.playbackSpeed),
        playbackPitch = sanitizePitch(settings.playbackPitch),
        crossfadeSeconds = settings.crossfadeSeconds.coerceIn(0, 12),
    )

    // ponytail: libVLC 3.0 built-in equalizer curves cached to avoid factory spin-up when discovery is unavailable.
    private val fallbackPresets = listOf(
        EqualizerPreset("Flat", 12f, List(10) { 0f }),
        EqualizerPreset("Classical", 12f, listOf(0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f)),
        EqualizerPreset("Club", 6f, listOf(0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f)),
        EqualizerPreset("Dance", 5f, listOf(9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f)),
        EqualizerPreset("Full bass", 5f, listOf(-8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f)),
        EqualizerPreset("Full bass and treble", 4f, listOf(7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f)),
        EqualizerPreset("Full treble", 3f, listOf(-9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 16f, 16f, 16f, 16.8f)),
        EqualizerPreset("Headphones", 4f, listOf(4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12.8f, 14.4f)),
        EqualizerPreset("Large Hall", 5f, listOf(10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f)),
        EqualizerPreset("Live", 7f, listOf(-4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f)),
        EqualizerPreset("Party", 6f, listOf(7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f)),
        EqualizerPreset("Pop", 6f, listOf(-1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f)),
        EqualizerPreset("Reggae", 8f, listOf(0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f)),
        EqualizerPreset("Rock", 5f, listOf(8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f)),
        EqualizerPreset("Ska", 6f, listOf(-2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f)),
        EqualizerPreset("Soft", 5f, listOf(4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f)),
        EqualizerPreset("Soft rock", 7f, listOf(4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f)),
        EqualizerPreset("Techno", 5f, listOf(8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f)),
    )
}
