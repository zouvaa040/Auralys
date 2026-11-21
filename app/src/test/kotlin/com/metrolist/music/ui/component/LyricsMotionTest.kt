/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.screens.settings.LyricsPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers and rules of the lyrics motion: the table of depth by distance, the sweep, the dots, the scroll anchor, the blur switch. */
class LyricsMotionTest {
    private val eps = 0.0001f

    // ---------------------------------------------------------------- depth by distance

    @Test
    fun `the active line is full opacity and full size, the others fall off like the Windows view`() {
        assertEquals(1f, LyricsMotion.lineAlpha(0, active = true), 0f)
        assertEquals(1f, LyricsMotion.lineAlpha(7, active = true), 0f)
        assertEquals(0.47f, LyricsMotion.lineAlpha(1, active = false), eps)
        assertEquals(0.38f, LyricsMotion.lineAlpha(2, active = false), eps)
        assertEquals(0.29f, LyricsMotion.lineAlpha(3, active = false), eps)
        assertEquals(0.20f, LyricsMotion.lineAlpha(4, active = false), eps)
        // from five rows on it stops at the floor
        for (d in 5..10) assertEquals(0.18f, LyricsMotion.lineAlpha(d, active = false), eps)
        assertEquals(1f, LyricsMotion.lineScale(true), 0f)
        assertEquals(0.94f, LyricsMotion.lineScale(false), 0f)
    }

    @Test
    fun `a row that is the anchor but not being sung, or a negative distance, counts as one row away`() {
        assertEquals(LyricsMotion.lineAlpha(1, false), LyricsMotion.lineAlpha(0, false), 0f)
        assertEquals(LyricsMotion.lineAlpha(1, false), LyricsMotion.lineAlpha(-3, false), 0f)
    }

    @Test
    fun `opacity never rises with the distance`() {
        var last = 1f
        for (d in 0..12) {
            val a = LyricsMotion.lineAlpha(d, active = d == 0)
            assertTrue("d=$d: $a > $last", a <= last + eps)
            last = a
        }
    }

    @Test
    fun `blur grows by 0 8 dp per row up to four rows and is off beyond that and for the active row`() {
        assertEquals(0f, LyricsMotion.lineBlurDp(0, active = true), 0f)
        assertEquals(0f, LyricsMotion.lineBlurDp(0, active = false), 0f)
        assertEquals(0.8f, LyricsMotion.lineBlurDp(1, false), eps)
        assertEquals(1.6f, LyricsMotion.lineBlurDp(2, false), eps)
        assertEquals(2.4f, LyricsMotion.lineBlurDp(3, false), eps)
        assertEquals(3.2f, LyricsMotion.lineBlurDp(4, false), eps)
        for (d in 5..10) assertEquals("d=$d", 0f, LyricsMotion.lineBlurDp(d, false), 0f)
        assertEquals(0f, LyricsMotion.lineBlurDp(2, active = true), 0f)
        assertEquals(0f, LyricsMotion.lineBlurDp(-1, false), 0f)
    }

    // ---------------------------------------------------------------- when blur may be used

    @Test
    fun `blur needs API 31, a phone with room, no battery saver, motion on and the user's switch`() {
        assertTrue(LyricsMotion.blurAllowed(sdk = 31, lowRam = false, powerSave = false, animationScale = 1f, userSwitch = true))
        assertTrue(LyricsMotion.blurAllowed(sdk = 36, lowRam = false, powerSave = false, animationScale = 0.5f, userSwitch = true))
        assertFalse("API 30", LyricsMotion.blurAllowed(30, false, false, 1f, true))
        assertFalse("API 26", LyricsMotion.blurAllowed(26, false, false, 1f, true))
        assertFalse("low RAM", LyricsMotion.blurAllowed(34, true, false, 1f, true))
        assertFalse("battery saver", LyricsMotion.blurAllowed(34, false, true, 1f, true))
        assertFalse("animation scale 0", LyricsMotion.blurAllowed(34, false, false, 0f, true))
        assertFalse("user switch off", LyricsMotion.blurAllowed(34, false, false, 1f, false))
    }

