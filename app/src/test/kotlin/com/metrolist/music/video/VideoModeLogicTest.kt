package com.metrolist.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModeLogicTest {
    private fun song(
        id: String = "abc123",
        video: Boolean = false,
        episode: Boolean = false,
        local: Boolean = false,
        sec: Int = 200,
    ) = VideoSong(id, video, episode, local, sec)

    // ---------------------------------------------------------------- eligibility

    @Test
    fun `device songs and episodes never get video`() {
        assertTrue(VideoEligibility.songAllows(song()))
        assertFalse(VideoEligibility.songAllows(song(local = true)))
        assertFalse(VideoEligibility.songAllows(song(episode = true)))
        assertFalse(VideoEligibility.songAllows(song(id = "")))
    }

    @Test
    fun `the situation rules out the switch off, the kill switch, a guest and casting`() {
        assertTrue(VideoEligibility.situationAllows(enabled = true, killed = false, listenTogetherGuest = false, casting = false))
        assertFalse(VideoEligibility.situationAllows(enabled = false, killed = false, listenTogetherGuest = false, casting = false))
        assertFalse(VideoEligibility.situationAllows(enabled = true, killed = true, listenTogetherGuest = false, casting = false))
        assertFalse(VideoEligibility.situationAllows(enabled = true, killed = false, listenTogetherGuest = true, casting = false))
        assertFalse(VideoEligibility.situationAllows(enabled = true, killed = false, listenTogetherGuest = false, casting = true))
    }

    @Test
    fun `a music video or a song from the Video chip is its own video, an audio recording is not`() {
        assertTrue(VideoEligibility.isOwnVideo(song(video = true), knownVideo = false))
        assertTrue(VideoEligibility.isOwnVideo(song(video = false), knownVideo = true))
        assertFalse(VideoEligibility.isOwnVideo(song(video = false), knownVideo = false))
    }

    @Test
    fun `a video within 15 seconds of the audio starts together with it`() {
        assertEquals(0L, VideoEligibility.counterpartOffsetMs(200, 215))
        assertEquals(0L, VideoEligibility.counterpartOffsetMs(200, 185))
        assertEquals(0L, VideoEligibility.counterpartOffsetMs(200, 200))
    }

    @Test
    fun `a longer video is taken to have an intro, its picture runs ahead by the difference`() {
        // the real case that showed this: audio 4:23 (263 s), music video 4:41 (281 s)
        assertEquals(18_000L, VideoEligibility.counterpartOffsetMs(263, 281))
        assertEquals(16_000L, VideoEligibility.counterpartOffsetMs(200, 216))
        assertEquals(45_000L, VideoEligibility.counterpartOffsetMs(200, 245))
    }

    @Test
    fun `a video that is too different in length is no video`() {
        // longer by more than 45 s
        assertNull(VideoEligibility.counterpartOffsetMs(200, 246))
        // shorter by more than 15 s: nothing says where the missing part is
        assertNull(VideoEligibility.counterpartOffsetMs(200, 184))
        // a length that is not known cannot be checked
        assertNull(VideoEligibility.counterpartOffsetMs(200, null))
        assertNull(VideoEligibility.counterpartOffsetMs(0, 200))
        assertNull(VideoEligibility.counterpartOffsetMs(200, 0))
    }

    // ---------------------------------------------------------------- plan

    @Test
    fun `a song pick beats a pending request, which beats the stored mode`() {
        assertEquals(VideoMode.AUDIO, VideoPlan.effectiveMode(VideoMode.AUDIO, oneShotRequested = true, stored = VideoMode.VIDEO))
        assertEquals(VideoMode.VIDEO, VideoPlan.effectiveMode(VideoMode.VIDEO, oneShotRequested = false, stored = VideoMode.AUDIO))
        assertEquals(VideoMode.VIDEO, VideoPlan.effectiveMode(null, oneShotRequested = true, stored = VideoMode.AUDIO))
        assertEquals(VideoMode.AUDIO, VideoPlan.effectiveMode(null, oneShotRequested = false, stored = VideoMode.AUDIO))
        assertEquals(VideoMode.VIDEO, VideoPlan.effectiveMode(null, oneShotRequested = false, stored = VideoMode.VIDEO))
    }

    @Test
    fun `the stored mode reads AUDIO for anything unknown`() {
        assertEquals(VideoMode.VIDEO, VideoMode.fromStored("VIDEO"))
        assertEquals(VideoMode.AUDIO, VideoMode.fromStored("AUDIO"))
        assertEquals(VideoMode.AUDIO, VideoMode.fromStored(null))
        assertEquals(VideoMode.AUDIO, VideoMode.fromStored("video"))
        assertEquals(VideoMode.AUDIO, VideoMode.fromStored(""))
    }

    @Test
    fun `a second player is needed only while offered, in video mode, expanded and on screen`() {
        assertTrue(VideoPlan.needsPlayer(offered = true, mode = VideoMode.VIDEO, expanded = true, started = true))
        assertFalse(VideoPlan.needsPlayer(offered = false, mode = VideoMode.VIDEO, expanded = true, started = true))
        assertFalse(VideoPlan.needsPlayer(offered = true, mode = VideoMode.AUDIO, expanded = true, started = true))
        assertFalse(VideoPlan.needsPlayer(offered = true, mode = VideoMode.VIDEO, expanded = false, started = true))
        assertFalse(VideoPlan.needsPlayer(offered = true, mode = VideoMode.VIDEO, expanded = true, started = false))
    }

    @Test
    fun `the player is let go 3 seconds after it stops being needed`() {
        assertEquals(3_000L, VideoPlan.RELEASE_DELAY_MS)
    }

    // ---------------------------------------------------------------- one-shot memory

    @Test
    fun `a request is used once`() {
        var now = 1_000L
        val m = VideoModeMemory(now = { now })
        m.requestPlayAsVideo("v1")
        assertTrue(m.peekFresh("v1"))
        assertTrue(m.takeIfFresh("v1"))
        assertFalse(m.takeIfFresh("v1"))
        assertFalse(m.peekFresh("v1"))
    }

    @Test
    fun `a request expires after 5 minutes`() {
        var now = 0L
        val m = VideoModeMemory(now = { now })
        m.requestPlayAsVideo("v1")
        now = 5 * 60 * 1000L
        assertTrue(m.peekFresh("v1"))
        now = 5 * 60 * 1000L + 1
        assertFalse(m.peekFresh("v1"))
        assertFalse(m.takeIfFresh("v1"))
    }

    @Test
    fun `a request for another song does not match`() {
        val m = VideoModeMemory(now = { 0L })
        m.requestPlayAsVideo("v1")
        assertFalse(m.takeIfFresh("v2"))
        assertTrue(m.takeIfFresh("v1"))
    }

    @Test
    fun `a newer request for the same song starts the 5 minutes again`() {
        var now = 0L
        val m = VideoModeMemory(now = { now })
        m.requestPlayAsVideo("v1")
        now = 4 * 60 * 1000L
        m.requestPlayAsVideo("v1")
        now = 8 * 60 * 1000L
        assertTrue(m.takeIfFresh("v1"))
    }

    @Test
    fun `a requested song is known to be a video, also after the request is used up`() {
        val m = VideoModeMemory(now = { 0L })
        assertFalse(m.isKnownVideo("v1"))
        m.requestPlayAsVideo("v1")
        assertTrue(m.isKnownVideo("v1"))
        m.takeIfFresh("v1")
        assertTrue(m.isKnownVideo("v1"))
        m.markKnownVideo("v2")
        assertTrue(m.isKnownVideo("v2"))
    }

    @Test
    fun `blank ids are ignored and the memory is bounded`() {
        val m = VideoModeMemory(now = { 0L }, maxEntries = 3)
        m.requestPlayAsVideo("")
        assertFalse(m.takeIfFresh(""))
        for (i in 1..5) m.requestPlayAsVideo("v$i")
        assertFalse(m.peekFresh("v1"))
        assertFalse(m.peekFresh("v2"))
        assertTrue(m.peekFresh("v5"))
    }

    // ---------------------------------------------------------------- session / kill switch

    @Test
    fun `three failed songs in a row switch the pill off for the session`() {
        val s = VideoSession()
        assertFalse(s.recordFailure())
        assertFalse(s.recordFailure())
        assertFalse(s.killed)
        assertTrue(s.recordFailure())
        assertTrue(s.killed)
        // it stays off, and the switch-off is reported only once
        assertFalse(s.recordFailure())
        assertTrue(s.killed)
        s.recordSuccess()
        assertTrue(s.killed)
    }

    @Test
    fun `a success ends the run of failures`() {
        val s = VideoSession()
        s.recordFailure()
        s.recordFailure()
        s.recordSuccess()
        assertEquals(0, s.consecutiveFailures)
        assertFalse(s.recordFailure())
        assertFalse(s.recordFailure())
        assertFalse(s.killed)
    }

    // ---------------------------------------------------------------- REVIEW-2026-11-07 F13: the sound goes first

    @Test
    fun `video work waits while audio work runs and goes on when it is done`() {
        var busyPolls = 3
        val started = System.nanoTime()
        kotlinx.coroutines.runBlocking {
            VideoPriority.awaitIdle(busy = { busyPolls-- > 0 }, pollMs = 5L, maxWaitMs = 5_000L)
        }
        assertTrue("it polled until idle", busyPolls < 0)
        assertTrue("and did not wait for the maximum", (System.nanoTime() - started) / 1_000_000L < 2_000L)
    }

    @Test
    fun `video work does not wait at all when no audio work runs`() {
        var calls = 0
        kotlinx.coroutines.runBlocking { VideoPriority.awaitIdle(busy = { calls++; false }, pollMs = 5L, maxWaitMs = 5_000L) }
        assertEquals(1, calls)
    }

    @Test
    fun `video work never waits longer than the maximum`() {
        var calls = 0
        kotlinx.coroutines.runBlocking { VideoPriority.awaitIdle(busy = { calls++; true }, pollMs = 10L, maxWaitMs = 50L) }
        assertTrue("polls: $calls", calls in 5..7)
    }

    @Test
    fun `songs without video are remembered for the session`() {
        val s = VideoSession(maxNoVideoEntries = 2)
        assertFalse(s.isNoVideo("a"))
        s.markNoVideo("a")
        assertTrue(s.isNoVideo("a"))
        s.markNoVideo("b")
        s.markNoVideo("c")
        assertFalse(s.isNoVideo("a"))
        assertTrue(s.isNoVideo("c"))
    }
}
