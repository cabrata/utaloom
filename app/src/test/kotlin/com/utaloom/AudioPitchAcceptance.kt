package com.utaloom

import com.sun.jna.Pointer
import com.utaloom.data.Paths
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.LibVlcCore
import com.utaloom.playback.syncAudioFilter
import com.utaloom.playback.audioEngineOptions
import com.utaloom.playback.createAudioEngine
import org.junit.Assert.assertTrue
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

/**
 * Real libVLC native fixture pitch acceptance check.
 * Generates known sine WAV files and captures audio via AudioCallback (amem) to verify
 * that native pitch shifting filters actually alter the output spectrum.
 */
fun main() {
    runAudioPitchAcceptance()
}

fun runAudioPitchAcceptance() {
    println("Starting AudioPitchAcceptance native validation...")
    val vlcErr = NativeLibs.discoverVlc()
    check(vlcErr == null) { "libVLC not discoverable: $vlcErr" }
    check(LibVlcCore.INSTANCE != null) { "libvlccore not loadable (vlcPath=${NativeLibs.vlcPath})" }

    val wavFile = Paths.dir.resolve("native_pitch_acceptance.wav")
    val sampleRate = 44100
    val baseFreq = 440.0
    generateSine(wavFile, sampleRate = sampleRate, freqHz = baseFreq, seconds = 3)

    try {
        println("Measuring pitch = 0 (natural frequency)...")
        val freq0 = measurePitch(wavFile, pitch = 0, sampleRate = sampleRate)
        println("Pitch 0 measured: ${freq0.roundToInt()} Hz (expected ~$baseFreq Hz)")
        assertTrue("Pitch 0 frequency within tolerance", freq0 in 430.0..450.0)

        println("Measuring pitch = +7 (up 7 semitones / fifth)...")
        val freq7 = measurePitch(wavFile, pitch = 7, sampleRate = sampleRate)
        println("Pitch +7 measured: ${freq7.roundToInt()} Hz (expected ~659 Hz)")
        assertTrue("Pitch +7 frequency within tolerance", freq7 in 635.0..685.0)

        println("Measuring pitch = -5 (down 5 semitones / fourth)...")
        val freqMinus5 = measurePitch(wavFile, pitch = -5, sampleRate = sampleRate)
        println("Pitch -5 measured: ${freqMinus5.roundToInt()} Hz (expected ~330 Hz)")
        assertTrue("Pitch -5 frequency within tolerance", freqMinus5 in 315.0..345.0)

        println("Testing real native engine control...")
        val engine = createAudioEngine(pitch = 0)
        try {
            assertTrue(engine.setRate(1.5f))
            println("Engine rate 1.5x set: OK")
            engine.setEqualizer(enabled = true, preamp = 4.0f, bands = List(10) { 1.5f })
            println("Engine equalizer set: OK")
            engine.setEqualizer(enabled = false, preamp = 0f, bands = List(10) { 0f })
            println("Engine equalizer disable: OK")
        } finally {
            engine.release()
        }

        println("AudioPitchAcceptance: ALL NATIVE CHECKS PASSED.")
    } finally {
        wavFile.delete()
    }
}

private fun measurePitch(wav: File, pitch: Int, sampleRate: Int): Double {
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

private fun generateSine(file: File, sampleRate: Int, freqHz: Double, seconds: Int) {
    val totalSamples = sampleRate * seconds
    val subchunk2Size = totalSamples * 2
    val chunkSize = 36 + subchunk2Size
    DataOutputStream(FileOutputStream(file)).use { out ->
        out.writeBytes("RIFF")
        out.writeInt(java.lang.Integer.reverseBytes(chunkSize))
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeInt(java.lang.Integer.reverseBytes(16))
        out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        out.writeInt(java.lang.Integer.reverseBytes(sampleRate))
        out.writeInt(java.lang.Integer.reverseBytes(sampleRate * 2))
        out.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
        out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        out.writeBytes("data")
        out.writeInt(java.lang.Integer.reverseBytes(subchunk2Size))
        for (i in 0 until totalSamples) {
            val sample = (16384.0 * sin(2.0 * PI * freqHz * i / sampleRate)).roundToInt().toShort()
            out.writeShort(java.lang.Short.reverseBytes(sample).toInt())
        }
    }
}
