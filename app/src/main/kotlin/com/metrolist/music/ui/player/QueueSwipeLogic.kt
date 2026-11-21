/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The decisions behind the swipe gestures of the player queue, kept free of Compose so they can be unit tested:
 *  - right = "Putar berikutnya": the row moves to the place right after the song that is playing;
 *  - left  = "Hapus": the row is removed (with Undo).
 * Plus the two player operations (move a row in the PLAY order, put a removed row back where it was) that the queue
 * screen and the tests share.
 *
 * The queue screen shows the songs in play order: with shuffle on, that is the shuffle order, not the order of the
 * timeline. So every index here is a position in that list, and the new order is computed on the list first and only
 * then handed to the player: moveMediaItem when shuffle is off (play order = timeline order), a new shuffle order when it
 * is on (moving the item in the timeline alone would not change what plays next).
 */

package com.metrolist.music.ui.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.extensions.getCurrentQueueIndex
import com.metrolist.music.playback.queues.PlayerShuffle
import com.metrolist.music.extensions.getQueueWindows
import kotlin.math.abs
import kotlin.math.min

/** The two neighbours a row had in the play order, enough to put it back after other edits moved the indices. */
class QueueSlot<T>(
    val before: T?,
    val after: T?,
)

object QueueSwipeLogic {
    /** Past this share of the row width the action is committed when the finger lets go. */
    const val ThresholdFraction = 0.40f

    /** A quick flick commits from this share of the width on... */
    const val FlingMinFraction = 0.20f

    /** ...when it is at least this fast (dp per second). */
    const val FlingVelocityDp = 700f

    /** Beyond the threshold the row follows the finger this much slower, and never further than this share of the width. */
    const val OvershootResistance = 0.35f
    const val MaxFraction = 0.70f

    /** A swipe that does nothing ("already next") stretches like a rubber band up to this share of the width. */
    const val RubberFraction = 0.22f

    /** On a locked queue a drag of this share of the width (either way) asks to unlock it. */
    const val HintFraction = 0.20f

    /** The unlock hint is shown at most once in this time (ms). */
    const val HintIntervalMs = 3000L

    /** What a swipe to the right does for a row. */
    enum class RightAction {
        /** Move the row so it plays right after the current song. */
        PlayNext,

        /** The row is the song that is playing now: nothing to do, say so. */
        IsCurrent,

        /** The row is already the next song: nothing to do, say so. */
        AlreadyNext,

        /** Swiping is off (Listen Together guest, locked queue, selection mode, nothing playing). */
        Disabled,
    }

    /** How the row follows the finger in one direction. */
    enum class Pull {
        /** 1:1 up to the threshold, then slower. */
        Action,

        /** Stretches and stops early: this swipe does nothing. */
        Rubber,

        /** Does not move. */
        None,
    }

    fun swipesAllowed(
        guest: Boolean,
        locked: Boolean,
        selecting: Boolean,
    ): Boolean = !guest && !locked && !selecting

    /**
     * [index] and [currentIndex] are positions in the list the screen shows (the play order, shuffle or not), so the
     * answer is the same with shuffle on: "next" is always the row right under the current one.
     */
    fun rightAction(
        index: Int,
        currentIndex: Int,
        guest: Boolean,
        locked: Boolean,
        selecting: Boolean,
    ): RightAction =
        when {
            !swipesAllowed(guest, locked, selecting) -> RightAction.Disabled
            index < 0 || currentIndex < 0 -> RightAction.Disabled
            index == currentIndex -> RightAction.IsCurrent
            index == currentIndex + 1 -> RightAction.AlreadyNext
            else -> RightAction.PlayNext
        }

    fun leftEnabled(
        guest: Boolean,
        locked: Boolean,
        selecting: Boolean,
    ): Boolean = swipesAllowed(guest, locked, selecting)

    /**
     * A swipe on a LOCKED queue changes nothing but is not silent: the row stretches, ticks once and says the queue is
     * locked. Not for a Listen Together guest (cannot unlock anything) and not in selection mode.
     */
    fun lockedHint(
        guest: Boolean,
        locked: Boolean,
        selecting: Boolean,
    ): Boolean = locked && !guest && !selecting

    /** The finger let go on a locked queue: ask to unlock when it went far enough, in either direction, slow or fast. */
    fun hintCommit(
        travelPx: Float,
        widthPx: Float,
    ): Boolean = widthPx > 0f && abs(travelPx) >= widthPx * HintFraction