    @Test
    fun `a row is blurred only when blur is allowed, it is on screen, near and the user is not scrolling or selecting`() {
        assertEquals(1.6f, LyricsMotion.targetBlurDp(true, 2, active = false, onScreen = true, relaxed = false), eps)
        assertEquals(0f, LyricsMotion.targetBlurDp(false, 2, false, true, false), 0f)
        assertEquals(0f, LyricsMotion.targetBlurDp(true, 2, false, false, false), 0f)
        assertEquals(0f, LyricsMotion.targetBlurDp(true, 2, false, true, true), 0f)
        assertEquals(0f, LyricsMotion.targetBlurDp(true, 5, false, true, false), 0f)
        assertEquals(0f, LyricsMotion.targetBlurDp(true, 0, true, true, false), 0f)
    }

    @Test
    fun `a row is on screen while any part of it is inside the area`() {
        assertTrue(LyricsMotion.onScreen(y = 0f, height = 50f, areaHeight = 600f))
        assertTrue(LyricsMotion.onScreen(y = -49f, height = 50f, areaHeight = 600f))
        assertFalse("ends exactly at the top", LyricsMotion.onScreen(y = -50f, height = 50f, areaHeight = 600f))
        assertTrue(LyricsMotion.onScreen(y = 599f, height = 50f, areaHeight = 600f))
        assertFalse("starts exactly at the bottom", LyricsMotion.onScreen(y = 600f, height = 50f, areaHeight = 600f))
        assertFalse(LyricsMotion.onScreen(y = 5000f, height = 50f, areaHeight = 600f))
        assertFalse("an empty area shows nothing", LyricsMotion.onScreen(y = 0f, height = 50f, areaHeight = 0f))
        // a margin makes a row ready just before it scrolls in
        assertTrue(LyricsMotion.onScreen(y = -120f, height = 50f, areaHeight = 600f, margin = 100f))
        assertFalse(LyricsMotion.onScreen(y = -160f, height = 50f, areaHeight = 600f, margin = 100f))
        assertTrue(LyricsMotion.onScreen(y = 690f, height = 50f, areaHeight = 600f, margin = 100f))
        assertFalse(LyricsMotion.onScreen(y = 700f, height = 50f, areaHeight = 600f, margin = 100f))
    }

    @Test
    fun `motion is off exactly when the animation scale is zero`() {
        assertTrue(LyricsMotion.animationsEnabled(1f))
        assertTrue(LyricsMotion.animationsEnabled(0.25f))
        assertFalse(LyricsMotion.animationsEnabled(0f))
        assertFalse(LyricsMotion.animationsEnabled(-1f))
    }

    // ---------------------------------------------------------------- lyrics without timing

    @Test
    fun `untimed rows step towards the edges of the view`() {
        assertEquals(0, LyricsMotion.edgeStep(centerY = 300f, areaHeight = 600f))
        assertEquals(0, LyricsMotion.edgeStep(centerY = 300f + 0.27f * 600f, areaHeight = 600f))
        assertEquals(1, LyricsMotion.edgeStep(centerY = 300f + 0.30f * 600f, areaHeight = 600f))
        assertEquals(2, LyricsMotion.edgeStep(centerY = 300f + 0.40f * 600f, areaHeight = 600f))
        assertEquals(2, LyricsMotion.edgeStep(centerY = -5000f, areaHeight = 600f))
        assertEquals(1, LyricsMotion.edgeStep(centerY = 300f - 0.30f * 600f, areaHeight = 600f))
        assertEquals(0, LyricsMotion.edgeStep(centerY = 100f, areaHeight = 0f))
        assertEquals(0, LyricsMotion.edgeStep(centerY = 100f, areaHeight = -10f))
    }

    @Test
    fun `untimed rows dim and blur by step and the steps are clamped`() {
        assertEquals(0.94f, LyricsMotion.edgeAlpha(0), eps)
        assertEquals(0.78f, LyricsMotion.edgeAlpha(1), eps)
        assertEquals(0.62f, LyricsMotion.edgeAlpha(2), eps)
        assertEquals(0.62f, LyricsMotion.edgeAlpha(9), eps)
        assertEquals(0.94f, LyricsMotion.edgeAlpha(-4), eps)
        assertEquals(0f, LyricsMotion.edgeBlurDp(0), 0f)
        assertEquals(0.7f, LyricsMotion.edgeBlurDp(1), eps)
        assertEquals(1.4f, LyricsMotion.edgeBlurDp(2), eps)
        assertEquals(1.4f, LyricsMotion.edgeBlurDp(8), eps)
    }

