/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.PlaybackRecovery.Action
import com.metrolist.music.playback.PlaybackRecovery.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules after a playback error (round 4.3.4, reports 2-4). Pure JVM. */
class PlaybackRecoveryTest {
    private fun rejected(
        r: PlaybackRecovery,
        id: String,
        canSkip: Boolean = true,
    ) = r.onError(id, PlaybackFailureKind.STREAM_REJECTED, networkUp = true, canSkip = canSkip)

    @Test
    fun `a refused stream gets three fresh streams from other clients, with growing pauses, then the song is skipped`() {
        val r = PlaybackRecovery()
        val tries = (1..3).map { rejected(r, "a") }
        assertTrue(tries.all { it.action == Action.RETRY && it.freshStream && it.excludeClient })
        assertEquals(listOf(1, 2, 3), tries.map { it.attempt })
        assertTrue(tries.zipWithNext().all { (x, y) -> y.delayMs > x.delayMs })
        assertFalse(tries.first().clearCache)
        assertTrue(tries.last().clearCache)
        val fourth = rejected(r, "a")
        assertEquals(Action.SKIP, fourth.action)
        assertEquals(1, r.failedInARow)
    }

    @Test
    fun `no connection waits for the network and never skips, however often it happens`() {
        val r = PlaybackRecovery()
        repeat(20) {
            assertEquals(Action.WAIT_FOR_NETWORK, r.onError("a", PlaybackFailureKind.NETWORK_DOWN, networkUp = false, canSkip = true).action)
            assertEquals(Action.WAIT_FOR_NETWORK, r.onError("a", PlaybackFailureKind.STREAM_REJECTED, networkUp = false, canSkip = true).action)
            assertEquals(Action.WAIT_FOR_NETWORK, r.onError("a", PlaybackFailureKind.NETWORK_SLOW, networkUp = false, canSkip = true).action)
        }
        assertEquals(0, r.failedInARow)
        assertEquals(0, r.attemptsFor("a"))
        assertEquals(0, r.unreachableAttempts)
    }

