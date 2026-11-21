/**
 * Auralys - lyrics of the Dynamic Island
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Immutable
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsSelection
import com.metrolist.music.lyrics.LyricsUtils
import kotlin.math.max
import kotlin.math.min

/** One line of the island's lyrics: when it starts and ends (song ms, the lyric offset not applied) and what it says. */
@Immutable
data class IslandLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val rtl: Boolean,
    /** Where each word is sung, when the lyrics have word times: the sung part then grows word by word. */
    val words: List<IslandWordSpan>? = null,
)

/** One word: sung from [startMs] to [endMs]; it covers [from]..[to] (0..1) of the line's text. */
@Immutable
data class IslandWordSpan(
    val startMs: Long,
    val endMs: Long,
    val from: Float,
    val to: Float,
)

enum class IslandLyricsStatus {
    /** Not asked for (the option is off or nothing needs the lyrics right now). */
    OFF,

    /** Looking them up. */
    LOADING,

    /** The sources have none. */
    NONE,

    /** Lyrics without time stamps: nothing to follow. */
    PLAIN,

    /** Lines with times: the island follows the song. */
    SYNCED,
}

@Immutable
data class IslandLyrics(
    val status: IslandLyricsStatus,
    val lines: List<IslandLyricLine>,
) {
    companion object {
        val Off = IslandLyrics(IslandLyricsStatus.OFF, emptyList())
        val Loading = IslandLyrics(IslandLyricsStatus.LOADING, emptyList())
    }
}

/** The lyric lines as the island needs them, and where in them the song is. Plain functions, no Android, no Compose. */
object IslandLyricsLogic {
    /** A line is shown this long before its time stamp, like the lyric view of the player. */
    const val LOOKAHEAD_MS = 120L

    /** A line without word times that is longer than the next one allows ends after at least this long... */
    private const val MIN_LINE_MS = 1_600L
    private const val MS_PER_CHAR = 95L

    /** ...and the last line of a song gets this long. */
    private const val LAST_LINE_MS = 4_000L

    /** What the song's stored lyrics (the LRC text of the database, null = no row yet) mean for the island. */
    fun fromStored(raw: String?): IslandLyrics {
        if (raw == null) return IslandLyrics.Loading
        if (raw == LYRICS_NOT_FOUND || raw.isBlank()) return IslandLyrics(IslandLyricsStatus.NONE, emptyList())
        if (!LyricsSelection.isSynced(raw)) return IslandLyrics(IslandLyricsStatus.PLAIN, emptyList())
        val lines = linesOf(LyricsUtils.parseLyrics(raw))
        return if (lines.isEmpty()) IslandLyrics(IslandLyricsStatus.PLAIN, emptyList()) else IslandLyrics(IslandLyricsStatus.SYNCED, lines)
    }

    /** The main vocal lines in time order. Background vocals, empty lines and "instrumental" gaps are left out. */
    fun linesOf(entries: List<LyricsEntry>): List<IslandLyricLine> {
        val main = entries.filter { !it.isBackground && it.text.isNotBlank() }.sortedBy { it.time }
        return main.mapIndexed { i, entry ->
            val next = main.getOrNull(i + 1)?.time ?: (entry.time + LAST_LINE_MS)
            val wordsEnd = entry.words?.lastOrNull()?.endTime?.let { (it * 1000).toLong() } ?: 0L
            val end =
                (
                    if (wordsEnd > entry.time) {
                        min(next, wordsEnd)
                    } else {
                        min(next, entry.time + max(MIN_LINE_MS, entry.text.length * MS_PER_CHAR))
                    }
                ).coerceAtLeast(entry.time + 1)
            val text = entry.text.trim()
            IslandLyricLine(entry.time, end, text, isRtl(text), wordSpans(entry))
        }
    }

    /** The words of [entry] with the part of the line each one covers (by letters), or null when the line has no word times. */
    private fun wordSpans(entry: LyricsEntry): List<IslandWordSpan>? {
        val words = entry.words?.filter { it.text.isNotEmpty() && it.endTime >= it.startTime }.orEmpty()
        if (words.size < 2) return null
        val total = words.sumOf { it.text.length + if (it.hasTrailingSpace) 1 else 0 }.toFloat()
        if (total <= 0f) return null
        var cumulative = 0
        return words.map { w ->
            val from = cumulative / total
            val to = (cumulative + w.text.length) / total
            cumulative += w.text.length + if (w.hasTrailingSpace) 1 else 0
            IslandWordSpan((w.startTime * 1000).toLong(), (w.endTime * 1000).toLong(), from, to.coerceAtMost(1f))
        }
    }