    @Test
    fun `the entrance of untimed rows is staggered by 40 ms for the first 14 rows`() {
        assertEquals(0L, LyricsMotion.enterDelayMs(0))
        assertEquals(40L, LyricsMotion.enterDelayMs(1))
        assertEquals(560L, LyricsMotion.enterDelayMs(14))
        assertEquals(560L, LyricsMotion.enterDelayMs(500))
        assertEquals(0L, LyricsMotion.enterDelayMs(-3))
    }

    // ---------------------------------------------------------------- the sweep of a line

    @Test
    fun `a line without word timing is swept until the next line, or for a time that grows with its length`() {
        // short line, next line far away: 1.6 s
        assertEquals(10_000L + 1600L, LyricsMotion.lineSweepEndMs(10_000L, nextStartMs = 20_000L, textLength = 5))
        // empty text still takes the minimum
        assertEquals(10_000L + 1600L, LyricsMotion.lineSweepEndMs(10_000L, 20_000L, 0))
        assertEquals(10_000L + 1600L, LyricsMotion.lineSweepEndMs(10_000L, 20_000L, -7))
        // 30 chars: 95 ms each = 2.85 s
        assertEquals(10_000L + 2850L, LyricsMotion.lineSweepEndMs(10_000L, 20_000L, 30))
        // never longer than the line itself (the next line starts first)
        assertEquals(11_000L, LyricsMotion.lineSweepEndMs(10_000L, 11_000L, 80))
        // the last line has no next one: 4 s is the longest it can take
        assertEquals(10_000L + 1600L, LyricsMotion.lineSweepEndMs(10_000L, null, 3))
        assertEquals(10_000L + 4000L, LyricsMotion.lineSweepEndMs(10_000L, null, 200))
    }

    @Test
    fun `the line sweep is 0 before the line, linear through it and 1 after it`() {
        assertEquals(0f, LyricsMotion.lineSweepFraction(900L, 1000L, 3000L), 0f)
        assertEquals(0f, LyricsMotion.lineSweepFraction(1000L, 1000L, 3000L), 0f)
        assertEquals(0.25f, LyricsMotion.lineSweepFraction(1500L, 1000L, 3000L), eps)
        assertEquals(0.5f, LyricsMotion.lineSweepFraction(2000L, 1000L, 3000L), eps)
        assertEquals(1f, LyricsMotion.lineSweepFraction(3000L, 1000L, 3000L), 0f)
        assertEquals("a position past the end is 1, not more", 1f, LyricsMotion.lineSweepFraction(90_000L, 1000L, 3000L), 0f)
    }

    @Test
    fun `a line of no length, or one that ends before it starts, jumps instead of dividing by zero`() {
        assertEquals(0f, LyricsMotion.lineSweepFraction(1000L, 1000L, 1000L), 0f)
        assertEquals(1f, LyricsMotion.lineSweepFraction(1001L, 1000L, 1000L), 0f)
        assertEquals(1f, LyricsMotion.lineSweepFraction(2000L, 1000L, 500L), 0f)
        assertEquals(0f, LyricsMotion.lineSweepFraction(-5L, 0L, 0L), 0f)
        // a negative position (a negative lyric offset at the start of the song)
        assertEquals(0f, LyricsMotion.lineSweepFraction(-500L, 0L, 2000L), 0f)
    }

    @Test
    fun `words are found in the text in reading order, a missing word gives null`() {
        val spans = LyricsMotion.wordSpans("Angin malam membawa cerita", listOf("Angin", "malam", "membawa", "cerita"))
        assertArrayEquals(intArrayOf(0, 5, 6, 11, 12, 19, 20, 26), spans)
        assertArrayEquals(intArrayOf(0, 2, 3, 5, 6, 8), LyricsMotion.wordSpans("la la la", listOf("la", "la", "la")))
        assertNull(LyricsMotion.wordSpans("Angin malam", listOf("Angin", "pagi")))
        assertNull("out of order", LyricsMotion.wordSpans("malam Angin", listOf("Angin", "malam")))
        assertArrayEquals(IntArray(0), LyricsMotion.wordSpans("anything", emptyList()))
        assertArrayEquals(intArrayOf(0, 0, 0, 3), LyricsMotion.wordSpans("abc", listOf("", "abc")))
        // the pieces of a held word are cut at hyphens and sit side by side
        assertArrayEquals(intArrayOf(0, 3, 3, 6, 6, 8), LyricsMotion.wordSpans("Oh-oh-oh", listOf("Oh-", "oh-", "oh")))
    }

