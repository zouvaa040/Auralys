/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The lyric clock frame by frame (60 fps): it moves on every frame, never backwards while the song plays, and jumps only
 * on a seek, a new song, a speed change, a pause or a reading far away. The readings are made like ExoPlayer's: the
 * position in 10 ms steps with a few ms of jitter, or (cast) every 500 ms.
 */
class LyricsClockTest {
    private val frameMs = 1000.0 / 60.0

    /** ExoPlayer-like reading at time [t] (ms) for a song started at [startPos] when the clock read [startAt]. */
    private fun reading(t: Double, startAt: Double, startPos: Long, speed: Double, jitter: Random): Long {
        val exact = startPos + (t - startAt) * speed
        val stepped = (exact / 10.0).toLong() * 10L
        return stepped - jitter.nextInt(0, 5)
    }

    @Test
    fun `while playing it moves every frame, never backwards, close to the song`() {
        val clock = LyricsClock()
        val rnd = Random(7)
        var t = 1000.0
        var last = clock.frame(t, 5000L, 1f, 0)
        var stalled = 0
        var maxError = 0.0
        repeat(600) {
            t += frameMs
            val r = reading(t, 1000.0, 5000L, 1.0, rnd)
            val p = clock.frame(t, r, 1f, 0)
            assertTrue("went back from $last to $p", p >= last)
            if (p == last) stalled++
            maxError = maxOf(maxError, abs(p - (5000.0 + (t - 1000.0))))
            last = p
        }
        assertEquals("frames without movement", 0, stalled)
        assertTrue("off the song by $maxError ms", maxError < 25.0)
    }

    @Test
    fun `each frame moves by about one frame of song time (no steps)`() {
        val clock = LyricsClock()
        val rnd = Random(11)
        var t = 0.0
        var last = clock.frame(t, 0L, 1f, 0).toDouble()
        var worst = 0.0
        repeat(600) {
            t += frameMs
            clock.frame(t, reading(t, 0.0, 0L, 1.0, rnd), 1f, 0)
            val p = clock.positionMs
            val step = p - last
            worst = maxOf(worst, abs(step - frameMs))
            last = p
        }
        // the old view took the 100 ms progress value: a step of 100 ms every sixth frame and 0 in between
        assertTrue("a frame stepped by ${frameMs + worst} ms", worst < frameMs * 0.3)
    }

    @Test
    fun `it follows the playback speed`() {
        for (speed in listOf(0.8f, 1.25f, 1.5f)) {
            val clock = LyricsClock()
            val rnd = Random(3)
            var t = 0.0
            clock.frame(t, 10_000L, speed, 0)
            repeat(300) {
                t += frameMs
                clock.frame(t, reading(t, 0.0, 10_000L, speed.toDouble(), rnd), speed, 0)
            }
            val expected = 10_000.0 + t * speed
            assertTrue("speed $speed: ${clock.positionMs} vs $expected", abs(clock.positionMs - expected) < 25.0)
        }
    }

