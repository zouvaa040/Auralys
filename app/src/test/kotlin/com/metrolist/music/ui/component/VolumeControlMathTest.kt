/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the volume control: value <-> position, detents, label, system steps, the memory of the mute. */
class VolumeControlMathTest {
    private val eps = 1e-4f

    // ------------------------------------------------------------ position <-> fraction

    @Test
    fun `a touch maps onto the track and back`() {
        // a track from 11 to 311 (300 wide)
        assertEquals(0f, VolumeMath.fractionAt(11f, 11f, 311f), eps)
        assertEquals(0.5f, VolumeMath.fractionAt(161f, 11f, 311f), eps)
        assertEquals(1f, VolumeMath.fractionAt(311f, 11f, 311f), eps)
        for (f in listOf(0f, 0.1f, 0.37f, 0.8f, 1f)) {
            assertEquals(f, VolumeMath.fractionAt(VolumeMath.positionAt(f, 11f, 311f), 11f, 311f), eps)
        }
    }

    @Test
    fun `a touch outside the track clamps to the ends`() {
        assertEquals(0f, VolumeMath.fractionAt(-40f, 11f, 311f), 0f)
        assertEquals(1f, VolumeMath.fractionAt(900f, 11f, 311f), 0f)
        assertEquals(11f, VolumeMath.positionAt(-1f, 11f, 311f), 0f)
        assertEquals(311f, VolumeMath.positionAt(2f, 11f, 311f), 0f)
    }

    @Test
    fun `a track with no width is silent and does not divide by zero`() {
        assertEquals(0f, VolumeMath.fractionAt(5f, 10f, 10f), 0f)
        assertEquals(0f, VolumeMath.fractionAt(5f, 10f, 4f), 0f)
    }

    // ------------------------------------------------------------ detents

    @Test
    fun `values near 0, 50 and 100 percent snap and others stay`() {
        assertEquals(0f, VolumeMath.snap(0.01f), 0f)
        assertEquals(0.5f, VolumeMath.snap(0.49f), 0f)
        assertEquals(0.5f, VolumeMath.snap(0.512f), 0f)
        assertEquals(1f, VolumeMath.snap(0.99f), 0f)
        assertEquals(0.4f, VolumeMath.snap(0.4f), 0f)
        assertEquals(0.52f, VolumeMath.snap(0.52f), 0f)
        assertEquals(0.97f, VolumeMath.snap(0.97f), 0f)
        assertEquals(0f, VolumeMath.snap(-3f), 0f)
        assertEquals(1f, VolumeMath.snap(7f), 0f)
    }

    @Test
    fun `a tick is felt when a detent is reached or passed, not when it is left`() {
        assertTrue("landing on 50", VolumeMath.detentCrossed(0.47f, 0.5f))
        assertTrue("landing on 50 from above", VolumeMath.detentCrossed(0.53f, 0.5f))
        assertTrue("passing 50 in one jump", VolumeMath.detentCrossed(0.40f, 0.60f))
        assertTrue("to 0", VolumeMath.detentCrossed(0.3f, 0f))
        assertTrue("to 100", VolumeMath.detentCrossed(0.7f, 1f))
        assertFalse("leaving 50", VolumeMath.detentCrossed(0.5f, 0.53f))
        assertFalse("leaving 0", VolumeMath.detentCrossed(0f, 0.2f))
        assertFalse("staying on a detent", VolumeMath.detentCrossed(0.5f, 0.5f))
        assertFalse("no detent in between", VolumeMath.detentCrossed(0.2f, 0.4f))
        assertFalse("no detent in between, down", VolumeMath.detentCrossed(0.9f, 0.6f))
    }

    // ------------------------------------------------------------ label

    @Test
    fun `the label is the whole percent`() {
        assertEquals("0%", VolumeMath.label(0f))
        assertEquals("40%", VolumeMath.label(0.4f))
        assertEquals("41%", VolumeMath.label(0.405f))
        assertEquals("100%", VolumeMath.label(1f))
        assertEquals("100%", VolumeMath.label(1.7f))
        assertEquals("0%", VolumeMath.label(-1f))
        assertEquals(40, VolumeMath.percent(0.4f))
    }

