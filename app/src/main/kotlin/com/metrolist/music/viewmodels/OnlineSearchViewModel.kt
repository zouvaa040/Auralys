/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchRanking
import com.metrolist.innertube.pages.SearchSectionKind
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.SearchMusicOnlyKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.TimedLruCache
import com.metrolist.music.utils.putIfComplete
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import com.metrolist.music.version.TrackMatcher
import com.metrolist.music.version.toTrackInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Finished searches of the last minutes, so coming back to a search shows it at once. */
object SearchResultCache {
    val summaries = TimedLruCache<String, SearchSummaryPage>(maxEntries = 8, ttlMs = 5 * 60_000L)
    val filtered = TimedLruCache<String, ItemsPage>(maxEntries = 12, ttlMs = 5 * 60_000L)
}

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    /** Items with artist ids and lengths filled in; [complete] is false when a call failed and data may be missing. */
    private class Resolved(
        val items: List<YTItem>,
        val complete: Boolean,
    )

    private suspend fun resolveSearchMetadata(items: List<YTItem>): Resolved =
        coroutineScope {
            val knownDurations =
                items
                    .filterIsInstance<SongItem>()
                    .mapNotNull { song -> song.duration?.let { song.id to it } }
                    .toMap()
            val missingDurationIds =
                items
                    .filterIsInstance<SongItem>()
                    .filter { it.duration == null && it.id !in knownDurations }
                    .map { it.id }
                    .distinct()
            val resolvedArtists = async { YouTube.resolveArtistIds(items) }
            val fetchedDurations =
                async {
                    if (missingDurationIds.isEmpty()) {
                        emptyMap<String, Int>() to true
                    } else {
                        val queued = YouTube.queue(videoIds = missingDurationIds)
                        queued
                            .getOrDefault(emptyList())
                            .mapNotNull { song -> song.duration?.let { song.id to it } }
                            .toMap() to queued.isSuccess
                    }
                }
            val (fetched, fetchedOk) = fetchedDurations.await()
            val durations = knownDurations + fetched

            Resolved(
                resolvedArtists.await().map { item ->
                    if (item is SongItem && item.duration == null) {
                        item.copy(duration = durations[item.id])
                    } else {
                        item
                    }
                },
                complete = fetchedOk,
            )
        }

    /**
     * When the top card is a music video, the catalogue song that is the same recording leads
     * (title, version and artist decide; the length is ignored because a video may carry an intro).
     */
    private val sameRecording: (SongItem, SongItem) -> Boolean = { video, song ->
        TrackMatcher.sameRecording(video.toTrackInfo(), song.toTrackInfo(), strictDuration = false)
    }

    /** The search settings, read once per search without blocking the main thread. */
    private suspend fun readFlags(): SearchFlags {
        val prefs = context.dataStore.data.first()
        return SearchFlags(
            hideExplicit = prefs[HideExplicitKey] ?: false,
            hideVideoSongs = prefs[HideVideoSongsKey] ?: false,
            hideYoutubeShorts = prefs[HideYoutubeShortsKey] ?: false,
            musicOnly = prefs[SearchMusicOnlyKey] ?: true,
            pauseHistory = prefs[PauseSearchHistoryKey] ?: false,
        )
    }

    private suspend fun loadSummaryPage() {
        if (summaryPage != null) return
        val flags = readFlags()
        val cacheKey = "all|$query|${flags.cacheKey}"
        SearchResultCache.summaries[cacheKey]?.let {
            summaryPage = it
            return
        }
        YouTube
            .searchSummary(query, recordSearchHistory = !flags.pauseHistory)
            .onSuccess { page ->
                val filtered =
                    page
                        .filterExplicit(flags.hideExplicit)
                        .filterVideoSongs(flags.hideVideoSongs)
                        .filterYoutubeShorts(flags.hideYoutubeShorts)
                // First paint: nothing slow happens before the list is shown.
                val firstPaint = SearchRanking.arrange(filtered, query, flags.musicOnly, sameRecording)
                summaryPage = firstPaint
                // Second pass (artist ids, missing lengths, the artist row of the top song): republish when done.
                // Only a complete result is cached; a failed or partial one is shown but looked up again next time.
                viewModelScope.launch {
                    val (enriched, complete) = enrichSummary(firstPaint, flags.musicOnly)
                    summaryPage = enriched
                    SearchResultCache.summaries.putIfComplete(cacheKey, enriched, complete)
                }
            }.onFailure {
                reportException(it)
            }
    }

    /** The page with its metadata filled in, and whether every lookup finished (false = do not cache it). */
    private suspend fun enrichSummary(
        page: SearchSummaryPage,
        musicOnly: Boolean,
    ): Pair<SearchSummaryPage, Boolean> =
        try {
            coroutineScope {
                val artistRow =
                    async {
                        val missing = SearchRanking.missingTopArtist(page) ?: return@async null to true
                        val answer =
                            withTimeoutOrNull(4_000) {
                                YouTube.search(missing.name, YouTube.SearchFilter.FILTER_ARTIST)
                            }
                        // timeout or failed search = unknown, not "there is no such artist"
                        val result = answer?.getOrNull() ?: return@async null to false
                        result.items
                            .filterIsInstance<ArtistItem>()
                            .firstOrNull { SearchRanking.isArtist(it, missing) } to true
                    }
                val metadata = resolveSearchMetadata(page.summaries.flatMap { it.items })
                var offset = 0
                val resolved =
                    page.summaries.map { summary ->
                        val next = offset + summary.items.size
                        val out = summary.copy(items = metadata.items.subList(offset, next))
                        offset = next
                        out
                    }
                val (artist, artistDone) = artistRow.await()
                SearchRanking.arrange(page.copy(summaries = resolved), query, musicOnly, sameRecording, artist) to
                    (metadata.complete && artistDone)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            page to false
        }

    /** Fills artist ids and lengths into a result list that is already on screen (items keep their place). */
    private suspend fun enrichFiltered(
        key: String,
        cacheKey: String,
    ) {
        val current = viewStateMap[key] ?: return
        val metadata =
            try {
                resolveSearchMetadata(current.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                return
            }
        val resolved = metadata.items.associateBy { it.id }
        val latest = viewStateMap[key] ?: return
        val updated = latest.copy(items = latest.items.map { resolved[it.id] ?: it })
        viewStateMap[key] = updated
        SearchResultCache.filtered.putIfComplete(cacheKey, updated, metadata.complete)
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the episodes section from the summary page instead (found by its items,
                    // not by its title, which YouTube translates).
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.kind == SearchSectionKind.EPISODES }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        val flags = readFlags()
                        val cacheKey = "${filter.value}|$query|${flags.cacheKey}"
                        val cached = SearchResultCache.filtered[cacheKey]
                        if (cached != null) {
                            viewStateMap[filter.value] = cached
                        } else {
                            YouTube
                                .search(query, filter, recordSearchHistory = !flags.pauseHistory)
                                .onSuccess { result ->
                                    val songsMusicOnly =
                                        filter == YouTube.SearchFilter.FILTER_SONG && flags.musicOnly
                                    // First paint straight from the response, the metadata follows.
                                    viewStateMap[filter.value] =
                                        ItemsPage(
                                            result.items
                                                .distinctBy { it.id }
                                                .filterExplicit(flags.hideExplicit)
                                                .filterVideoSongs(flags.hideVideoSongs)
                                                .filterYoutubeShorts(flags.hideYoutubeShorts)
                                                .let { if (songsMusicOnly) SearchRanking.musicOnly(it) else it },
                                            result.continuation,
                                        )
                                    val key = filter.value
                                    viewModelScope.launch { enrichFiltered(key, cacheKey) }
                                }.onFailure {
                                    reportException(it)
                                }
                        }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val currentFilter = filter.value
        val filterValue = currentFilter?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val flags = readFlags()
            val searchResult =
                YouTube.searchContinuation(
                    continuation,
                    recordSearchHistory = !flags.pauseHistory,
                ).getOrNull() ?: return@launch
            val resolvedItems = resolveSearchMetadata(searchResult.items).items
            val songsMusicOnly = currentFilter == YouTube.SearchFilter.FILTER_SONG && flags.musicOnly
            val newItems = resolvedItems
                .filterExplicit(flags.hideExplicit)
                .filterVideoSongs(flags.hideVideoSongs)
                .filterYoutubeShorts(flags.hideYoutubeShorts)
                .let { if (songsMusicOnly) SearchRanking.musicOnly(it) else it }
            // The list may have been enriched while this page loaded: build on the latest state, not the old copy.
            val latest = viewStateMap[filterValue] ?: viewState
            viewStateMap[filterValue] = ItemsPage(
                (latest.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }
}

/** The search settings of one search. [cacheKey] separates results that were filtered differently. */
data class SearchFlags(
    val hideExplicit: Boolean,
    val hideVideoSongs: Boolean,
    val hideYoutubeShorts: Boolean,
    val musicOnly: Boolean,
    val pauseHistory: Boolean,
) {
    val cacheKey: String get() = listOf(hideExplicit, hideVideoSongs, hideYoutubeShorts, musicOnly).joinToString(",")
}
