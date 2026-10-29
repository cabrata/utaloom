package com.utaloom.ui

import androidx.compose.ui.graphics.Color
import com.utaloom.shared.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLyricsMotionTest {

    private val lines = listOf(
        LyricLine(1_000, "First line"),
        LyricLine(3_000, "   "),
        LyricLine(4_000, "Background voice", background = true),
        LyricLine(5_000, "Second line"),
    )

    @Test
    fun currentLineIndexFollowsPositionWithLookahead() {
        assertEquals(-1, currentLineIndex(lines, 0))
        assertEquals(-1, currentLineIndex(lines, 699))
        assertEquals(0, currentLineIndex(lines, 700))
        assertEquals(0, currentLineIndex(lines, 1_000))

        // indexOfLast matches the last non-background line at/before position+lookahead.
        assertEquals(1, currentLineIndex(lines, 3_000))
        // Background vocals never become current.
        assertEquals(1, currentLineIndex(lines, 4_200))
        assertEquals(3, currentLineIndex(lines, 5_500))

        // Past the last line, the last non-background line stays active.
        assertEquals(3, currentLineIndex(lines, 999_000))
    }

    @Test
    fun currentLineIndexBoundaryValues() {
        val single = listOf(LyricLine(0, "Start"))
        assertEquals(0, currentLineIndex(single, 0))
        assertEquals(0, currentLineIndex(single, -300))
        assertEquals(-1, currentLineIndex(single, -301))
        assertEquals(-1, currentLineIndex(emptyList(), 1_000))
        assertEquals(-1, currentLineIndex(emptyList(), 0))
    }

    @Test
    fun currentLineIndexUnsyncedReturnsMinusOne() {
        assertEquals(-1, currentLineIndex(lines, 5_000, synced = false))
        assertEquals(-1, currentLineIndex(lines, 0, synced = false))
        assertEquals(-1, currentLineIndex(emptyList(), 1_000, synced = false))
    }

    @Test
    fun neighboringLinesReturnsWindowIncludingCurrent() {
        val window = neighboringLines(lines, 2)
        // radius=2 around index 2 in a 4-line list spans 0..3.
        assertEquals(listOf(0, 1, 2, 3), window.map { it.index })
    }

    @Test
    fun neighboringLinesClampAtEdges() {
        assertEquals(listOf(0, 1, 2), neighboringLines(lines, 0, radius = 2).map { it.index })
        assertEquals(listOf(1, 2, 3), neighboringLines(lines, 3, radius = 2).map { it.index })
    }

    @Test
    fun neighboringLinesHandlesOutOfBoundsAndEmpty() {
        assertTrue(neighboringLines(lines, -1).isEmpty())
        assertTrue(neighboringLines(lines, 99).isEmpty())
        assertTrue(neighboringLines(emptyList(), 0).isEmpty())
    }

    @Test
    fun karaokeGlowWhiteToDim() {
        assertEquals(1f, karaokeGlow(0f))
        assertEquals(0.875f, karaokeGlow(0.25f))
        assertEquals(0.5f, karaokeGlow(1f))
        // Clamp outside 0..1.
        assertEquals(1f, karaokeGlow(-0.5f))
        assertEquals(0.5f, karaokeGlow(2f))
    }

    @Test
    fun seekFractionBoundsAndUnknownDuration() {
        assertEquals(0f, seekFraction(0, 100_000))
        assertEquals(0.5f, seekFraction(50_000, 100_000))
        assertEquals(1f, seekFraction(120_000, 100_000))
        assertEquals(0f, seekFraction(-1_000, 100_000))
        assertEquals(0f, seekFraction(1_000, 0))
        assertEquals(0f, seekFraction(1_000, -100))
    }

    @Test
    fun islandAccentContrastAndFallback() {
        assertEquals(DefaultSeed, islandAccentForSeed(null))
        assertEquals(DefaultSeed, islandAccentForSeed(Color.Transparent))

        // Near-black dark seed gets lifted to readable brightness on Obsidian (#08080A).
        val lifted = islandAccentForSeed(Color(0.08f, 0.02f, 0.02f))
        val liftedHsv = rgbToHsv(lifted.red, lifted.green, lifted.blue)
        assertTrue(liftedHsv[1] >= 0.44f)
        assertTrue(liftedHsv[2] >= 0.67f)

        // Greyscale art falls back to soft neutral light instead of oversaturating noise.
        val neutral = islandAccentForSeed(Color(0.25f, 0.25f, 0.25f))
        assertEquals(Color(0xFFE5E5EA), neutral)
    }
}
