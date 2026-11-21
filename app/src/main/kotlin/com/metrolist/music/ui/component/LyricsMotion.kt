/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The numbers and the rules behind the motion of the lyrics view, as plain functions with no Compose state, so they can
 * be unit tested. They follow the lyric view of the Windows app: the line being sung is full ink and a little bigger, the
 * lines around it step back (smaller, fainter, a little blurred), the sung part of the line sweeps left to right, an
 * instrumental gap is three dots that fill up, and the active line sits about a third from the top.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lyrics are drawn at the left edge unless the user chose another position (Windows look; also Apple Music and Spotify on phones). */
internal val DEFAULT_LYRICS_POSITION = LyricsPosition.LEFT

/**
 * The vertical place of every row of the lyrics view, relative to the top of the anchor (active) row.
 *
 * Rows are laid out by hand from their measured heights. A row that has not been measured yet gets a guessed height, and
 * every row whose place depends on a guess is marked inexact. The view does not draw an inexact row and never animates
 * a row from an inexact place: before this, the first frame put rows 68 dp apart while a wrapped line is twice as tall,
 * and the rows then slid for 750 ms from those overlapping places to the real ones (two lines drawn on top of each
 * other while the view settles, user video 2026-11).
 */
internal object LyricsRowPlacement {
    class Placement(val positions: Map<Int, Float>, private val exact: BooleanArray) {
        fun isExact(index: Int): Boolean = exact.getOrElse(index) { false }
    }

    /**
     * [measured] gives the measured height of a row or null, [guess] the height to assume for a row not measured yet,
     * [gapBefore] the space between row i - 1 and row i (decided by row i: none before a background line or a gap
     * indicator). The same rule is used above and below the anchor, so a row does not jump when the anchor passes it.
     */
    fun compute(
        count: Int,
        anchor: Int,
        measured: (Int) -> Int?,
        guess: (Int) -> Float,
        gapBefore: (Int) -> Float,
    ): Placement {
        val map = HashMap<Int, Float>()
        val exact = BooleanArray(count.coerceAtLeast(0))
        if (count <= 0 || anchor !in 0 until count) return Placement(map, exact)
        map[anchor] = 0f
        exact[anchor] = true
        var y = 0f
        var ok = true
        for (i in anchor - 1 downTo 0) {
            val h = measured(i)
            if (h == null) ok = false
            y -= (h?.toFloat() ?: guess(i)) + gapBefore(i + 1)
            map[i] = y
            exact[i] = ok
        }
        y = 0f
        ok = true
        for (i in anchor until count - 1) {
            val h = measured(i)
            if (h == null) ok = false
            y += (h?.toFloat() ?: guess(i)) + gapBefore(i + 1)
            map[i + 1] = y
            exact[i + 1] = ok
        }
        return Placement(map, exact)
    }
}

internal object LyricsMotion {
    // ---------------------------------------------------------------- depth of a line

    /** Inactive lines are a little smaller than the one being sung. */
    const val INACTIVE_SCALE = 0.94f
    private const val ALPHA_START = 0.56f
    private const val ALPHA_STEP = 0.09f
    private const val ALPHA_FLOOR = 0.18f

    /** Lines when the user scrolls by hand or picks lines to share: readable, no distance dimming. */
    const val RELAXED_ALPHA = 0.50f

    /** Blur grows by this much per row away from the active one, up to [BLUR_MAX_DISTANCE] rows. */
    const val BLUR_STEP_DP = 0.8f
    const val BLUR_MAX_DISTANCE = 4

    /** A tap squeezes the line a little. */
    const val PRESS_SCALE = 0.985f

    /** Opacity of a line [distance] rows from the anchor row. The active line is full; the others fall off and stop at a floor. */
    fun lineAlpha(distance: Int, active: Boolean): Float =
        if (active) 1f else (ALPHA_START - ALPHA_STEP * max(distance, 1)).coerceAtLeast(ALPHA_FLOOR)

    fun lineScale(active: Boolean): Float = if (active) 1f else INACTIVE_SCALE

