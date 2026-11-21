/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 *
 * Finds the other recording of a song: the catalogue audio of a music video, or the music video of an audio
 * song. Idea from BitChord (GPLv3, resolveAudio / resolveVideo): a Songs- or Videos-filtered search plus a strict
 * matcher, never a guess.
 */

package com.metrolist.music.version

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.music.local.LocalMusic
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

fun SongItem.toTrackInfo() =
    TrackInfo(
        id = id,
        title = title,
        artists = artists.map { it.name },
        durationSec = duration?.takeIf { it > 0 },
        isVideo = looksLikeVideo,
        isOfficialVideo = musicVideoType == MUSIC_VIDEO_TYPE_OMV,
        isEpisode = isEpisode,
    )

fun MediaMetadata.toTrackInfo() =
    TrackInfo(
        id = id,
        title = title,
        artists = artists.map { it.name },
        durationSec = duration.takeIf { it > 0 },
        isVideo = isVideoSong,
        isOfficialVideo = musicVideoType == MUSIC_VIDEO_TYPE_OMV,
        isEpisode = isEpisode,
    )

/**
 * The other recording of a song. [isVideo] says what the counterpart is. [official] = found through YouTube's own link
 * (see [OfficialCounterpart]), not by a title search; [offsetMs] = the measured start of the counterpart in relation to
 * the source (counterpart position = source position + offset), null when it is not known.
 */
data class Counterpart(
    val item: SongItem,
    val isVideo: Boolean,
    val offsetMs: Long? = null,
    val official: Boolean = false,
)

/**
 * Looks up the counterpart with the app's search. Results (also "none found") are kept for the session; a
 * failed search is not kept, so it is tried again later. Nothing here throws except cancellation.
 */
class VersionResolver(
    private val search: suspend (query: String, filter: YouTube.SearchFilter) -> List<SongItem>? = ::searchSongs,
    private val searchTimeoutMs: Long = 6_000,
    private val maxEntries: Int = 300,
    /** Lives as long as the process: a lookup started for a tap goes on after the caller stopped waiting. */
    private val lookupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * YouTube's own link from an audio recording to its music video (one `next` request), asked BEFORE any title search.
     * Null (the default, so tests and fakes never touch the network) = no official lookup.
     */
    private val official: (suspend (String) -> Result<OfficialCounterpart?>)? = null,
    private val officialTimeoutMs: Long = 5_000,
    /** An answer of "there is none" is forgotten after this long (search results vary; a miss must not stick for the whole run). */
    private val noneTtlMs: Long = 30 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private inner class Entry(
        val counterpart: Counterpart?,
    ) {
        val at: Long = clock()

        /** A found counterpart stays; "none" expires. */
        fun fresh(): Boolean = counterpart != null || clock() - at < noneTtlMs
    }

    private val cache: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
            },
        )

    /** Offered at all? Never for device songs, episodes or items without a title and artist. */
    fun canOffer(source: TrackInfo): Boolean =
        !source.isEpisode &&
            source.id.isNotBlank() &&
            !LocalMusic.isLocalSongId(source.id) &&
            source.title.isNotBlank() &&
            source.artists.any { it.isNotBlank() }

    /** Already known (from an earlier lookup)? Never searches. `null` = unknown, [Known] = known, maybe empty. */
    fun peek(id: String): Known? = fresh(id)?.let { Known(it.counterpart) }

    private fun fresh(id: String): Entry? {
        val entry = cache[id] ?: return null
        if (entry.fresh()) return entry
        cache.remove(id)
        return null
    }

    class Known(
        val counterpart: Counterpart?,
    )

    /**
     * The counterpart of [source]: its catalogue audio when [source] is a video, otherwise its music video.
     * Null when there is none, when [source] is not eligible, or when the search failed.
     */
    suspend fun counterpartOf(source: TrackInfo): Counterpart? {
        if (!canOffer(source)) return null
        fresh(source.id)?.let { return it.counterpart }
        return try {
            val wantVideo = !source.isVideo
            val filter = if (wantVideo) YouTube.SearchFilter.FILTER_VIDEO else YouTube.SearchFilter.FILTER_SONG
            var anySearchWorked = false
            var found: SongItem? = null
            if (wantVideo && official != null) {
                val answer = withTimeoutOrNull(officialTimeoutMs) { official.invoke(source.id) }
                val link = answer?.getOrNull()
                if (link != null && link.song.id != source.id) {
                    val counterpart = Counterpart(link.song, isVideo = true, offsetMs = link.offsetMs, official = true)
                    cache[source.id] = Entry(counterpart)
                    return counterpart
                }
            }
            for (query in TrackMatcher.queries(source)) {
                val rows = withTimeoutOrNull(searchTimeoutMs) { search(query, filter) } ?: continue
                anySearchWorked = true
                found = pick(source, rows, wantVideo)
                if (found != null) break
            }
            if (found != null) {
                val counterpart = Counterpart(found, isVideo = wantVideo)
                cache[source.id] = Entry(counterpart)
                counterpart
            } else {
                if (anySearchWorked) cache[source.id] = Entry(null)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private val inFlight = HashMap<String, Deferred<Counterpart?>>()

    /**
     * Starts (or joins) the lookup of [source] in the resolver's own scope. Callers may stop waiting (timeout, menu
     * closed, screen recreated): the lookup goes on and its result is cached, so the next ask is instant. Awaiting
     * the returned value never cancels the lookup itself.
     */
    fun lookupInBackground(source: TrackInfo): Deferred<Counterpart?> {
        fresh(source.id)?.let { return CompletableDeferred(it.counterpart) }
        synchronized(inFlight) {
            inFlight[source.id]?.let { return it }
            val job = lookupScope.async { counterpartOf(source) }
            inFlight[source.id] = job
            job.invokeOnCompletion { synchronized(inFlight) { if (inFlight[source.id] === job) inFlight.remove(source.id) } }
            return job
        }
    }

    private fun pick(
        source: TrackInfo,
        rows: List<SongItem>,
        wantVideo: Boolean,
    ): SongItem? {
        val byId = rows.associateBy { it.id }
        // a music video may open with an intro the catalogue recording does not have: it may be longer by a few seconds
        val longer = if (wantVideo) com.metrolist.music.video.VideoEligibility.COUNTERPART_MAX_INTRO_SEC else TrackMatcher.MAX_DURATION_DIFF_SEC
        val best = TrackMatcher.best(source, rows.map { it.toTrackInfo() }, wantVideo, maxLongerSec = longer, titleNamesArtist = wantVideo) ?: return null
        return byId[best.id]
    }

    companion object {
        /**
         * The official link is only asked when the account is signed in: a signed-out `next` answer carries no counterpart
         * (measured on 2026-11-07, 4 of 4 songs, also with the radio playlist), so asking would cost a request for nothing.
         */
        val shared =
            VersionResolver(
                official = { id -> if (YouTube.cookie.isNullOrBlank()) Result.success(null) else OfficialCounterpartFetcher.fetch(id) },
            )

        suspend fun searchSongs(
            query: String,
            filter: YouTube.SearchFilter,
        ): List<SongItem>? =
            YouTube
                .search(query, filter, recordSearchHistory = false)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
    }
}
