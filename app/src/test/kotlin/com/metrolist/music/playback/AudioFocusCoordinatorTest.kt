/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.video.VirtualTimeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The focus handling of the music service with a REAL ExoPlayer (its volume property), the REAL AudioManager code path
 * (Robolectric's AudioManager records our request and hands back the listener we registered) and a virtual clock.
 * Android's behaviour is played by hand: a duck is a call of the registered listener, and, as on a phone, asking for the
 * focus again produces NO callback.
 *
 * The service wires the same pieces to its own player; these tests cover every piece except the service class itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioFocusCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = VirtualTimeDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val players = mutableListOf<ExoPlayer>()

    private fun newPlayer(): ExoPlayer =
        ExoPlayer
            .Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(SilentRenderer()) })
            .setLooper(Looper.getMainLooper())
            .build()
            .also { players += it }

    /** ExoPlayer refuses to be built without a renderer; this one does nothing. */
    private class SilentRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "Silent"

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

        override fun render(
            positionUs: Long,
            elapsedRealtimeUs: Long,
        ) {}

        override fun isReady() = true

        override fun isEnded() = true
    }

    /** Counts what reaches Android, in front of the real AudioManager port. */
    private class CountingPort(
        private val delegate: AudioFocusPort,
    ) : AudioFocusPort {
        var requests = 0
        var abandons = 0

        override fun request(): Int {
            requests++
            return delegate.request()
        }

        override fun abandon() {
            abandons++
            delegate.abandon()
        }
    }

    /** What the service is, as far as the coordinator can see. */
    private inner class TestHost : AudioFocusHost {
        var player: ExoPlayer = newPlayer()
        var playing = false
        override var isCasting = false
        override var isVolumeLocked = false
        var userVolume = 0.8f
        var muted = false
        var sleepMultiplier = 1f
        val focusMultiplier = MutableStateFlow(1f)
        var pauses = 0
        var plays = 0
        val persistedUserVolumes = mutableListOf<Float>()
        lateinit var coordinator: AudioFocusCoordinator

        override val isPlaying: Boolean get() = playing
        override val currentVolume: Float get() = player.volume
        override val expectedVolume: Float
            get() = VolumeDuckGuard.effectiveVolume(userVolume, muted, sleepMultiplier, 1f)

        override fun pause() {
            playing = false
            pauses++
        }

        override fun play() {
            plays++
            startPlaying()
        }

        override fun applyVolume() {
            if (isVolumeLocked) return
            player.volume = VolumeDuckGuard.effectiveVolume(userVolume, muted, sleepMultiplier, focusMultiplier.value)
        }

        /** What the service does when the player starts: the focus request on the state event, then the playing event. */
        fun startPlaying() {
            playing = true
            coordinator.request()
            coordinator.onPlaybackResumed()
        }

        /** The user moves the volume slider. */
        fun moveSlider(value: Float) {
            userVolume = value
            persistedUserVolumes += value
            applyVolume()
        }
    }

    private lateinit var host: TestHost
    private lateinit var port: CountingPort
    private lateinit var guard: VolumeDuckGuard

    @Before
    fun setUp() {
        host = TestHost()
        guard = VolumeDuckGuard(clock = { dispatcher.time })
        host.coordinator =
            AudioFocusCoordinator(
                scope = scope,
                host = host,
                guard = guard,
                focusMultiplier = host.focusMultiplier,
            ) { listener ->
                CountingPort(AudioManagerFocusPort(audioManager, listener)).also { port = it }
            }
    }

    @After
    fun tearDown() {
        scope.cancel()
        players.forEach { it.release() }
    }

    /** The listener Android would call, taken from the request that reached the (shadow) AudioManager. */
    private fun androidSends(focusChange: Int) {
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener
        listener.onAudioFocusChange(focusChange)
        dispatcher.runCurrent()
    }

    private fun volume() = host.player.volume

    private fun assertVolume(expected: Float) = assertEquals(expected, volume(), 1e-6f)

    private fun userStartsPlayback() {
        host.startPlaying()
        host.applyVolume()
        dispatcher.runCurrent()
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the focus request carries our listener, music attributes and delayed gain`() {
        userStartsPlayback()
        assertEquals(1, port.requests)

        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, request.audioAttributes.contentType)
        assertTrue(request.acceptsDelayedFocusGain())
        assertFalse(request.willPauseWhenDucked())
    }

    @Test
    fun `duck then gain restores the exact user volume`() {
        userStartsPlayback()
        assertVolume(0.8f)

        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertVolume(0.16f)
        assertTrue(host.coordinator.isDucked)
        assertTrue("a duck does not pause", host.playing)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f)
        assertFalse(host.coordinator.isDucked)
        assertEquals(0.8f, host.userVolume, 0f)
    }

    @Test
    fun `a play, pause, play and a buffering event during the duck do not ask for the focus again and the gain restores`() {
        // This is the sequence that left the music quiet: every player event during the duck asked for the focus again.
        userStartsPlayback()
        assertEquals(1, port.requests)

        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertVolume(0.16f)

        host.playing = false // the user pauses
        host.coordinator.request() // the player reports a state change
        host.startPlaying() // and plays again
        host.coordinator.request() // buffering after the seek
        host.applyVolume()

        assertEquals("no second request to Android while ducked", 1, port.requests)
        assertVolume(0.16f)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f)
        assertFalse(host.coordinator.isDucked)
    }

    @Test
    fun `a song the user paused during the duck is not started by itself on the gain`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        host.playing = false // the user pauses while the notification plays

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        dispatcher.advanceBy(1_000)

        assertFalse(host.playing)
        assertEquals(0, host.plays)
        assertVolume(0.8f)
    }

    @Test
    fun `a transient loss pauses and the gain resumes after the short delay with full volume`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(host.playing)
        assertEquals(1, host.pauses)
        assertFalse(host.coordinator.hasFocus)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("waits before it resumes", !host.playing)
        dispatcher.advanceBy(299)
        assertFalse(host.playing)
        dispatcher.advanceBy(1)

        assertTrue(host.playing)
        assertEquals(1, host.plays)
        assertVolume(0.8f)
        assertFalse(host.coordinator.wasPlayingBeforeLoss)
    }

    @Test
    fun `duck during a call pause then gain keeps one resume and ends at full volume`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // call
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) // notification during the call
        assertVolume(0.16f)
        assertTrue("the pending resume survives the second event", host.coordinator.wasPlayingBeforeLoss)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        dispatcher.advanceBy(300)

        assertTrue(host.playing)
        assertEquals(1, host.plays)
        assertVolume(0.8f)
    }

    @Test
    fun `the user presses play while ducked after a call pause and the volume is lifted by the granted request`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertVolume(0.16f)
        assertEquals(1, port.requests)

        host.startPlaying() // we do not hold the focus, so this asks again and Android grants without a callback

        assertEquals(2, port.requests)
        assertFalse(host.coordinator.isDucked)
        assertVolume(0.8f)
    }

    @Test
    fun `a permanent loss pauses, gives the request back and leaves full volume for the next play`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        androidSends(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse(host.playing)
        assertEquals(1, port.abandons)
        assertFalse(host.coordinator.hasFocus)
        assertEquals(1f, host.focusMultiplier.value, 0f)

        userStartsPlayback()
        assertEquals(2, port.requests)
        assertVolume(0.8f)
    }

    @Test
    fun `a duck whose gain never arrives is lifted by the watchdog after 30 seconds`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertVolume(0.16f)

        dispatcher.advanceBy(VolumeDuckGuard.MAX_DUCK_MS - 1)
        assertVolume(0.16f)
        dispatcher.advanceBy(1)

        assertVolume(0.8f)
        assertFalse(host.coordinator.isDucked)
        assertTrue("still ours, nothing was requested again", host.coordinator.hasFocus)
        assertEquals(1, port.requests)
    }

    @Test
    fun `a second duck restarts the watchdog and an early gain cancels it`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        dispatcher.advanceBy(20_000)
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        dispatcher.advanceBy(20_000)
        assertVolume(0.16f) // 40 s after the first duck, 20 s after the second

        dispatcher.advanceBy(10_000)
        assertVolume(0.8f)

        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        dispatcher.advanceBy(5_000)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f)

        // a user change after the gain must not be touched by a leftover watchdog
        host.moveSlider(0.5f)
        dispatcher.advanceBy(60_000)
        assertVolume(0.5f)
    }

    @Test
    fun `moving the slider during a duck keeps the duck and the user volume is what gets saved`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        host.moveSlider(0.5f)
        assertVolume(0.1f)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.5f)

        assertEquals("only the slider ever writes the user volume, never a ducked value", listOf(0.5f), host.persistedUserVolumes)
        assertEquals(0.5f, host.userVolume, 0f)
    }

    @Test
    fun `a player that is rebuilt during the duck stays ducked and is restored by the gain`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        val old = host.player
        host.player = newPlayer() // what the service does when a setting needs a new ExoPlayer
        host.applyVolume() // newPlayer.volume = calculateEffectiveVolume()
        old.release()
        assertEquals(0.16f, host.player.volume, 1e-6f)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0.8f, host.player.volume, 1e-6f)
    }

    @Test
    fun `a gain that comes during a crossfade is applied when the crossfade ends`() {
        userStartsPlayback()
        host.isVolumeLocked = true // the fade owns the player volume

        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f) // never written during the fade

        host.isVolumeLocked = false
        host.applyVolume() // cleanupCrossfade -> applyEffectiveVolume
        assertVolume(0.8f)
        assertEquals("the focus part is already back to 1", 1f, host.focusMultiplier.value, 0f)
    }

    @Test
    fun `a duck that is still on when the crossfade ends is applied then`() {
        userStartsPlayback()
        host.isVolumeLocked = true
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        host.isVolumeLocked = false
        host.applyVolume()
        assertVolume(0.16f)

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f)
    }

    @Test
    fun `a song change while ducked keeps the duck until the gain`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // next song: the player goes buffering -> ready, the service asks for the focus and reports "playing"
        repeat(3) {
            host.coordinator.request()
            host.coordinator.onPlaybackResumed()
        }

        assertEquals(1, port.requests)
        assertVolume(0.16f)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.8f)
    }

    @Test
    fun `the safety net raises a player that is quieter than the user volume when it starts and no duck is active`() {
        userStartsPlayback()
        host.player.volume = 0.1f // something else lowered it and no duck explains it

        host.coordinator.onPlaybackResumed()

        assertVolume(0.8f)
    }

    @Test
    fun `the safety net does nothing while a duck is active, while muted, during a crossfade or when the volume is fine`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        host.coordinator.onPlaybackResumed()
        assertVolume(0.16f)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)

        host.muted = true
        host.applyVolume()
        host.coordinator.onPlaybackResumed()
        assertVolume(0f)
        host.muted = false
        host.applyVolume()

        host.isVolumeLocked = true
        host.player.volume = 0.3f
        host.coordinator.onPlaybackResumed()
        assertVolume(0.3f)
        host.isVolumeLocked = false

        host.applyVolume()
        host.coordinator.onPlaybackResumed()
        assertVolume(0.8f)
    }

    @Test
    fun `the sleep timer fade is part of the expected volume, so the safety net does not fight it`() {
        userStartsPlayback()
        host.sleepMultiplier = 0.5f
        host.applyVolume()
        assertVolume(0.4f)

        host.coordinator.onPlaybackResumed()
        assertVolume(0.4f)

        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertVolume(0.08f)
        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        assertVolume(0.4f)
    }

    @Test
    fun `abandoning while ducked gives the request back and clears the duck`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        host.coordinator.abandon()

        assertEquals(1, port.abandons)
        assertSame(
            shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest,
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
        assertEquals(1f, host.focusMultiplier.value, 0f)
        assertFalse(host.coordinator.isDucked)
        assertFalse(host.coordinator.hasFocus)

        // nothing to give back a second time
        host.coordinator.abandon()
        assertEquals(1, port.abandons)
    }

    @Test
    fun `casting keeps the phone silent after the gain`() {
        userStartsPlayback()
        androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        host.isCasting = true

        androidSends(AudioManager.AUDIOFOCUS_GAIN)
        dispatcher.advanceBy(500)

        assertEquals(0, host.plays)
        assertFalse(host.playing)
    }

    @Test
    fun `many notifications in a row always end at the user volume`() {
        userStartsPlayback()
        repeat(25) { index ->
            androidSends(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            if (index % 3 == 0) host.coordinator.request()
            if (index % 4 == 0) host.coordinator.onPlaybackResumed()
            dispatcher.advanceBy(1_500L)
            androidSends(AudioManager.AUDIOFOCUS_GAIN)
            assertVolume(0.8f)
        }
        assertEquals(1, port.requests)
        assertEquals(0.8f, host.userVolume, 0f)
    }
}