    /** Blur radius in dp for a row [distance] rows away; none for the active row and none beyond [BLUR_MAX_DISTANCE] rows. */
    fun lineBlurDp(distance: Int, active: Boolean): Float =
        if (active || distance < 1 || distance > BLUR_MAX_DISTANCE) 0f else BLUR_STEP_DP * distance

    /**
     * Whether blur may be used at all. It needs RenderEffect (API 31), and it is switched off on low-RAM phones, in
     * battery saver, when the system animation scale is 0 and when the user turned it off.
     */
    fun blurAllowed(sdk: Int, lowRam: Boolean, powerSave: Boolean, animationScale: Float, userSwitch: Boolean): Boolean =
        sdk >= 31 && !lowRam && !powerSave && animationScale > 0f && userSwitch

    /** Blur radius for one row: only rows near the active one and on screen, and only when blur is allowed. */
    fun targetBlurDp(allowed: Boolean, distance: Int, active: Boolean, onScreen: Boolean, relaxed: Boolean): Float =
        if (!allowed || !onScreen || relaxed) 0f else lineBlurDp(distance, active)

    /** True while the row (top [y], height [height]) overlaps the lyric area of height [areaHeight], widened by [margin] at both ends so a row is ready just before it scrolls in. */
    fun onScreen(y: Float, height: Float, areaHeight: Float, margin: Float = 0f): Boolean = y + height > -margin && y < areaHeight + margin

    /** The system animation scale as a setting value: 0 means the user wants no motion. */
    fun animationsEnabled(animationScale: Float): Boolean = animationScale > 0f

    // ---------------------------------------------------------------- lyrics without timing

    /** 0 in the middle of the view, 1 and 2 towards the edges. Stepped, so a row only redraws when it crosses a step. */
    fun edgeStep(centerY: Float, areaHeight: Float): Int {
        if (areaHeight <= 0f) return 0
        val away = abs(centerY - areaHeight / 2f) / areaHeight
        return if (away < 0.28f) 0 else if (away < 0.38f) 1 else 2
    }

    fun edgeAlpha(step: Int): Float = 0.94f - 0.16f * step.coerceIn(0, 2)

    fun edgeBlurDp(step: Int): Float = 0.7f * step.coerceIn(0, 2)

    /** The staggered entrance of untimed lyrics: row [index] waits this long (the first 14 rows are staggered). */
    fun enterDelayMs(index: Int): Long = min(max(index, 0), 14) * 40L

    // ---------------------------------------------------------------- the sweep

    /** The sweep of a line with no word timing ends when the next line starts, or after a time that grows with its length. */
    fun lineSweepEndMs(startMs: Long, nextStartMs: Long?, textLength: Int): Long {
        val next = nextStartMs ?: (startMs + 4000L)
        return min(next, startMs + max(1600L, textLength.coerceAtLeast(0) * 95L))
    }

    /** How much of the line is sung at [positionMs]: 0..1, linear between [startMs] and [endMs]. */
    fun lineSweepFraction(positionMs: Long, startMs: Long, endMs: Long): Float =
        ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    /** The char range [start, end) of every word in [text], in reading order, as pairs in one array; null if a word is not in the text. */
    fun wordSpans(text: String, words: List<String>): IntArray? {
        val spans = IntArray(words.size * 2)
        var cursor = 0
        for ((i, word) in words.withIndex()) {
            val at = if (word.isEmpty()) cursor else text.indexOf(word, cursor)
            if (at < 0) return null
            spans[2 * i] = at
            spans[2 * i + 1] = at + word.length
            cursor = at + word.length
        }
        return spans
    }

    /**
     * True when the line has a word held over several syllables ("Oh-oh-oh", "Hel-lo" timed per syllable): the word
     * machinery keeps drawing such lines letter by letter, with the growing and swaying of the held syllables. The rule is
     * the one of the letter-by-letter drawing: a word with a hyphen inside and no space after it is cut after each
     * hyphen, and a run of pieces that end in a hyphen is a held word.
     */
    fun hasHyphenGroup(texts: List<String>, trailingSpace: List<Boolean>): Boolean {
        var run = 0
        for ((i, word) in texts.withIndex()) {
            val split = word.contains('-') && word.length > 1 && (!trailingSpace.getOrElse(i) { true } || texts.size == 1)
            val pieces = if (split) word.split('-').let { parts -> parts.mapIndexedNotNull { k, part -> if (k < parts.size - 1) "$part-" else part.ifEmpty { null } } } else listOf(word)
            for (piece in pieces) {
                run++
                if (!piece.endsWith("-")) {
                    if (run > 1) return true
                    run = 0
                }
            }
        }
        return false
    }

