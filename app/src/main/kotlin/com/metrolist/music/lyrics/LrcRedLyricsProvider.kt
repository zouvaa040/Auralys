/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableLrcRedKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * lrc.red: Apple Music TTML (per word or per line), filed by ISRC (idea from BitChord, GPLv3).
 *
 * The site's own search is a free-text one and happily ranks a medley above the song, so the hits are
 * never taken in order: [bestHit] requires the same title, the same version (live, remix...), a shared
 * artist and a length within [DURATION_TOLERANCE_SECONDS]. The document of the chosen hit is
 * `https://lrc.red/s/<ISRC>.ttml`.
 */
object LrcRedLyricsProvider : LyricsProvider {
    override val name = "lrc.red"
    override val wantsFullTitle = true

    private const val BASE = "https://lrc.red/"
    internal const val DURATION_TOLERANCE_SECONDS = 4.0

    /** `CC-XXX-YY-NNNNN` without dashes; nothing else may end up in a path. */
    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcRedKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = find(title, artist, duration).map { it.text }

    override suspend fun fetch(
        context: Context,
        request: LyricsRequest,
    ): Result<LyricsHit> = find(request.title, request.artist, request.duration)

    private suspend fun find(
        title: String,
        artist: String,
        duration: Int,
    ): Result<LyricsHit> {
        val searchUrl =
            LyricsHttp.url("${BASE}search.json", "q" to "$title $artist".trim())
                ?: return Result.failure(IllegalStateException("Bad query"))
        val searchBody = LyricsHttp.get(searchUrl) ?: return Result.failure(IllegalStateException("No answer"))
        val hit =
            parseHits(searchBody)?.let { bestHit(it, title, artist, duration) }
                ?: return Result.failure(IllegalStateException("No matching track"))
        val documentUrl = documentUrl(hit.isrc) ?: return Result.failure(IllegalStateException("No document"))
        val ttml =
            LyricsHttp.get(documentUrl, accept = "application/xml, text/xml, */*")
                ?: return Result.failure(IllegalStateException("No document"))
        val lrc = LyricsConvert.ttmlToLrc(ttml) ?: return Result.failure(IllegalStateException("Lyrics unavailable"))
        val seconds = LyricsConvert.ttmlDurationSeconds(ttml) ?: hit.duration
        return Result.success(LyricsHit(lrc, hit.title, hit.artist, seconds))
    }

    internal fun documentUrl(isrc: String?): String? =
        isrc
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { ISRC.matches(it) }
            ?.let { "${BASE}s/$it.ttml" }

    internal fun parseHits(body: String): List<Hit>? =
        runCatching { json.decodeFromString<Response>(body) }.getOrNull()?.hits

    /** The hit that is the playing recording, or null when none is sure to be. Closest in length wins. */
    internal fun bestHit(
        hits: List<Hit>,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Hit? =
        hits
            .filter { hit ->
                documentUrl(hit.isrc) != null &&
                    LyricsHitMatch.isSameRecording(
                        hitTitle = hit.title,
                        hitArtist = hit.artist,
                        hitSeconds = hit.duration,
                        wantedTitle = title,
                        wantedArtist = artist,
                        wantedSeconds = durationSeconds,
                        toleranceSeconds = DURATION_TOLERANCE_SECONDS,
                    )
            }.minByOrNull { LyricsHitMatch.gap(it.duration, durationSeconds) }

    @Serializable
    internal data class Response(
        val hits: List<Hit>? = null,
    )

    @Serializable
    internal data class Hit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Seconds, fractional. */
        val duration: Double? = null,
    )
}
