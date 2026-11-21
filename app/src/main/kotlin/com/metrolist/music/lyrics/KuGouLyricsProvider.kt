/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.kugou.KuGou
import com.metrolist.music.constants.EnableKugouKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        KuGou.getLyrics(title, artist, duration, album)

    /** The hit must be the playing recording (title with its version words, artist); the length is checked by the caller. */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> =
        KuGou
            .findLyrics(request.searchTitle, request.artist, request.duration.takeIf { it > 0 } ?: -1, request.album) { song, singer, _ ->
                (song.isBlank() || LyricsHitMatch.sameTitle(song, request.title)) &&
                    SongMatch.sameArtist(singer, request.artist) &&
                    !LyricsHitMatch.guestConflict(song, singer, request.rawTitle, request.artist)
            }.map { LyricsHit(it.lyrics, title = it.song.ifBlank { null }, artist = it.singer.ifBlank { null }, durationSeconds = it.durationSeconds) }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        KuGou.getAllPossibleLyricsOptions(title, artist, duration, album, callback)
    }
}
