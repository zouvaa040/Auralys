package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizationGainTest {
    @Test
    fun `perceptual loudness takes priority`() {
        assertEquals(-506, normalizationGainMb(-20.0, -8.94, -14f))
    }

    @Test
    fun `legacy loudness uses YouTube reference level`() {
        assertEquals(-506, normalizationGainMb(-1.94, null, -14f))
    }

    @Test
    fun `gain is clamped to processor limits`() {
        // the boost limit is +6 dB since the limiter (LoudnessEngine) protects the peaks; the cut limit is unchanged
        assertEquals(600, normalizationGainMb(-30.0, null, -14f))
        assertEquals(-1500, normalizationGainMb(null, 10.0, -14f))
    }

    @Test
    fun `missing loudness disables normalization`() {
        assertNull(normalizationGainMb(null, null, -14f))
    }

    @Test
    fun `the default level lifts a typical track to the YouTube Music reference`() {
        // -8.94 LUFS (loudnessDb -1.94) at the -7 LUFS target: +1.94 dB, the same as the rival's rule gain = -loudnessDb
        assertEquals(194, normalizationGainMb(-1.94, -8.94, -7f))
        assertEquals(194, normalizationGainMb(-1.94, null, -7f))
    }
}
