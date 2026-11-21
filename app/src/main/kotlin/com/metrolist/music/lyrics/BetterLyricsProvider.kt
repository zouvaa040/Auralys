/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.betterlyrics.BetterLyrics
import com.metrolist.music.constants.EnableBetterLyricsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = BetterLyrics.getLyrics(title, artist, duration, album)

    /**
     * Asked with the title as the song has it, version words kept ("La La Lost You (Acoustic Version)"); only when that
     * finds nothing, with every bracket taken off. The server does not say what it matched and does not always respect
     * version words (live probe 2026-11-12: "Despacito (Remix)" gave the original's lyrics), so:
     *  - a hit of the full title carries no title: [LyricsSelection.rate] then keeps a song with version words at
     *    "loose" and a source that checks the version (BiniLyrics, lrc.red, LrcLib) wins;
     *  - a hit of the bracket-less title carries that title, so a dropped version word makes it "another version";
     *  - the length of the TTML document must fit the song in both cases.
     */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> {
        val titles = listOf(request.title, request.searchTitle).distinct().filter { it.isNotBlank() }
        var last: Throwable = IllegalStateException("Lyrics unavailable")
        for (title in titles) {
            BetterLyrics
                .find(title, request.artist, request.duration, request.album)
                .onSuccess { (lrc, seconds) ->
                    val matchedTitle = if (title == request.title) null else title
                    return Result.success(LyricsHit(lrc, title = matchedTitle, durationSeconds = seconds))
                }
                .onFailure { last = it }
        }
        return Result.failure(last)
    }
}
