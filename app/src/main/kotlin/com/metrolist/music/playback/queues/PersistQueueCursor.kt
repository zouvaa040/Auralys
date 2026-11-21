/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.PersistQueue
import java.io.Serializable

/**
 * Where the listener is in the saved queue: the song, the position and the shuffle order. A small file written at every
 * save, next to the big queue file that is only rewritten when the queue itself changed. It belongs to the queue whose
 * [signature] it carries; a cursor of another queue is ignored.
 *
 * A separate class and file because [PersistQueue] has no serialVersionUID: a new field there would make every queue
 * saved by 4.3.3 unreadable (and deleted) after the update.
 */
data class PersistQueueCursor(
    val signature: Long,
    val size: Int,
    val mediaItemIndex: Int,
    val currentId: String,
    val position: Long,
    /** Positions in the saved items, in play order; null with shuffle off. */
    val shuffleOrder: IntArray?,
    val savedAt: Long = System.currentTimeMillis(),
) : Serializable {
    /** Where to start [queue], or null when this cursor is not about that queue. */
    fun resolve(queue: PersistQueue): Resolved? {
        if (queue.items.size != size || size == 0) return null
        if (QueueSignature.of(queue.title, queue.items.map { it.id }) != signature) return null
        if (queue.items.getOrNull(mediaItemIndex)?.id != currentId) return null
        val order = shuffleOrder?.takeIf { ShuffleOrders.isPermutation(it, size) }
        return Resolved(mediaItemIndex, position.coerceAtLeast(0L), order, savedAt)
    }

    class Resolved(
        val mediaItemIndex: Int,
        val position: Long,
        val shuffleOrder: IntArray?,
        val savedAt: Long,
    )

    // arrays compare by content, so two cursors of the same state are equal
    override fun equals(other: Any?): Boolean =
        other is PersistQueueCursor &&
            signature == other.signature &&
            size == other.size &&
            mediaItemIndex == other.mediaItemIndex &&
            currentId == other.currentId &&
            position == other.position &&
            savedAt == other.savedAt &&
            (shuffleOrder?.contentEquals(other.shuffleOrder) ?: (other.shuffleOrder == null))

    override fun hashCode(): Int =
        listOf(signature, size, mediaItemIndex, currentId, position, savedAt, shuffleOrder?.contentHashCode()).hashCode()

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A cheap fingerprint of a saved queue (title + song ids in timeline order): FNV-1a, 64 bit. */
object QueueSignature {
    fun of(
        title: String?,
        ids: List<String>,
    ): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        fun mix(c: Int) {
            h = h xor c.toLong()
            h *= 0x100000001b3L
        }
        title?.forEach { mix(it.code) }
        mix(0x1F)
        for (id in ids) {
            for (ch in id) mix(ch.code)
            mix(0x1E)
        }
        mix(ids.size)
        return h
    }
}