    @Test
    fun `a held word is found by its hyphens, an ordinary line is not`() {
        assertTrue(LyricsMotion.hasHyphenGroup(listOf("Oh-oh-oh"), listOf(false)))
        assertTrue(LyricsMotion.hasHyphenGroup(listOf("Hel-", "lo", "world"), listOf(false, true, false)))
        assertFalse("one piece alone is not a group", LyricsMotion.hasHyphenGroup(listOf("Hel-"), listOf(false)))
        assertFalse(LyricsMotion.hasHyphenGroup(listOf("Angin", "malam"), listOf(true, false)))
        assertFalse("a hyphen inside a word followed by a space is one word", LyricsMotion.hasHyphenGroup(listOf("well-known", "song"), listOf(true, false)))
        assertFalse(LyricsMotion.hasHyphenGroup(emptyList(), emptyList()))
    }

    @Test
    fun `made up word times cover the line, grow with the words and never go backwards`() {
        val words = LyricsMotion.spreadWords("Angin malam membawa cerita", 1000L, 3000L)
        assertEquals(listOf("Angin", "malam", "membawa", "cerita"), words.map { it.text })
        assertEquals(1000L, words.first().startMs)
        assertEquals(3000L, words.last().endMs)
        for (i in 1 until words.size) assertTrue(words[i].startMs >= words[i - 1].startMs && words[i].startMs >= words[i - 1].endMs - 1)
        for (w in words) assertTrue(w.endMs >= w.startMs)
        // longer words take longer
        assertTrue(words[2].endMs - words[2].startMs > words[0].endMs - words[0].startMs)
        assertEquals(listOf(true, true, true, false), words.map { it.trailingSpace })
    }

    @Test
    fun `made up word times for empty text, extra spaces and a line that ends before it starts`() {
        assertTrue(LyricsMotion.spreadWords("", 0L, 1000L).isEmpty())
        assertTrue(LyricsMotion.spreadWords("   ", 0L, 1000L).isEmpty())
        val spaced = LyricsMotion.spreadWords("  a   b ", 0L, 100L)
        assertEquals(listOf("a", "b"), spaced.map { it.text })
        val backwards = LyricsMotion.spreadWords("one two", 5000L, 1000L)
        for (w in backwards) assertTrue("${w.startMs}..${w.endMs}", w.endMs >= w.startMs && w.startMs >= 5000L)
    }

    @Test
    fun `the word curve sits on the word being sung and creeps across the gaps`() {
        // two words: 1000..2000 over 0..0.4 and 3000..4000 over 0.5..1
        val curve = LyricsMotion.sweepCurve(longArrayOf(1000L, 3000L), longArrayOf(2000L, 4000L), floatArrayOf(0f, 0.5f), floatArrayOf(0.4f, 1f))
        assertNotNull(curve)
        curve!!
        assertEquals("before the first word", 0f, curve.fraction(0L), 0f)
        assertEquals(0f, curve.fraction(1000L), eps)
        assertEquals(0.2f, curve.fraction(1500L), eps)
        assertEquals(0.4f, curve.fraction(2000L), eps)
        assertEquals("half way through the pause", 0.45f, curve.fraction(2500L), eps)
        assertEquals(0.5f, curve.fraction(3000L), eps)
        assertEquals(0.75f, curve.fraction(3500L), eps)
        assertEquals(1f, curve.fraction(4000L), eps)
        assertEquals("after the last word", 1f, curve.fraction(99_000L), 0f)
    }

    @Test
    fun `the word curve is monotonic even when the data is not`() {
        val curve = LyricsMotion.sweepCurve(longArrayOf(2000L, 1000L), longArrayOf(2500L, 1500L), floatArrayOf(0.5f, 0.2f), floatArrayOf(0.8f, 0.4f))!!
        var last = -1f
        for (t in 0L..4000L step 50L) {
            val f = curve.fraction(t)
            assertTrue("t=$t: $f < $last", f >= last - eps)
            assertTrue(f in 0f..1f)
            last = f
        }
    }

