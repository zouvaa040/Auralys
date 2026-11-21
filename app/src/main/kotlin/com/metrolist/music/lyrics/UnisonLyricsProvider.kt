/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.music.constants.EnableUnisonKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unison, a community-submitted lyrics database (idea from BitChord, GPLv3).
 *
 * Entries come in three shapes, and the answer says which: Apple style TTML, line-stamped LRC (the
 * app's lyrics parser also reads word stamps inside it), or plain text. The answer names the song and
 * artist it holds; they must agree with the song that is playing or the entry is a miss.
 */
object UnisonLyricsProvider : LyricsProvider {
    override val name = "Unison"
    override val wantsFullTitle = true

    private const val BASE = "https://unison.boidu.dev/lyrics"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableUnisonKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val url =
            LyricsHttp.url(
                BASE,
                "song" to title,
                "artist" to artist,
                "album" to album,
                "duration" to duration.takeIf { it > 0 }?.toString(),
            ) ?: return Result.failure(IllegalStateException("Bad query"))
        val body = LyricsHttp.get(url) ?: return Result.failure(IllegalStateException("No answer"))
        val lyrics = parse(body, title, artist) ?: return Result.failure(IllegalStateException("Lyrics unavailable"))
        return Result.success(lyrics)
    }

    /** The entry names the song and artist it holds, and a TTML entry its length: all of it is checked by the caller. */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> {
        val url =
            LyricsHttp.url(
                BASE,
                "song" to request.title,
                "artist" to request.artist,
                "album" to request.album,
                "duration" to request.duration.takeIf { it > 0 }?.toString(),
            ) ?: return Result.failure(IllegalStateException("Bad query"))
        val body = LyricsHttp.get(url) ?: return Result.failure(IllegalStateException("No answer"))
        val lyrics = parse(body, request.title, request.artist) ?: return Result.failure(IllegalStateException("Lyrics unavailable"))
        val entry = runCatching { json.decodeFromString<Response>(body) }.getOrNull()?.data
        val seconds = entry?.lyrics?.takeIf { entry.format.equals("ttml", ignoreCase = true) }?.let { LyricsConvert.ttmlDurationSeconds(it) }
        return Result.success(LyricsHit(lyrics, title = entry?.song?.takeIf { it.isNotBlank() }, artist = entry?.artist?.takeIf { it.isNotBlank() }, durationSeconds = seconds))
    }

    /** Lyrics text in the app's format, or null when the answer is a miss or for another song. */
    internal fun parse(
        body: String,
        title: String,
        artist: String,
    ): String? {
        val response = runCatching { json.decodeFromString<Response>(body) }.getOrNull() ?: return null
        if (response.success != true) return null
        val entry = response.data ?: return null
        val text = entry.lyrics?.takeIf { it.isNotBlank() } ?: return null
        if (!sameSong(entry, title, artist)) return null
        return when {
            entry.format.equals("ttml", ignoreCase = true) -> LyricsConvert.ttmlToLrc(text)
            else -> text.trim()
        }
    }

    private fun sameSong(
        entry: Entry,
        title: String,
        artist: String,
    ): Boolean {
        val songOk = entry.song.isNullOrBlank() || LyricsHitMatch.sameTitle(entry.song, title)
        return songOk && SongMatch.sameArtist(entry.artist, artist)
    }

    @Serializable
    internal data class Response(
        val success: Boolean? = null,
        val data: Entry? = null,
    )

    @Serializable
    internal data class Entry(
        val song: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val lyrics: String? = null,
        /** `ttml` or `lrc`. */
        val format: String? = null,
        /** `wordsync`, `linesync` or `plain`. */
        val syncType: String? = null,
    )
}