    private class FakeClock(
        var now: Long = 1_000_000L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `finding 6 - connection errors while online are retried with backoff, a fresh stream from the second on`() {
        val clock = FakeClock()
        val r = PlaybackRecovery(clock = clock, jitter = { 0.5 }) // 0.5 = no jitter
        val tries =
            (1..8).map {
                r.onError("a", PlaybackFailureKind.NETWORK_DOWN, networkUp = true, canSkip = true).also { d -> clock.now += d.delayMs }
            }
        assertTrue(tries.all { it.action == Action.RETRY && it.onValidatedNetwork })
        // 1, 3, 8, 20, 45, 90 s, then 90 s again
        assertEquals(listOf(1_000L, 3_000L, 8_000L, 20_000L, 45_000L, 90_000L, 90_000L, 90_000L), tries.map { it.delayMs })
        assertEquals(listOf(false, true, true, true, true, true, true, true), tries.map { it.freshStream })
        // never a skip, never a failed song, never a client excluded (the client is not at fault)
        assertEquals(0, r.failedInARow)
        assertTrue(tries.none { it.excludeClient })
    }

    @Test
    fun `finding 6 - jitter stays within 20 percent`() {
        val low = PlaybackRecovery(clock = FakeClock(), jitter = { 0.0 }).onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true)
        val high = PlaybackRecovery(clock = FakeClock(), jitter = { 1.0 }).onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true)
        assertEquals(800L, low.delayMs)
        assertEquals(1_200L, high.delayMs)
    }

    @Test
    fun `finding 6 - after about five minutes playback stops with the unreachable message, to resume on a validated network`() {
        val clock = FakeClock()
        val r = PlaybackRecovery(clock = clock, jitter = { 0.5 })
        var last: PlaybackRecovery.Decision
        var n = 0
        do {
            last = r.onError("a", PlaybackFailureKind.NETWORK_DOWN, networkUp = true, canSkip = true)
            clock.now += last.delayMs + 2_000L // the retry itself takes a moment
            n++
        } while (last.action == Action.RETRY && n < 100)
        assertEquals(Action.STOP, last.action)
        assertEquals(StopReason.UNREACHABLE, last.stopReason)
        assertTrue(last.onValidatedNetwork)
        // errors at 0, 3, 8, 18, 40, 87, 179, 271 s are retried; the one at 363 s (past 5 minutes) stops
        assertEquals(9, n)
        // the next outage starts from scratch
        assertEquals(Action.RETRY, r.onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true).action)
        assertEquals(1, r.unreachableAttempts)
    }

    @Test
    fun `finding 6 - real progress, the user or a validated network after the stop start a new outage`() {
        val clock = FakeClock()
        val r = PlaybackRecovery(clock = clock, jitter = { 0.5 })
        repeat(3) { r.onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true) }
        assertEquals(3, r.unreachableAttempts)
        r.onProgress("a", 0, true)
        r.onProgress("a", 6_000, true)
        assertEquals(0, r.unreachableAttempts)
        repeat(2) { r.onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true) }
        r.onUserAction("a")
        assertEquals(0, r.unreachableAttempts)
        repeat(2) { r.onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true) }
        r.onNetworkBack()
        assertEquals(1_000L, r.onError("a", PlaybackFailureKind.NETWORK_DOWN, true, true).delayMs)
    }

    @Test
    fun `video A - a playlist whose songs all fail stops after the third failed song instead of running through`() {
        val r = PlaybackRecovery()
        val songs = (1..15).map { "song$it" }
        var skipped = 0
        var stopped: PlaybackRecovery.Decision? = null
        loop@ for (id in songs) {
            while (true) {
                val d = rejected(r, id)
                when (d.action) {
                    Action.RETRY -> continue
                    Action.SKIP -> {
                        skipped++
                        continue@loop
                    }
                    else -> {
                        stopped = d
                        break@loop
                    }
                }
            }
        }
        assertEquals(2, skipped)
        assertEquals(Action.STOP, stopped!!.action)
        assertEquals(StopReason.SONGS_FAILING, stopped.stopReason)
    }

    @Test
    fun `being ready for a moment is not playing - only real progress resets the counters`() {
        val r = PlaybackRecovery()
        rejected(r, "a")
        rejected(r, "a")
        // ready, a second of audio, then the next failure: no reset
        r.onProgress("a", 0, isPlaying = true)
        r.onProgress("a", 1_000, isPlaying = true)
        assertEquals(2, r.attemptsFor("a"))
        // five seconds of real playback: fresh budget
        r.onProgress("a", 6_000, isPlaying = true)
        assertEquals(0, r.attemptsFor("a"))
        assertEquals(Action.RETRY, rejected(r, "a").action)
    }

    @Test
    fun `a song that plays resets the run of failed songs`() {
        val r = PlaybackRecovery()
        repeat(4) { rejected(r, "x") } // x skipped
        repeat(4) { rejected(r, "y") } // y skipped
        assertEquals(2, r.failedInARow)
        r.onProgress("z", 0, true)
        r.onProgress("z", 5_000, true)
        assertEquals(0, r.failedInARow)
    }

    @Test
    fun `a slow connection is retried longer, keeps the running extraction first, then stops with a network message`() {
        val r = PlaybackRecovery()
        val tries = (1..4).map { r.onError("a", PlaybackFailureKind.NETWORK_SLOW, networkUp = true, canSkip = true) }
        assertTrue(tries.all { it.action == Action.RETRY })
        assertEquals(listOf(false, false, true, true), tries.map { it.freshStream })
        assertEquals(PlaybackRecovery.SLOW_BACKOFF_MS.toList(), tries.map { it.delayMs })
        val last = r.onError("a", PlaybackFailureKind.NETWORK_SLOW, networkUp = true, canSkip = true)
        assertEquals(Action.STOP, last.action)
        assertEquals(StopReason.NETWORK, last.stopReason)
    }

    @Test
    fun `a song youtube refuses for good is skipped at once, and stops when there is nothing to skip to`() {
        val r = PlaybackRecovery()
        assertEquals(Action.SKIP, r.onError("a", PlaybackFailureKind.SONG_UNAVAILABLE, true, canSkip = true).action)
        val alone = r.onError("b", PlaybackFailureKind.SONG_UNAVAILABLE, true, canSkip = false)
        assertEquals(Action.STOP, alone.action)
        assertEquals(StopReason.SONG_FAILED, alone.stopReason)
    }

    @Test
    fun `the audio output is retried twice then playback stops - skipping would not help`() {
        val r = PlaybackRecovery()
        assertEquals(Action.RETRY, r.onError("a", PlaybackFailureKind.AUDIO_OUTPUT, true, true).action)
        assertEquals(Action.RETRY, r.onError("a", PlaybackFailureKind.AUDIO_OUTPUT, true, true).action)
        val third = r.onError("a", PlaybackFailureKind.AUDIO_OUTPUT, true, true)
        assertEquals(Action.STOP, third.action)
        assertEquals(StopReason.AUDIO_OUTPUT, third.stopReason)
        // no network does not turn an audio output error into a wait
        assertEquals(Action.RETRY, r.onError("b", PlaybackFailureKind.AUDIO_OUTPUT, networkUp = false, canSkip = true).action)
    }

    @Test
    fun `a decoder error switches to software decoding once, then counts as a failed song`() {
        val r = PlaybackRecovery()
        val first = r.onError("a", PlaybackFailureKind.DECODER, true, true)
        assertEquals(Action.RETRY, first.action)
        assertTrue(first.softwareDecoder)
        assertEquals(Action.SKIP, r.onError("a", PlaybackFailureKind.DECODER, true, true).action)
    }

    @Test
    fun `a cache mismatch drops the cached bytes and gets a fresh stream`() {
        val r = PlaybackRecovery()
        val d = r.onError("a", PlaybackFailureKind.CACHE_MISMATCH, true, true)
        assertEquals(Action.RETRY, d.action)
        assertTrue(d.clearCache && d.freshStream)
    }

    @Test
    fun `the user pressing play gives the song a fresh set of tries`() {
        val r = PlaybackRecovery()
        repeat(3) { rejected(r, "a") }
        r.onUserAction("a")
        assertEquals(0, r.attemptsFor("a"))
        assertEquals(1, rejected(r, "a").attempt)
    }

    @Test
    fun `the stall watchdog giving up - a song that never started counts as failed, a stall mid-song stops`() {
        val r = PlaybackRecovery()
        assertEquals(Action.SKIP, r.onStallGiveUp("a", neverStarted = true, canSkip = true).action)
        val mid = r.onStallGiveUp("b", neverStarted = false, canSkip = true)
        assertEquals(Action.STOP, mid.action)
        assertEquals(StopReason.NETWORK, mid.stopReason)
    }

    @Test
    fun `a song ended early is resumed twice at most`() {
        val r = PlaybackRecovery()
        assertTrue(r.onPrematureEnd("a"))
        assertTrue(r.onPrematureEnd("a"))
        assertFalse(r.onPrematureEnd("a"))
        r.onUserAction("a")
        assertTrue(r.onPrematureEnd("a"))
    }

    @Test
    fun `a queue run through without being heard stops at the third song, normal plays never trigger it`() {
        val r = PlaybackRecovery()
        // video A: 3:58, 5:27, 3:48 ... each "played" in about 2.5 s
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(327_000, 3_000, 1f))
        assertTrue(r.onSongEnded(228_000, 2_000, 1f))
        // after the stop the count starts again; a song heard in full resets it
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 236_000, 1f))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        // fast at 1.4x (nightcore) or a short track is not "run through"
        repeat(5) { assertFalse(r.onSongEnded(200_000, 140_000, 1.4f)) }
        repeat(5) { assertFalse(r.onSongEnded(40_000, 1_000, 1f)) }
    }

    @Test
    fun `finding 19 - scrubbing to the end of three songs is not a run-through, three natural fast ends still are`() {
        val r = PlaybackRecovery()
        // previewing a playlist: 3 s heard, then the seek bar dragged to the last seconds of each song
        repeat(5) { assertFalse(r.onSongEnded(238_000, 3_000, 1f, userSeekedForwardMs = 230_000)) }
        // seek buttons pressed a few times (5 s each) do not hide a real run-through
        assertFalse(r.onSongEnded(238_000, 2_500, 1f, userSeekedForwardMs = 15_000))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f, userSeekedForwardMs = 15_000))
        assertTrue(r.onSongEnded(238_000, 2_500, 1f, userSeekedForwardMs = 15_000))
        // a scrubbed song in the middle of a run breaks it
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 2_000, 1f, userSeekedForwardMs = 200_000))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertTrue(r.onSongEnded(238_000, 2_500, 1f))
    }

    @Test
    fun `finding 19 - a song the user picks starts the run-through count again`() {
        val r = PlaybackRecovery()
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        r.onUserAction("x")
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertFalse(r.onSongEnded(238_000, 2_500, 1f))
        assertTrue(r.onSongEnded(238_000, 2_500, 1f))
    }

    @Test
    fun `ended early - only clear cases count`() {
        assertTrue(PlaybackRecovery.endedEarly(31_000, 212_000)) // a 512 KiB chunk of a 3:32 song
        assertFalse(PlaybackRecovery.endedEarly(205_000, 212_000)) // the last seconds
        assertFalse(PlaybackRecovery.endedEarly(10_000, 25_000)) // a short track
        assertFalse(PlaybackRecovery.endedEarly(10_000, -9_223_372_036_854_775_807L)) // unknown length (C.TIME_UNSET)
        assertFalse(PlaybackRecovery.endedEarly(180_000, 190_000)) // 10 s before the end: not more than 15 s
    }
}