    @Test
    fun `a word with the same start and end, and words that start together, do not break the curve`() {
        val curve = LyricsMotion.sweepCurve(longArrayOf(1000L, 1000L), longArrayOf(1000L, 2000L), floatArrayOf(0f, 0.3f), floatArrayOf(0.3f, 1f))!!
        assertEquals(0f, curve.fraction(999L), eps)
        assertEquals(0.3f, curve.fraction(1000L), eps)
        assertEquals(0.65f, curve.fraction(1500L), eps)
        assertEquals(1f, curve.fraction(2000L), eps)
    }

    @Test
    fun `there is no curve for no words or for arrays of different size`() {
        assertNull(LyricsMotion.sweepCurve(LongArray(0), LongArray(0), FloatArray(0), FloatArray(0)))
        assertNull(LyricsMotion.sweepCurve(longArrayOf(1L), longArrayOf(2L, 3L), floatArrayOf(0f), floatArrayOf(1f)))
    }

    @Test
    fun `the soft edge of a one line sweep runs from the start to past the end`() {
        val widths = floatArrayOf(300f)
        val start = LyricsMotion.sweepEdge(0f, widths, 14f)
        assertEquals(0, start.line)
        assertEquals(0f, start.offset, 0f)
        val end = LyricsMotion.sweepEdge(1f, widths, 14f)
        assertEquals(0, end.line)
        assertEquals("past the last glyph, so it is fully sung", 314f, end.offset, eps)
        assertEquals(157f, LyricsMotion.sweepEdge(0.5f, widths, 14f).offset, eps)
        assertEquals("a fraction past 1 is 1", 314f, LyricsMotion.sweepEdge(9f, widths, 14f).offset, eps)
        assertEquals("a negative fraction is 0", 0f, LyricsMotion.sweepEdge(-1f, widths, 14f).offset, 0f)
    }

    @Test
    fun `the soft edge goes line by line on a wrapped line`() {
        val widths = floatArrayOf(100f, 50f) // spans 110 and 60 with a 10 px edge, 170 in all
        val first = LyricsMotion.sweepEdge(55f / 170f, widths, 10f)
        assertEquals(0, first.line)
        assertEquals(55f, first.offset, eps)
        val second = LyricsMotion.sweepEdge(140f / 170f, widths, 10f)
        assertEquals(1, second.line)
        assertEquals(30f, second.offset, eps)
        val done = LyricsMotion.sweepEdge(1f, widths, 10f)
        assertEquals(1, done.line)
        assertEquals(60f, done.offset, eps)
    }

    @Test
    fun `the soft edge of no lines, and of empty lines, is at the start`() {
        val none = LyricsMotion.sweepEdge(0.5f, FloatArray(0), 14f)
        assertEquals(0, none.line)
        assertEquals(0f, none.offset, 0f)
        val empty = LyricsMotion.sweepEdge(1f, floatArrayOf(0f), 14f)
        assertEquals(0, empty.line)
        assertEquals(14f, empty.offset, eps)
    }

    @Test
    fun `turning a place on the line into a fraction and back is the same place`() {
        val widths = floatArrayOf(120f, 80f, 40f)
        for (line in widths.indices) {
            for (x in listOf(0f, 10f, widths[line], widths[line] + 14f)) {
                val f = LyricsMotion.fractionAt(line, x, widths, 14f)
                val edge = LyricsMotion.sweepEdge(f, widths, 14f)
                // the end of a line and the start of the next are the same moment, so either answer is the place
                val same = (edge.line == line && Math.abs(edge.offset - x) < 0.01f) ||
                    (edge.line == line + 1 && edge.offset < 0.01f && Math.abs(x - (widths[line] + 14f)) < 0.01f) ||
                    (line > 0 && edge.line == line - 1 && x < 0.01f && Math.abs(edge.offset - (widths[line - 1] + 14f)) < 0.01f)
                assertTrue("line=$line x=$x -> f=$f -> ${edge.line}/${edge.offset}", same)
            }
        }
        assertEquals(0f, LyricsMotion.fractionAt(0, 5f, FloatArray(0), 14f), 0f)
        assertEquals(0f, LyricsMotion.fractionAt(0, -50f, widths, 14f), 0f)
        assertEquals(1f, LyricsMotion.fractionAt(2, 9999f, widths, 14f), 0f)
    }