    /** Where a row on a locked queue is (px) when the finger has just reached [HintFraction] of the width. */
    fun hintOffsetPx(widthPx: Float): Float = offsetFor(widthPx * HintFraction, widthPx, Pull.Rubber)

    /** True from the moment the finger has gone far enough on a locked queue: the single light tick. */
    fun isHintArmed(
        offsetPx: Float,
        widthPx: Float,
    ): Boolean = widthPx > 0f && abs(offsetPx) >= hintOffsetPx(widthPx)

    /** At most one hint per [intervalMs]. [lastShownMs] is null before the first one. A clock that went back allows it. */
    fun hintAllowed(
        nowMs: Long,
        lastShownMs: Long?,
        intervalMs: Long = HintIntervalMs,
    ): Boolean = lastShownMs == null || nowMs < lastShownMs || nowMs - lastShownMs >= intervalMs

    fun rightPull(action: RightAction): Pull =
        when (action) {
            RightAction.PlayNext -> Pull.Action
            RightAction.IsCurrent, RightAction.AlreadyNext -> Pull.Rubber
            RightAction.Disabled -> Pull.None
        }

    fun leftPull(enabled: Boolean): Pull = if (enabled) Pull.Action else Pull.None

    /** Where the row is (px, signed like [travelPx]) after the finger moved [travelPx] on a row [widthPx] wide. */
    fun offsetFor(
        travelPx: Float,
        widthPx: Float,
        pull: Pull,
    ): Float {
        if (widthPx <= 0f || travelPx == 0f) return 0f
        val a = abs(travelPx)
        val moved =
            when (pull) {
                Pull.None -> 0f
                Pull.Action -> {
                    val threshold = widthPx * ThresholdFraction
                    if (a <= threshold) a else min(threshold + (a - threshold) * OvershootResistance, widthPx * MaxFraction)
                }
                Pull.Rubber -> {
                    val limit = widthPx * RubberFraction
                    limit * (1f - 1f / (a * 0.55f / limit + 1f))
                }
            }
        return if (travelPx < 0f) -moved else moved
    }

    /** True while the row is past the threshold: the action fires if the finger lets go now. */
    fun isArmed(
        offsetPx: Float,
        widthPx: Float,
    ): Boolean = widthPx > 0f && abs(offsetPx) >= widthPx * ThresholdFraction

    /**
     * 0 at rest, 1 at [limitFraction] of the width (the threshold by default) and beyond. Drives the fade and scale of the
     * reveal. A row that only gets a note never reaches the threshold, so it passes [RubberFraction].
     */
    fun progress(
        offsetPx: Float,
        widthPx: Float,
        limitFraction: Float = ThresholdFraction,
    ): Float = if (widthPx <= 0f) 0f else (abs(offsetPx) / (widthPx * limitFraction)).coerceIn(0f, 1f)

