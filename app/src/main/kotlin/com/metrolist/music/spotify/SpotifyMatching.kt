/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify is never played: every song of a Spotify list is matched to a YouTube Music song in the background, with the same
 * matcher as the link import (PlaylistTrackMatcher). The list shows at once from Spotify's own data; matches arrive a few at a
 * time. Matches are kept in memory for the session so reopening a list is instant. Flow adapted from BitChord's Spotify pages
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE.
 */

package com.metrolist.music.spotify

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.utils.ExternalTrack
import com.metrolist.music.utils.PlaylistTrackMatcher
import com.metrolist.music.viewmodels.PlaylistLinkImportViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** What is known about the YouTube Music version of one Spotify song. */
sealed interface SpotifyMatch {
    /** Not looked for yet (or being looked for). */
    object Pending : SpotifyMatch

    class Found(val song: SongItem) : SpotifyMatch

    /** Looked for and not found. */
    object Missing : SpotifyMatch
}

fun SpotifyTrack.toExternalTrack(): ExternalTrack = ExternalTrack(title = title, artists = artists, durationMs = durationMs.takeIf { it > 0 })

interface SpotifyMatchStore {
    /** A match found earlier, a miss not older than [LruSpotifyMatchStore.MISS_TTL_MS], or null. */
    fun get(trackId: String): SpotifyMatch?

    fun put(trackId: String, match: SpotifyMatch)
}

/**
 * Keeps up to [maxEntries] matches, least recently used out first. A miss is only remembered for [MISS_TTL_MS]: the matcher
 * also answers "not found" when YouTube was busy, and a song should get another try on a later visit.
 */
class LruSpotifyMatchStore(
    private val maxEntries: Int = 4000,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyMatchStore {
    private class Entry(val match: SpotifyMatch, val at: Long)

    private val map =
        object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
        }

    @Synchronized
    override fun get(trackId: String): SpotifyMatch? {
        val entry = map[trackId] ?: return null
        if (entry.match is SpotifyMatch.Missing && now() - entry.at > MISS_TTL_MS) {
            map.remove(trackId)
            return null
        }
        return entry.match
    }

    @Synchronized
    override fun put(trackId: String, match: SpotifyMatch) {
        if (match is SpotifyMatch.Pending) return
        map[trackId] = Entry(match, now())
    }

    /** Forgets the misses among [trackIds], so the next run looks for them again. */
    @Synchronized
    fun forgetMisses(trackIds: Collection<String>) {
        for (id in trackIds) if (map[id]?.match is SpotifyMatch.Missing) map.remove(id)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    companion object {
        const val MISS_TTL_MS = 10L * 60 * 1000
    }
}

/** The session's matches, shared by every Spotify list. Cleared when the account changes. */
object SpotifyMatchCache {
    private val store = LruSpotifyMatchStore()

    val shared: LruSpotifyMatchStore get() = store

    fun clear() = store.clear()
}

class SpotifyMatchRunner(
    private val matcher: suspend (ExternalTrack) -> SongItem? = { PlaylistTrackMatcher.match(it) },
    private val parallelism: Int = DEFAULT_PARALLELISM,
    /** A short rest after each search, like the link import: a long list must not hammer YouTube. */
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
) {
    /**
     * Matches every track that [store] does not know yet, in list order, [parallelism] at a time. [onResult] gets the index
     * and the result of each track as it becomes known (the stored ones first, at once). It runs on the matching coroutines,
     * several at the same time, so it has to be safe for that. Returns when every track has a result; cancelling the caller
     * stops it at once. A matcher that throws counts as "not found" for that run but is not remembered.
     */
    suspend fun run(
        tracks: List<SpotifyTrack>,
        store: SpotifyMatchStore,
        onResult: (index: Int, match: SpotifyMatch) -> Unit,
    ) {
        val todo = ArrayList<Int>()
        tracks.forEachIndexed { index, track ->
            val known = store.get(track.id)
            if (known != null) onResult(index, known) else todo += index
        }
        if (todo.isEmpty()) return
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(minOf(parallelism.coerceAtLeast(1), todo.size)) {
                launch {
                    while (true) {
                        val slot = next.getAndIncrement()
                        if (slot >= todo.size) break
                        val index = todo[slot]
                        val track = tracks[index]
                        ensureActive()
                        val result =
                            try {
                                val song = matcher(track.toExternalTrack())
                                (if (song != null) SpotifyMatch.Found(song) else SpotifyMatch.Missing).also { store.put(track.id, it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                SpotifyMatch.Missing
                            }
                        onResult(index, result)
                        if (pauseMs > 0) delay(pauseMs)
                    }
                }
            }
        }
    }

    companion object {
        /** The same limits as the link import (PlaylistLinkImportViewModel): 3 searches at a time and a short pause after each. */
        const val DEFAULT_PARALLELISM = PlaylistLinkImportViewModel.MAX_PARALLEL
        const val DEFAULT_PAUSE_MS = PlaylistLinkImportViewModel.REQUEST_PAUSE_MS
    }
}