    @Test
    fun `a seek jumps at once, also backwards, and says so`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 60_000L, 1f, 0)
        repeat(30) {
            t += frameMs
            clock.frame(t, 60_000L + t.toLong(), 1f, 0)
        }
        assertFalse(clock.jumped)
        t += frameMs
        val p = clock.frame(t, 20_000L, 1f, 1) // the player announced a seek back
        assertEquals(20_000L, p)
        assertTrue(clock.jumped)
        t += frameMs
        clock.frame(t, 20_000L + frameMs.toLong(), 1f, 1)
        assertFalse(clock.jumped)
    }

    @Test
    fun `a far reading nobody announced is jumped to, a small one is glided to`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 1000L, 1f, 0)
        t += frameMs
        clock.frame(t, 1017L, 1f, 0)
        // 2 s ahead without an event (another player took over): jump
        t += frameMs
        assertEquals(3034L, clock.frame(t, 3034L, 1f, 0))
        assertTrue(clock.jumped)
        // 200 ms behind: no jump back, it only runs slower until the song catches up
        var last = clock.positionMs
        repeat(60) {
            t += frameMs
            clock.frame(t, (2834 + (it + 1) * frameMs).toLong(), 1f, 0)
            assertTrue(clock.positionMs >= last)
            assertTrue("slower, never stopped", clock.positionMs - last >= frameMs * 0.49)
            last = clock.positionMs
        }
    }

    @Test
    fun `paused it stands where the player says, and resumes without a jump`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 4000L, 1f, 0)
        repeat(10) {
            t += frameMs
            clock.frame(t, (4000 + t).toLong(), 1f, 0)
        }
        assertEquals(4200L, clock.hold(4200L))
        assertFalse("a pause is not a seek", clock.jumped)
        assertEquals(9000L, clock.hold(9000L)) // a seek while paused
        assertTrue(clock.jumped)
        t += 5000.0 // paused for 5 s
        assertEquals(9000L, clock.frame(t, 9000L, 1f, 0))
        assertFalse(clock.jumped)
        t += frameMs
        val p = clock.frame(t, 9010L, 1f, 0)
        assertTrue(p in 9010L..9020L)
    }

    @Test
    fun `a speed change starts again from the reading`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 1000L, 1f, 0)
        t += frameMs
        clock.frame(t, 1016L, 1f, 0)
        t += frameMs
        assertEquals(1033L, clock.frame(t, 1033L, 1.5f, 0))
        t += frameMs
        val p = clock.frame(t, 1033L + (frameMs * 1.5).toLong(), 1.5f, 0)
        assertTrue(p in 1050L..1062L)
    }

    @Test
    fun `sparse readings (cast, twice a second) still give a smooth clock`() {
        val clock = LyricsClock()
        var t = 0.0
        var r = 30_000L
        clock.frame(t, r, 1f, 0)
        var last = clock.positionMs
        var worst = 0.0
        repeat(600) { f ->
            t += frameMs
            if (f % 30 == 29) r = (30_000 + t - 120).toLong() // a reading every 500 ms, 120 ms old when it arrives
            clock.frame(t, r, 1f, 0)
            val step = clock.positionMs - last
            assertTrue(step >= 0.0)
            worst = maxOf(worst, abs(step - frameMs))
            last = clock.positionMs
        }
        assertTrue("a frame stepped by ${frameMs + worst} ms", worst < frameMs * 0.6)
        assertTrue("off by ${abs(clock.positionMs - (30_000 + t))}", abs(clock.positionMs - (30_000 + t)) < 300)
    }

    @Test
    fun `a reading that stops moving while playing (cast buffering) stops the clock, no run ahead, no snap back`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 50_000L, 1f, 0)
        repeat(60) {
            t += frameMs
            clock.frame(t, (50_000 + t).toLong(), 1f, 0)
        }
        val stalledAt = (50_000 + t).toLong()
        var last = clock.positionMs
        // the device buffers for 5 s: the reading stays, "playing" stays true
        repeat(300) {
            t += frameMs
            clock.frame(t, stalledAt, 1f, 0)
            assertTrue("went back", clock.positionMs >= last)
            last = clock.positionMs
        }
        assertTrue("ran ahead to ${clock.positionMs}", clock.positionMs <= stalledAt + LyricsClock.MAX_AHEAD_MS + 1)
        // playing again from where it stopped: no jump back, it moves on from there
        t += frameMs
        clock.frame(t, stalledAt + 16, 1f, 0)
        assertTrue(clock.positionMs >= last)
        assertFalse(clock.jumped)
    }

    @Test
    fun `a stalled frame catches up without jumping past the song`() {
        val clock = LyricsClock()
        var t = 0.0
        clock.frame(t, 0L, 1f, 0)
        repeat(10) {
            t += frameMs
            clock.frame(t, t.toLong(), 1f, 0)
        }
        t += 300.0 // the main thread was busy for 300 ms
        val p = clock.frame(t, t.toLong(), 1f, 0)
        assertTrue(abs(p - t) < 5)
    }
}
