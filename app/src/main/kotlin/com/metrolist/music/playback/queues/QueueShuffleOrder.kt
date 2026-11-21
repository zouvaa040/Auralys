/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The shuffle order the player uses. Same as ExoPlayer's DefaultShuffleOrder except for one thing: songs added to the
 * queue go to the END of the play order. DefaultShuffleOrder puts each added song at a random place of the whole order,
 * also before the song that plays now, where it is never reached in this pass (and the app then reshuffled everything,
 * which brought the songs already heard back: "lagunya keulang-ulang").
 *
 * Removing songs keeps the order of the others; a moved song keeps its place in the play order.
 */
class QueueShuffleOrder(
    order: IntArray,
    seed: Long = System.nanoTime(),
) : ShuffleOrder {
    private val shuffled: IntArray = order.copyOf()
    private val random = java.util.Random(seed)
    private val positionOf = IntArray(shuffled.size)

    init {
        require(ShuffleOrders.isPermutation(shuffled, shuffled.size)) { "not a permutation of 0 until ${shuffled.size}" }
        for (i in shuffled.indices) positionOf[shuffled[i]] = i
    }

    /** The play order: timeline indexes, first to last. */
    fun toArray(): IntArray = shuffled.copyOf()

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        val p = positionOf[index] + 1
        return if (p < shuffled.size) shuffled[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = positionOf[index] - 1
        return if (p >= 0) shuffled[p] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[shuffled.size - 1]

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[0]

    override fun cloneAndInsert(
        insertionIndex: Int,
        insertionCount: Int,
    ): ShuffleOrder {
        // a whole new list (setMediaItems from a media controller, Android Auto, Listen Together): random, as before;
        // the app's own queues then get their pass with the first song first (PlayerShuffle.startPass)
        if (shuffled.isEmpty()) {
            val seed = random.nextLong()
            return QueueShuffleOrder(ShuffleOrders.fresh(insertionCount, -1, Random(seed)), seed)
        }
        return QueueShuffleOrder(ShuffleOrders.insertAtEnd(shuffled, insertionIndex, insertionCount), random.nextLong())
    }

    override fun cloneAndRemove(
        indexFrom: Int,
        indexToExclusive: Int,
    ): ShuffleOrder = QueueShuffleOrder(ShuffleOrders.remove(shuffled, indexFrom, indexToExclusive), random.nextLong())

    override fun cloneAndMove(
        indexFrom: Int,
        indexToExclusive: Int,
        newIndexFrom: Int,
    ): ShuffleOrder = QueueShuffleOrder(ShuffleOrders.move(shuffled, indexFrom, indexToExclusive, newIndexFrom), random.nextLong())

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random.nextLong())
}

/**
 * Shuffle as the listener expects it, on plain arrays so it can be tested: a "play order" is the list of timeline
 * indexes in the order they play. A pass plays every song once; songs already heard in this pass are never put back in
 * front of the one playing now.
 */
