/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One stream extraction per song at a time, with a limit on how long the PLAYER waits for it.
 *
 * Before round 4.3.4 the resolver ran the whole extraction inside ExoPlayer's loader with no overall limit: on a weak or
 * busy connection (a game running, a bad signal) every client was tried with its own 8 s request limit, the PO token with
 * its own, the watch page with 60 s socket limits, and the song sat at 0:00 for minutes, sometimes 10 to 30 ("musik mati
 * sendiri, 10-30 menit hidup sendiri").
 *
 * Now the player waits at most [waitMs]; the extraction itself goes on in [scope] (up to [hardCapMs]), so a slow but
 * working extraction is not thrown away: the service's retry a few seconds later joins the same one instead of starting
 * over. A finished extraction is kept for [keepMs] so a retry that arrives just after it still gets its result.
 *
 * Review 2026-11-12, finding 27: an extraction of a song the user skipped is cancelled as soon as nobody waits for it and
 * the song is neither the current nor the next one ([retainOnly]); fast skipping on a weak link no longer leaves ten
 * extractions competing with the song being played. Finished entries are swept after [keepMs].
 */
internal class ExtractionGate<T>(
    private val scope: CoroutineScope,
    private val waitMs: Long,
    private val hardCapMs: Long,
    private val keepMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T>(
        val deferred: Deferred<Result<T>>,
        @Volatile var finishedAtMs: Long = NOT_FINISHED,
        val waiters: AtomicInteger = AtomicInteger(0),
    )

    private val entries = ConcurrentHashMap<String, Entry<T>>()

    /** The songs that may keep an extraction nobody waits for (current and next); null = every song (not set yet). */
    @Volatile
    private var wanted: Set<String>? = null

    /**
     * The result of [block] for [key]: from an extraction already running or just finished, else from a new one. Null when
     * it is still running after [waitMs] (the caller reports a slow network and tries again later).
     */
    suspend fun await(
        key: String,
        block: suspend () -> Result<T>,
    ): Result<T>? {
        sweep()
        repeat(MAX_ROUNDS) {
            val entry = entryFor(key, block)
            entry.waiters.incrementAndGet()
            try {
                val result =
                    try {
                        withTimeoutOrNull(waitMs) { entry.deferred.await() } ?: return null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // the extraction (not this wait) was cancelled a moment before we joined it: start a new one
                        if (entry.deferred.isCancelled && kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) {
                            entries.remove(key, entry)
                            return@repeat
                        }
                        throw e
                    }
                // a failure is never reused: the next try starts a fresh extraction (with the clients that failed excluded)
                if (result.isFailure) entries.remove(key, entry)
                return result
            } finally {
                // the last waiter left (time limit, or the loader was cancelled by a skip): drop it if the song is not wanted
                if (entry.waiters.decrementAndGet() == 0) cancelIfOrphan(key, entry)
            }
        }
        return null
    }

    /** Drops what is known about [key] (a stream that was refused must not be handed out again). */
    fun forget(key: String) {
        val entry = entries.remove(key) ?: return
        // nobody waits for its result any more: do not let it run on in the background
        if (entry.waiters.get() == 0) entry.deferred.cancel()
    }

    /**
     * Only [keys] (the current and the next song) keep an extraction that nobody waits for; the others are cancelled and
     * forgotten. An extraction that a player load still waits for is left alone; it is dropped when that wait ends.
     */
    fun retainOnly(keys: Set<String>) {
        wanted = keys
        for ((key, entry) in entries) {
            if (key !in keys) cancelIfOrphan(key, entry)
        }
    }

    /** True while an extraction for [key] runs (for tests). */
    fun isRunning(key: String): Boolean = entries[key]?.deferred?.isActive == true

    /** How many extractions are known (running or kept), for tests. */
    fun size(): Int = entries.size

    private fun cancelIfOrphan(
        key: String,
        entry: Entry<T>,
    ) {
        val keep = wanted ?: return
        if (key in keep || entry.waiters.get() > 0) return
        if (entries.remove(key, entry)) entry.deferred.cancel()
    }

    /** Removes finished entries older than [keepMs] and failed ones (they are never handed out again anyway). */
    private fun sweep() {
        val now = clock()
        for ((key, entry) in entries) {
            val finished = entry.finishedAtMs
            if (finished == NOT_FINISHED) {
                if (entry.deferred.isCancelled) entries.remove(key, entry)
                continue
            }
            if (now - finished >= keepMs || !entry.deferred.isCompletedOk()) entries.remove(key, entry)
        }
    }

    private fun entryFor(
        key: String,
        block: suspend () -> Result<T>,
    ): Entry<T> {
        while (true) {
            val existing = entries[key]
            if (existing != null) {
                val usable =
                    existing.deferred.isActive ||
                        (existing.finishedAtMs != NOT_FINISHED && clock() - existing.finishedAtMs < keepMs && existing.deferred.isCompletedOk())
                if (usable) return existing
                entries.remove(key, existing)
                continue
            }
            lateinit var created: Entry<T>
            val deferred =
                scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    val result =
                        try {
                            withTimeout(hardCapMs) { block() }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            Result.failure(e)
                        }
                    created.finishedAtMs = clock()
                    result
                }
            created = Entry(deferred)
            if (entries.putIfAbsent(key, created) == null) {
                deferred.start()
                return created
            }
            deferred.cancel()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Deferred<Result<T>>.isCompletedOk(): Boolean = isCompleted && !isCancelled && getCompleted().isSuccess

    private companion object {
        const val NOT_FINISHED = Long.MIN_VALUE
        const val MAX_ROUNDS = 2
    }
}