    /** One word with a made-up timing, for a line that has none. */
    class TimedWord(val text: String, val startMs: Long, val endMs: Long, val trailingSpace: Boolean)

    /** Splits a line at its spaces and spreads the words over [startMs, endMs] in proportion to their length. */
    fun spreadWords(text: String, startMs: Long, endMs: Long): List<TimedWord> {
        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val total = parts.sumOf { it.length + 1 }.toLong()
        val span = (endMs - startMs).coerceAtLeast(1L)
        var before = 0L
        return parts.mapIndexed { i, word ->
            val from = startMs + span * before / total
            before += word.length + 1
            val to = startMs + span * min(before, total) / total
            TimedWord(word, from, max(to, from), i < parts.size - 1)
        }
    }

    /**
     * Time to place on the line, as a piecewise linear curve through (start of word, left edge of word) and (end of word,
     * right edge of word), so the edge of the sweep rests on the word being sung and creeps over the space between words.
     * Positions are fractions of the width of the whole text (0..1) in reading order.
     */
    class SweepCurve(times: LongArray, fractions: FloatArray) {
        private val t = times.copyOf()
        private val f = fractions.copyOf()

        init {
            require(t.size == f.size && t.isNotEmpty())
            for (i in 1 until t.size) {
                if (t[i] < t[i - 1]) t[i] = t[i - 1]
                if (f[i] < f[i - 1]) f[i] = f[i - 1]
            }
        }

        fun fraction(positionMs: Long): Float {
            if (positionMs < t[0]) return f[0]
            var lo = 0
            var hi = t.size - 1
            while (lo < hi) { // the last point at or before the position
                val mid = (lo + hi + 1) ushr 1
                if (t[mid] <= positionMs) lo = mid else hi = mid - 1
            }
            if (lo == t.size - 1) return f[lo]
            val span = (t[lo + 1] - t[lo]).coerceAtLeast(1L)
            return f[lo] + (f[lo + 1] - f[lo]) * ((positionMs - t[lo]).toFloat() / span)
        }
    }

    /** The curve of a line from its words: times in ms, and where each word starts and ends as a fraction of the text width. */
    fun sweepCurve(starts: LongArray, ends: LongArray, fromFraction: FloatArray, toFraction: FloatArray): SweepCurve? {
        val n = starts.size
        if (n == 0 || ends.size != n || fromFraction.size != n || toFraction.size != n) return null
        val times = LongArray(n * 2)
        val fractions = FloatArray(n * 2)
        for (i in 0 until n) {
            times[2 * i] = starts[i]
            times[2 * i + 1] = max(ends[i], starts[i])
            fractions[2 * i] = fromFraction[i].coerceIn(0f, 1f)
            fractions[2 * i + 1] = toFraction[i].coerceIn(0f, 1f)
        }
        return SweepCurve(times, fractions)
    }

    /**
     * Where the soft edge of the sweep is, on the wrapped line of [widths] (px of each visual line, left to right, top to
     * bottom). The edge travels [edge] px beyond the end of every line so the last glyph is fully sung when the sweep ends.
     * Returns the visual line index and the distance of the edge from that line's left side.
     */
    fun sweepEdge(fraction: Float, widths: FloatArray, edge: Float): SweepEdge {
        if (widths.isEmpty()) return SweepEdge(0, 0f)
        var total = 0f
        for (w in widths) total += max(w, 0f) + edge
        var remaining = fraction.coerceIn(0f, 1f) * total
        for (i in widths.indices) {
            val span = max(widths[i], 0f) + edge
            if (remaining <= span || i == widths.lastIndex) return SweepEdge(i, min(remaining, span))
            remaining -= span
        }
        return SweepEdge(widths.lastIndex, max(widths.last(), 0f) + edge)
    }

    class SweepEdge(val line: Int, val offset: Float)