    // ---------------------------------------------------------------- the dots

    @Test
    fun `the progress of a gap is 0 to 1, a gap that is over counts as full, a gap with no length does not divide by zero`() {
        assertEquals(0f, LyricsMotion.gapProgress(900L, 1000L, 5000L), 0f)
        assertEquals(0f, LyricsMotion.gapProgress(1000L, 1000L, 5000L), 0f)
        assertEquals(0.25f, LyricsMotion.gapProgress(2000L, 1000L, 5000L), eps)
        assertEquals(1f, LyricsMotion.gapProgress(5000L, 1000L, 5000L), 0f)
        assertEquals(1f, LyricsMotion.gapProgress(9000L, 1000L, 5000L), 0f)
        assertEquals(1f, LyricsMotion.gapProgress(1000L, 1000L, 1000L), 0f)
        assertEquals(0f, LyricsMotion.gapProgress(999L, 1000L, 1000L), 0f)
        assertEquals("a gap shorter than a second", 0.5f, LyricsMotion.gapProgress(1400L, 1000L, 1800L), eps)
        assertEquals(1f, LyricsMotion.gapProgress(2000L, 1000L, 500L), 0f)
    }

    @Test
    fun `the three dots fill one after the other`() {
        assertEquals(3, LyricsMotion.DOT_COUNT)
        fun fills(p: Float) = (0 until 3).map { LyricsMotion.dotFill(p, it) }
        assertEquals(listOf(0f, 0f, 0f), fills(0f))
        assertEquals(1f / 3f * 3f * 0.3f, fills(0.1f)[0], eps) // 0.3: the first dot is a third full
        assertEquals(listOf(1f, 0f, 0f), fills(1f / 3f).map { Math.round(it * 1000f) / 1000f })
        assertEquals(listOf(1f, 0.5f, 0f), fills(0.5f))
        assertEquals(listOf(1f, 1f, 0f), fills(2f / 3f).map { Math.round(it * 1000f) / 1000f })
        assertEquals(listOf(1f, 1f, 1f), fills(1f))
        assertEquals("progress above 1", listOf(1f, 1f, 1f), fills(3f))
        assertEquals("negative progress", listOf(0f, 0f, 0f), fills(-2f))
        // a dot that does not exist
        assertEquals(0f, LyricsMotion.dotFill(0.5f, 7), 0f)
        assertEquals(1f, LyricsMotion.dotFill(0.5f, -2), 0f)
    }

    @Test
    fun `the dots breathe between 0 92 and 1 08 and the ends are the limits`() {
        assertEquals(0.92f, LyricsMotion.breathScale(0f), eps)
        assertEquals(1.08f, LyricsMotion.breathScale(1f), eps)
        assertEquals(1.0f, LyricsMotion.breathScale(0.5f), eps)
        assertEquals(0.92f, LyricsMotion.breathScale(-4f), eps)
        assertEquals(1.08f, LyricsMotion.breathScale(7f), eps)
        var last = 0f
        for (i in 0..20) {
            val s = LyricsMotion.breathScale(i / 20f)
            assertTrue(s in 0.92f..1.08f)
            assertTrue(s >= last - eps)
            last = s
        }
        assertEquals(1100, LyricsMotion.BREATH_MS)
        assertEquals(0.25f, LyricsMotion.DOT_BASE_ALPHA, 0f)
        assertTrue(LyricsMotion.DOT_SIZE_DP in 11f..13f)
    }

    @Test
    fun `the dots sit where the text sits, or where the next singer is`() {
        assertEquals(0f, LyricsMotion.horizontalFor(null, true, LyricsPosition.LEFT), 0f)
        assertEquals(0.5f, LyricsMotion.horizontalFor(null, true, LyricsPosition.CENTER), 0f)
        assertEquals(1f, LyricsMotion.horizontalFor(null, true, LyricsPosition.RIGHT), 0f)
        assertEquals(0f, LyricsMotion.horizontalFor("v1", true, LyricsPosition.RIGHT), 0f)
        assertEquals(1f, LyricsMotion.horizontalFor("v2", true, LyricsPosition.LEFT), 0f)
        assertEquals(0.5f, LyricsMotion.horizontalFor("v1000", true, LyricsPosition.LEFT), 0f)
        assertEquals("agents are ignored when the user turned them off", 0.5f, LyricsMotion.horizontalFor("v2", false, LyricsPosition.CENTER), 0f)
        assertEquals(0f, LyricsMotion.horizontalFor("v7", true, LyricsPosition.LEFT), 0f)
    }

