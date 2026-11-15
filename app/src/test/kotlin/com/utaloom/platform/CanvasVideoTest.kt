package com.utaloom.platform

import org.jetbrains.skia.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class CanvasVideoTest {
    @Test
    fun realVideoLoopsPausesResumesAndReleases() {
        assumeTrue("VLC required", NativeLibs.discoverVlc() == null)
        val video = fixture()
        val player = CanvasVideoPlayer(video.absolutePath, true)
        try {
            await("first decoded frames") { player.revision.get() >= 3 || player.failed }
            assertFalse("Fixture must decode", player.failed)
            Surface.makeRasterN32Premul(240, 320).use { surface ->
                player.draw(surface.canvas, 240f, 320f)
                surface.makeImageSnapshot().use { image ->
                    org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                        val bytes = checkNotNull(bitmap.readPixels())
                        assertTrue("Decoded video must contain visible color", bytes.withIndex().any { it.index % 4 != 3 && (it.value.toInt() and 255) > 50 })
                    }
                }
            }
            Thread.sleep(2100)
            val afterLoop = player.revision.get()
            Thread.sleep(350)
            assertTrue("Frames must continue beyond the one-second clip", player.revision.get() > afterLoop)
            player.setPlaying(false)
            await("native decoder paused") { player.paused }
            val paused = player.revision.get()
            Thread.sleep(300)
            assertTrue("Pause must stop advancing video frames", player.revision.get() - paused <= 1)
            player.setPlaying(true)
            await("resumed video") { !player.paused && player.revision.get() > paused + 2 }
        } finally {
            player.close()
            player.released.get(10, TimeUnit.SECONDS)
        }
        val released = player.revision.get()
        player.setPlaying(true)
        Thread.sleep(100)
        assertTrue("Disposed video must stay stopped", player.revision.get() == released)
        player.close()
    }

    @Test
    fun pausedStartShowsOneFrameThenResumes() {
        assumeTrue("VLC required", NativeLibs.discoverVlc() == null)
        val player = CanvasVideoPlayer(fixture().absolutePath, false)
        try {
            await("paused first frame") { player.revision.get() > 0 || player.failed }
            assertFalse(player.failed)
            val first = player.revision.get()
            Thread.sleep(250)
            assertTrue("Paused start must hold its first frame", player.revision.get() == first)
            player.setPlaying(true)
            await("play after paused start") { player.revision.get() > first + 2 }
        } finally { player.close(); player.released.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun invalidMediaFailsAndCanBeReleasedDuringStartup() {
        assumeTrue("VLC required", NativeLibs.discoverVlc() == null)
        val missing = File("build/canvas-test/missing.mp4").absolutePath
        val bad = CanvasVideoPlayer(missing, true)
        try { await("invalid video error") { bad.failed } }
        finally { bad.close(); bad.released.get(10, TimeUnit.SECONDS) }
        repeat(3) {
            val cancelled = CanvasVideoPlayer(missing, true)
            cancelled.close()
            cancelled.released.get(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        internal fun fixture(): File {
            val directory = File("build/canvas-test").apply { mkdirs() }
            val video = directory.resolve("loop.mp4")
            if (!video.isFile) {
                val log = directory.resolve("ffmpeg.log")
                val process = runCatching {
                    ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=180x320:rate=15", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.absolutePath)
                        .redirectErrorStream(true).redirectOutput(log).start()
                }.onFailure { assumeTrue("ffmpeg required for the video fixture", false) }.getOrThrow()
                check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Video fixture failed: ${log.readText()}" }
            }
            return video
        }

        private fun await(message: String, condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < end) {
                if (condition()) return
                Thread.sleep(20)
            }
            check(condition()) { "Timed out: $message" }
        }
    }
}
