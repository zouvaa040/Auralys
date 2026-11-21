/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context

interface LyricsProvider {
    val name: String

    /** True for providers that match on the full title (version words kept, see LyricsQuery) and verify the hit themselves. */
    val wantsFullTitle: Boolean get() = false

    fun isEnabled(context: Context): Boolean

    suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String>

    /**
     * The lookup the automatic choice and the source picker use. The default asks [getLyrics] with the title this
     * provider wants and reports no metadata; providers that know what they matched override it so the hit can be
     * checked against the song (see [LyricsSelection.rate]).
     */
    suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> =
        getLyrics(
            context,
            request.id,
            if (wantsFullTitle) request.title else request.searchTitle,
            request.artist,
            request.duration,
            request.album,
        ).map { LyricsHit(it) }

    suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        getLyrics(context, id, title, artist, duration, album).onSuccess(callback)
    }
}
