/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.paxsenix.Paxsenix
import com.metrolist.music.constants.EnablePaxsenixKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import timber.log.Timber

object PaxsenixLyricsProvider : LyricsProvider {
    private const val TAG = "PaxsenixProvider"
    
    override val name = "Paxsenix"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        Timber.tag(TAG).d("getLyrics called: title='$title', artist='$artist', duration=$duration")
        
        try {
            Paxsenix.init(context)
            val result = Paxsenix.getLyrics(title, artist, duration, album)
            
            result.onSuccess { lyrics ->
                Timber.tag(TAG).i("Success! Got ${lyrics.length} chars of lyrics")
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to get lyrics")
            }
            
            return result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception in getLyrics")
            return Result.failure(e)
        }
    }

    /** Apple Music search through Paxsenix: the hit must be the playing recording before its lyrics are fetched. */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> =
        try {
            Paxsenix.init(context)
            Paxsenix
                .findLyrics(request.title, request.artist, request.duration) { title, artist, seconds ->
                    LyricsHitMatch.isSameRecording(
                        hitTitle = title,
                        hitArtist = artist,
                        hitSeconds = seconds?.toDouble(),
                        wantedTitle = request.title,
                        wantedArtist = request.artist,
                        wantedSeconds = request.duration,
                        toleranceSeconds = LyricsSelection.LOOSE_GAP_SECONDS,
                    ) && !LyricsHitMatch.guestConflict(title, artist, request.rawTitle, request.artist)
                }.map { LyricsHit(it.lyrics, it.title, it.artist, it.durationSeconds?.toDouble()) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        Timber.tag(TAG).d("getAllLyrics called")
        try {
            Paxsenix.init(context)
            Paxsenix.getAllLyrics(title, artist, duration, album, callback)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching lyrics from Paxsenix")
            callback("")
        }
    }
}
