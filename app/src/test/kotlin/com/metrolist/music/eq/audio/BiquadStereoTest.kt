/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.eq.audio

import com.metrolist.music.eq.data.FilterType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The allocation-free stereo path of the equaliser (left = processSample, right = processRight) is the old Pair path. */
class BiquadStereoTest {
    @Test
    fun `left and right without a Pair give exactly the old stereo result`() {
        for (type in listOf(FilterType.PK, FilterType.LSC, FilterType.HSC)) {
            val old = BiquadFilter(48_000, 1_000.0, 6.0, 0.9, type)
            val new = BiquadFilter(48_000, 1_000.0, 6.0, 0.9, type)
            for (n in 0 until 4_000) {
                val l = kotlin.math.sin(n * 0.031) * 0.7
                val r = kotlin.math.cos(n * 0.017) * 0.4
                val (ol, or) = old.processStereo(l, r)
                assertEquals(ol, new.processSample(l), 0.0)
                assertEquals(or, new.processRight(r), 0.0)
            }
        }
    }
}