    /** The line that is sung at [positionMs] (the lyric offset already added): the last one that started, -1 before the first. */
    fun indexAt(
        lines: List<IslandLyricLine>,
        positionMs: Long,
    ): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        val at = positionMs + LOOKAHEAD_MS
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= at) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** How much of line [index] has been sung at [positionMs], 0..1. A line that is not the current one is 0 (not yet) or 1 (done). */
    fun sweepAt(
        lines: List<IslandLyricLine>,
        index: Int,
        positionMs: Long,
    ): Float {
        val line = lines.getOrNull(index) ?: return 0f
        line.words?.let { return wordSweep(it, positionMs) }
        val span = (line.endMs - line.startMs).coerceAtLeast(1L)
        return ((positionMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** The sung part of a line with word times: inside a word it grows with the word, between two words it waits. */
    private fun wordSweep(
        words: List<IslandWordSpan>,
        positionMs: Long,
    ): Float {
        var done = 0f
        for (w in words) {
            if (positionMs < w.startMs) return done
            if (positionMs <= w.endMs) {
                val length = (w.endMs - w.startMs).coerceAtLeast(1L)
                return (w.from + (w.to - w.from) * ((positionMs - w.startMs).toFloat() / length).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            }
            done = w.to
        }
        return 1f
    }

    /** How far a line wider than its room has glided to the left while [sweep] of it is sung: the sung part stays in view. */
    fun followFraction(sweep: Float): Float = ((sweep - 0.25f) / 0.6f).coerceIn(0f, 1f)

    /**
     * Splits [text] for a pill with a hole in the middle (the camera): the first part is as long as fits in [leftPx] (at a
     * space when it can), the second is the rest, drawn after the hole. [measure] gives the width of a string in px. An empty
     * first part = nothing fits left of the hole; an empty second part = it all fits.
     */
    fun splitAround(
        text: String,
        leftPx: Float,
        measure: (String) -> Float,
    ): Pair<String, String> {
        if (leftPx <= 0f || text.isEmpty()) return "" to text
        if (measure(text) <= leftPx) return text to ""
        // the last space with everything before it fitting
        var cut = -1
        var from = 0
        while (true) {
            val space = text.indexOf(' ', from)
            if (space < 0) break
            if (measure(text.substring(0, space)) <= leftPx) cut = space else break
            from = space + 1
        }
        if (cut <= 0) {
            // one word wider than the room: break it by letters
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (measure(text.substring(0, mid)) <= leftPx) lo = mid else hi = mid - 1
            }
            cut = lo
        }
        if (cut <= 0) return "" to text
        return text.substring(0, cut).trimEnd() to text.substring(cut).trimStart()
    }

    /**
     * The camera hole in the lyric text, in dp from the start of the text, or null when the hole is not over the text (left of it,
     * right of it, or no hole): then the line is drawn as usual. All positions are screen px; [margin] is kept free on both sides.
     */
    fun cameraGapDp(
        holeLeft: Float,
        holeRight: Float,
        textLeft: Float,
        textWidth: Float,
        margin: Float,
        density: Float,
    ): Pair<Float, Float>? {
        if (holeRight <= holeLeft || density <= 0f) return null
        val start = holeLeft - margin - textLeft
        val end = holeRight + margin - textLeft
        if (end <= 0f || start >= textWidth) return null
        return start / density to end / density
    }

    /** The sung part (0..1) of the two parts of a split line: the first fills up, then the second. [first] and [second] are their widths. */
    fun splitSweep(
        sweep: Float,
        first: Float,
        second: Float,
    ): Pair<Float, Float> {
        val total = first + second
        if (total <= 0f) return 0f to 0f
        val sung = sweep.coerceIn(0f, 1f) * total
        val a = if (first > 0f) (sung / first).coerceIn(0f, 1f) else 0f
        val b = if (second > 0f) ((sung - first) / second).coerceIn(0f, 1f) else 0f
        return a to b
    }

    /** True when the first letter that has a direction runs right to left (Arabic, Hebrew). */
    fun isRtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                else -> Unit
            }
        }
        return false
    }

    /** The open card can show the current line under the artist only when the three text lines fit beside the cover. */
    fun miniLineFits(config: DynamicIslandConfig): Boolean {
        if (config.layout == com.metrolist.music.constants.DynamicIslandLayout.COMPACT_ROW) {
            val room = DynamicIslandDimens.rowCover(config)
            return (if (config.showArtist) MINI_TEXT_ROW_DP else MINI_TEXT_ROW_DP - 15f) * (config.textScale / 100f) <= room
        }
        val room = DynamicIslandDimens.cardCover(config)
        return (if (config.showArtist) MINI_TEXT_CARD_DP else MINI_TEXT_CARD_DP - 16.3f) * (config.textScale / 100f) <= room
    }

    /** Height of the part of the open card under the header (progress bar and buttons) that the lyric page can use, unscaled dp. */
    fun pageRegionHeight(config: DynamicIslandConfig): Float {
        var h = 0f
        if (config.showProgress) h += DynamicIslandDimens.CARD_PROGRESS_GAP + DynamicIslandDimens.CARD_PROGRESS
        if (config.showButtons) {
            h += (if (config.showProgress) DynamicIslandDimens.CARD_BUTTONS_GAP else DynamicIslandDimens.CARD_PROGRESS_GAP) +
                DynamicIslandDimens.CARD_BUTTONS
        }
        return h
    }

    /** The lyric page replaces progress bar and buttons, so it needs the buttons' room, and a card layout. */
    fun pageAvailable(config: DynamicIslandConfig): Boolean =
        config.showLyrics &&
            config.layout == com.metrolist.music.constants.DynamicIslandLayout.CARD &&
            pageRegionHeight(config) >= MIN_PAGE_DP

    /** Title (16 x 1.2) + artist (13 x 1.25) + current line (12 x 1.25) in the card; title (15 x 1.2) + artist + line in the row. */
    private const val MINI_TEXT_CARD_DP = 50.5f
    private const val MINI_TEXT_ROW_DP = 48.5f
    private const val MIN_PAGE_DP = 50f
}
