/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The glides of the lyrics (critically damped curve, added up), the wave of the rows behind them, and the cached active lines. */
class LyricsScrollMathTest {
    private val dt = 1f / 60f

    @Test
    fun `a glide reaches its place without overshoot and settles in under a second`() {
        var last = LyricsFollowMath.remaining(0f)
        assertEquals(1f, last)
        var t = 0f
        var settledAt = -1f
        while (t < 2f) {
            t += dt
            val r = LyricsFollowMath.remaining(t)
            assertTrue("overshoot at $t", r >= 0f)
            assertTrue("moved backwards at $t", r <= last)
            last = r
            if (settledAt < 0f && 400f * r < LyricsFollowMath.REST_PX) settledAt = t
        }
        assertTrue("400 px settled after $settledAt s", settledAt in 0.4f..1.0f)
        assertTrue(LyricsFollowMath.glideOver(400f, settledAt + LyricsFollowMath.DELAY_MAX_S + dt))
    }

    @Test
    fun `rows behind the anchor wait 20 ms per row, the anchor and the rows ahead do not`() {
        val row = 150f
        assertEquals(0f, LyricsFollowMath.delayFor(rowTop = 0f, anchorTop = 600f, delta = 200f, rowPx = row)) // above, moving up
        assertEquals(0f, LyricsFollowMath.delayFor(600f, 600f, 200f, row))
        assertEquals(0.04f, LyricsFollowMath.delayFor(900f, 600f, 200f, row), 1e-6f)
        assertEquals(LyricsFollowMath.DELAY_MAX_S, LyricsFollowMath.delayFor(90_000f, 600f, 200f, row), 1e-6f)
        // moving down (a seek back): the rows above wait
        assertEquals(0.04f, LyricsFollowMath.delayFor(300f, 600f, -200f, row), 1e-6f)
        assertEquals(0f, LyricsFollowMath.delayFor(900f, 600f, -200f, row))
    }

    /** One row's place on screen (relative to its first rest place) through glides added every [periodFrames] frames. */
    private fun rowPath(delay: Float, periodFrames: Int, frames: Int, delta: Float): FloatArray {
        val starts = mutableListOf<Int>()
        val ys = FloatArray(frames)
        for (f in 0 until frames) {
            if (f % periodFrames == 0) starts += f
            val rest = -delta * starts.size // the rest place moves up by one line at every line change
            var off = 0f
            for (s in starts) off += LyricsFollowMath.glideOffset(delta, (f - s) * dt, delay)
            ys[f] = rest + off
        }
        return ys
    }

    @Test
    fun `glides that overlap never stop, never turn back and never jump a row`() {
        // lines every 450 ms (27 frames) and every 300 ms (18 frames), rows 0 to 10 rows behind the anchor
        for (period in listOf(18, 27)) {
            for (rows in 0..10) {
                val ys = rowPath(LyricsFollowMath.DELAY_PER_ROW_S * rows, period, 600, 160f)
                var stopped = 0
                var moved = false
                for (f in 1 until ys.size) {
                    val d = ys[f] - ys[f - 1]
                    assertTrue("period $period, row $rows, frame $f: moved down $d px", d <= 0.001f)
                    assertTrue("period $period, row $rows, frame $f: jumped $d px", abs(d) < 25f)
                    if (abs(d) > 1f) {
                        assertTrue("period $period, row $rows: stood still for $stopped frames mid glide", !moved || stopped == 0 || stopped > 15)
                        moved = true
                        stopped = 0
                    } else if (abs(d) < 0.1f && moved) {
                        stopped++
                    }
                }
            }
        }
    }