    // ---------------------------------------------------------------- the scroll anchor

    @Test
    fun `the active line sits about a third from the top`() {
        assertEquals(320f, LyricsMotion.anchorY(1000f), 0.01f)
        assertTrue(LyricsMotion.ANCHOR_RATIO in 0.30f..0.35f)
        assertEquals(0f, LyricsMotion.anchorY(0f), 0f)
    }

    @Test
    fun `the hand scroll limits are the old ones with the new anchor`() {
        val area = 2000f
        val anchor = LyricsMotion.anchorY(area)
        // by hand: the last line may rise to 100 px from the top, the first may sink to 150 px from the bottom
        assertEquals(100f - anchor - 800f - 120f, LyricsMotion.scrollMin(anchor, belowActivePx = 800f, lastHeightPx = 120f, keepBelowPx = 100f), 0.01f)
        assertEquals(area - 150f - anchor + 500f, LyricsMotion.scrollMax(area, keepAbovePx = 150f, anchorY = anchor, aboveActivePx = 500f), 0.01f)
    }

    @Test
    fun `at the start of the song the first lines fit under the fade, and at the end the last line does`() {
        for (areaDp in listOf(420f, 520f, 640f, 760f)) {
            val anchor = LyricsMotion.anchorY(areaDp)
            val fadeTop = areaDp * LyricsMotion.FADE_TOP
            val fadeBottom = areaDp * (1f - LyricsMotion.FADE_BOTTOM)
            // the active line (even a long one of three wrapped lines, 3 x 42 dp) is clear of both fades
            val oneLine = 42f + 2 * LyricsMotion.ROW_PAD_DP
            val threeLines = 3 * 42f + 2 * LyricsMotion.ROW_PAD_DP
            assertTrue("area $areaDp: the anchor is under the top fade", anchor >= fadeTop)
            assertTrue("area $areaDp: one line is above the bottom fade", anchor + oneLine <= fadeBottom)
            assertTrue("area $areaDp: three lines are above the bottom fade", anchor + threeLines <= fadeBottom)
            // and there is room above for the previous line to peek in, and below for the next ones
            assertTrue(anchor > oneLine * 0.9f || areaDp < 450f)
            assertTrue(areaDp - anchor > 2 * oneLine)
        }
    }

    @Test
    fun `the fade is 13 percent at the top and 14 percent at the bottom`() {
        assertEquals(0.13f, LyricsMotion.FADE_TOP, 0f)
        assertEquals(0.14f, LyricsMotion.FADE_BOTTOM, 0f)
        assertTrue(LyricsMotion.FADE_TOP + LyricsMotion.FADE_BOTTOM < 0.5f)
    }

    // ---------------------------------------------------------------- the user's settings

    @Test
    fun `the default settings leave the type as it is, and extremes are held`() {
        assertEquals(1f, LyricsMotion.sizeScale(24f), eps)
        assertEquals(1f, LyricsMotion.spacingScale(1.2f), eps)
        assertEquals(1.35f, LyricsMotion.sizeScale(200f), 0f)
        assertEquals(0.75f, LyricsMotion.sizeScale(2f), 0f)
        assertEquals(0.75f, LyricsMotion.sizeScale(-5f), 0f)
        assertEquals(1.3f, LyricsMotion.spacingScale(9f), 0f)
        assertEquals(0.85f, LyricsMotion.spacingScale(0f), 0f)
        assertEquals(1.25f, 1.25f * LyricsMotion.spacingScale(1.2f), eps)
    }

    @Test
    fun `the type is bold and tight like the Windows view`() {
        assertTrue(LyricsMotion.TEXT_SP in 32f..36f)
        assertEquals(1.25f, LyricsMotion.LINE_HEIGHT_RATIO, 0f)
        assertTrue(LyricsMotion.LETTER_SPACING_SP in -0.5f..-0.2f)
    }

    @Test
    fun `lyrics start at the left edge unless the user chose a position`() {
        assertEquals(LyricsPosition.LEFT, DEFAULT_LYRICS_POSITION)
    }
}
