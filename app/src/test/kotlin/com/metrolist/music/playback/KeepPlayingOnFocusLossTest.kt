/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Tetap putar saat bermain game": a game that takes the audio focus must not stop or lower the music when the user chose so. */
class KeepPlayingOnFocusLossTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private class FakeHost : AudioFocusHost {
        var playing = true
        var pauses = 0
        override val isPlaying: Boolean get() = playing
        override val isCasting = false
        override val isVolumeLocked = false
        override val currentVolume = 1f
        override val expectedVolume = 1f

        override fun pause() {
            playing = false
            pauses++
        }

        override fun play() {
            playing = true
        }

        override fun applyVolume() = Unit
    }

    private class FakePort : AudioFocusPort {
        var abandons = 0

        override fun request(): Int = AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        override fun abandon() {
            abandons++
        }
    }

    private class Rig(
        scope: CoroutineScope,
        keep: Boolean,
    ) {
        val host = FakeHost()
        val port = FakePort()
        val multiplier = MutableStateFlow(1f)
        val coordinator =
            AudioFocusCoordinator(
                scope = scope,
                host = host,
                guard = VolumeDuckGuard(clock = { 0L }),
                focusMultiplier = multiplier,
                keepPlaying = { keep },
            ) { port }

        init {
            coordinator.request()
        }
    }

    @Test
    fun `a permanent loss pauses the music unless the user keeps it playing`() {
        val normal = Rig(scope, keep = false)
        normal.coordinator.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, normal.host.pauses)

        val kept = Rig(scope, keep = true)
        kept.coordinator.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0, kept.host.pauses)
        assertTrue(kept.host.playing)
        assertEquals("the music is not given up either", 0, kept.port.abandons)
    }

    @Test
    fun `a duck lowers the music unless the user keeps it playing`() {
        val normal = Rig(scope, keep = false)
        normal.coordinator.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue("ducked", normal.multiplier.value < 1f)

        val kept = Rig(scope, keep = true)
        kept.coordinator.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(1f, kept.multiplier.value, 0f)
    }

    @Test
    fun `a phone call still pauses the music`() {
        val kept = Rig(scope, keep = true)
        kept.coordinator.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, kept.host.pauses)
        assertTrue(kept.coordinator.wasPlayingBeforeLoss)
    }
}
