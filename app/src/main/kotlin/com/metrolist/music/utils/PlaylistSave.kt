/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The one place that turns a list of matched YouTube Music songs into a normal local playlist. Used by the link
 * import (viewmodels/PlaylistLinkImportViewModel) and by the Spotify library (spotify/SpotifyPlaylistViewModel).
 */

package com.metrolist.music.utils

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Inserts [songs] (a song that cannot be inserted is skipped), creates an editable local playlist called [name] with
 * [coverUrl] as its picture and adds the songs in order. Returns the new playlist's id.
 * Same building blocks as the CSV/M3U import.
 */
suspend fun MusicDatabase.saveSongsAsPlaylist(
    name: String,
    coverUrl: String?,
    songs: List<SongItem>,
): String {
    songs.forEach { song ->
        try {
            insert(song.toMediaMetadata())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Insert failed for ${song.id}")
        }
    }
    val entity =
        PlaylistEntity(
            name = name,
            bookmarkedAt = LocalDateTime.now(),
            isEditable = true,
            thumbnailUrl = coverUrl,
        )
    insert(entity)
    addSongsToPlaylist(
        Playlist(playlist = entity, songCount = 0, songThumbnails = emptyList()),
        songs.map { it.id to null },
    )
    return entity.id
}