    /** The reveal starts to show after a quarter of the way and is fully there at 85 percent of it. */
    fun revealAlpha(progress: Float): Float {
        val t = ((progress - 0.25f) / 0.60f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * What happens when the finger lets go: committed past the threshold, or after a quick flick over [FlingMinFraction].
     * [travelPx] is the finger travel (signed), [velocityTowardDpPerSec] the speed in the direction of the swipe (negative
     * when the finger is already coming back).
     */
    fun shouldCommit(
        travelPx: Float,
        widthPx: Float,
        velocityTowardDpPerSec: Float,
    ): Boolean {
        if (widthPx <= 0f) return false
        val a = abs(travelPx)
        return a >= widthPx * ThresholdFraction ||
            (a >= widthPx * FlingMinFraction && velocityTowardDpPerSec >= FlingVelocityDp)
    }

    // ------------------------------------------------------------------------------------------- play order

    /** [order] with [item] moved to right after [after] (null: to the front). Null when that cannot be done. */
    fun <T> placeAfter(
        order: List<T>,
        item: T,
        after: T?,
    ): List<T>? {
        val from = order.indexOf(item)
        if (from < 0) return null
        if (after != null && (after == item || after !in order)) return null
        val rest = order.toMutableList().also { it.removeAt(from) }
        rest.add(if (after == null) 0 else rest.indexOf(after) + 1, item)
        return rest
    }

    /** [order] with [item] moved to right before [before]. Null when that cannot be done. */
    fun <T> placeBefore(
        order: List<T>,
        item: T,
        before: T,
    ): List<T>? {
        val from = order.indexOf(item)
        if (from < 0 || before == item || before !in order) return null
        val rest = order.toMutableList().also { it.removeAt(from) }
        rest.add(rest.indexOf(before), item)
        return rest
    }

    /**
     * Puts [item] back where [slot] says it was: after the row that was above it, else before the row that was under it,
     * else at the front when it was the first row. Null when neither neighbour is in the queue any more (and it was not
     * the first): the caller leaves the item where it is.
     */
    fun <T> restore(
        order: List<T>,
        item: T,
        slot: QueueSlot<T>,
    ): List<T>? {
        val before = slot.before
        val after = slot.after
        return when {
            before != null && before in order && before != item -> placeAfter(order, item, before)
            after != null && after in order && after != item -> placeBefore(order, item, after)
            before == null && after == null -> null
            before == null -> placeAfter(order, item, null)
            else -> null
        }
    }

    /** The neighbours of the row at [position] in [order]. */
    fun <T> slotOf(
        order: List<T>,
        position: Int,
    ): QueueSlot<T> =
        if (position !in order.indices) QueueSlot(null, null) else QueueSlot(order.getOrNull(position - 1), order.getOrNull(position + 1))
}

// ----------------------------------------------------------------------------------------------- player operations

/**
 * Rewrites the play order with [compute] (from the current order of window uids to the new one). Never moves the song
 * that is playing. True when the order changed.
 */
private fun ExoPlayer.reorderQueue(
    item: Any,
    compute: (order: List<Any>) -> List<Any>?,
): Boolean {
    val windows = getQueueWindows()
    val order = windows.map { it.uid }
    val current = getCurrentQueueIndex()
    if (current < 0 || order.getOrNull(current) == item) return false
    val newOrder = compute(order) ?: return false
    if (newOrder == order) return false
    if (!shuffleModeEnabled) {
        // play order = timeline order: the row at [from] in the list is the window at the same index
        val from = windows.first { it.uid == item }.firstPeriodIndex
        moveMediaItem(from, newOrder.indexOf(item))
    } else {
        // the timeline stays as it is: only the shuffle order is rewritten, with the new order as the traversal order
        val windowOf = windows.associate { it.uid to it.firstPeriodIndex }
        // QueueShuffleOrder (4.3.4): songs added later go after the waiting ones, not to a random place of the pass
        if (!PlayerShuffle.setPlayOrder(this, IntArray(newOrder.size) { windowOf.getValue(newOrder[it]) })) return false
    }
    return true
}

/**
 * "Putar berikutnya" for a row that is already in the queue: it moves to right after the song that is playing (the
 * queue keeps one copy of it; the menu action inserts a second one). Returns where the row was, for Undo, or null
 * when nothing changed (the row is playing, is already next, or is gone).
 */
fun ExoPlayer.playNextInQueue(uid: Any): QueueSlot<Any>? {
    val order = getQueueWindows().map { it.uid }
    val from = order.indexOf(uid)
    val current = getCurrentQueueIndex()
    if (from < 0 || current < 0 || from == current || from == current + 1) return null
    val slot = QueueSwipeLogic.slotOf(order, from)
    val currentUid = order[current]
    return if (reorderQueue(uid) { QueueSwipeLogic.placeAfter(it, uid, currentUid) }) slot else null
}

/** Undo of [playNextInQueue]. Does nothing when the neighbours are gone or the row is playing by now. */
fun ExoPlayer.undoPlayNext(
    uid: Any,
    slot: QueueSlot<Any>,
): Boolean = reorderQueue(uid) { QueueSwipeLogic.restore(it, uid, slot) }

/** Neighbours of the row [uid] before it is removed (for [reinsertInQueue]). */
fun ExoPlayer.slotOfRow(uid: Any): QueueSlot<Any> {
    val order = getQueueWindows().map { it.uid }
    return QueueSwipeLogic.slotOf(order, order.indexOf(uid))
}

/**
 * Undo of a removal: adds [item] again and puts it where [slot] says it was in the play order (also with shuffle on,
 * where adding alone would put it at a random place).
 */
fun ExoPlayer.reinsertInQueue(
    item: MediaItem,
    slot: QueueSlot<Any>,
) {
    val appendedIndex = mediaItemCount
    addMediaItem(item)
    val uid = getQueueWindows().firstOrNull { it.firstPeriodIndex == appendedIndex }?.uid ?: return
    reorderQueue(uid) { QueueSwipeLogic.restore(it, uid, slot) }
}
