/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class WidgetRenderQueueTest {
    /** Keeps the submitted work until [runAll] is called, like a busy worker thread. */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun `requests that arrive before the worker runs become one redraw with all ids`() {
        val executor = ManualExecutor()
        val queue = WidgetRenderQueue(executor)
        val drawn = mutableListOf<List<Int>?>()
        var finished = 0
        val render: (IntArray?) -> Unit = { drawn += it?.sorted() }

        queue.request(intArrayOf(1), render) { finished++ }
        queue.request(intArrayOf(2), render) { finished++ }
        queue.request(intArrayOf(1), render) { finished++ }
        assertEquals(1, executor.tasks.size)

        executor.runAll()

        assertEquals(listOf<List<Int>?>(listOf(1, 2)), drawn)
        assertEquals(3, finished)
    }

    @Test
    fun `a request for every widget wins over single ids`() {
        val executor = ManualExecutor()
        val queue = WidgetRenderQueue(executor)
        val drawn = mutableListOf<List<Int>?>()
        val render: (IntArray?) -> Unit = { drawn += it?.sorted() }
        queue.request(intArrayOf(5), render) {}
        queue.request(null, render) {}
        executor.runAll()
        assertEquals(1, drawn.size)
        assertNull(drawn[0])
    }

    @Test
    fun `a request during a redraw is drawn after it and never at the same time`() {
        val executor = ManualExecutor()
        val queue = WidgetRenderQueue(executor)
        var active = 0
        var maxActive = 0
        val drawn = mutableListOf<List<Int>?>()
        lateinit var render: (IntArray?) -> Unit
        render = { ids ->
            active++
            maxActive = maxOf(maxActive, active)
            drawn += ids?.sorted()
            // while the first redraw is busy another event arrives
            if (drawn.size == 1) queue.request(intArrayOf(9), render) {}
            active--
        }
        queue.request(intArrayOf(1), render) {}
        executor.runAll()
        assertEquals(listOf<List<Int>?>(listOf(1), listOf(9)), drawn)
        assertEquals(1, maxActive)
    }

    @Test
    fun `a failing redraw still finishes its callers and the queue keeps working`() {
        val executor = ManualExecutor()
        val queue = WidgetRenderQueue(executor)
        var finished = 0
        queue.request(intArrayOf(1), { error("boom") }) { finished++ }
        executor.runAll()
        assertEquals(1, finished)
        var drawn = false
        queue.request(intArrayOf(1), { drawn = true }) { finished++ }
        executor.runAll()
        assertTrue(drawn)
        assertEquals(2, finished)
    }
}
