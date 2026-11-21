/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.queues.ShuffleOrders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersistQueueWindowTest {
    @Test
    fun `a queue of up to 5000 songs is saved whole - a 731-song playlist is not cut to 501 any more`() {
        assertEquals(0..99, PersistQueueWindow.range(100, 40))
        assertEquals(0..0, PersistQueueWindow.range(1, 0))
        assertEquals(0..730, PersistQueueWindow.range(731, 0))
        assertEquals(0..730, PersistQueueWindow.range(731, 730))
        val plan = PersistQueueWindow.plan(731, 600, null)
        assertEquals(731, plan.kept.size)
        assertEquals(600, plan.currentIndex)
        assertNull(plan.order)
        assertEquals(0..4999, PersistQueueWindow.range(5000, 4999))
    }

    @Test
    fun `with shuffle on the play order is saved with the queue`() {
        val order = ShuffleOrders.fresh(731, 17, Random(1))
        val plan = PersistQueueWindow.plan(731, 17, order)
        assertEquals(17, plan.currentIndex)
        assertArrayEquals(order, plan.order)
    }

    @Test
    fun `a longer queue keeps the songs around the current one in PLAY order, mostly the coming ones`() {
        // no shuffle: 500 before, the rest after
        val plain = PersistQueueWindow.plan(8000, 3000, null)
        assertEquals(5000, plain.kept.size)
        assertEquals(2500, plain.kept.first())
        assertEquals(7499, plain.kept.last())
        assertEquals(3000, plain.kept[plain.currentIndex])
        // near the end: more of the heard ones fill the window
        val end = PersistQueueWindow.plan(8000, 7990, null)
        assertEquals(5000, end.kept.size)
        assertEquals(7999, end.kept.last())
        assertEquals(7990, end.kept[end.currentIndex])
        // shuffle: the window follows the play order, and the saved order is that part of it
        val size = 8000
        val order = ShuffleOrders.fresh(size, 42, Random(2))
        // pretend 1000 songs were heard: the current one is the 1001st of the order
        val current = order[1000]
        val plan = PersistQueueWindow.plan(size, current, order)
        assertEquals(5000, plan.kept.size)
        assertEquals(current, plan.kept[plan.currentIndex])
        val savedOrder = plan.order!!
        assertTrue(ShuffleOrders.isPermutation(savedOrder, 5000))
        val asTimeline = savedOrder.map { plan.kept[it] }
        assertEquals(order.toList().subList(500, 5500), asTimeline)
        // the song playing is the 501st of the saved order: 500 heard ones kept, 4499 to come
        assertEquals(500, asTimeline.indexOf(current))
    }

    @Test
    fun `rows that cannot be written are left out and the order still fits`() {
        val order = intArrayOf(3, 0, 4, 1, 2)
        val plan = PersistQueueWindow.plan(5, 4, order, keep = { it != 0 })
        assertArrayEquals(intArrayOf(1, 2, 3, 4), plan.kept)
        assertEquals(4, plan.kept[plan.currentIndex])
        assertEquals(listOf(3, 4, 1, 2), plan.order!!.map { plan.kept[it] })
        // the current row itself cannot be written: the next one in play order takes its place
        val skip = PersistQueueWindow.plan(5, 4, order, keep = { it != 4 })
        assertEquals(1, skip.kept[skip.currentIndex])
    }

    @Test
    fun `an empty queue, a bad index or a bad order never throws`() {
        assertTrue(PersistQueueWindow.range(0, 0).isEmpty())
        assertEquals(0..99, PersistQueueWindow.range(100, -1))
        assertEquals(0..99, PersistQueueWindow.range(100, 9999))
        val badOrder = PersistQueueWindow.plan(4, 1, intArrayOf(0, 0, 1, 2))
        assertNull(badOrder.order)
        assertEquals(4, badOrder.kept.size)
        assertEquals(0, PersistQueueWindow.plan(3, 0, null, keep = { false }).kept.size)
    }
}
