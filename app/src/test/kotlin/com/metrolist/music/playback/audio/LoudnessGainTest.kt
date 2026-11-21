/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import com.metrolist.music.constants.LoudnessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class LoudnessGainTest {
    /** The rival app's rule (BitChord PlaybackService.loudnessGainMb): gain = -loudnessDb, -15 dB .. +3 dB. */
    private fun rivalHeadphoneGainDb(loudnessDb: Double): Double = (-loudnessDb).coerceIn(-15.0, 3.0)

    /** The rival app on the phone speaker (its default): normalisation is skipped, the track plays untouched. */
    private val rivalSpeakerGainDb = 0.0

    private val default = LoudnessGain.DEFAULT_LEVEL.targetLufs.toDouble()

    @Test
    fun `the default level is the loudest preset`() {
        assertEquals(LoudnessLevel.AGGRESSIVE, LoudnessGain.DEFAULT_LEVEL)
        assertEquals(-7.0, default, 0.0)
        assertTrue(LoudnessLevel.entries.all { it.targetLufs <= default })
    }

    @Test
    fun `limits`() {
        assertEquals(6.0, LoudnessGain.MAX_BOOST_DB, 0.0)
        assertEquals(15.0, LoudnessGain.MAX_CUT_DB, 0.0)
    }

    @Test
    fun `perceptual loudness wins over loudnessDb`() {
        assertEquals(-11.71, LoudnessGain.measuredLufs(2.29, -11.71)!!, 1e-9)
        assertEquals(-8.94, LoudnessGain.measuredLufs(-20.0, -8.94)!!, 1e-9)
    }

    @Test
    fun `a bare loudnessDb is read against the YouTube Music reference`() {
        assertEquals(-8.94, LoudnessGain.measuredLufs(-1.94, null)!!, 1e-9)
        assertEquals(-7.0, LoudnessGain.measuredLufs(0.0, null)!!, 1e-9)
    }

    @Test
    fun `nothing usable gives null`() {
        assertNull(LoudnessGain.measuredLufs(null, null))
        assertNull(LoudnessGain.measuredLufs(Double.NaN, Double.NaN))
        assertNull(LoudnessGain.measuredLufs(Double.POSITIVE_INFINITY, null))
        assertNull(LoudnessGain.gainMb(null, null, default))
    }

    @Test
    fun `a broken perceptual figure falls back to loudnessDb`() {
        assertEquals(-8.94, LoudnessGain.measuredLufs(-1.94, Double.NaN)!!, 1e-9)
        assertEquals(-8.94, LoudnessGain.measuredLufs(-1.94, -300.0)!!, 1e-9)
        assertEquals(-8.94, LoudnessGain.measuredLufs(-1.94, 55.0)!!, 1e-9)
    }

    @Test
    fun `an absurd loudnessDb gives null instead of a wild gain`() {
        assertNull(LoudnessGain.measuredLufs(500.0, null))
        assertNull(LoudnessGain.measuredLufs(-500.0, null))
    }

    @Test
    fun `gain is target minus measured`() {
        assertEquals(2.0, LoudnessGain.gainDb(-9.0, -7.0), 1e-12)
        assertEquals(-5.0, LoudnessGain.gainDb(-9.0, -14.0), 1e-12)
        assertEquals(0.0, LoudnessGain.gainDb(-7.0, -7.0), 1e-12)
    }

    @Test
    fun `boost stops at 6 dB and cut at 15 dB`() {
        assertEquals(6.0, LoudnessGain.gainDb(-30.0, -7.0), 0.0)
        assertEquals(-15.0, LoudnessGain.gainDb(0.0, -19.0), 0.0)
        assertEquals(6.0, LoudnessGain.gainDb(-13.0, -7.0), 1e-12)
        assertEquals(5.9, LoudnessGain.gainDb(-12.9, -7.0), 1e-12)
    }

    @Test
    fun `non finite input gives no gain`() {
        assertEquals(0.0, LoudnessGain.gainDb(Double.NaN, -7.0), 0.0)
        assertEquals(0.0, LoudnessGain.gainDb(-9.0, Double.NaN), 0.0)
        assertEquals(0.0, LoudnessGain.gainDb(Double.NEGATIVE_INFINITY, -7.0), 0.0)
    }

    @Test
    fun `gain never leaves its limits for any input`() {
        var measured = -80.0
        while (measured <= 20.0) {
            for (level in LoudnessLevel.entries) {
                val g = LoudnessGain.gainDb(measured, level.targetLufs.toDouble())
                assertTrue("$measured $level -> $g", g in -15.0..6.0)
            }
            measured += 0.37
        }
    }

    @Test
    fun `a louder preset never gives less gain`() {
        var measured = -40.0
        while (measured <= 0.0) {
            val gains = LoudnessLevel.entries.sortedBy { it.targetLufs }.map { LoudnessGain.gainDb(measured, it.targetLufs.toDouble()) }
            assertTrue("$measured: $gains", gains.zipWithNext().all { (a, b) -> b >= a - 1e-12 })
            measured += 0.5
        }
    }

    @Test
    fun `the cache unit is millibels and rounds`() {
        assertEquals(194, LoudnessGain.gainMb(-1.94, -8.94, default))
        assertEquals(-506, LoudnessGain.gainMb(-1.94, -8.94, -14.0))
        assertEquals(600, LoudnessGain.gainMb(null, -40.0, default))
        assertEquals(-1500, LoudnessGain.gainMb(null, 5.0, -19.0))
        assertEquals(0, LoudnessGain.gainMb(null, -7.0, default))
    }

    @Test
    fun `linear gain matches the decibel definition`() {
        assertEquals(1f, LoudnessGain.linearGain(0.0), 0f)
        assertEquals(2f, LoudnessGain.linearGain(6.0206), 1e-4f)
        assertEquals(0.5f, LoudnessGain.linearGain(-6.0206), 1e-4f)
        assertEquals(10.0.pow(-5.06 / 20.0).toFloat(), LoudnessGain.linearGainFromMb(-506), 1e-6f)
    }

    // ---- the table behind the report: what each pipeline does for tracks of different loudnessDb -------------------------

    /** YouTube Music figures (reference -7 LUFS, the test data): loudnessDb L goes with -7 + L LUFS. */
    @Test
    fun `at the default level Auralys is never quieter than the rival on headphones, for any track`() {
        var loudnessDb = -15.0
        while (loudnessDb <= 12.0) {
            val ours = LoudnessGain.gainDb(LoudnessGain.measuredLufs(loudnessDb, loudnessDb - 7.0)!!, default)
            assertTrue("L=$loudnessDb ours=$ours rival=${rivalHeadphoneGainDb(loudnessDb)}", ours >= rivalHeadphoneGainDb(loudnessDb) - 1e-9)
            loudnessDb += 0.25
        }
    }

    @Test
    fun `without the perceptual figure the default level plays exactly the rival rule up to the higher cap`() {
        var loudnessDb = -15.0
        while (loudnessDb <= 12.0) {
            val ours = LoudnessGain.gainMb(loudnessDb, null, default)!! / 100.0
            val rival = rivalHeadphoneGainDb(loudnessDb)
            if (rival < 3.0) assertEquals("L=$loudnessDb", rival, ours, 0.0051) else assertTrue(ours >= 3.0 - 0.0051)
            loudnessDb += 0.25
        }
    }

    @Test
    fun `with web style figures (reference -14 LUFS) the default is louder than the rival still`() {
        var loudnessDb = -10.0
        while (loudnessDb <= 12.0) {
            val ours = LoudnessGain.gainDb(LoudnessGain.measuredLufs(loudnessDb, loudnessDb - 14.0)!!, default)
            assertTrue(ours >= rivalHeadphoneGainDb(loudnessDb) - 1e-9)
            loudnessDb += 0.25
        }
    }

    @Test
    fun `a track not louder than -7 LUFS is never lowered, so it is at least as loud as the rival on the phone speaker`() {
        var lufs = -40.0
        while (lufs <= -7.0) {
            assertTrue(LoudnessGain.gainDb(lufs, default) >= rivalSpeakerGainDb)
            lufs += 0.25
        }
    }

    @Test
    fun `before and after table for the report`() {
        // measured -10 .. +3 dB loudnessDb, YouTube Music reference. before = balanced preset with the old +3 cap and the
        // old perceptual/legacy mix as the app shipped (BALANCED, -14); after = default level, +6 cap.
        val rows = listOf(-10.0, -6.0, -3.0, 0.0, 3.0)
        val before = rows.map { l -> (-(LoudnessGain.measuredLufs(l, l - 7.0)!! + 14.0)).coerceIn(-15.0, 3.0) }
        val after = rows.map { l -> LoudnessGain.gainDb(LoudnessGain.measuredLufs(l, l - 7.0)!!, default) }
        val rival = rows.map { rivalHeadphoneGainDb(it) }
        assertEquals(listOf(3.0, -1.0, -4.0, -7.0, -10.0).map { it.coerceAtMost(3.0) }, before.map { Math.round(it * 100) / 100.0 })
        assertEquals(listOf(6.0, 6.0, 3.0, 0.0, -3.0), after.map { Math.round(it * 100) / 100.0 })
        assertEquals(listOf(3.0, 3.0, 3.0, 0.0, -3.0), rival.map { Math.round(it * 100) / 100.0 })
        assertNotNull(after)
        assertTrue(rows.indices.all { after[it] >= rival[it] && after[it] > before[it] - 1e-9 })
    }

    @Test
    fun `the gain function is monotonic in the measured loudness`() {
        var previous = Double.POSITIVE_INFINITY
        var measured = -60.0
        while (measured <= 5.0) {
            val g = LoudnessGain.gainDb(measured, default)
            assertTrue(g <= previous + 1e-12)
            assertTrue(abs(g) <= 15.0)
            previous = g
            measured += 0.1
        }
    }
}
