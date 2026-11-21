/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.StallWatchdog.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 2: "pause icon, 0:00 for ever". The watchdog, ticked once a second, never lets a wanted song sit in buffering. */
class StallWatchdogTest {
    private var now = 0L
    private val dog = StallWatchdog(clock = { now })

    private fun tick(
        buffering: Boolean = true,
        playing: Boolean = false,
        position: Long = 0L,
        network: Boolean = true,
        mediaId: String? = "song",
        playWhenReady: Boolean = true,
        eligible: Boolean = true,
    ): Action = dog.onTick(mediaId, buffering, playing, playWhenReady, position, network, eligible)

    /** Ticks every second for [seconds] and returns the actions that were not None, with the second they came at. */
    private fun run(
        seconds: Int,
        position: Long = 0L,
        network: Boolean = true,
    ): List<Pair<Long, Action>> {
        val out = ArrayList<Pair<Long, Action>>()
        repeat(seconds) {
            now += 1_000L
            val a = tick(position = position, network = network)
            if (a != Action.None) out += now / 1000 to a
        }
        return out
    }

    @Test
    fun `a song stuck at 0 00 is recovered at 12, 20 and 30 s intervals, then given up`() {
        tick() // the stall starts at 0
        val actions = run(130)
        assertEquals(
            listOf(
                12L to Action.Recover(1, atStart = true),
                32L to Action.Recover(2, atStart = true),
                62L to Action.Recover(3, atStart = true),
                92L to Action.GiveUp(neverStarted = true),
            ),
            actions.take(4),
        )
    }

    @Test
    fun `a stall in the middle of a song waits longer before each recovery`() {
        tick(position = 95_000)
        val actions = run(100, position = 95_000)
        assertEquals(listOf(20L, 50L, 95L), actions.map { it.first })
        assertTrue(actions.all { (it.second as Action.Recover).atStart.not() })
    }

    @Test
    fun `no network is waiting for the network, not a stall`() {
        tick(network = false)
        assertEquals(emptyList<Pair<Long, Action>>(), run(300, network = false))
    }

    @Test
    fun `playing resets the stall clock, and a few seconds of play reset the recoveries`() {
        tick()
        assertEquals(1, run(13).size) // recovered once at 12 s
        assertEquals(1, dog.recoveriesForCurrent)
        // it plays again: 4 s of progress
        for (p in listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L)) {
            now += 1_000L
            assertEquals(Action.None, tick(buffering = false, playing = true, position = p))
        }
        assertEquals(0, dog.recoveriesForCurrent)
    }

    @Test
    fun `paused, not eligible (a phone file, casting) or a new song never fire`() {
        tick(playWhenReady = false)
        repeat(60) {
            now += 1_000L
            assertEquals(Action.None, tick(playWhenReady = false))
            assertEquals(Action.None, tick(eligible = false))
        }
        // a new song starts its own clock
        tick(mediaId = "other")
        now += 11_000L
        assertEquals(Action.None, tick(mediaId = "other"))
        now += 1_000L
        assertEquals(Action.Recover(1, atStart = true), tick(mediaId = "other"))
    }
}
