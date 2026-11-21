package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The places of the rows of the lyrics view (the overlap seen in a user video: rows put 68 dp apart from a guessed height
 * while a wrapped line is twice that, then sliding for 750 ms to their real places).
 */
class LyricsRowPlacementTest {
    private val gap = 6f

    private fun place(
        heights: Map<Int, Int>,
        count: Int,
        anchor: Int,
        noGapBefore: Set<Int> = emptySet(),
    ) = LyricsRowPlacement.compute(
        count = count,
        anchor = anchor,
        measured = { heights[it] },
        guess = { 68f },
        gapBefore = { if (it in noGapBefore) 0f else gap },
    )

    @Test
    fun `measured rows are stacked by their real heights and marked exact`() {
        val p = place(mapOf(0 to 120, 1 to 200, 2 to 80, 3 to 90), count = 4, anchor = 1)
        assertEquals(0f, p.positions.getValue(1))
        assertEquals(-(120f + gap), p.positions.getValue(0))
        assertEquals(200f + gap, p.positions.getValue(2))
        assertEquals(200f + gap + 80f + gap, p.positions.getValue(3))
        assertTrue((0..3).all { p.isExact(it) })
    }

    @Test
    fun `a row whose place rests on a guessed height is inexact`() {
        // row 2 (below the anchor) is not measured yet: row 3 rests on its guessed height, row 2 itself does not
        val p = place(mapOf(0 to 120, 1 to 200, 3 to 90), count = 4, anchor = 1)
        assertTrue(p.isExact(1))
        assertTrue(p.isExact(2))
        assertFalse(p.isExact(3))
        // above the anchor a row's own height decides its place
        val q = place(mapOf(1 to 200, 2 to 80), count = 3, anchor = 1)
        assertFalse(q.isExact(0))
        assertTrue(q.isExact(2))
    }

    @Test
    fun `nothing measured yet is all inexact except the anchor`() {
        val p = place(emptyMap(), count = 5, anchor = 2)
        assertTrue(p.isExact(2))
        assertEquals(listOf(false, false, true, false, false), (0..4).map { p.isExact(it) })
        // the guess is what the first frame used: rows 68 + 6 px apart
        assertEquals(74f, p.positions.getValue(3))
    }

    @Test
    fun `the gap rule is the same above and below the anchor`() {
        // row 2 is a background line under row 1: no gap before it, wherever the anchor is
        val heights = mapOf(0 to 100, 1 to 100, 2 to 50, 3 to 100)
        val fromTop = place(heights, 4, anchor = 0, noGapBefore = setOf(2))
        val fromBottom = place(heights, 4, anchor = 3, noGapBefore = setOf(2))
        val distanceTop = fromTop.positions.getValue(3) - fromTop.positions.getValue(1)
        val distanceBottom = fromBottom.positions.getValue(3) - fromBottom.positions.getValue(1)
        assertEquals(distanceTop, distanceBottom)
        assertEquals(100f + 50f + gap, distanceTop)
    }

    @Test
    fun `an empty list or a bad anchor gives no places`() {
        assertTrue(place(emptyMap(), 0, 0).positions.isEmpty())
        assertTrue(place(emptyMap(), 3, -1).positions.isEmpty())
        assertFalse(place(emptyMap(), 3, -1).isExact(0))
    }
}
