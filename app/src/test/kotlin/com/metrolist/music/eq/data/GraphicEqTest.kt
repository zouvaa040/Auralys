/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.eq.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The easy equaliser: presets, the volume it takes off by itself, and the three knobs over the ten bands. */
class GraphicEqTest {
    @Test
    fun `there are twelve presets with ten gains each inside the slider range and a name key of their own`() {
        assertEquals(12, GraphicEq.PRESETS.size)
        assertEquals(GraphicEq.PRESETS.size, GraphicEq.PRESETS.map { it.id }.toSet().size)
        for (p in GraphicEq.PRESETS) {
            assertEquals(p.id, GraphicEq.FREQUENCIES.size, p.gains.size)
            assertTrue(p.id, p.gains.all { it in -GraphicEq.MAX_DB..GraphicEq.MAX_DB })
            // every gain sits on the half-dB grid of the sliders, so a preset can be shown exactly
            assertTrue(p.id, p.gains.all { GraphicEq.snap(it) == it })
        }
        assertTrue(GraphicEq.sameGains(GraphicEq.PRESETS.first().gains, GraphicEq.FLAT))
    }

    @Test
    fun `flat takes no volume off and boosts nothing`() {
        assertEquals(0.0, GraphicEq.peakDb(GraphicEq.FLAT), 0.001)
        assertEquals(0.0, GraphicEq.autoPreamp(GraphicEq.FLAT), 0.0)
    }

    @Test
    fun `a single band boosts its own centre by exactly its gain`() {
        val gains = GraphicEq.withBand(GraphicEq.FLAT, 5, 6.0) // 1 kHz
        // the other nine bands are flat, so the top of the curve is the centre of this one
        assertEquals(6.0, GraphicEq.peakDb(gains), 0.35)
    }

    @Test
    fun `after the automatic preamp no preset can go above full scale`() {
        for (p in GraphicEq.PRESETS) {
            val preamp = GraphicEq.autoPreamp(p.gains)
            assertTrue("${p.id}: preamp $preamp", preamp <= 0.0 && preamp >= -18.0)
            assertTrue("${p.id}: peak ${GraphicEq.peakDb(p.gains)} with $preamp", GraphicEq.peakDb(p.gains) + preamp <= 0.001)
        }
    }

    @Test
    fun `cutting only never takes volume off`() {
        val cut = List(10) { -3.0 }
        assertEquals(0.0, GraphicEq.autoPreamp(cut), 0.0)
    }

    @Test
    fun `the profile is ten peaking bands that stay out of the headphone list`() {
        val gains = GraphicEq.PRESETS.first { it.id == "rock" }.gains
        val profile = GraphicEq.profile(gains)
        assertEquals(GraphicEq.ID, profile.id)
        assertEquals(10, profile.bands.size)
        assertTrue(profile.bands.all { it.filterType == FilterType.PK && it.enabled && it.q == GraphicEq.BAND_Q })
        assertTrue("the headphone list shows isCustom profiles only", !profile.isCustom)
        assertEquals(gains, GraphicEq.gainsOf(profile))
        assertEquals(GraphicEq.autoPreamp(gains), profile.preamp, 0.0)
    }

    @Test
    fun `a profile that was not made here gives no gains`() {
        val other = GraphicEq.profile(GraphicEq.FLAT).copy(bands = listOf(ParametricEQBand(100.0, 3.0)))
        assertNull(GraphicEq.gainsOf(other))
        val shifted = GraphicEq.profile(GraphicEq.FLAT).let { it.copy(bands = it.bands.map { b -> b.copy(frequency = b.frequency + 1) }) }
        assertNull(GraphicEq.gainsOf(shifted))
    }

    @Test
    fun `a preset is found again from its gains and a curve of the listener's own is not`() {
        for (p in GraphicEq.PRESETS) assertEquals(p.id, GraphicEq.presetFor(p.gains)?.id)
        assertNull(GraphicEq.presetFor(GraphicEq.withBand(GraphicEq.FLAT, 3, 1.5)))
    }

    @Test
    fun `snap keeps the half dB grid and the range`() {
        assertEquals(1.5, GraphicEq.snap(1.6), 0.0)
        assertEquals(-2.0, GraphicEq.snap(-2.2), 0.0)
        assertEquals(12.0, GraphicEq.snap(30.0), 0.0)
        assertEquals(-12.0, GraphicEq.snap(-99.0), 0.0)
    }

    @Test
    fun `a knob moves its whole region and leaves the others alone`() {
        val bass = GraphicEq.withGroupLevel(GraphicEq.FLAT, GraphicEq.BASS, 6.0)
        assertEquals(listOf(6.0, 6.0, 6.0, 6.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), bass)
        assertEquals(6.0, GraphicEq.groupLevel(bass, GraphicEq.BASS), 0.0)
        assertEquals(0.0, GraphicEq.groupLevel(bass, GraphicEq.VOCAL), 0.0)
        assertEquals(0.0, GraphicEq.groupLevel(bass, GraphicEq.TREBLE), 0.0)
        val treble = GraphicEq.withGroupLevel(bass, GraphicEq.TREBLE, -4.0)
        assertEquals(listOf(6.0, 6.0, 6.0, 6.0, 0.0, 0.0, 0.0, -4.0, -4.0, -4.0), treble)
    }

    @Test
    fun `a knob keeps the shape inside its region and never leaves the range`() {
        val preset = GraphicEq.PRESETS.first { it.id == "bass" }.gains // 6, 5, 3.5, 1.5 in the bass region
        val lifted = GraphicEq.withGroupLevel(preset, GraphicEq.BASS, GraphicEq.groupLevel(preset, GraphicEq.BASS) + 2.0)
        assertEquals(listOf(8.0, 7.0, 5.5, 3.5), lifted.take(4))
        val maxed = GraphicEq.withGroupLevel(preset, GraphicEq.BASS, 12.0)
        assertTrue(maxed.all { it in -12.0..12.0 })
    }
}
