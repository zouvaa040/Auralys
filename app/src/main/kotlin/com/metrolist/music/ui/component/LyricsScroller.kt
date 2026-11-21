/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * How the rows of the lyrics view are placed and how they glide to the line being sung.
 *
 * All rows are measured in one layout pass and stacked by their measured heights, so a row is never placed from a
 * guessed height (the overlap fixed in 4.3.4 cannot come back) and never hidden while it waits for a measurement.
 *
 * When the line being sung changes, the content keeps its place on screen and glides from there along the curve of a
 * critically damped spring, h(t) = (1 + wt) e^-wt: no overshoot, settled in about 0.75 s. Each line change is one glide;
 * glides that overlap (a new line before the last glide ended) are added up, so the content never stops and never
 * restarts from a standstill. The wave of the old view is kept: rows behind the anchor in the direction of travel start
 * their part of a glide a little later (20 ms per row, at most 200 ms), and while one glide waits for them the others go
 * on, so a row is never held still in the middle of its motion and the gaps between rows only ever open.
 * Before (4.3.4) every row ran its own 750 ms tween with a delay of up to 200 ms: a line change during a glide restarted
 * all of them from a standstill (the rows stopped and waited, then eased in again), and a list of 300 lines ran 300
 * animations at every line change.
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object LyricsFollowMath {
    /** Stiffness of the glide as an angular frequency (rad/s): half way after about 0.19 s, settled after about 0.75 s. */
    const val OMEGA = 9f

    /** A row behind the anchor starts its part of a glide this much later per row height, up to [DELAY_MAX_ROWS] rows. */
    const val DELAY_PER_ROW_S = 0.02f
    const val DELAY_MAX_ROWS = 10f
    const val DELAY_MAX_S = DELAY_PER_ROW_S * DELAY_MAX_ROWS

    /** Below this (px) a glide is over. */
    const val REST_PX = 0.5f

    /** How much of a glide is still to go [t] s after it started: 1 at the start, falling to 0 without overshoot. */
    fun remaining(t: Float): Float {
        if (t <= 0f) return 1f
        val wt = OMEGA * t
        return (1f + wt) * exp(-wt)
    }

    /**
     * How long (s) the row whose top is [rowTop] waits before it follows a glide of [delta] px that started when the
     * anchor row's top was [anchorTop]: rows behind the anchor in the direction of travel wait 20 ms per typical row
     * ([rowPx]); the anchor and the rows ahead of it start at once.
     */
    fun delayFor(rowTop: Float, anchorTop: Float, delta: Float, rowPx: Float): Float {
        if (rowPx <= 0f || delta == 0f) return 0f
        val behind = if (delta > 0f) rowTop - anchorTop else anchorTop - rowTop
        return DELAY_PER_ROW_S * (behind / rowPx).coerceIn(0f, DELAY_MAX_ROWS)
    }

    /** The part of a glide of [delta] px that a row with [delay] s still has to go [elapsed] s after the glide began. */
    fun glideOffset(delta: Float, elapsed: Float, delay: Float): Float = delta * remaining(elapsed - delay)

    /** Whether a glide of [delta] px is over for every row, [elapsed] s after it began. */
    fun glideOver(delta: Float, elapsed: Float): Boolean = abs(delta) * remaining(elapsed - DELAY_MAX_S) < REST_PX

    /** The top of every row in the stack (px from the top of row 0) from the measured [heights] and the [gaps] before each row. */
    fun stack(heights: IntArray, gaps: FloatArray): FloatArray {
        val top = FloatArray(heights.size)
        var y = 0f
        for (i in heights.indices) {
            if (i > 0) y += heights[i - 1] + gaps[i]
            top[i] = y
        }
        return top
    }
}

/**
 * The glides of the lyrics content. The layout adds one when the anchor row changes ([shift]); one frame loop
 * ([runGlide]) moves the clock while any glide is on and drops those that are over. Only the placement reads them.
 */
@Stable
internal class LyricsFollow {
    private class Glide(val delta: Float, val anchor: Int) {
        // the frame the glide begins on; until that frame comes the glide stands at its start (no frame shows a jump)
        var startNanos = -1L
    }

