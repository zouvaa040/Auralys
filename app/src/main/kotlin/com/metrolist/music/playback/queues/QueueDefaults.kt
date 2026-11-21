/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

/** Defaults of the queue settings, shared by the service and the settings screen so they cannot drift apart. */
object QueueDefaults {
    /**
     * "Acak persisten": ON since 4.3.4. Shuffle switched on by the listener stays on when they tap another song (that song
     * plays first, the rest at random), as in other music apps; before, every tap switched it off and the list played in
     * order ("lagunya urut terus"). A value the listener saved is kept.
     */
    const val PERSISTENT_SHUFFLE = true

    /** "Putar otomatis tanpa lagu berulang": suggestions never bring back a song heard in this session. */
    const val AUTOPLAY_NO_REPEAT = true

    /** The queue screen is unlocked unless the listener locked it (swipe to remove or play next, drag to move). */
    const val QUEUE_LOCKED = false

    /** Pages of a YouTube playlist loaded at most when it is shuffled (100 songs each). */
    const val SHUFFLE_FILL_MAX_PAGES = 50

    /** The shuffle fill gives up after this many failed pages in a row (no network: it used to fire 50 requests at once). */
    const val SHUFFLE_FILL_MAX_FAILURES = 3

    /** Pauses after the first and the second failed page of the shuffle fill. */
    val SHUFFLE_FILL_BACKOFF_MS = longArrayOf(2_000L, 6_000L)
}

/**
 * Loads pages one after the other until [shouldContinue] says stop or [maxPages] were asked (review 2026-11-12,
 * finding 31). A failed page (no network, a server error) is retried after a pause ([backoffMs], the last one repeats);
 * after [maxFailuresInARow] failures in a row the fill stops. Returns how many pages were loaded.
 */
internal object PageFill {
    suspend fun run(
        maxPages: Int,
        maxFailuresInARow: Int,
        backoffMs: LongArray,
        shouldContinue: () -> Boolean,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        loadPage: suspend () -> Boolean,
    ): Int {
        var asked = 0
        var loaded = 0
        var failures = 0
        while (asked < maxPages && shouldContinue()) {
            asked++
            if (loadPage()) {
                loaded++
                failures = 0
                continue
            }
            failures++
            if (failures >= maxFailuresInARow) break
            if (backoffMs.isNotEmpty()) sleep(backoffMs[(failures - 1).coerceAtMost(backoffMs.lastIndex)])
        }
        return loaded
    }
}