    @Test
    fun `rows only move apart during glides, never together`() {
        val heights = intArrayOf(90, 160, 90, 90, 220, 90, 60, 90, 90, 90, 90, 90, 140, 90)
        val gaps = FloatArray(heights.size) { if (it == 0 || it == 6) 0f else 16f }
        val top = LyricsFollowMath.stack(heights, gaps)
        val rnd = Random(9)
        repeat(4000) {
            // up to three glides on at once, up or down, at any point of their course, anchored on any row
            val glides = List(rnd.nextInt(1, 4)) { Triple(rnd.nextFloat() * 1600f - 800f, rnd.nextFloat() * 1.2f, rnd.nextInt(heights.size)) }
            var prevBottom = Float.NEGATIVE_INFINITY
            for (i in heights.indices) {
                var off = 0f
                for ((delta, elapsed, anchor) in glides) {
                    off += LyricsFollowMath.glideOffset(delta, elapsed, LyricsFollowMath.delayFor(top[i], top[anchor], delta, 150f))
                }
                val y = top[i] + off
                assertTrue("glides $glides: row $i overlaps the row above", y - prevBottom >= gaps[i] - 0.01f)
                prevBottom = y + heights[i]
            }
        }
    }

    @Test
    fun `the stack follows the measured heights and the gaps`() {
        val top = LyricsFollowMath.stack(intArrayOf(100, 200, 50), floatArrayOf(0f, 6f, 0f))
        assertEquals(0f, top[0])
        assertEquals(106f, top[1])
        assertEquals(306f, top[2])
    }

    private fun words(start: Double, vararg w: String): List<WordTimestamp> {
        var t = start
        return w.mapIndexed { i, s ->
            val ws = WordTimestamp(s, t, t + 0.3, i < w.size - 1)
            t += 0.35
            ws
        }
    }

    @Test
    fun `the cached active lines are the same as the slow search at every position`() {
        val lineSynced = listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + (1..40).map { LyricsEntry(it * 2300L + (it % 3) * 400L, "line $it") }
        val withBackground =
            listOf(LyricsEntry.HEAD_LYRICS_ENTRY) +
                (1..30).flatMap {
                    listOf(
                        LyricsEntry(it * 3000L, "main $it", words = words(it * 3.0, "a", "b", "c")),
                        LyricsEntry(it * 3000L + 600L, "(bg $it)", words = words(it * 3.0 + 0.6, "x", "y"), isBackground = true),
                    )
                }
        val unsorted = listOf(LyricsEntry(0L, ""), LyricsEntry(5000L, "b"), LyricsEntry(3000L, "a"), LyricsEntry(9000L, "c"))
        for (lines in listOf(lineSynced, withBackground, unsorted)) {
            val cache = ActiveLinesCache(lines) { LyricsUtils.findActiveLineIndices(lines, it) }
            val rnd = Random(5)
            // forwards frame by frame, then random seeks
            var p = -500L
            while (p < 130_000L) {
                assertEquals("at $p", LyricsUtils.findActiveLineIndices(lines, p), cache.at(p))
                p += 16L
            }
            repeat(3000) {
                val q = rnd.nextLong(-1000L, 140_000L)
                assertEquals("at $q", LyricsUtils.findActiveLineIndices(lines, q), cache.at(q))
            }
        }
    }

    @Test
    fun `the cached active lines are worked out again only when a line starts or ends`() {
        val lines = listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + (1..40).map { LyricsEntry(it * 2500L, "line $it") }
        var searches = 0
        val cache = ActiveLinesCache(lines) { searches++; LyricsUtils.findActiveLineIndices(lines, it) }
        var p = 0L
        var frames = 0
        while (p < 100_000L) {
            cache.at(p)
            p += 16L
            frames++
        }
        // a line starts and ends (two times per line): at most two searches per line, against 6250 frames
        assertTrue("$searches searches in $frames frames", searches <= 2 * lines.size + 2)
    }

    @Test
    fun `the first line shown for new lyrics is the last main line that has started`() {
        val lines =
            listOf(
                LyricsEntry.HEAD_LYRICS_ENTRY,
                LyricsEntry(1000L, "one"),
                LyricsEntry(1500L, "(bg)", isBackground = true),
                LyricsEntry(4000L, "two"),
            )
        assertEquals(0, startedLineIndex(lines, 500L))
        assertEquals(1, startedLineIndex(lines, 1800L))
        assertEquals(3, startedLineIndex(lines, 9000L))
    }
}
