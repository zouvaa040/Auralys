/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * How far the current page has scrolled, so a page's top bar can fade a tint in as content slides under it (instead of
 * jumping to the M3 `surface` colour, which is white in a light theme). Idea after BitChord's top fade (GPL-3.0): the
 * bar itself stays transparent and only a colour fade, driven by the scroll position, appears behind it.
 *
 * One tracker sits on the NavHost. Every list on every page reports its consumed scroll to it through nested scroll, so
 * pages need no code. The offset is remembered per back-stack entry, so coming back to a scrolled page keeps its tint.
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** 0 while the page is at the top, 1 once content has slid [rampPx] under the bar (after [startPx] of free scroll). */
fun topBarFade(offsetPx: Float, startPx: Float, rampPx: Float): Float {
    if (rampPx <= 0f) return if (offsetPx > startPx) 1f else 0f
    return ((offsetPx - startPx) / rampPx).coerceIn(0f, 1f)
}

/**
 * New scrolled distance after a scroll step. [consumedY] is negative when the content moves up (the user reads on),
 * [availableY] > 0 with nothing consumed means the user pulled down at the very top, which resets the distance.
 */
fun nextScrollOffset(current: Float, consumedY: Float, availableY: Float): Float {
    if (consumedY == 0f && availableY > 0f) return 0f
    return (current - consumedY).coerceAtLeast(0f)
}

@Stable
class TopBarScrollState {
    /** Read only inside graphicsLayer / draw blocks, so scrolling never recomposes anything. */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    private var currentKey: String? = null
    private val saved = HashMap<String, Float>()

    val connection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (consumed.y != 0f || available.y > 0f) {
                    offsetPx = nextScrollOffset(offsetPx, consumed.y, available.y)
                }
                return Offset.Zero
            }
        }

    /**
     * Flat list for saved instance state: [current key or ""], [current offset], then key and offset of every parked
     * page. Only strings and floats, so it always fits in a Bundle.
     */
    fun toSaved(): ArrayList<Any> {
        val out = ArrayList<Any>(2 + saved.size * 2)
        out.add(currentKey.orEmpty())
        out.add(offsetPx)
        saved.forEach { (key, offset) ->
            out.add(key)
            out.add(offset)
        }
        return out
    }

    fun restoreFrom(list: List<Any?>) {
        currentKey = (list.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() }
        offsetPx = (list.getOrNull(1) as? Float) ?: 0f
        var i = 2
        while (i + 1 < list.size) {
            val key = list[i] as? String
            val offset = list[i + 1] as? Float
            if (key != null && offset != null) saved[key] = offset
            i += 2
        }
    }

    /** Called when the visible back-stack entry changes: park the old page's offset, load the new page's. */
    fun switchTo(key: String?) {
        if (key == currentKey) return
        currentKey?.let { saved[it] = offsetPx }
        currentKey = key
        offsetPx = key?.let { saved[it] } ?: 0f
        if (saved.size > 64) saved.clear()
    }
}

/**
 * Survives a recreated Activity (rotation, theme change, process death): the pages' lists come back scrolled, so the
 * tracker must come back with the same offsets or the Glass scrim stays at 0 under a scrolled list.
 */
val TopBarScrollStateSaver: Saver<TopBarScrollState, Any> =
    Saver(
        save = { it.toSaved() },
        restore = { value ->
            @Suppress("UNCHECKED_CAST")
            TopBarScrollState().also { it.restoreFrom(value as List<Any?>) }
        },
    )

val LocalTopBarScroll = staticCompositionLocalOf<TopBarScrollState?> { null }
