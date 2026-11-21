package com.metrolist.music.video

import com.metrolist.music.video.VideoSyncLogic.NO_CORRECTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSyncLogicTest {
    private fun input(
        audio: Long = 10_000,
        video: Long = 10_000,
        audioPlaying: Boolean = true,
        audioBuffering: Boolean = false,
        videoReady: Boolean = true,
        videoBuffering: Boolean = false,
        duration: Long = 200_000,
        speed: Float = 1f,
        now: Long = 100_000,
        last: Long = NO_CORRECTION,
        force: Boolean = false,
    ) = VideoSyncLogic.Input(audio, video, audioPlaying, audioBuffering, videoReady, videoBuffering, duration, speed, now, last, force)

    @Test
    fun `in sync - play at the audio speed, no seek`() {
        val d = VideoSyncLogic.decide(input(speed = 1.25f))
        assertTrue(d.play)
        assertEquals(1.25f, d.rate, 0f)
        assertNull(d.seekToMs)
    }

    @Test
    fun `small errors below 100 ms are left alone`() {
        val d = VideoSyncLogic.decide(input(video = 10_090))
        assertEquals(1f, d.rate, 0f)
        assertNull(d.seekToMs)
    }

    @Test
    fun `video ahead is slowed, video behind is sped up, 5 percent at 100 ms growing to 10 percent`() {
        val ahead = VideoSyncLogic.decide(input(video = 10_100))
        assertEquals(0.95f, ahead.rate, 0.0001f)
        val behind = VideoSyncLogic.decide(input(video = 9_900))
        assertEquals(1.05f, behind.rate, 0.0001f)
        val farAhead = VideoSyncLogic.decide(input(video = 10_350))
        assertNull(farAhead.seekToMs)
        assertEquals(0.90f, farAhead.rate, 0.01f)
        val mid = VideoSyncLogic.decide(input(video = 9_775)) // 225 ms behind: halfway
        assertEquals(1.075f, mid.rate, 0.001f)
    }

    @Test
    fun `the nudge scales with the audio speed`() {
        val d = VideoSyncLogic.decide(input(speed = 2f, video = 9_900))
        assertEquals(2.1f, d.rate, 0.0001f)
    }

    @Test
    fun `above 350 ms the video is jumped to the audio position`() {
        val d = VideoSyncLogic.decide(input(video = 10_400))
        assertEquals(10_000L, d.seekToMs)
        assertEquals(1f, d.rate, 0f)
        assertTrue(d.play)
    }

    @Test
    fun `a second jump within 1500 ms is not made, the strongest nudge holds instead`() {
        val d = VideoSyncLogic.decide(input(video = 12_000, now = 100_000, last = 99_000))
        assertNull(d.seekToMs)
        assertEquals(0.90f, d.rate, 0.0001f)
        val later = VideoSyncLogic.decide(input(video = 12_000, now = 100_000, last = 98_400))
        assertEquals(10_000L, later.seekToMs)
    }

    @Test
    fun `while the video buffers the gap between jumps is 4 seconds`() {
        val tooSoon = VideoSyncLogic.decide(input(video = 12_000, videoBuffering = true, videoReady = false, now = 100_000, last = 97_000))
        assertNull(tooSoon.seekToMs)
        assertEquals(1f, tooSoon.rate, 0f) // no nudge while not ready
        val ok = VideoSyncLogic.decide(input(video = 12_000, videoBuffering = true, videoReady = false, now = 100_000, last = 95_900))
        assertEquals(10_000L, ok.seekToMs)
    }

    @Test
    fun `an audio seek forces the jump once even inside the gap`() {
        val d = VideoSyncLogic.decide(input(video = 50_000, now = 100_000, last = 99_900, force = true))
        assertEquals(10_000L, d.seekToMs)
    }

    @Test
    fun `the video waits while the audio is paused or buffers`() {
        assertFalse(VideoSyncLogic.decide(input(audioPlaying = false)).play)
        assertFalse(VideoSyncLogic.decide(input(audioBuffering = true)).play)
    }

    @Test
    fun `a seek made while paused moves the picture, but not for small errors and not too often`() {
        val small = VideoSyncLogic.decide(input(audioPlaying = false, video = 10_200))
        assertNull(small.seekToMs)
        val far = VideoSyncLogic.decide(input(audioPlaying = false, video = 40_000))
        assertEquals(10_000L, far.seekToMs)
        assertFalse(far.play)
        val tooSoon = VideoSyncLogic.decide(input(audioPlaying = false, video = 40_000, now = 100_000, last = 99_900))
        assertNull(tooSoon.seekToMs)
        val forced = VideoSyncLogic.decide(input(audioPlaying = false, video = 40_000, now = 100_000, last = 99_900, force = true))
        assertEquals(10_000L, forced.seekToMs)
    }

    @Test
    fun `the picture holds its last frame when the audio is past the end of the video`() {
        val d = VideoSyncLogic.decide(input(audio = 199_900, video = 199_000, duration = 200_000))
        assertFalse(d.play)
        assertNull(d.seekToMs)
        // an unknown video length never counts as the end
        assertTrue(VideoSyncLogic.decide(input(audio = 500_000, video = 500_000, duration = 0)).play)
    }

    @Test
    fun `the speed is kept inside a sane range`() {
        assertEquals(2f, VideoSyncLogic.decide(input(speed = 8f)).rate, 0f)
        assertEquals(0.25f, VideoSyncLogic.decide(input(speed = 0.01f)).rate, 0f)
    }

    @Test
    fun `a clock that went backwards counts as no time passed, no second jump`() {
        val d = VideoSyncLogic.decide(input(video = 12_000, now = 1_000, last = 5_000))
        assertNull(d.seekToMs)
    }
}
