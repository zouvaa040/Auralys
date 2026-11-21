/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.lrclib.LrcLib
import com.metrolist.lrclib.models.Track
import com.metrolist.music.constants.EnableLrcLibKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcLibKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = LrcLib.getLyrics(title, artist, duration, album)

    /**
     * LRCLIB's search is loose (title only, then free text), so every hit is rated like a final answer (title and version
     * words, guests, artist, length, timing; see [LyricsSelection.rate]) and the best one is taken. Before, a hit was
     * taken when its name contained the bracket-less title and its length was within 5 s: "La La Lost You (Acoustic
     * Version)" got the original's timing and "Dracula (feat. JENNIE)" the solo version's.
     *
     * Asked with [LyricsRequest.title] (credits and labels such as "(Bonus Track)" or "(Remastered 2011)" are already
     * off, version words kept): the full YouTube title found nothing for those (review 2026-11-12). When that gives no
     * usable hit, once more with every bracket off; the hits are still checked against [LyricsRequest.title].
     */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> =
        runCatching {
            var picked: LyricsHit? = null
            for (title in listOf(request.title, request.searchTitle).filter { it.isNotBlank() }.distinct()) {
                val tracks = LrcLib.lyrics(request.artist, title).getOrThrow()
                picked = pick(tracks, request)
                if (picked != null) break
            }
            picked ?: throw IllegalStateException("No matching track")
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

    internal fun pick(
        tracks: List<Track>,
        request: LyricsRequest,
    ): LyricsHit? =
        tracks
            .flatMap { track ->
                listOfNotNull(track.syncedLyrics, track.plainLyrics)
                    .filter { it.isNotBlank() }
                    .map { text -> LyricsHit(text, track.trackName, track.artistName, track.duration) }
            }.map { hit -> hit to LyricsSelection.rate(name, request, hit) }
            .withIndex()
            .filter { it.value.second.usable }
            .minWithOrNull(
                compareBy<IndexedValue<Pair<LyricsHit, LyricsSelection.Candidate>>> { it.value.second.rank }
                    .thenBy { LyricsHitMatch.gap(it.value.first.durationSeconds, request.duration) }
                    .thenBy { it.index },
            )?.value
            ?.first

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        LrcLib.getAllLyrics(title, artist, duration, album, callback)
    }
}
