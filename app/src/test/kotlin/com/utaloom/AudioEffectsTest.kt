package com.utaloom

import com.sun.jna.Pointer
import com.utaloom.data.Paths
import com.utaloom.data.Settings
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.AudioEffects
import com.utaloom.playback.AudioEngine
import com.utaloom.playback.LibVlcCore
import com.utaloom.playback.syncAudioFilter
import com.utaloom.playback.audioEngineOptions
import com.utaloom.playback.createAudioEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioEffectsTest {

    @Test
    fun sanitizationBoundaries() {
        assertEquals(0f, AudioEffects.sanitizePreamp(Float.NaN), 0.001f)
        assertEquals(0f, AudioEffects.sanitizePreamp(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(20f, AudioEffects.sanitizePreamp(25f), 0.001f)
        assertEquals(-20f, AudioEffects.sanitizePreamp(-35f), 0.001f)
        assertEquals(5.5f, AudioEffects.sanitizePreamp(5.5f), 0.001f)

        val badBands = listOf(Float.NaN, -30f, 40f, Float.NEGATIVE_INFINITY)
        val sanitized = AudioEffects.sanitizeBands(badBands)
        assertEquals(10, sanitized.size)
        assertEquals(0f, sanitized[0], 0.001f)
        assertEquals(-20f, sanitized[1], 0.001f)
        assertEquals(20f, sanitized[2], 0.001f)
        assertEquals(0f, sanitized[3], 0.001f)
        for (i in 4 until 10) assertEquals(0f, sanitized[i], 0.001f)

        assertEquals(1f, AudioEffects.sanitizeSpeed(Float.NaN), 0.001f)
        assertEquals(0.5f, AudioEffects.sanitizeSpeed(0.2f), 0.001f)
        assertEquals(2.0f, AudioEffects.sanitizeSpeed(3.5f), 0.001f)
        assertEquals(1.25f, AudioEffects.sanitizeSpeed(1.25f), 0.001f)

        assertEquals(12, AudioEffects.sanitizePitch(15))
        assertEquals(-12, AudioEffects.sanitizePitch(-20))
        assertEquals(3, AudioEffects.sanitizePitch(3))

        val s = Settings(
            equalizerPreamp = 30f,
            equalizerBands = listOf(Float.NaN, 50f),
            playbackSpeed = 5f,
            playbackPitch = -15,
            crossfadeSeconds = 99,
        )
        val cleaned = AudioEffects.sanitized(s)
        assertEquals(20f, cleaned.equalizerPreamp, 0.001f)
        assertEquals(10, cleaned.equalizerBands.size)
        assertEquals(20f, cleaned.equalizerBands[1], 0.001f)
        assertEquals(2f, cleaned.playbackSpeed, 0.001f)
        assertEquals(-12, cleaned.playbackPitch)
        assertEquals(12, cleaned.crossfadeSeconds)
    }

    @Test
    fun presetsAndBandFrequencies() {
        val freqs = AudioEffects.bandFreqs
        assertEquals(10, freqs.size)
        assertTrue(freqs.first() in 20f..40f)
        assertTrue(freqs.last() in 14000f..18000f)

        val presets = AudioEffects.presets()
        assertTrue("Presets available", presets.isNotEmpty())
        assertTrue("Has Flat preset", presets.any { it.name == "Flat" })
        presets.forEach { p ->
            assertTrue("Preset name not blank", p.name.isNotBlank())
            assertTrue("Preamp finite and bounded", p.preamp.isFinite() && p.preamp in -20f..20f)
            assertEquals("Exactly 10 bands", 10, p.bands.size)
            p.bands.forEach { b -> assertTrue("Band finite and bounded", b.isFinite() && b in -20f..20f) }
        }
    }

    @Test
    fun optionGenerationFilters() {
        val zeroOpts = audioEngineOptions(0)
        assertTrue(zeroOpts.contains("--audio-filter=scaletempo"))
        assertFalse(zeroOpts.any { it.startsWith("--pitch-shift=") })

        val pitch7Opts = audioEngineOptions(7)
        assertTrue(pitch7Opts.contains("--audio-filter=scaletempo:scaletempo_pitch"))
        assertTrue(pitch7Opts.contains("--pitch-shift=7"))

        val clampedOpts = audioEngineOptions(15)
        assertTrue(clampedOpts.contains("--audio-filter=scaletempo:scaletempo_pitch"))
        assertTrue(clampedOpts.contains("--pitch-shift=12"))
    }

    @Test
    fun realNativeSinePitchAcceptance() {
        assumeTrue("libVLC required for real native fixture", NativeLibs.discoverVlc() == null)
        assumeTrue("libvlccore required for real native pitch shift", LibVlcCore.INSTANCE != null)

        val wavFile = Paths.dir.resolve("audio_pitch_acceptance_sine.wav")
        val sampleRate = 44100
        val baseFreq = 440.0
        generateSineWav(wavFile, sampleRate = sampleRate, freqHz = baseFreq, durationSeconds = 3)

        try {
            // Check Pitch 0 -> ~440 Hz
            val freq0 = measureCallbackFrequency(wavFile, pitch = 0, sampleRate = sampleRate)
            assertTrue("Pitch 0 measured ~$baseFreq Hz (was $freq0)", freq0 in 430.0..450.0)

            // Check Pitch +7 (up a fifth: 440 * 2^(7/12) ≈ 659.25 Hz)
            val freq7 = measureCallbackFrequency(wavFile, pitch = 7, sampleRate = sampleRate)
            assertTrue("Pitch +7 measured ~659 Hz (was $freq7)", freq7 in 635.0..685.0)

            // Check Pitch -5 (down a fourth: 440 * 2^(-5/12) ≈ 329.63 Hz)
            val freqMinus5 = measureCallbackFrequency(wavFile, pitch = -5, sampleRate = sampleRate)
            assertTrue("Pitch -5 measured ~330 Hz (was $freqMinus5)", freqMinus5 in 315.0..345.0)
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun runAudioPitchAcceptanceMainFixture() {
        assumeTrue("libVLC required for standalone fixture", NativeLibs.discoverVlc() == null)
        assumeTrue("libvlccore required for standalone fixture", LibVlcCore.INSTANCE != null)
        runAudioPitchAcceptance()
    }

    @Test
    fun realNativeEqualizerAndRateEngineControl() {
        assumeTrue("libVLC required for native engine test", NativeLibs.discoverVlc() == null)
        val engine = createAudioEngine(pitch = 0)
        try {
            val wavFile = Paths.dir.resolve("audio_eq_rate_smoke.wav")
            generateSineWav(wavFile, sampleRate = 44100, freqHz = 440.0, durationSeconds = 2)
            try {
                // Test Rate change
                assertTrue(engine.setRate(1.25f))
                // Test Equalizer application
                engine.setEqualizer(enabled = true, preamp = 3.5f, bands = List(10) { 2.0f })
                engine.setEqualizer(enabled = false, preamp = 0f, bands = List(10) { 0f })

                val loaded = engine.load(wavFile.absolutePath, emptyMap<String, String>(), startPaused = true)
                assertTrue("Native engine loaded local test WAV", loaded)
            } finally {
                wavFile.delete()
            }
        } finally {
            engine.release()
        }
    }

    private fun measureCallbackFrequency(wav: File, pitch: Int, sampleRate: Int): Double {
        val options = audioEngineOptions(pitch)
        val factory = MediaPlayerFactory(*options.toTypedArray())
        val player = factory.mediaPlayers().newMediaPlayer()
        val samples = mutableListOf<Short>()
        val latch = CountDownLatch(1)

        try {
            syncAudioFilter(player, pitch, enabled = false)

            player.audio().callback("S16N", sampleRate, 1, object : AudioCallbackAdapter() {
                override fun play(mediaPlayer: MediaPlayer, data: Pointer, sampleCount: Int, pts: Long) {
                    val arr = data.getShortArray(0, sampleCount)
                    synchronized(samples) {
                        for (s in arr) {
                            if (samples.size < sampleRate) samples.add(s)
                        }
                    }
                    if (samples.size >= sampleRate) latch.countDown()
                }
            })

            player.media().play(wav.absolutePath)
            syncAudioFilter(player, pitch, enabled = false)
            latch.await(4, TimeUnit.SECONDS)
            player.controls().stop()
        } finally {
            player.release()
            factory.release()
        }

        synchronized(samples) {
            if (samples.isEmpty()) return 0.0
            var crossings = 0
            var prev: Short = 0
            for (s in samples) {
                if ((prev < 0 && s >= 0) || (prev >= 0 && s < 0)) crossings++
                prev = s
            }
            return (crossings / 2.0) * (sampleRate.toDouble() / samples.size)
        }
    }

    private fun generateSineWav(file: File, sampleRate: Int, freqHz: Double, durationSeconds: Int) {
        val totalSamples = sampleRate * durationSeconds
        val subchunk2Size = totalSamples * 2
        val chunkSize = 36 + subchunk2Size
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeInt(java.lang.Integer.reverseBytes(chunkSize))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeInt(java.lang.Integer.reverseBytes(16)) // PCM subchunk size
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat 1 = PCM
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // Channels = 1
            out.writeInt(java.lang.Integer.reverseBytes(sampleRate))
            out.writeInt(java.lang.Integer.reverseBytes(sampleRate * 2)) // Byte rate
            out.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt()) // Block align
            out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // Bits per sample
            out.writeBytes("data")
            out.writeInt(java.lang.Integer.reverseBytes(subchunk2Size))
            for (i in 0 until totalSamples) {
                val sample = (16384.0 * sin(2.0 * PI * freqHz * i / sampleRate)).roundToInt().toShort()
                out.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }
}
