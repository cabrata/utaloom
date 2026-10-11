package com.utaloom.playback

import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Confirmed completed interval of silence with preserved padding. */
data class SilenceRange(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val rawStartMs: Long get() = (startMs - 100L).coerceAtLeast(0L)
    val rawEndMs: Long get() = endMs + 100L
}

// ponytail: LibVLC audio-callback silence scanner; add multi-rate PTS tracking only if streaming decode needs faster-than-realtime lookahead.
private const val SAMPLE_RATE = 48000
private const val CHANNELS = 2
private const val SILENCE_THRESHOLD = 256
private const val MIN_SILENCE_DURATION_MS = 800L
private const val PADDING_MS = 100L
private const val MAX_SUPPORTED_DURATION_MS = 20 * 60 * 1000L

/**
 * Decodes [url] in background via a separate libVLC audio callback without normal audio output,
 * detecting completed silence intervals >800ms preserving ~100ms padding at boundaries.
 */
suspend fun analyze(
    url: String,
    headers: Map<String, String> = emptyMap(),
    durationMs: Long = 0L,
): List<SilenceRange> {
    if (durationMs > MAX_SUPPORTED_DURATION_MS) return emptyList()

    return withContext(Dispatchers.IO) {
        val timeoutMs = if (durationMs > 0) durationMs + 10_000L else 30_000L
        val factoryOptions = listOf(
            "--no-video",
            "--no-audio-time-stretch",
            "--no-audio-replay-gain",
        ) + if (System.getenv("UTALOOM_DEBUG") == null) listOf("--quiet") else listOf("--verbose=1")

        val factory = MediaPlayerFactory(*factoryOptions.toTypedArray())
        val player = factory.mediaPlayers().newMediaPlayer()
        try {
            val lock = Any()
            val ranges = mutableListOf<SilenceRange>()
            var totalFrames = 0L
            var silenceStartFrame: Long? = null
            val done = CountDownLatch(1)

            fun finalizeTrailingSilence() {
                synchronized(lock) {
                    silenceStartFrame?.let { startFrame ->
                        val rawStartMs = startFrame * 1000L / SAMPLE_RATE
                        val rawEndMs = totalFrames * 1000L / SAMPLE_RATE
                        if (rawEndMs - rawStartMs > MIN_SILENCE_DURATION_MS) {
                            val paddedStart = rawStartMs + PADDING_MS
                            val paddedEnd = (rawEndMs - PADDING_MS).coerceAtLeast(paddedStart)
                            ranges.add(SilenceRange(paddedStart, paddedEnd))
                        }
                        silenceStartFrame = null
                    }
                }
            }

            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    finalizeTrailingSilence()
                    done.countDown()
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    finalizeTrailingSilence()
                    done.countDown()
                }
            })

            player.audio().setMute(true)
            player.audio().setVolume(0)
            player.audio().callback("S16N", SAMPLE_RATE, CHANNELS, object : AudioCallbackAdapter() {
                override fun play(mediaPlayer: MediaPlayer, samples: Pointer, count: Int, pts: Long) {
                    if (count <= 0) return
                    val buffer = samples.getByteBuffer(0, count.toLong() * CHANNELS * 2).order(ByteOrder.nativeOrder())
                    synchronized(lock) {
                        for (i in 0 until count) {
                            val ch0 = abs(buffer.short.toInt())
                            val ch1 = abs(buffer.short.toInt())
                            val isSilent = ch0 < SILENCE_THRESHOLD && ch1 < SILENCE_THRESHOLD
                            val frame = totalFrames
                            if (isSilent) {
                                if (silenceStartFrame == null) {
                                    silenceStartFrame = frame
                                }
                            } else {
                                silenceStartFrame?.let { startFrame ->
                                    val rawStartMs = startFrame * 1000L / SAMPLE_RATE
                                    val rawEndMs = frame * 1000L / SAMPLE_RATE
                                    if (rawEndMs - rawStartMs > MIN_SILENCE_DURATION_MS) {
                                        val paddedStart = rawStartMs + PADDING_MS
                                        val paddedEnd = (rawEndMs - PADDING_MS).coerceAtLeast(paddedStart)
                                        ranges.add(SilenceRange(paddedStart, paddedEnd))
                                    }
                                    silenceStartFrame = null
                                }
                            }
                            totalFrames++
                        }
                    }
                }
            })

            val mediaOptions = headers.mapNotNull { (name, value) ->
                when {
                    name.equals("User-Agent", ignoreCase = true) -> ":http-user-agent=$value"
                    name.equals("Referer", ignoreCase = true) -> ":http-referrer=$value"
                    else -> null
                }
            } + listOfNotNull(
                ":http-reconnect".takeIf { url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) },
            )

            val started = player.media().play(url, *mediaOptions.toTypedArray())
            if (!started) return@withContext emptyList()

            val deadline = System.currentTimeMillis() + timeoutMs
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                if (done.await(50, TimeUnit.MILLISECONDS)) break
            }

            finalizeTrailingSilence()
            synchronized(lock) { ranges.toList() }
        } finally {
            try {
                player.controls().stop()
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } finally {
                factory.release()
            }
        }
    }
}

