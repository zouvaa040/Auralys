/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.queues.ShuffleOrders

/**
 * Which part of a long queue is written to disk.
 *
 * Until 4.3.3 only 150 songs before and 350 after the current one (timeline order) were kept, so a restored queue had
 * at most 501 songs: a 731-song playlist lost its bottom part at every restart, and with shuffle on the songs kept were
 * the ones around the current TIMELINE index, i.e. mostly the top of the playlist ("cuma bagian atas yang diputar").
 * Now the whole queue is kept up to [MAX_SAVED] songs (a phone library of a few thousand songs fits; the big file is
 * only rewritten when the queue itself changes, see MusicService.writeQueueSnapshot). Beyond that the songs around the
 * current one in the PLAY order are kept: at most [MAX_SAVED_BEFORE] already heard, all the rest still to come.
 */
internal object PersistQueueWindow {
    const val MAX_SAVED = 5000
    const val MAX_SAVED_BEFORE = 500

    /** What to write: [kept] timeline indexes (ascending), the current song's place in it, the play order over it. */
    class Plan(
        val kept: IntArray,
        val currentIndex: Int,
        /** The shuffle order as positions in [kept] (a permutation of 0 until kept.size), null with shuffle off. */
        val order: IntArray?,
    )

    /**
     * [playOrder] is the player's shuffle order (timeline indexes in play order) when shuffle is on, else null.
     * [keep] says which timeline indexes can be written at all (a row without metadata cannot).
     */
    fun plan(
        size: Int,
        currentIndex: Int,
        playOrder: IntArray?,
        keep: (Int) -> Boolean = { true },
        max: Int = MAX_SAVED,
        maxBefore: Int = MAX_SAVED_BEFORE,
    ): Plan {
        if (size <= 0) return Plan(IntArray(0), 0, null)
        val current = currentIndex.coerceIn(0, size - 1)
        val shuffle = playOrder != null && ShuffleOrders.isPermutation(playOrder, size)
        val order = if (shuffle) playOrder!! else IntArray(size) { it }

        // the window, in play order
        val selected: List<Int> =
            if (size <= max) {
                order.toList()
            } else {
                val p = order.indexOf(current).coerceAtLeast(0)
                var before = minOf(p, maxBefore, max - 1)
                val after = minOf(size - 1 - p, max - 1 - before)
                before = minOf(p, max - 1 - after)
                order.copyOfRange(p - before, p + after + 1).toList()
            }
        val usable = selected.filter(keep)
        val kept = usable.sorted().toIntArray()
        if (kept.isEmpty()) return Plan(kept, 0, null)
        val positionInKept = HashMap<Int, Int>(kept.size * 2)
        kept.forEachIndexed { i, v -> positionInKept[v] = i }
        // the current song, or the first kept one after it in play order when it cannot be written
        val currentKept =
            positionInKept[current]
                ?: selected.dropWhile { it != current }.firstNotNullOfOrNull { positionInKept[it] }
                ?: 0
        val keptOrder = if (shuffle) usable.map { positionInKept.getValue(it) }.toIntArray() else null
        return Plan(kept, currentKept, keptOrder)
    }

    /** Kept for callers of the old window: the timeline range [plan] keeps with shuffle off and every row writable. */
    fun range(
        size: Int,
        currentIndex: Int,
    ): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val kept = plan(size, currentIndex, null).kept
        return kept.first()..kept.last()
    }
}
