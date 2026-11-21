package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class HistoryPage(
    val sections: List<HistorySection>?,
) {
    data class HistorySection(
        val title: String,
        val songs: List<SongItem>
    )

    companion object {
        /**
         * One response of the history page, the first page or a continuation, whichever shape YouTube Music answers
         * with: the section list (day shelves), a bare shelf continuation or appended items. Nothing here throws on a
         * missing field; a shelf without a title is not a day, so its songs go to the section before it.
         */
        fun parseChunk(response: BrowseResponse): HistoryChunk {
            val firstList =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val listContinuation = response.continuationContents?.sectionListContinuation
            val shelfContinuation = response.continuationContents?.musicShelfContinuation
            val appended =
                response.onResponseReceivedActions
                    ?.flatMap { it.appendContinuationItemsAction?.continuationItems.orEmpty() }
                    .orEmpty()

            val shelves = (firstList?.contents ?: listContinuation?.contents).orEmpty().mapNotNull { it.musicShelfRenderer }
            val sections = ArrayList<HistorySection>()
            val moreSongs = ArrayList<SongItem>()
            var lastShelf: MusicShelfRenderer? = null
            for (shelf in shelves) {
                val songs = songsOf(shelf)
                val title = shelf.title?.runs?.firstOrNull()?.text?.takeIf { it.isNotBlank() }
                if (title != null) {
                    sections += HistorySection(title, songs)
                } else if (sections.isNotEmpty()) {
                    sections[sections.lastIndex] = sections.last().let { it.copy(songs = it.songs + songs) }
                } else {
                    moreSongs += songs
                }
                lastShelf = shelf
            }
            if (shelfContinuation != null) {
                moreSongs += songsOf(shelfContinuation)
                lastShelf = shelfContinuation
            }
            if (appended.isNotEmpty()) {
                moreSongs += appended.getItems().mapNotNull { fromMusicResponsiveListItemRenderer(it) }
            }

            val sectionToken = (firstList?.continuations ?: listContinuation?.continuations)?.getContinuation()
            val shelfToken =
                lastShelf?.continuations?.getContinuation()
                    ?: lastShelf?.contents?.getContinuation()
                    ?: appended.getContinuation()
            return HistoryChunk(
                sections = sections,
                moreSongs = moreSongs,
                sectionContinuation = sectionToken,
                shelfContinuation = shelfToken,
            )
        }

        private fun songsOf(renderer: MusicShelfRenderer): List<SongItem> =
            renderer.contents?.getItems()?.mapNotNull { fromMusicResponsiveListItemRenderer(it) }.orEmpty()

        fun fromMusicShelfRenderer(renderer: MusicShelfRenderer): HistorySection {
            return HistorySection(
                title = renderer.title?.runs?.firstOrNull()?.text!!,
                songs = renderer.contents?.getItems()?.mapNotNull {
                    fromMusicResponsiveListItemRenderer(it)
                }!!
            )
        }

        private fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            // Split the secondary line by bullet separator to separate artists from other metadata (like views)
            val secondaryLineRuns = renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()

            return SongItem(
                id = renderer.videoId ?: return null,
                title =
                    renderer.flexColumns
                        .firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: return null,
                artists = PageHelper.extractArtists(secondaryLineRuns?.firstOrNull()),
                album =
                    renderer.flexColumns.getOrNull(3)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null,
                        )
                    },
                duration =
                    renderer.fixedColumns
                        ?.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime(),
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
                historyRemoveToken = renderer.menu?.menuRenderer?.items?.find {
                    it.menuServiceItemRenderer?.icon?.iconType == "REMOVE_FROM_HISTORY"
                }?.menuServiceItemRenderer?.serviceEndpoint?.feedbackEndpoint?.feedbackToken,
                isEpisode = renderer.isEpisode
            )
        }
    }
}

/**
 * A piece of the history page: the day sections it brought, the songs that continue the last section of the piece
 * before it (a continuation with no title) and the tokens for the next requests. Both tokens are null at the end.
 */
data class HistoryChunk(
    val sections: List<HistoryPage.HistorySection>,
    val moreSongs: List<SongItem>,
    /** The next day sections. */
    val sectionContinuation: String?,
    /** More songs of the last section. */
    val shelfContinuation: String?,
)
