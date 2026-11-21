/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import java.util.concurrent.Executor

/**
 * Runs widget redraws one at a time. While a widget is resized the launcher sends many "options changed" events; each
 * one used to start its own thread that drew the glass background, so several heavy bitmaps were alive at once. Here the
 * requests that arrive while a redraw runs are merged into one more redraw (the union of their widget ids; null means
 * every widget) and every caller's [request] `done` callback runs after the redraw that covered it.
 */
internal class WidgetRenderQueue(
    private val executor: Executor,
) {
    private val lock = Any()
    private var pendingAll = false
    private val pendingIds = HashSet<Int>()
    private val pendingDone = ArrayList<() -> Unit>()
    private var latestRender: ((IntArray?) -> Unit)? = null
    private var running = false

    fun request(
        ids: IntArray?,
        render: (IntArray?) -> Unit,
        done: () -> Unit,
    ) {
        val start =
            synchronized(lock) {
                if (ids == null) pendingAll = true else pendingIds += ids.toList()
                pendingDone += done
                latestRender = render
                (!running).also { if (it) running = true }
            }
        if (start) executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val ids: IntArray?
            val dones: List<() -> Unit>
            val render: ((IntArray?) -> Unit)?
            synchronized(lock) {
                if (pendingDone.isEmpty()) {
                    running = false
                    return
                }
                ids = if (pendingAll) null else pendingIds.toIntArray()
                dones = pendingDone.toList()
                render = latestRender
                pendingAll = false
                pendingIds.clear()
                pendingDone.clear()
            }
            try {
                render?.invoke(ids)
            } catch (e: Throwable) {
                timber.log.Timber.tag("AuralysWidget").w(e, "Widget refresh failed")
            } finally {
                dones.forEach { runCatching(it) }
            }
        }
    }
}
