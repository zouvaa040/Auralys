/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 *
 * The OFFICIAL link between the audio recording of a song and its music video: YouTube Music's watch panel (`next`) wraps
 * a song that has a video in a `playlistPanelVideoWrapperRenderer` with the other recording as its `counterpart` (this is
 * what the Song | Video toggle of YouTube Music uses). The innertube models of the app do not read that wrapper, so this
 * file reads the raw answer itself. It is used before any title search: a title search can only guess, this is the link
 * YouTube itself draws. When the wrapper also carries a `segmentMap`, the offset between the two recordings is MEASURED
 * (no assumption about an intro).
 *
 * Everything here is tolerant: a missing or renamed field gives null (the caller falls back to the title search), never
 * an exception. The parser is pure (a string in, a value out) and unit tested with a sample answer.
 */

package com.metrolist.music.version

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.utils.parseTime
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The other recording of an audio id as YouTube Music itself names it. [song] is the counterpart (a music video when the
 * asked id is the catalogue audio). [offsetMs] is the measured start of the counterpart in relation to the asked id (the
 * counterpart position = the asked position + [offsetMs]); null when the answer had no usable segment map.
 */
data class OfficialCounterpart(
    val song: SongItem,
    val offsetMs: Long?,
    /** How many segments the answer's map had (more than one: the offset is only exact for the first part). */
    val segments: Int,
)

object OfficialCounterpartParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** An offset beyond this is not an intro, it is a different cut: the measured value is ignored. */
    const val MAX_ABS_OFFSET_MS = 180_000L

    /**
     * Reads the raw `next` answer for [askedId]. Returns the counterpart, or null when the answer names none (the song
     * has no video counterpart, or the answer has another shape).
     */
    fun parse(
        body: String,
        askedId: String,
    ): OfficialCounterpart? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val panel =
            root
                .path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
                .array()
                ?.firstOrNull()
                .obj()
                ?.path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents")
                .array()
                ?: return null
        for (entry in panel) {
            val wrapper = entry.obj()?.get("playlistPanelVideoWrapperRenderer").obj() ?: continue
            val primary = wrapper.path("primaryRenderer", "playlistPanelVideoRenderer").obj() ?: continue
            val first = wrapper["counterpart"].array()?.firstOrNull().obj() ?: continue
            val other = first.path("counterpartRenderer", "playlistPanelVideoRenderer").obj() ?: continue
            val primaryId = primary.videoId() ?: continue
            val otherId = other.videoId() ?: continue
            // the entry of the asked song (the panel may hold other songs, which are not ours)
            val askedIsPrimary = primaryId == askedId
            if (!askedIsPrimary && otherId != askedId) continue
            val wanted = if (askedIsPrimary) other else primary
            val song = wanted.toSongItem() ?: continue
            val segments = first.path("segmentMap", "segment").array().orEmpty()
            val offset = offsetOf(segments, askedIsPrimary)
            return OfficialCounterpart(song, offset, segments.size)
        }
        return null
    }

    /** The start of the wanted recording in relation to the asked one, from the first segment; null without a usable one. */
    internal fun offsetOf(
        segments: List<JsonElement>,
        askedIsPrimary: Boolean,
    ): Long? {
        val s = segments.firstOrNull().obj() ?: return null
        val primaryStart = s["primaryVideoStartTimeMilliseconds"].long() ?: return null
        val counterpartStart = s["counterpartVideoStartTimeMilliseconds"].long() ?: return null
        val offset = if (askedIsPrimary) counterpartStart - primaryStart else primaryStart - counterpartStart
        return offset.takeIf { kotlin.math.abs(it) <= MAX_ABS_OFFSET_MS }
    }

    private fun JsonObject.videoId(): String? =
        (get("videoId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: path("navigationEndpoint", "watchEndpoint", "videoId").str()?.takeIf { it.isNotBlank() }

    private fun JsonObject.toSongItem(): SongItem? {
        val id = videoId() ?: return null
        val title = path("title", "runs").array()?.firstOrNull().obj()?.get("text").str()?.takeIf { it.isNotBlank() } ?: return null
        val seconds = path("lengthText", "runs").array()?.firstOrNull().obj()?.get("text").str()?.parseTime()
        val runs = path("longBylineText", "runs").array().orEmpty().mapNotNull { it.obj() }
        val artists = artistsOf(runs)
        val type = path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str()
        val thumb =
            path("thumbnail", "thumbnails")
                .array()
                ?.lastOrNull()
                .obj()
                ?.get("url")
                .str()
                .orEmpty()
        return SongItem(
            id = id,
            title = title,
            artists = artists,
            duration = seconds,
            musicVideoType = type,
            thumbnail = thumb,
        )
    }

    /** The credited artists: the runs before the first " • ", each one with a channel link, else the plain names. */
    private fun artistsOf(runs: List<JsonObject>): List<Artist> {
        val head = ArrayList<JsonObject>()
        for (run in runs) {
            if (run["text"].str()?.trim() == "•") break
            head += run
        }
        val linked =
            head.mapNotNull { run ->
                val name = run["text"].str()?.trim().orEmpty()
                val id = run.path("navigationEndpoint", "browseEndpoint", "browseId").str()
                if (name.isNotEmpty() && id != null && id.startsWith("UC")) Artist(name, id) else null
            }
        if (linked.isNotEmpty()) return linked
        val joined = head.joinToString("") { it["text"].str().orEmpty() }
        return joined
            .split(Regex("\\s*(?:,|&|\\band\\b)\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Artist(it, null) }
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.array(): JsonArray? = this as? JsonArray

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

    private fun JsonObject.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
        return current
    }
}

/** The network side: one `next` request. Never throws except cancellation. */
object OfficialCounterpartFetcher {
    suspend fun fetch(askedId: String): Result<OfficialCounterpart?> =
        try {
            val response = YouTube.extractionTransport().innerTube.next(YouTubeClient.WEB_REMIX, videoId = askedId)
            Result.success(OfficialCounterpartParser.parse(response.bodyAsText(), askedId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
}
