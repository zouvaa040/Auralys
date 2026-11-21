package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicCardShelfRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

/**
 * What a section of the All view holds. It is worked out from the items (never from the section title,
 * which YouTube translates: "Songs" is "Lagu" with hl=id), so ranking and labels work in every language.
 */
enum class SearchSectionKind {
    TOP,
    SONGS,
    VIDEOS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
    PODCASTS,
    EPISODES,
    PROFILES,
    OTHER,
    ;

    companion object {
        fun infer(
            items: List<YTItem>,
            isTopResult: Boolean,
        ): SearchSectionKind {
            if (isTopResult) return TOP
            if (items.isEmpty()) return OTHER
            val songs = items.filterIsInstance<SongItem>()
            return when {
                items.all { it is EpisodeItem || (it is SongItem && it.isEpisode) } -> EPISODES
                items.all { it is PodcastItem } -> PODCASTS
                items.all { it is AlbumItem } -> ALBUMS
                items.all { it is ArtistItem && it.isProfile } -> PROFILES
                items.all { it is ArtistItem } -> ARTISTS
                items.all { it is PlaylistItem } -> PLAYLISTS
                songs.size == items.size ->
                    if (songs.count { it.looksLikeVideo } > songs.size / 2) VIDEOS else SONGS
                else -> OTHER
            }
        }
    }
}

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
    /** True for the section built from YouTube's "Top result" card (its title may be localised). */
    val isTopResult: Boolean = false,
    /** Kept by `copy(items = ...)`: a section stays a "songs" section after some rows were removed. */
    val kind: SearchSectionKind = SearchSectionKind.infer(items, isTopResult),
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    /**
     * "Songs only" view of the All results: no video uploads, medley guard for the top result.
     * See [SearchRanking.musicOnly].
     */
    fun musicOnly(query: String? = null) = SearchRanking.musicOnly(this, query)

    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    s.copy(
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterVideoSongs(disableVideos: Boolean) =
        if (disableVideos) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    s.copy(
                        items =
                            s.items.filterVideoSongs(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterYoutubeShorts(enabled: Boolean = false) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    s.copy(
                        items =
                            s.items.filterYoutubeShorts(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = PageHelper.extractArtists(renderer.subtitle.runs),
                        album =
                            subtitle?.getOrNull(2)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration =
                            subtitle
                                ?.lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        musicVideoType = renderer.onTap.musicVideoType,
                        thumbnail = renderer.thumbnail.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                        isWideThumb = renderer.thumbnail.isNotSquare(),
                        hasVideoLabel = SearchPage.isVideoLabel(subtitle?.firstOrNull()),
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MIX" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = PageHelper.extractArtists(renderer.subtitle.runs),
                        year = null,
                        thumbnail = renderer.thumbnail.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id =
                            renderer.onTap.browseEndpoint.browseId
                                .removePrefix("VL"),
                        title =
                            renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author = PlaylistPage.ownerFromByline(renderer.subtitle.runs),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        radioEndpoint = null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPodcastEndpoint == true -> {
                    PodcastItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author = PodcastPage.extractPodcastByline(renderer.subtitle.runs).firstOrNull(),
                        episodeCountText = null,
                        thumbnail = renderer.thumbnail.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint,
                    )
                }

                else -> null
            }
        }
    }
}
