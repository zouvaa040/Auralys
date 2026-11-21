package com.metrolist.music.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundVibeTest {
    @Test
    fun `normal style and unknown values keep the system playback rate`() {
        assertFalse(soundVibeNeedsSoftwareSpeed(null))
        assertFalse(soundVibeNeedsSoftwareSpeed(""))
        assertFalse(soundVibeNeedsSoftwareSpeed("NORMAL"))
        assertFalse(soundVibeNeedsSoftwareSpeed("something old"))
    }

    @Test
    fun `every other style changes the speed inside the app`() {
        SoundVibe.entries.filter { it != SoundVibe.NORMAL }.forEach {
            assertTrue("${it.name} should use the software speed", soundVibeNeedsSoftwareSpeed(it.name))
        }
    }

    @Test
    fun `the styles match the Windows app`() {
        assertEquals(listOf("NORMAL", "SPED_UP", "SLOWED_REVERB", "NIGHTCORE", "LOFI", "SPATIAL_8D", "CONCERT"), SoundVibe.entries.map { it.name })
        assertEquals(1.25f, SoundVibe.SPED_UP.speed, 0f)
        assertEquals(0.85f, SoundVibe.SLOWED_REVERB.speed, 0f)
        assertEquals(1.4f, SoundVibe.NIGHTCORE.speed, 0f)
        assertEquals(0.94f, SoundVibe.LOFI.speed, 0f)
        assertEquals(1f, SoundVibe.SLOWED_REVERB.reverbAmount, 0f)
        assertEquals(0.75f, SoundVibe.CONCERT.reverbAmount, 0f)
    }

    @Test
    fun `speed and pitch always move together`() {
        SoundVibe.entries.forEach { assertEquals("${it.name}: pitch follows speed", it.speed, it.pitch, 0f) }
    }

    @Test
    fun `only the styles that change the audio rule out offload`() {
        assertFalse(SoundVibe.NORMAL.processesAudio)
        assertFalse(SoundVibe.SPED_UP.processesAudio)
        assertFalse(SoundVibe.NIGHTCORE.processesAudio)
        assertTrue(SoundVibe.SLOWED_REVERB.processesAudio)
        assertTrue(SoundVibe.LOFI.processesAudio)
        assertTrue(SoundVibe.SPATIAL_8D.processesAudio)
        assertTrue(SoundVibe.CONCERT.processesAudio)
    }
}