    private val glides = ArrayList<Glide>(4)
    private val frameNanos = mutableLongStateOf(0L)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many glides were started (for the measurements). */
    var starts = 0
        private set

    /** True while a glide is on. */
    val moving: Boolean get() = glides.isNotEmpty()

    /** The rows moved by [delta] px under the view (anchor row [anchor]): the content stays on screen and glides from there. */
    fun shift(delta: Float, anchor: Int) {
        if (delta == 0f || !delta.isFinite()) return
        glides += Glide(delta, anchor)
        starts++
        wake.trySend(Unit)
    }

    /** At rest at once (new lyrics, motion off). */
    fun stop() {
        glides.clear()
    }

    /**
     * How far (px) the row whose top is [rowTop] still is from its resting place. [tops] are the tops of all rows (for
     * the anchor of each glide), [rowPx] a typical row height. Read in the placement: it follows the frame clock.
     */
    fun offsetOf(rowTop: Float, tops: FloatArray, rowPx: Float): Float {
        val now = frameNanos.longValue
        var sum = 0f
        // an index loop: this runs for every row on every frame of a glide (no iterator each time)
        for (k in 0 until glides.size) {
            val g = glides[k]
            val anchorTop = tops.getOrElse(g.anchor) { 0f }
            val elapsed = if (g.startNanos < 0L) 0f else (now - g.startNanos) / 1_000_000_000f
            sum += LyricsFollowMath.glideOffset(g.delta, elapsed, LyricsFollowMath.delayFor(rowTop, anchorTop, g.delta, rowPx))
        }
        return if (sum.isFinite()) sum else 0f
    }

    /** Runs the clock of the glides, frame by frame, while there is one; sleeps otherwise. With [animate] off they end at once. */
    suspend fun runGlide(animate: () -> Boolean) {
        while (true) {
            if (glides.isEmpty()) wake.receive()
            while (glides.isNotEmpty()) {
                withFrameNanos { now ->
                    if (!animate()) {
                        glides.clear()
                    } else {
                        for (g in glides) if (g.startNanos < 0L) g.startNanos = now
                        glides.removeAll { LyricsFollowMath.glideOver(it.delta, (now - it.startNanos) / 1_000_000_000f) }
                    }
                    frameNanos.longValue = now
                }
            }
        }
    }
}

/** What the last layout of the rows knows, for the drag handler and for the next layout. Plain fields: nothing recomposes. */
internal class LyricsRowsState {
    /** How far the user may drag the content (px), from the measured heights. */
    var dragMin = 0f
    var dragMax = 0f

    /** For the measurements: rows placed in the last placement, measure and placement passes, and (when the test hands
     * an array) the top of every row after each placement. */
    var placedRows = 0
    var measures = 0
    var placements = 0
    var probeY: FloatArray? = null

    internal var anchorKey = Long.MIN_VALUE
    internal var anchorIndex = -1
}

/** Told the top (px) and height of every row after each placement (lyrics without timing). Primitive, so nothing is boxed. */
internal fun interface LyricsRowPlaced {
    fun placed(index: Int, y: Float, height: Int, areaHeight: Int)
}

/** A key that stays the same for the same line in a list made again from the same lyrics (same index, same time). */
internal fun lyricsRowKey(item: LyricsListItem): Long =
    when (item) {
        is LyricsListItem.Line -> (item.index.toLong() shl 42) xor (item.entry.time and 0x3FFFFFFFFFFL)
        is LyricsListItem.Indicator -> ((item.afterLineIndex.toLong() shl 42) xor (item.gapStartMs and 0x3FFFFFFFFFFL)).inv()
    }

private val ROW_GAP = LyricsMotion.ROW_GAP_DP.dp
private val TYPICAL_ROW = 68.dp
private val CHIP_ABOVE = 32.dp
private val KEEP_BELOW = 100.dp
private val KEEP_ABOVE = 150.dp

