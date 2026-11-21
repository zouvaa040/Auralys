/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** The shuffle rules on plain arrays (no player): one pass = every song once, songs heard stay heard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ShuffleOrdersTest {
    private fun assertPermutation(
        order: IntArray,
        size: Int,
    ) = assertTrue("not a permutation of 0 until $size: ${order.toList().take(20)}", ShuffleOrders.isPermutation(order, size))

    @Test
    fun `a fresh pass has every song once and starts with the chosen one`() {
        for (size in listOf(1, 2, 3, 15, 731, 5000)) {
            val first = size / 3
            val order = ShuffleOrders.fresh(size, first, Random(size))
            assertPermutation(order, size)
            assertEquals(first, order[0])
        }
        assertEquals(0, ShuffleOrders.fresh(0, 0).size)
    }

    @Test
    fun `a fresh pass is random - the song after the first one is spread evenly`() {
        // 10 songs, song 0 first, 9 others: each should come second about 1/9 of the time
        val size = 10
        val runs = 18_000
        val counts = IntArray(size)
        val random = Random(42)
        repeat(runs) { counts[ShuffleOrders.fresh(size, 0, random)[1]]++ }
        assertEquals(0, counts[0])
        val expected = runs / 9.0
        for (song in 1 until size) {
            assertTrue("song $song came second ${counts[song]} times, expected about $expected", kotlin.math.abs(counts[song] - expected) < expected * 0.12)
        }
        // and every place of the tail: the old first song (the one swapped away) is not stuck at one place
        val lastCounts = IntArray(size)
        repeat(runs) { lastCounts[ShuffleOrders.fresh(size, 3, random)[size - 1]]++ }
        for (song in (0 until size).filter { it != 3 }) {
            assertTrue("song $song came last ${lastCounts[song]} times", kotlin.math.abs(lastCounts[song] - expected) < expected * 0.12)
        }
    }

    @Test
    fun `two passes of a big playlist are not the same order`() {
        val a = ShuffleOrders.fresh(731, 5, Random(1))
        val b = ShuffleOrders.fresh(731, 5, Random(2))
        assertFalse(a.contentEquals(b))
        // not "almost in order" either: few songs keep their list neighbour
        val inOrder = (1 until a.size).count { a[it] == a[it - 1] + 1 }
        assertTrue("$inOrder neighbours in list order", inOrder < 15)
    }

    @Test
    fun `list first keeps the list before the songs added later`() {
        val order = ShuffleOrders.freshListFirst(count = 20, first = 4, originalSize = 12, random = Random(3))
        assertPermutation(order, 20)
        assertEquals(4, order[0])
        assertTrue(order.drop(1).take(11).all { it < 12 })
        assertTrue(order.drop(12).all { it >= 12 })
        // the current song is one of the added ones
        val fromAdded = ShuffleOrders.freshListFirst(count = 20, first = 15, originalSize = 12, random = Random(4))
        assertPermutation(fromAdded, 20)
        assertEquals(15, fromAdded[0])
        assertTrue(fromAdded.drop(1).take(12).all { it < 12 })
    }

    @Test
    fun `the next pass keeps the playing song last and does not start where the last pass started`() {
        val random = Random(7)
        repeat(200) {
            val order = ShuffleOrders.fresh(30, it % 30, random)
            val current = order.last()
            val next = ShuffleOrders.nextPass(order, current, random)
            assertPermutation(next, 30)
            assertEquals(current, next.last())
            assertNotEquals(order[0], next[0])
        }
        // too short to reshuffle: unchanged
        assertArrayEquals(intArrayOf(1, 0), ShuffleOrders.nextPass(intArrayOf(1, 0), 0))
    }

    @Test
    fun `songs added to the queue go after every song still waiting`() {
        // play order 4 2 0 [3] 1, current 3; songs 5 and 6 arrive
        val order = intArrayOf(4, 2, 0, 3, 1, 5, 6)
        assertArrayEquals(intArrayOf(4, 2, 0, 3, 1, 5, 6), ShuffleOrders.appendAtEnd(intArrayOf(5, 4, 2, 6, 0, 3, 1), listOf(5, 6)))
        assertArrayEquals(order, ShuffleOrders.appendAtEnd(order, listOf(5, 6)))
    }

    @Test
    fun `play next puts the new songs right after the playing one`() {
        // timeline 0..6 after inserting 4 and 5 after the current one (3); they were placed anywhere
        val order = intArrayOf(5, 6, 2, 3, 0, 4, 1)
        assertArrayEquals(intArrayOf(6, 2, 3, 4, 5, 0, 1), ShuffleOrders.placeAfterCurrent(order, 3, listOf(4, 5)))
    }

    @Test
    fun `a new page of the same playlist joins the waiting songs only`() {
        val random = Random(11)
        val played = listOf(9, 3, 7)
        val current = 0
        val waiting = listOf(5, 1, 8, 2, 4, 6)
        val fresh = (10 until 30).toList()
        val order = (played + current + waiting + fresh).toIntArray()
        val mixed = ShuffleOrders.mixIntoUpcoming(order, current, fresh, keepNext = true, random = random)
        assertPermutation(mixed, 30)
        assertEquals(played + current, mixed.take(4))
        assertEquals("the next song stays next", 5, mixed[4])
        val tail = mixed.drop(5)
        assertEquals((waiting.drop(1) + fresh).toSet(), tail.toSet())
        // the new songs are spread among the waiting ones, not all put after them
        assertNotEquals(setOf(1, 8, 2, 4, 6), tail.take(5).toSet())
        assertTrue("a new song comes before the last waiting one", tail.indexOfFirst { it >= 10 } < tail.indexOfLast { it < 10 })
    }

    @Test
    fun `insert, remove and move keep the play order of the other songs`() {
        val order = intArrayOf(2, 0, 3, 1)
        // two songs inserted at timeline index 1: old 1, 2, 3 become 3, 4, 5; new 1, 2 at the end
        assertArrayEquals(intArrayOf(4, 0, 5, 3, 1, 2), ShuffleOrders.insertAtEnd(order, 1, 2))
        // remove timeline 1 until 3 (songs 1 and 2): 0 and 3 stay, 3 becomes 1
        assertArrayEquals(intArrayOf(0, 1), ShuffleOrders.remove(order, 1, 3))
        // move timeline song 0 to the end: [1, 2, 3, 0] -> old 0 is now 3, old 1 -> 0, old 2 -> 1, old 3 -> 2
        assertArrayEquals(intArrayOf(1, 3, 2, 0), ShuffleOrders.move(order, 0, 1, 3))
        // the same songs (by old index) in the same play order
        val moved = ShuffleOrders.move(order, 0, 1, 3)
        val oldOf = intArrayOf(1, 2, 3, 0) // new index -> old index
        assertArrayEquals(order, IntArray(moved.size) { oldOf[moved[it]] })
    }

    @Test
    fun `removal runs go from the highest index down and merge neighbours`() {
        assertEquals(listOf(9..9, 4..6, 0..1), ShuffleOrders.removalRuns(listOf(5, 0, 9, 1, 4, 6)))
        assertTrue(ShuffleOrders.removalRuns(emptyList()).isEmpty())
    }

    @Test
    fun `QueueShuffleOrder walks the order and appends inserted songs at the end`() {
        val so = QueueShuffleOrder(intArrayOf(2, 0, 3, 1))
        assertEquals(2, so.firstIndex)
        assertEquals(1, so.lastIndex)
        assertEquals(3, so.getNextIndex(0))
        assertEquals(C.INDEX_UNSET, so.getNextIndex(1))
        assertEquals(C.INDEX_UNSET, so.getPreviousIndex(2))
        val inserted = so.cloneAndInsert(4, 2) as QueueShuffleOrder
        assertArrayEquals(intArrayOf(2, 0, 3, 1, 4, 5), inserted.toArray())
        assertEquals(0, so.cloneAndClear().length)
        // setMediaItems (clear, then insert all): a random order of the new list, like ExoPlayer's own
        val fresh = (so.cloneAndClear().cloneAndInsert(0, 300) as QueueShuffleOrder).toArray()
        assertTrue(ShuffleOrders.isPermutation(fresh, 300))
        assertFalse(fresh.contentEquals(IntArray(300) { it }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `QueueShuffleOrder refuses an order that is not a permutation`() {
        QueueShuffleOrder(intArrayOf(0, 0, 1))
    }

    @Test
    fun `the Acak buttons start at a random song of the whole list`() {
        val items = (0 until 731).map { MediaItem.Builder().setMediaId("s$it").build() }
        val random = Random(5)
        val starts = (0 until 400).map { ListQueue.shuffled("t", items, random).startIndex }
        assertTrue(starts.all { it in 0 until 731 })
        assertTrue("starts: ${starts.toSet().size} different", starts.toSet().size > 250)
        // songs of the bottom half start too, not only the top part
        assertTrue(starts.count { it >= 365 } > 120)
        val q = ListQueue.shuffled("t", items, Random(1))
        assertEquals(true, q.shuffle)
        assertEquals(items, q.items)
        assertEquals(0, ListQueue.shuffled("t", emptyList()).startIndex)
        assertEquals(null, ListQueue(items = items).shuffle)
    }
}