    /** The inverse of [sweepEdge]: the fraction at which the edge is [offsetPx] px from the left side of visual line [line]. */
    fun fractionAt(line: Int, offsetPx: Float, widths: FloatArray, edge: Float): Float {
        if (widths.isEmpty()) return 0f
        var total = 0f
        var before = 0f
        for ((i, w) in widths.withIndex()) {
            val span = max(w, 0f) + edge
            if (i < line) before += span
            total += span
        }
        if (total <= 0f) return 0f
        return ((before + offsetPx.coerceAtLeast(0f)) / total).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- the dots of an instrumental gap

    /** Where the dots of a gap sit in the row (0 start, 0.5 middle, 1 end): where the next line is sung from, or where the user put the text. */
    fun horizontalFor(nextAgent: String?, respectAgent: Boolean, position: LyricsPosition): Float =
        when {
            respectAgent && nextAgent == "v1" -> 0f
            respectAgent && nextAgent == "v2" -> 1f
            respectAgent && nextAgent == "v1000" -> 0.5f
            else -> when (position) {
                LyricsPosition.LEFT -> 0f
                LyricsPosition.CENTER -> 0.5f
                LyricsPosition.RIGHT -> 1f
            }
        }

    const val DOT_COUNT = 3
    const val DOT_SIZE_DP = 12f
    const val DOT_SPACING_DP = 9f
    const val DOT_BASE_ALPHA = 0.25f
    const val BREATH_MIN = 0.92f
    const val BREATH_MAX = 1.08f
    const val BREATH_MS = 1100

    /** How far into the gap we are: 0..1. A gap that has no length counts as over. */
    fun gapProgress(positionMs: Long, startMs: Long, endMs: Long): Float =
        if (endMs <= startMs) (if (positionMs >= startMs) 1f else 0f)
        else ((positionMs - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)

    /** The fill of dot [index] (0, 1, 2): the dots fill one after the other as the gap runs down. */
    fun dotFill(progress: Float, index: Int): Float = (progress.coerceIn(0f, 1f) * DOT_COUNT - index).coerceIn(0f, 1f)

    /** Scale of the dots while they breathe, for a phase 0..1 of one half cycle (0.92 to 1.08, eased). */
    fun breathScale(phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        return BREATH_MIN + (BREATH_MAX - BREATH_MIN) * eased
    }

    // ---------------------------------------------------------------- scrolling

    /** The top of the active line sits this far down the lyric area. */
    const val ANCHOR_RATIO = 0.32f

    /** The fade at the top and the bottom of the lyric area, as a share of its height. */
    const val FADE_TOP = 0.13f
    const val FADE_BOTTOM = 0.14f

    fun anchorY(areaHeightPx: Float): Float = areaHeightPx * ANCHOR_RATIO

    /** How far the content can be dragged up by hand: the last line may rise to [keepBelowPx] from the top. */
    fun scrollMin(anchorY: Float, belowActivePx: Float, lastHeightPx: Float, keepBelowPx: Float): Float =
        keepBelowPx - anchorY - belowActivePx - lastHeightPx

    /** How far the content can be dragged down by hand: the first line may sink to [keepAbovePx] from the bottom. */
    fun scrollMax(areaHeightPx: Float, keepAbovePx: Float, anchorY: Float, aboveActivePx: Float): Float =
        areaHeightPx - keepAbovePx - anchorY + aboveActivePx

    // ---------------------------------------------------------------- the user's size settings

    /** The size setting is 24 sp by default; it scales the lyric type around the app's own size. */
    fun sizeScale(userSizeSp: Float): Float = (userSizeSp / 24f).coerceIn(0.75f, 1.35f)

    /** The spacing setting is 1.2 by default; it scales the line height around the app's own ratio. */
    fun spacingScale(userSpacing: Float): Float = (userSpacing / 1.2f).coerceIn(0.85f, 1.3f)

    /** Type of the main lyric line, before the user's settings. */
    const val TEXT_SP = 36f
    const val LINE_HEIGHT_RATIO = 1.25f
    const val LETTER_SPACING_SP = -0.3f

    /** Padding above and below a line, and the extra space between two lines (the rows are laid out by hand). */
    const val ROW_PAD_DP = 10f
    const val ROW_GAP_DP = 6f
}