/**
 * The rows of the lyrics view, stacked by their measured heights; the anchor row's top sits at [LyricsMotion.ANCHOR_RATIO]
 * of the height. [chip] (the "Lyrics from X" chip, or nothing) sits above the first row. Rows outside the view (plus a
 * quarter of its height) are not placed, so they are not drawn. Only the placement reads the anchor, the glide and the
 * user's drag: a glide or a drag never recomposes or measures a row.
 *
 * @param keys [lyricsRowKey] of every row, to keep the anchor's place when the list is made again from the same lyrics
 * @param noGapBefore true for rows that sit right under the row before them (background lines, gap dots)
 * @param glide false when an anchor change must be shown at once (auto scroll off, motion off)
 * @param onPlaced for lyrics without timing: every row's top and height after each placement
 */
@Composable
internal fun LyricsRowsLayout(
    keys: LongArray,
    anchorIndex: () -> Int,
    noGapBefore: (Int) -> Boolean,
    follow: LyricsFollow,
    manualOffset: () -> Float,
    glide: () -> Boolean,
    state: LyricsRowsState,
    modifier: Modifier = Modifier,
    onPlaced: LyricsRowPlaced? = null,
    chip: @Composable () -> Unit,
    rows: @Composable () -> Unit,
) {
    Layout(contents = listOf(chip, rows), modifier = modifier) { (chipMeasurables, rowMeasurables), constraints ->
        state.measures++
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val rowConstraints = Constraints(maxWidth = width)
        val n = rowMeasurables.size
        val placeables = ArrayList<Placeable>(n)
        val heights = IntArray(n)
        val gaps = FloatArray(n)
        val gapPx = ROW_GAP.toPx()
        for (i in 0 until n) {
            val p = rowMeasurables[i].measure(rowConstraints)
            placeables += p
            heights[i] = p.height
            gaps[i] = if (i == 0 || noGapBefore(i)) 0f else gapPx
        }
        val top = LyricsFollowMath.stack(heights, gaps)
        val chipPlaceable = chipMeasurables.firstOrNull()?.measure(Constraints(maxWidth = width))
        val rowPx = TYPICAL_ROW.toPx()
        val chipAbove = CHIP_ABOVE.toPx()
        val keepBelow = KEEP_BELOW.toPx()
        val keepAbove = KEEP_ABOVE.toPx()

        layout(width, height) {
            state.placements++
            if (n == 0) {
                state.placedRows = 0
                return@layout
            }
            val a = anchorIndex().coerceIn(0, n - 1)
            val key = keys.getOrElse(a) { Long.MIN_VALUE }
            if (key != state.anchorKey) {
                val old = state.anchorKey
                val prev =
                    when {
                        old == Long.MIN_VALUE -> -1
                        state.anchorIndex in 0 until n && keys.getOrNull(state.anchorIndex) == old -> state.anchorIndex
                        else -> keys.indexOf(old)
                    }
                // the content keeps its place on screen and glides from there; new lyrics or no motion: in place at once
                if (prev >= 0 && glide()) follow.shift(top[a] - top[prev], a) else follow.stop()
                state.anchorKey = key
                state.anchorIndex = a
            }
            val anchorY = height * LyricsMotion.ANCHOR_RATIO
            val base = anchorY - top[a]
            val below = keepBelow - anchorY - (top[n - 1] - top[a]) - heights[n - 1]
            val above = height - keepAbove - anchorY + top[a]
            state.dragMin = min(below, above)
            state.dragMax = max(below, above)
            // a drag offset that is not a number (a broken fling) must not reach roundToInt
            val manual = manualOffset().let { if (it.isFinite()) it else 0f }.coerceIn(state.dragMin, state.dragMax)
            val margin = height * 0.25f
            var placed = 0
            for (i in 0 until n) {
                val y = base + top[i] + follow.offsetOf(top[i], top, rowPx) + manual
                val p = placeables[i]
                if (y + p.height > -margin && y < height + margin) {
                    p.place(0, y.roundToInt())
                    placed++
                }
                onPlaced?.placed(i, y, p.height, height)
                state.probeY?.let { if (i < it.size) it[i] = y }
            }
            state.placedRows = placed
            if (chipPlaceable != null) {
                val y = base + top[0] + follow.offsetOf(top[0], top, rowPx) + manual - chipAbove
                if (y + chipPlaceable.height > -margin && y < height + margin) chipPlaceable.place(0, y.roundToInt())
            }
        }
    }
}