object ShuffleOrders {
    fun isPermutation(
        order: IntArray,
        size: Int,
    ): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (v in order) {
            if (v !in 0 until size || seen[v]) return false
            seen[v] = true
        }
        return true
    }

    /** A new pass: [first] plays first (when it is a valid index), all the others follow in a uniform random order. */
    fun fresh(
        count: Int,
        first: Int,
        random: Random = Random.Default,
    ): IntArray {
        if (count <= 0) return IntArray(0)
        val order = IntArray(count) { it }
        order.shuffle(random)
        if (first in 0 until count) {
            val at = order.indexOf(first)
            order[at] = order[0]
            order[0] = first
            // the swap above would leave the old first song always at [at]: shuffle the tail again so it is uniform
            shuffleRange(order, 1, count, random)
        }
        return order
    }

    /**
     * "Acak playlist atau album terlebih dahulu": the songs of the list itself ([originalSize] first indexes) come
     * before the songs added later, each part in its own random order; [first] plays first.
     */
    fun freshListFirst(
        count: Int,
        first: Int,
        originalSize: Int,
        random: Random = Random.Default,
    ): IntArray {
        if (originalSize <= 0 || originalSize >= count) return fresh(count, first, random)
        val original = (0 until originalSize).filter { it != first }.shuffled(random)
        val added = (originalSize until count).filter { it != first }.shuffled(random)
        val head = if (first in 0 until count) listOf(first) else emptyList()
        return (head + original + added).toIntArray()
    }

    /**
     * The next pass, decided when the last song of this pass starts (repeat all): every other song in a new random
     * order, [current] stays last so the song that plays now is not touched. The song that opens the new pass is not
     * the one that opened the pass that ends now, when there is a choice.
     */
    fun nextPass(
        order: IntArray,
        current: Int,
        random: Random = Random.Default,
    ): IntArray {
        if (order.size < 3 || current !in order) return order.copyOf()
        val others = order.filter { it != current }.toMutableList()
        others.shuffle(random)
        val previousFirst = order[0]
        if (others.size > 1 && others[0] == previousFirst) {
            val swapWith = 1 + random.nextInt(others.size - 1)
            others[0] = others[swapWith]
            others[swapWith] = previousFirst
        }
        return (others + current).toIntArray()
    }

    /**
     * [newIndexes] (already in the timeline and somewhere in [order]) go right after [current], in the order given;
     * everything else keeps its place. For "Putar berikutnya" and for a radio started from the playing song.
     */
    fun placeAfterCurrent(
        order: IntArray,
        current: Int,
        newIndexes: List<Int>,
    ): IntArray {
        val fresh = newIndexes.toSet()
        val rest = order.filter { it !in fresh }
        val at = rest.indexOf(current)
        if (at < 0) return (rest + newIndexes).toIntArray()
        return (rest.subList(0, at + 1) + newIndexes + rest.subList(at + 1, rest.size)).toIntArray()
    }

    /** [newIndexes] go to the end of the play order (after every song still waiting). For "Tambah ke antrean". */
    fun appendAtEnd(
        order: IntArray,
        newIndexes: Collection<Int>,
    ): IntArray {
        val fresh = newIndexes.toSet()
        return (order.filter { it !in fresh } + newIndexes.sorted()).toIntArray()
    }

    /**
     * More songs of the SAME list arrived (the next page of a YouTube playlist): they join the songs still waiting, in
     * a random order. The songs already heard and the one playing stay where they are; with [keepNext] the song right
     * after the current one stays next as well (the player may be loading it already).
     */
    fun mixIntoUpcoming(
        order: IntArray,
        current: Int,
        newIndexes: Collection<Int>,
        keepNext: Boolean = true,
        random: Random = Random.Default,
    ): IntArray {
        val fresh = newIndexes.toSet()
        val rest = order.filter { it !in fresh }
        val at = rest.indexOf(current)
        if (at < 0) return (rest + newIndexes.shuffled(random)).toIntArray()
        val played = rest.subList(0, at + 1)
        val upcoming = rest.subList(at + 1, rest.size)
        val pinned = if (keepNext && upcoming.isNotEmpty()) listOf(upcoming[0]) else emptyList()
        val mixed = (upcoming.drop(pinned.size) + fresh).shuffled(random)
        return (played + pinned + mixed).toIntArray()
    }

    // ------------------------------------------------------------------------------------------------ clone helpers

    /** Indexes from [insertionIndex] move up by [count]; the inserted ones are added at the end, in timeline order. */
    fun insertAtEnd(
        order: IntArray,
        insertionIndex: Int,
        count: Int,
    ): IntArray {
        val out = IntArray(order.size + count)
        for (i in order.indices) {
            val v = order[i]
            out[i] = if (v >= insertionIndex) v + count else v
        }
        for (k in 0 until count) out[order.size + k] = insertionIndex + k
        return out
    }

    fun remove(
        order: IntArray,
        from: Int,
        toExclusive: Int,
    ): IntArray {
        val n = toExclusive - from
        if (n <= 0) return order.copyOf()
        val out = IntArray(order.size - n)
        var j = 0
        for (v in order) {
            if (v in from until toExclusive) continue
            out[j++] = if (v >= toExclusive) v - n else v
        }
        return out
    }

    /** The timeline block [from, toExclusive) moves to start at [newFrom]; every song keeps its place in the play order. */
    fun move(
        order: IntArray,
        from: Int,
        toExclusive: Int,
        newFrom: Int,
    ): IntArray {
        val n = toExclusive - from
        if (n <= 0 || from == newFrom) return order.copyOf()
        // the timeline after the move, as old indexes
        val timeline = (0 until order.size).toMutableList()
        val block = timeline.subList(from, toExclusive).toList()
        repeat(n) { timeline.removeAt(from) }
        timeline.addAll(newFrom, block)
        val newIndexOf = IntArray(order.size)
        timeline.forEachIndexed { newIndex, old -> newIndexOf[old] = newIndex }
        return IntArray(order.size) { newIndexOf[order[it]] }
    }

    private fun shuffleRange(
        a: IntArray,
        from: Int,
        toExclusive: Int,
        random: Random,
    ) {
        for (i in toExclusive - 1 downTo from + 1) {
            val j = from + random.nextInt(i - from + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
    }

    // ------------------------------------------------------------------------------------------------ player reads

    /** The play order of [timeline] (shuffle order when [shuffle], else 0, 1, 2...). */
    fun playOrder(
        timeline: Timeline,
        shuffle: Boolean,
    ): IntArray {
        if (timeline.isEmpty) return IntArray(0)
        val out = IntArray(timeline.windowCount)
        var n = 0
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && n < out.size) {
            out[n++] = i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    fun playOrder(player: Player): IntArray = playOrder(player.currentTimeline, player.shuffleModeEnabled)

    /** How many songs come after [current] in the play order, counting at most [limit] (cheap on a long queue). */
    fun remainingAfter(
        timeline: Timeline,
        current: Int,
        shuffle: Boolean,
        limit: Int,
    ): Int {
        if (timeline.isEmpty || current !in 0 until timeline.windowCount) return 0
        if (!shuffle) return (timeline.windowCount - 1 - current).coerceAtMost(limit)
        var n = 0
        var i = current
        while (n < limit) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            if (i == C.INDEX_UNSET) break
            n++
        }
        return n
    }

    fun remainingAfter(
        player: Player,
        limit: Int,
    ): Int = remainingAfter(player.currentTimeline, player.currentMediaItemIndex, player.shuffleModeEnabled, limit)

    /** The songs before [current] in the play order: what has been heard in this pass. Timeline indexes. */
    fun playedBefore(
        timeline: Timeline,
        current: Int,
        shuffle: Boolean,
    ): List<Int> {
        if (timeline.isEmpty || current !in 0 until timeline.windowCount) return emptyList()
        if (!shuffle) return (0 until current).toList()
        val out = ArrayList<Int>()
        var i = current
        while (true) {
            i = timeline.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            if (i == C.INDEX_UNSET) break
            out += i
        }
        return out
    }

    /**
     * Timeline indexes to delete as runs `[from, toExclusive)`, highest first, so each removal leaves the indexes of the
     * next ones valid and a long list is removed in few calls.
     */
    fun removalRuns(indexes: Collection<Int>): List<IntRange> {
        if (indexes.isEmpty()) return emptyList()
        val sorted = indexes.toSortedSet().toList()
        val runs = ArrayList<IntRange>()
        var start = sorted[0]
        var end = sorted[0]
        for (k in 1 until sorted.size) {
            val v = sorted[k]
            if (v == end + 1) {
                end = v
            } else {
                runs += start..end
                start = v
                end = v
            }
        }
        runs += start..end
        return runs.asReversed()
    }
}