    @Test
    fun `only exactly zero is muted`() {
        assertTrue(VolumeMath.isMuted(0f))
        assertFalse(VolumeMath.isMuted(0.001f))
    }

    @Test
    fun `keyboard and accessibility steps are whole percents and clamp`() {
        assertEquals(0.45f, VolumeMath.stepBy(0.4f, VolumeMath.KeyStep), 0f)
        assertEquals(0.35f, VolumeMath.stepBy(0.4f, -VolumeMath.KeyStep), 0f)
        assertEquals(1f, VolumeMath.stepBy(0.98f, VolumeMath.KeyStep), 0f)
        assertEquals(0f, VolumeMath.stepBy(0.02f, -VolumeMath.KeyStep), 0f)
        // twenty steps up from 0 land on 100 with no drift
        var f = 0f
        repeat(20) { f = VolumeMath.stepBy(f, VolumeMath.KeyStep) }
        assertEquals(1f, f, 0f)
    }

    // ------------------------------------------------------------ system volume steps

    @Test
    fun `a fraction rounds to the nearest system step and back`() {
        assertEquals(0, VolumeMath.systemStep(0f, 15))
        assertEquals(15, VolumeMath.systemStep(1f, 15))
        assertEquals(6, VolumeMath.systemStep(0.4f, 15))
        assertEquals(8, VolumeMath.systemStep(0.5f, 15)) // 7.5 rounds up
        assertEquals(0, VolumeMath.systemStep(0.03f, 15)) // below half a step is silent
        assertEquals(15, VolumeMath.systemStep(0.99f, 15)) // the old floor gave 14 here
        assertEquals(0.4f, VolumeMath.fromSystemStep(6, 15), eps)
        assertEquals(1f, VolumeMath.fromSystemStep(15, 15), 0f)
        assertEquals(1f, VolumeMath.fromSystemStep(40, 15), 0f)
        assertEquals(0f, VolumeMath.fromSystemStep(3, 0), 0f)
        for (step in 0..15) assertEquals(step, VolumeMath.systemStep(VolumeMath.fromSystemStep(step, 15), 15))
    }

    // ------------------------------------------------------------ the memory of the mute

    @Test
    fun `mute and restore returns to the level before`() {
        val m = VolumeMuteMemory()
        assertEquals(0f, m.toggle(0.4f), 0f)
        assertEquals(0.4f, m.toggle(0f), 0f)
        assertEquals(0f, m.toggle(0.4f), 0f)
        assertEquals(0.4f, m.toggle(0f), 0f)
    }

    @Test
    fun `opened at zero with nothing remembered restores half`() {
        val m = VolumeMuteMemory()
        assertEquals(VolumeMath.DefaultRestore, m.toggle(0f), 0f)
    }

    @Test
    fun `a drag that ends at zero does not overwrite the remembered level`() {
        val m = VolumeMuteMemory()
        m.commit(0.7f) // the gesture ended at 70
        m.commit(0f) // the next gesture ended at 0
        assertEquals(0.7f, m.toggle(0f), 0f)
    }

    @Test
    fun `a level that is nearly silent is not remembered`() {
        val m = VolumeMuteMemory()
        m.commit(0.6f)
        m.commit(0.02f)
        assertEquals(0.6f, m.level, 0f)
        // and tapping to mute from that nearly silent level still restores the last real one
        assertEquals(0f, m.toggle(0.02f), 0f)
        assertEquals(0.6f, m.toggle(0f), 0f)
    }

    @Test
    fun `the player menu and the player page keep separate memories`() {
        val a = PlayerGainMuteMemory
        val b = SystemVolumeMuteMemory
        a.commit(0.3f)
        b.commit(0.9f)
        assertEquals(0.3f, a.level, 0f)
        assertEquals(0.9f, b.level, 0f)
    }
}
