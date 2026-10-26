package com.utaloom.ui

import com.utaloom.shared.LyricLine

// ponytail: 300ms lookahead mirrors Shared.currentIndex so both stay in sync; unify when the island renders line lists directly.
private const val LYRIC_LOOKAHEAD_MS = 300L

private const val GLOW_FULL_ALPHA = 1f

// Matches the dim tint the island already paints for non-current lines (0x80FFFFFF).
private const val GLOW_DIM_ALPHA = 0.5f

/**
 * Index of the active lyric line at [positionMs]: the last non-background line that
 * started at or 300ms before the position. Returns -1 for empty lists, unsynced
 * lyrics, or positions before the first line's lookahead window.
 */
internal fun currentLineIndex(lines: List<LyricLine>, positionMs: Long, synced: Boolean = true): Int {
    if (!synced || lines.isEmpty()) return -1
    return lines.indexOfLast { !it.background && it.timeMs <= positionMs + LYRIC_LOOKAHEAD_MS }
}

/**
 * Window of [radius] lines around [index] (inclusive of the current line), as
 * (original index, line) pairs. Clamped at both ends; empty when [index] is out
 * of bounds or [lines] is empty.
 */
internal fun neighboringLines(lines: List<LyricLine>, index: Int, radius: Int = 2): List<IndexedValue<LyricLine>> {
    if (index !in lines.indices) return emptyList()
    val from = (index - radius).coerceAtLeast(0)
    val to = (index + radius).coerceAtMost(lines.lastIndex)
    return (from..to).map { IndexedValue(it, lines[it]) }
}

/**
 * Alpha for the active line as it plays through: 1f (white) at the line start
 * fading to [GLOW_DIM_ALPHA] at the end. Values outside 0..1 clamp.
 */
internal fun karaokeGlow(progress: Float): Float = GLOW_FULL_ALPHA - GLOW_DIM_ALPHA * progress.coerceIn(0f, 1f)

/** Played fraction for the compact seek bar, clamped to 0..1; 0f when duration is unknown. */
internal fun seekFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
