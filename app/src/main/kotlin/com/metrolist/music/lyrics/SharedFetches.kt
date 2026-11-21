/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * One running fetch per key, shared by every caller that asks for the same key meanwhile.
 *
 * The fetch runs on [scope] (not on the caller), so the second caller can join a fetch the first one started. To
 * keep skipped songs from leaving work behind, the fetch is cancelled as soon as the last caller waiting for it
 * is cancelled or gone.
 */
internal class SharedFetches<T>(
    private val scope: CoroutineScope,
) {
    private class Flight<T>(val deferred: Deferred<T>) {
        var waiters = 0
    }

    private val inFlight = HashMap<String, Flight<T>>()

    /** Number of fetches running now. */
    fun runningCount(): Int = synchronized(inFlight) { inFlight.size }

    suspend fun await(
        key: String,
        fetch: suspend () -> T,
    ): T {
        val flight =
            synchronized(inFlight) {
                val running =
                    inFlight[key] ?: Flight(scope.async { fetch() }).also { started ->
                        inFlight[key] = started
                        started.deferred.invokeOnCompletion {
                            synchronized(inFlight) { if (inFlight[key] === started) inFlight.remove(key) }
                        }
                    }
                running.waiters++
                running
            }
        try {
            return flight.deferred.await()
        } finally {
            synchronized(inFlight) {
                flight.waiters--
                if (flight.waiters <= 0 && !flight.deferred.isCompleted) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                    flight.deferred.cancel()
                }
            }
        }
    }
}
