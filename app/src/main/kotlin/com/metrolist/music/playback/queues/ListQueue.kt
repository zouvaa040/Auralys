/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import kotlin.random.Random

class ListQueue(
    val title: String? = null,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val position: Long = 0L,
    override val shuffle: Boolean? = null,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() = Queue.Status(title, items, startIndex, position)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()

    companion object {
        /**
         * The "Acak" buttons: the list in its own order with the player's shuffle ON, starting at a random song. Every
         * song plays once in a random order before any repeats, a new random pass follows with "ulangi semua", and
         * turning shuffle off continues the list in order from the song that plays.
         */
        fun shuffled(
            title: String? = null,
            items: List<MediaItem>,
            random: Random = Random.Default,
        ): ListQueue =
            ListQueue(
                title = title,
                items = items,
                startIndex = if (items.isEmpty()) 0 else random.nextInt(items.size),
                shuffle = true,
            )
    }
}
