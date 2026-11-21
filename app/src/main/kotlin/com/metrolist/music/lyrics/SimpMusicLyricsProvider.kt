/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableSimpMusicKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * SimpMusic's community lyrics database, keyed on the YouTube video id (idea from BitChord, GPLv3).
 *
 * The key is the point: every other provider matches on title and artist and can return another edit
 * of the song, which drifts out of sync. This one looks up the exact video that is playing, and the
 * entry's length must still be within [DURATION_TOLERANCE_SECONDS] of the song. Some regions get an
 * HTTP 403 with a text body instead of JSON; that is a plain miss, the other providers carry on.
 */
object SimpMusicLyricsProvider : LyricsProvider {
    override val name = "SimpMusic"

    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"
    internal const val DURATION_TOLERANCE_SECONDS = 10
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    // `<00:12.34>` word stamps: the app's lyrics parser reads this "rich sync" text as it is.
    private val WORD_STAMPS = Regex("""^\[\d{1,3}:\d{2}[.:]\d{2,3}\].*<\d{1,3}:\d{2}[.:]\d{2,3}>""", RegexOption.MULTILINE)
    private val LINE_STAMP = Regex("""^\[\d{1,3}:\d{2}[.:]\d{2,3}\]""", RegexOption.MULTILINE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableSimpMusicKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        if (!VIDEO_ID.matches(id)) return Result.failure(IllegalStateException("Not a YouTube video id"))
        val body = LyricsHttp.get(BASE + id) ?: return Result.failure(IllegalStateException("No answer"))
        val lyrics = parse(body, duration) ?: return Result.failure(IllegalStateException("Lyrics unavailable"))
        return Result.success(lyrics)
    }

    /** Keyed on the video: it can only be another upload of the same song, so the wider length window applies. */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> = getLyrics(context, request.id, request.title, request.artist, request.duration, request.album).map { LyricsHit(it, keyedOnVideo = true) }

    /**
     * Picks the lyrics out of an answer: the entry closest in length (within the tolerance), then word
     * timing, else line timing, else plain text. Null for a miss, a refusal or a body that is not JSON.
     */
    internal fun parse(
        body: String,
        durationSeconds: Int,
    ): String? {
        val response = runCatching { json.decodeFromString<Response>(body) }.getOrNull() ?: return null
        if (!response.success) return null
        val entry =
            response.data
                .orEmpty()
                .filter { durationSeconds <= 0 || abs((it.duration ?: Int.MAX_VALUE / 2) - durationSeconds) <= DURATION_TOLERANCE_SECONDS }
                .minByOrNull { abs((it.duration ?: 0) - durationSeconds) }
                ?: return null

        entry.richSyncLyrics?.takeIf { WORD_STAMPS.containsMatchIn(it) }?.let { return it.trim() }
        entry.syncedLyrics?.takeIf { LINE_STAMP.containsMatchIn(it) }?.let { return it.trim() }
        return entry.plainLyrics?.takeIf { it.isNotBlank() }?.trim()
    }

    @Serializable
    internal data class Response(
        val success: Boolean = false,
        val data: List<Entry>? = null,
    )

    @Serializable
    internal data class Entry(
        val duration: Int? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
    )
}
