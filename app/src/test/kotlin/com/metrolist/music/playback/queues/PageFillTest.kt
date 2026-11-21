/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review 2026-11-12, finding 31: the background fill of a shuffled YouTube playlist fired up to 50 failing page requests
 * back to back when the network was down. Pure JVM.
 */
class PageFillTest {
    @Test
    fun `a page that keeps failing is asked three times with pauses, not fifty times at once`() =
        runBlocking {
            var asked = 0
            val sleeps = ArrayList<Long>()
            val loaded =
                PageFill.run(
                    maxPages = QueueDefaults.SHUFFLE_FILL_MAX_PAGES,
                    maxFailuresInARow = QueueDefaults.SHUFFLE_FILL_MAX_FAILURES,
                    backoffMs = QueueDefaults.SHUFFLE_FILL_BACKOFF_MS,
                    shouldContinue = { true },
                    sleep = { sleeps += it },
                ) {
                    asked++
                    false
                }
            assertEquals(0, loaded)
            assertEquals(3, asked)
            assertEquals(listOf(2_000L, 6_000L), sleeps)
        }

    @Test
    fun `a page that works again resets the failure count, and the fill ends with the playlist`() =
        runBlocking {
            // pages: fail, ok, fail, fail, ok, ok, then the playlist has no next page
            val script = ArrayDeque(listOf(false, true, false, false, true, true))
            val sleeps = ArrayList<Long>()
            val loaded =
                PageFill.run(
                    maxPages = 50,
                    maxFailuresInARow = 3,
                    backoffMs = longArrayOf(2_000L, 6_000L),
                    shouldContinue = { script.isNotEmpty() },
                    sleep = { sleeps += it },
                ) { script.removeFirst() }
            assertEquals(3, loaded)
            assertEquals(listOf(2_000L, 2_000L, 6_000L), sleeps)
        }

    @Test
    fun `never more pages than the limit`() =
        runBlocking {
            var asked = 0
            val loaded =
                PageFill.run(maxPages = 5, maxFailuresInARow = 3, backoffMs = longArrayOf(1L), shouldContinue = { true }, sleep = {}) {
                    asked++
                    true
                }
            assertEquals(5, loaded)
            assertEquals(5, asked)
        }
}
