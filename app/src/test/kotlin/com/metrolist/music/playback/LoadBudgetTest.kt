/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-12, finding 7: the whole-song buffer (15 min / 8 MiB) also ran on mobile data, so every skipped song had
 * been downloaded whole. Pure JVM: the budget decisions, and the network facts behind them.
 */
class LoadBudgetTest {
    private val s = 1_000_000L // one second in microseconds

    /** Simulates a song being loaded and played: [budget] decides, the buffer grows while loading and drains while playing. */
    private fun maxBuffered(
        wholeSong: Boolean,
        bytesPerSecond: Int = 20_000,
        songSeconds: Long = 240,
    ): Pair<Long, Int> {
        val budget = LoadBudget()
        var buffered = 0L
        var loadedTotal = 0L
        var maxSeen = 0L
        var loadsStarted = 0
        var wasLoading = false
        // 0.5 s steps: loading adds 4 s of audio per step (a fast link), playing removes 0.5 s
        repeat(1_000) {
            val bytes = (buffered / s * bytesPerSecond).toInt()
            val load = loadedTotal < songSeconds * s && budget.shouldLoad("p", delegateSays = true, wholeSong = wholeSong, bufferedUs = buffered, bytesAllocated = bytes)
            if (load) {
                if (!wasLoading) loadsStarted++
                val add = minOf(4 * s, songSeconds * s - loadedTotal)
                buffered += add
                loadedTotal += add
            }
            wasLoading = load
            buffered = (buffered - s / 2).coerceAtLeast(0L)
            maxSeen = maxOf(maxSeen, buffered)
        }
        return maxSeen to loadsStarted
    }

    @Test
    fun `on Wi-Fi the whole song is loaded, on mobile data the buffer stays near 90 s`() {
        val (wifi, _) = maxBuffered(wholeSong = true)
        assertTrue("whole song buffered on Wi-Fi: $wifi", wifi > 200 * s)
        val (data, starts) = maxBuffered(wholeSong = false)
        assertTrue("moderate buffer on data: $data", data in 85 * s..95 * s)
        // between 60 and 90 s it does not stop and start for every few kilobytes
        assertTrue("load bursts: $starts", starts < 10)
    }

    @Test
    fun `on mobile data the byte cap applies too (a high bitrate)`() {
        val budget = LoadBudget()
        assertFalse(budget.shouldLoad("p", true, wholeSong = false, bufferedUs = 40 * s, bytesAllocated = LoadBudget.METERED_MAX_BYTES))
        // but the player is never starved, whatever the bytes say
        assertTrue(budget.shouldLoad("p", true, wholeSong = false, bufferedUs = 5 * s, bytesAllocated = 10 * 1024 * 1024))
    }

    @Test
    fun `the whole-song control's own no is always kept, and the budget is per player`() {
        val budget = LoadBudget()
        assertFalse(budget.shouldLoad("p", delegateSays = false, wholeSong = true, bufferedUs = 0, bytesAllocated = 0))
        assertFalse(budget.shouldLoad("p", delegateSays = false, wholeSong = false, bufferedUs = 0, bytesAllocated = 0))
        assertFalse(budget.shouldLoad("a", true, wholeSong = false, bufferedUs = 95 * s, bytesAllocated = 0))
        assertTrue(budget.shouldLoad("b", true, wholeSong = false, bufferedUs = 70 * s, bytesAllocated = 0))
        // "a" stopped at 95 s: at 70 s it still waits (hysteresis), below 60 s it loads again
        assertFalse(budget.shouldLoad("a", true, wholeSong = false, bufferedUs = 70 * s, bytesAllocated = 0))
        assertTrue(budget.shouldLoad("a", true, wholeSong = false, bufferedUs = 59 * s, bytesAllocated = 0))
    }

    @Test
    fun `a change from data to Wi-Fi in the middle of a song lets it load the rest`() {
        val budget = LoadBudget()
        assertFalse(budget.shouldLoad("p", true, wholeSong = false, bufferedUs = 91 * s, bytesAllocated = 0))
        assertTrue(budget.shouldLoad("p", true, wholeSong = true, bufferedUs = 91 * s, bytesAllocated = 0))
        // and back to data: stops at once above the cap
        assertFalse(budget.shouldLoad("p", true, wholeSong = false, bufferedUs = 120 * s, bytesAllocated = 0))
    }

    @Test
    fun `the default network - a validated network appearing is told once, metered follows the network`() {
        val state = DefaultNetworkState()
        // registration: the current default network, validated
        assertTrue(state.onCapabilities("wifi", validated = true, unmetered = true))
        assertTrue(state.unmetered)
        // the same network again (signal strength, link speed): nothing new
        assertFalse(state.onCapabilities("wifi", validated = true, unmetered = true))
        // the Wi-Fi lost its upstream: validation goes away ...
        assertFalse(state.onCapabilities("wifi", validated = false, unmetered = true))
        assertFalse(state.validated)
        // ... and comes back: that is the moment to retry
        assertTrue(state.onCapabilities("wifi", validated = true, unmetered = true))
        // the phone moves to mobile data, already validated: new network
        assertTrue(state.onCapabilities("cell", validated = true, unmetered = false))
        assertFalse(state.unmetered)
        // the old Wi-Fi is lost after the switch: the default stays mobile data
        state.onLost("wifi")
        assertEquals("cell", state.network)
        state.onLost("cell")
        assertFalse(state.validated)
        assertFalse(state.unmetered)
    }
}
