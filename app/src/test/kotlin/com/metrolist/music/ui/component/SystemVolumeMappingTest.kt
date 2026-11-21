/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * Report 7: the player page's volume bar on the system music stream. "0 % masih ada suara; 1-3 % pelan; 4 % langsung seperti
 * 10 %". The old bar rounded to the nearest of the phone's steps (15 here): 0-3 % were all one step and 4 % the next.
 */
class SystemVolumeMappingTest {
    private val max = 15
    private val table = SystemVolumeMapping.stepDbTable(max, systemDb = null)

    /** The loudness that reaches the ear: the step's dB plus the app's trim. */
    private fun loudnessDb(fraction: Float): Float {
        val t = SystemVolumeMapping.target(fraction, 0, max, table)
        return if (t.trim <= 0f) Float.NEGATIVE_INFINITY else table[t.index] + 20f * log10(t.trim)
    }

    @Test
    fun `0 percent is silent even on a phone whose lowest step is not`() {
        val t = SystemVolumeMapping.target(0f, 0, max, table)
        assertEquals(0f, t.trim, 0f)
        val noMute = SystemVolumeMapping.target(0f, 1, max, table) // lowest step 1, still audible
        assertEquals(1, noMute.index)
        assertEquals(0f, noMute.trim, 0f)
    }

    @Test
    fun `the old bar - 0 to 3 percent were one step and 4 percent the next`() {
        // what the bar did before (rounding to the nearest step), kept here as the documented bug
        val old = (0..10).map { VolumeMath.systemStep(it / 100f, max) }
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2), old)
    }

    @Test
    fun `loudness rises smoothly and never jumps from 1 to 100 percent`() {
        var previous = loudnessDb(0.01f)
        for (p in 2..100) {
            val now = loudnessDb(p / 100f)
            assertTrue("level fell at $p %: $previous -> $now", now >= previous - 1e-3f)
            // no single percent may add more than 5 dB (the old 3 -> 4 % jump was a whole step)
            assertTrue("jump of ${now - previous} dB at $p %", now - previous <= 5f)
            previous = now
        }
        assertEquals(0f, loudnessDb(1f), 1e-3f)
    }

    @Test
    fun `a hardware key step lands exactly on a step with no trim`() {
        for (i in 1..max) {
            val f = SystemVolumeMapping.fractionOf(i, 0, max)
            val t = SystemVolumeMapping.target(f, 0, max, table)
            assertEquals(i, t.index)
            assertEquals(1f, t.trim, 1e-4f)
        }
    }

    @Test
    fun `below the first step the bar fades out instead of sitting on one loud step`() {
        val two = SystemVolumeMapping.target(0.02f, 0, max, table)
        val five = SystemVolumeMapping.target(0.05f, 0, max, table)
        assertEquals(1, two.index)
        assertEquals(1, five.index)
        assertTrue(two.trim < five.trim)
        assertTrue("4 % must be well under the first step", loudnessDb(0.04f) < table[1] - 6f)
    }

    @Test
    fun `the phone's own table is used when it rises step by step, else the default curve`() {
        val phone = SystemVolumeMapping.stepDbTable(4) { i -> floatArrayOf(-99f, -40f, -25f, -12f, 0f)[i] }
        assertEquals(-25f, phone[2], 0f)
        val broken = SystemVolumeMapping.stepDbTable(4) { i -> floatArrayOf(-99f, -10f, -25f, -12f, 0f)[i] }
        assertEquals(SystemVolumeMapping.defaultCurveDb(2, 4), broken[2], 0f)
        val infinite = SystemVolumeMapping.stepDbTable(4) { Float.NEGATIVE_INFINITY }
        assertEquals(SystemVolumeMapping.defaultCurveDb(3, 4), infinite[3], 0f)
        val throwing = SystemVolumeMapping.stepDbTable(4) { error("no such device") }
        assertEquals(SystemVolumeMapping.defaultCurveDb(1, 4), throwing[1], 0f)
    }

    @Test
    fun `phones with many steps (vivo 100+) behave the same`() {
        val big = SystemVolumeMapping.stepDbTable(150, null)
        val t = SystemVolumeMapping.target(0.04f, 0, 150, big)
        assertEquals(6, t.index)
        assertTrue(t.trim in 0f..1f)
    }
}