/**
 * Native real-libVLC fixture test: generates 1s sound / 3s silence / 1s sound WAV,
 * analyzes it, verifies detected timeline ~1000..4000ms, bounds, and clean lifecycle.
 */
fun main() {
    fun writeShortLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
    fun writeIntLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
    runBlocking {
        println("Running native SilenceAnalysis fixture check...")

        // 1. Duration > 20 min unsupported bound check
        val unsupported = analyze("file:///dev/null", durationMs = 21 * 60 * 1000L)
        check(unsupported.isEmpty()) { "Duration > 20min must return empty list" }

        // 2. Real libVLC PCM fixture check: 1s sound / 3s silence / 1s sound
        val scratchDir = System.getenv("JCODE_SCRATCH_DIR")?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"))
        val wav = File.createTempFile("silence-fixture-", ".wav", scratchDir)
        try {
            DataOutputStream(wav.outputStream().buffered()).use { out ->
                val rate = SAMPLE_RATE
                val totalFrames = rate * 5
                val dataBytes = totalFrames * CHANNELS * 2
                out.writeBytes("RIFF")
                writeIntLE(out, dataBytes + 36)
                out.writeBytes("WAVEfmt ")
                writeIntLE(out, 16)
                writeShortLE(out, 1)
                writeShortLE(out, CHANNELS)
                writeIntLE(out, rate)
                writeIntLE(out, rate * CHANNELS * 2)
                writeShortLE(out, CHANNELS * 2)
                writeShortLE(out, 16)
                out.writeBytes("data")
                writeIntLE(out, dataBytes)

                for (f in 0 until totalFrames) {
                    val s = if (f in rate until rate * 4) 0 else (sin(f * 2.0 * PI * 440.0 / rate) * 12000.0).toInt()
                    writeShortLE(out, s)
                    writeShortLE(out, -s)
                }
            }

            val ranges = analyze(wav.toURI().toString(), durationMs = 5000L)
            println("Detected silence ranges: $ranges")
            check(ranges.size == 1) { "Expected exactly 1 silence range, got: $ranges" }

            val r = ranges[0]
            check(r.rawStartMs in 950L..1050L) { "Expected rawStartMs ~1000ms, got: ${r.rawStartMs}" }
            check(r.rawEndMs in 3950L..4050L) { "Expected rawEndMs ~4000ms, got: ${r.rawEndMs}" }
            check(r.startMs in 1050L..1150L) { "Expected startMs with 100ms padding ~1100ms, got: ${r.startMs}" }
            check(r.endMs in 3850L..3950L) { "Expected endMs with 100ms padding ~3900ms, got: ${r.endMs}" }
            println("Fixture passed: raw ${r.rawStartMs}..${r.rawEndMs}ms, padded ${r.startMs}..${r.endMs}ms (~1000..4000ms)")
        } finally {
            wav.delete()
        }
        println("SilenceAnalysis self-check SUCCESS")
    }
}
