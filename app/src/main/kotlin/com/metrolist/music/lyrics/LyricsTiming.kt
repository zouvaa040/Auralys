/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The per song lyrics offset (SongEntity.lyricsOffset, ms) in words people understand.
 *
 * The stored value is added to the playing position before the lines are looked up, so a POSITIVE value shows the lyrics
 * EARLIER and a negative one LATER. Users never see the sign: the controls say "Earlier" and "Later" and the value reads
 * "0.3 s earlier". Before, the dialog showed "+300ms" with a slider and nobody could tell which way the lyrics would move.
 */
object LyricsTiming {
    const val SMALL_STEP_MS = 100
    const val BIG_STEP_MS = 500
    const val LIMIT_MS = 15_000

    /** Lines a tapped line is put this far into, so it is clearly the active one. */
    const val TAP_LEAD_MS = 100L

    enum class Direction { NONE, EARLIER, LATER }

    fun clamp(offsetMs: Int): Int = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)

    /** Saved values that come back this soon after a tap are echoes of earlier taps. */
    const val ECHO_WINDOW_MS = 1_500L

    /**
     * What the timing bar shows when the saved offset [saved] comes back from the database: while the user is tapping
     * (within [ECHO_WINDOW_MS] of the last tap) the bar keeps its own value [shown], since the database answers each tap
     * late and an earlier tap's echo would undo the later ones; otherwise the saved value (another screen changed it).
     */
    fun shownAfterSave(
        shown: Int,
        saved: Int,
        msSinceLastTap: Long,
    ): Int = if (msSinceLastTap in 0 until ECHO_WINDOW_MS) shown else saved

    fun earlier(
        offsetMs: Int,
        stepMs: Int,
    ): Int = clamp(offsetMs + stepMs)

    fun later(
        offsetMs: Int,
        stepMs: Int,
    ): Int = clamp(offsetMs - stepMs)

    fun direction(offsetMs: Int): Direction =
        when {
            offsetMs > 0 -> Direction.EARLIER
            offsetMs < 0 -> Direction.LATER
            else -> Direction.NONE
        }

    /** "0.3", "1.25" (two decimals only when the value needs them), in the decimal style of [locale]. */
    fun seconds(
        offsetMs: Int,
        locale: Locale = Locale.getDefault(),
    ): String {
        val ms = abs(offsetMs)
        return if (ms % 100 == 0) String.format(locale, "%.1f", ms / 1000.0) else String.format(locale, "%.2f", ms / 1000.0)
    }

    /**
     * The offset that makes the line starting at [lineTimeMs] the one being sung at [positionMs]: what "tap the line that
     * is sung now" sets. Rounded to 10 ms.
     */
    fun forTappedLine(
        lineTimeMs: Long,
        positionMs: Long,
    ): Int {
        val raw = (lineTimeMs + TAP_LEAD_MS - positionMs).toDouble()
        return clamp(((raw / 10.0).roundToLong() * 10L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }
}

/**
 * Requests from the lyrics menu to the lyrics view on screen (open the source picker, open the timing bar). The menu
 * lives in another sheet, so it cannot reach the view's state; [listeners] tells it whether a view will answer, and if
 * not it falls back to its own dialog.
 */
object LyricsUiEvents {
    enum class Event { OPEN_SOURCES, OPEN_TIMING }

    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = flow

    /** How many lyrics views on screen answer each request (the classic view only answers OPEN_SOURCES). */
    private val listeners = Event.entries.associateWith { AtomicInteger(0) }

    fun listen(event: Event) {
        listeners.getValue(event).incrementAndGet()
    }

    fun stopListening(event: Event) {
        listeners.getValue(event).updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun isListened(event: Event): Boolean = listeners.getValue(event).get() > 0

    /** True when a lyrics view took the request. */
    fun send(event: Event): Boolean = isListened(event) && flow.tryEmit(event)
}
