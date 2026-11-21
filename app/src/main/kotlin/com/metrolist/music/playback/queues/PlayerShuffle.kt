/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.random.Random

/**
 * The shuffle operations of the player queue (MusicService, queue screen), on a real [ExoPlayer]: every one keeps the
 * songs already heard in this pass where they are, so a pass of N songs plays each of them once.
 */
object PlayerShuffle {
    /** Sets [order] (timeline indexes in play order) as the shuffle order. False when it does not fit the queue. */
    fun setPlayOrder(
        player: ExoPlayer,
        order: IntArray,
    ): Boolean {
        if (!ShuffleOrders.isPermutation(order, player.mediaItemCount)) return false
        player.setShuffleOrder(QueueShuffleOrder(order))
        return true
    }

    /**
     * A new pass that starts with the song playing now (shuffle switched on, a new queue started shuffled). With
     * [listFirst] ("Acak playlist atau album terlebih dahulu") the first [originalSize] songs come before later ones.
     */
    fun startPass(
        player: ExoPlayer,
        listFirst: Boolean = false,
        originalSize: Int = 0,
        random: Random = Random.Default,
    ) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex
        val order =
            if (listFirst) {
                ShuffleOrders.freshListFirst(count, current, originalSize, random)
            } else {
                ShuffleOrders.fresh(count, current, random)
            }
        setPlayOrder(player, order)
    }

    /**
     * Repeat all with shuffle on: when the last song of the pass starts, the next pass gets a new random order (the
     * player would otherwise play the same order again). True when a new order was set.
     */
    fun newPassIfLast(
        player: ExoPlayer,
        random: Random = Random.Default,
    ): Boolean {
        if (!player.shuffleModeEnabled || player.repeatMode != Player.REPEAT_MODE_ALL) return false
        if (player.mediaItemCount < 3) return false
        val current = player.currentMediaItemIndex
        if (ShuffleOrders.remainingAfter(player.currentTimeline, current, true, 1) != 0) return false
        val order = ShuffleOrders.playOrder(player.currentTimeline, true)
        return setPlayOrder(player, ShuffleOrders.nextPass(order, current, random))
    }

    /** How songs just added at timeline indexes [added] join the play order when shuffle is on. */
    enum class Join {
        /** after every song still waiting ("Tambah ke antrean", the radio after a list) */
        END,

        /** among the songs still waiting, at random (the next page of the same playlist) */
        MIX,

        /** right after the song playing, in their order (a radio started from the playing song) */
        NEXT,
    }

    fun afterAdd(
        player: ExoPlayer,
        added: IntRange,
        join: Join,
        random: Random = Random.Default,
    ) {
        if (!player.shuffleModeEnabled || added.isEmpty() || player.mediaItemCount == 0) return
        val order = ShuffleOrders.playOrder(player.currentTimeline, true)
        val current = player.currentMediaItemIndex
        val newOrder =
            when (join) {
                Join.END -> ShuffleOrders.appendAtEnd(order, added.toList())
                Join.MIX -> ShuffleOrders.mixIntoUpcoming(order, current, added.toList(), keepNext = true, random = random)
                Join.NEXT -> ShuffleOrders.placeAfterCurrent(order, current, added.toList())
            }
        setPlayOrder(player, newOrder)
    }

    /** Adds [items] at the end of the timeline and puts them in the play order as [join] says. */
    fun addItems(
        player: ExoPlayer,
        items: List<androidx.media3.common.MediaItem>,
        join: Join,
        random: Random = Random.Default,
    ) {
        if (items.isEmpty()) return
        val from = player.mediaItemCount
        player.addMediaItems(items)
        afterAdd(player, from until from + items.size, join, random)
    }

    /** The songs heard before the current one in this pass (play order), as timeline indexes. */
    fun played(player: Player): List<Int> =
        ShuffleOrders.playedBefore(player.currentTimeline, player.currentMediaItemIndex, player.shuffleModeEnabled)

    /**
     * "Hapus lagu yang sudah diputar": removes every song before the current one in the play order (the rows above it
     * in the queue). The song playing is never touched. Returns how many were removed.
     */
    fun removePlayed(player: Player): Int {
        val played = played(player)
        if (played.isEmpty()) return 0
        for (run in ShuffleOrders.removalRuns(played)) {
            player.removeMediaItems(run.first, run.last + 1)
        }
        return played.size
    }
}
