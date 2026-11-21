/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableBiniLyricsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * BiniLyrics (lyrics-api.binimum.org): the same Apple Music TTML catalogue as lrc.red, with its own
 * matcher over title, artist and length (idea from BitChord, GPLv3). The answer lists hits with a link
 * to the TTML document; a hit is only used when it agrees with the playing song ([bestHit]), since the
 * server also returns near matches.
 */
object BiniLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics"
    override val wantsFullTitle = true

    private const val BASE = "https://lyrics-api.binimum.org/"
    internal const val DURATION_TOLERANCE_SECONDS = 8.0

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBiniLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = find(title, artist, duration, album).map { it.text }

    /** The hit's own title, artist and length come back with the lyrics; the TTML `dur` is preferred as the length. */
    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> = find(request.title, request.artist, request.duration, request.album)

    private suspend fun find(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<LyricsHit> {
        val url =
            LyricsHttp.url(
                BASE,
                "track" to title,
                "artist" to artist,
                "album" to album,
                "duration" to duration.takeIf { it > 0 }?.toString(),
            ) ?: return Result.failure(IllegalStateException("Bad query"))
        val body = LyricsHttp.get(url) ?: return Result.failure(IllegalStateException("No answer"))
        val hit = bestHit(body, title, artist, duration) ?: return Result.failure(IllegalStateException("No matching track"))
        val document = hit.lyricsUrl
        if (!LyricsHttp.isHttps(document)) return Result.failure(IllegalStateException("No document"))
        val ttml =
            LyricsHttp.get(document!!, accept = "application/xml, text/xml, */*")
                ?: return Result.failure(IllegalStateException("No document"))
        val lrc = LyricsConvert.ttmlToLrc(ttml) ?: return Result.failure(IllegalStateException("Lyrics unavailable"))
        val seconds = LyricsConvert.ttmlDurationSeconds(ttml) ?: hit.duration?.toDouble()
        return Result.success(LyricsHit(lrc, hit.trackName, hit.artistName, seconds))
    }

    /** The hit that is the playing recording and has a document, closest in length first; null if none. */
    internal fun bestHit(
        body: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Hit? {
        val results = runCatching { json.decodeFromString<Response>(body) }.getOrNull()?.results.orEmpty()
        return results
            .filter { hit ->
                !hit.lyricsUrl.isNullOrBlank() &&
                    LyricsHitMatch.isSameRecording(
                        hitTitle = hit.trackName,
                        hitArtist = hit.artistName,
                        hitSeconds = hit.duration?.toDouble(),
                        wantedTitle = title,
                        wantedArtist = artist,
                        wantedSeconds = durationSeconds,
                        toleranceSeconds = DURATION_TOLERANCE_SECONDS,
                    )
            }.minByOrNull { LyricsHitMatch.gap(it.duration?.toDouble(), durationSeconds) }
    }

    @Serializable
    internal data class Response(
        val results: List<Hit>? = null,
    )

    @Serializable
    internal data class Hit(
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int? = null,
        val isrc: String? = null,
        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )
}
