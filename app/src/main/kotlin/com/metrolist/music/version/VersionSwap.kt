/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 *
 * The "prefer audio" start: a music video that was tapped starts with its catalogue audio recording (setting
 * "Utamakan versi audio"). The menu rows "Ubah ke audio / video" that swapped the playing item are gone: the video
 * mode (video/ package) shows the video over the same audio instead.
 */

package com.metrolist.music.version

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue

/** Pure decisions of the "prefer audio" start, kept apart from the player so they can be tested. */
object VersionSwap {
    /** Longest wait for the audio version when a music video is started (then the original plays). */
    const val PREFER_AUDIO_TIMEOUT_MS = 2_500L

    /**
     * True for a song the user keeps: liked, in the library, downloaded or from the phone. Such a song always plays
     * under the id the user saved (offline playback, the heart and the stats depend on it), so "prefer audio" never
     * replaces it. [saved] is the database row, null when the song is not stored at all.
     */
    fun isSavedByUser(saved: SongEntity?): Boolean =
        saved != null &&
            (saved.liked || saved.inLibrary != null || saved.isDownloaded || saved.dateDownload != null || saved.isLocal)

    /** The queue item for the counterpart. Queue identity (who suggested it) is kept; list-only ids are not. */
    fun targetMetadata(
        original: MediaMetadata,
        counterpart: Counterpart,
    ): MediaMetadata = counterpart.item.toMediaMetadata().copy(suggestedBy = original.suggestedBy)
}

/**
 * Starts a song that is a music video with its catalogue audio instead (setting "Utamakan versi audio").
 * The queue behaves exactly as the original one; only the item that was tapped is replaced.
 */
class AudioFirstQueue(
    private val inner: Queue,
    private val original: MediaMetadata,
    private val audio: MediaMetadata,
) : Queue {
    private val audioItem: MediaItem = audio.toMediaItem()

    override val preloadItem: MediaMetadata? = if (inner.preloadItem != null) audio else null

    override suspend fun getInitialStatus(): Queue.Status {
        val status = inner.getInitialStatus()
        val index =
            if (status.items.getOrNull(status.mediaItemIndex)?.mediaId == original.id) {
                status.mediaItemIndex
            } else {
                status.items.indexOfFirst { it.mediaId == original.id }
            }
        if (index < 0) return status
        // later copies of the audio recording would repeat it right away
        val items =
            status.items.mapIndexedNotNull { i, item ->
                when {
                    i == index -> audioItem
                    i > index && item.mediaId == audio.id -> null
                    else -> item
                }
            }
        return status.copy(items = items)
    }

    override fun hasNextPage() = inner.hasNextPage()

    override suspend fun nextPage() = inner.nextPage()
}

object PreferAudio {
    /**
     * The song that is about to start, when it is a music video the swap may apply to. Only a discovery tap counts:
     * a search row ([forced]) or a song started as a radio ([YouTubeQueue], home feeds). A list queue (library,
     * downloads, liked songs, playlists, history) is the user's own and never gets a swap. Whether the song itself is
     * saved is checked later against the database ([VersionSwap.isSavedByUser]).
     */
    fun startCandidate(
        queue: Queue,
        forced: MediaMetadata?,
    ): MediaMetadata? {
        val song =
            forced ?: (queue as? YouTubeQueue)?.preloadItem?.takeIf { it.isVideoSong } ?: return null
        return if (VersionResolver.shared.canOffer(song.toTrackInfo())) song else null
    }
}

/**
 * Which recording really plays for a row the user tapped: a search row of a music video ends up playing its audio
 * version, whose id differs. The pair is remembered for the session so the row still counts as the active one.
 */
object PlayedAs {
    private const val MAX = 64
    private val map =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
            },
        )

    fun record(
        tappedId: String,
        playedId: String,
    ) {
        if (tappedId.isNotBlank() && playedId.isNotBlank() && tappedId != playedId) map[tappedId] = playedId
    }

    /**
     * True when the row [rowId] is the song that plays now ([currentId]), directly or through a swap. [exact] (the
     * Video chip) only accepts the very same id, because that chip wants the video itself.
     */
    fun isActive(
        rowId: String,
        currentId: String?,
        exact: Boolean = false,
    ): Boolean = currentId != null && (rowId == currentId || (!exact && map[rowId] == currentId))

    fun clear() = map.clear()
}
