/**
 * Auralys - the song cards of the home screen
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
import androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING
import androidx.media3.exoplayer.offline.Download.STATE_QUEUED
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalArtistNameAliases
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.LocalIosMotion
import com.metrolist.music.ui.component.iosAppear
import com.metrolist.music.ui.component.iosTap
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.component.rememberCoverTintOf
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------------------------------
// The rows: a row is ALL big cards or ALL old small grid items, never a mix of the two
// ---------------------------------------------------------------------------------------------------------------------

private val HomeBigCardWidth = 172.dp
private val HomeBigCardHeight = 216.dp

/** Fewest items a row needs to be laid out as big cards (below that the row is all old small grid items). */
internal const val BIG_ROW_MIN_ITEMS = 3

/** True when a row has enough items to be worth the big layout (every item of a big row is a big card, whatever its type). */
internal fun <T> bigRowUseful(items: List<T>): Boolean = items.size >= BIG_ROW_MIN_ITEMS

/**
 * A row where EVERY item is the big cover-melt card ([big]): songs, albums, playlists, artists, podcasts, episodes. [contentPadding]
 * is the row padding the grid items use (the cards add the same 12 dp of side padding the grid items carry, so the first card sits on
 * the same margin). While iOS animations are on, the cards rise in one after the other ([iosAppear]).
 */
@Composable
internal fun <T> HomeBigOnlyRow(
    items: List<T>,
    keyOf: (T) -> String,
    contentPadding: PaddingValues,
    big: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, contentPadding = contentPadding) {
        itemsIndexed(items, key = { _, item -> keyOf(item) }) { index, item ->
            Box(
                Modifier
                    .iosAppear(index)
                    .padding(start = 12.dp, top = 8.dp, end = if (index == items.lastIndex) 12.dp else 0.dp, bottom = 8.dp),
            ) {
                big(item)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The cards
// ---------------------------------------------------------------------------------------------------------------------

/** Press feel of a card: the iOS squash and bounce while iOS animations are on, the calm press otherwise. */
@Composable
private fun Modifier.homeCardPress(): Modifier = if (LocalIosMotion.current) iosTap(0.92f) else pressScale(0.97f)

/**
 * The big card (172 x 216 dp), for a song, an album, a playlist, an artist, a podcast or an episode: the cover melts into a panel of the cover's own
 * colour (same idea as the "Temuan hari ini" card, smaller), with the title and the subtitle on the panel. The playing song
 * shows the usual chip on its cover; a song that is not playing a small play disc. What opens instead of plays ([playable]
 * false: playlist, artist, podcast, episode) has no disc; an album gets the disc as a REAL button ([onPlayDisc]: what the old grid item's quick-play
 * button did; a tap elsewhere on the card still opens it, a long press on the disc opens the same menu). One brush and one
 * animated colour per card, nothing blurred.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeBigSongCardBody(
    coverUrl: String?,
    title: String,
    byline: String,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    playable: Boolean = true,
    onPlayDisc: (() -> Unit)? = null,
    badges: @Composable (Color) -> Unit = {},
) {
    val shape = RoundedCornerShape(22.dp)
    val tint = rememberCoverTintOf(coverUrl, ignoreMode = true)
    val panel by animateColorAsState(
        targetValue = tint?.let { lerp(it, Color.Black, 0.38f) } ?: Color(0xFF2A2A2E),
        animationSpec = tween(260),
        label = "homeBigCardPanel",
    )
    val soft = Color.White.copy(alpha = 0.74f)
    Column(
        modifier =
            modifier
                .size(HomeBigCardWidth, HomeBigCardHeight)
                .homeCardPress()
                .clip(shape)
                .background(panel)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ItemThumbnail(
                thumbnailUrl = coverUrl,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = RectangleShape,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, panel))),
            )
            if (!isActive && (playable || onPlayDisc != null)) {
                // 44 dp touch area around the 32 dp disc (the disc sits where it always did)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(44.dp)
                            .then(
                                if (onPlayDisc != null) {
                                    Modifier.clip(CircleShape).combinedClickable(onClick = onPlayDisc, onLongClick = onLongClick)
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.40f)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = if (onPlayDisc != null) stringResource(R.string.play) else null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                badges(soft)
                Text(
                    text = byline,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The big song card with the peek: a long press (after its haptic) lifts the card and opens a small menu next to it (PeekMenu.kt)
 * when [peek] is given and the card's place is known; otherwise the long press is [onLongClick], as before.
 */
@Composable
internal fun HomeBigSongCard(
    coverUrl: String?,
    title: String,
    byline: String,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    playable: Boolean = true,
    onPlayDisc: (() -> Unit)? = null,
    badges: @Composable (Color) -> Unit = {},
    peek: PeekSource? = null,
) {
    val host = rememberPeekHost(peek, RoundedCornerShape(22.dp))
    val longClick = rememberPeekLongClick(host, onLongClick)
    HomeBigSongCardBody(coverUrl, title, byline, isActive, isPlaying, onClick, longClick, modifier.peekAnchor(host), playable, onPlayDisc, badges)
    PeekOverlay(host, onCardClick = onClick) { liftedModifier, tap ->
        HomeBigSongCardBody(coverUrl, title, byline, isActive, isPlaying, tap, {}, liftedModifier, playable, null, badges)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// What the old grid item showed next to the song: liked, explicit, download state (same data, drawn small in [tint])
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeBadgeIcon(
    icon: Int,
    tint: Color,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(15.dp).padding(end = 2.dp),
    )
}

@Composable
internal fun HomeSongBadges(
    liked: Boolean,
    explicit: Boolean,
    downloadState: Int?,
    tint: Color,
) {
    if (liked) HomeBadgeIcon(R.drawable.favorite, tint)
    if (explicit) HomeBadgeIcon(R.drawable.explicit, tint)
    when (downloadState) {
        STATE_COMPLETED -> HomeBadgeIcon(R.drawable.offline, tint)
        STATE_QUEUED, STATE_DOWNLOADING ->
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                color = tint,
                modifier = Modifier.size(12.dp).padding(end = 2.dp),
            )
    }
}

/** The badges of a YouTube song (the same three the old grid item had: liked in the library, explicit, download). */
@Composable
internal fun HomeYtSongBadges(
    item: SongItem,
    tint: Color,
) {
    val database = LocalDatabase.current
    val song by produceState<Song?>(initialValue = null, item.id) {
        value = database.song(item.id).firstOrNull()
    }
    val download by LocalDownloadUtil.current.getDownload(item.id).collectAsStateWithLifecycle(initialValue = null)
    HomeSongBadges(liked = song?.song?.liked == true, explicit = item.explicit, downloadState = download?.state, tint = tint)
}

/** The badges of a song of the library (liked and download, as the old grid item). */
@Composable
internal fun HomeLocalSongBadges(
    song: Song,
    tint: Color,
) {
    val download by LocalDownloadUtil.current.getDownload(song.id).collectAsStateWithLifecycle(initialValue = null)
    HomeSongBadges(liked = song.song.liked, explicit = false, downloadState = download?.state, tint = tint)
}

/** "Artists - 3:45" of a YouTube song, with the artist names the listener renamed. */
@Composable
internal fun homeYtSongByline(item: SongItem): String {
    val aliases = LocalArtistNameAliases.current
    val separator = " ${stringResource(R.string.and)} "
    return joinByBullet(
        item.artists.joinToArtistString(separator) { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        makeTimeString(item.duration?.times(1000L)),
    )
}

/** "Artists - 3:45" of a song of the library. */
@Composable
internal fun homeLocalSongByline(song: Song): String {
    val aliases = LocalArtistNameAliases.current
    val separator = " ${stringResource(R.string.and)} "
    return joinByBullet(
        song.orderedArtists.joinToArtistString(separator) { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        makeTimeString(song.song.duration * 1000L),
    )
}

/** The badges of a YouTube album (what the old grid item had: in the library, explicit). */
@Composable
internal fun HomeYtAlbumBadges(
    item: AlbumItem,
    tint: Color,
) {
    val database = LocalDatabase.current
    val album by produceState<Album?>(initialValue = null, item.id) {
        value = database.album(item.id).firstOrNull()
    }
    HomeSongBadges(liked = album?.album?.bookmarkedAt != null, explicit = item.explicit, downloadState = null, tint = tint)
}

/** The badges of a YouTube playlist (explicit, as the old grid item). */
@Composable
internal fun HomeYtPlaylistBadges(
    item: PlaylistItem,
    tint: Color,
) {
    HomeSongBadges(liked = false, explicit = item.explicit, downloadState = null, tint = tint)
}

/** The badges of an album of the library: in the library, explicit, and the download state of all its songs (as the old grid item). */
@Composable
internal fun HomeLocalAlbumBadges(
    album: Album,
    tint: Color,
) {
    val downloadUtil = LocalDownloadUtil.current
    val database = LocalDatabase.current
    val songs by produceState<List<Song>>(initialValue = emptyList(), album.id) {
        withContext(Dispatchers.IO) {
            value = database.albumSongs(album.id).first()
        }
    }
    val allDownloads by downloadUtil.downloads.collectAsStateWithLifecycle()
    val downloadState =
        remember(songs, allDownloads) {
            if (songs.isEmpty()) {
                Download.STATE_STOPPED
            } else {
                when {
                    songs.all { allDownloads[it.id]?.state == STATE_COMPLETED } -> STATE_COMPLETED
                    songs.any { allDownloads[it.id]?.state in listOf(STATE_QUEUED, STATE_DOWNLOADING) } -> STATE_DOWNLOADING
                    else -> Download.STATE_STOPPED
                }
            }
        }
    HomeSongBadges(liked = album.album.bookmarkedAt != null, explicit = album.album.explicit, downloadState = downloadState, tint = tint)
}

/** The badge of an artist of the library: in the library (as the old grid item). */
@Composable
internal fun HomeLocalArtistBadges(
    artist: Artist,
    tint: Color,
) {
    HomeSongBadges(liked = artist.artist.bookmarkedAt != null, explicit = false, downloadState = null, tint = tint)
}

/** "Artists - 2024" of a YouTube album. */
@Composable
internal fun homeYtAlbumByline(item: AlbumItem): String {
    val aliases = LocalArtistNameAliases.current
    val separator = " ${stringResource(R.string.and)} "
    return joinByBullet(
        item.artists?.joinToArtistString(separator) { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        item.year?.toString(),
    )
}

/** "Author - 7 songs" of a YouTube playlist. */
@Composable
internal fun homeYtPlaylistByline(item: PlaylistItem): String {
    val aliases = LocalArtistNameAliases.current
    return joinByBullet(
        item.author?.let { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        item.songCountText,
    )
}

/** The artists of an album of the library. */
@Composable
internal fun homeLocalAlbumByline(album: Album): String =
    album.artists.joinToArtistString(" ${stringResource(R.string.and)} ") { it.name }

/** "7 lagu" of an artist of the library (nothing when it has no song). */
@Composable
internal fun homeLocalArtistByline(artist: Artist): String =
    if (artist.songCount > 0) pluralStringResource(R.plurals.n_song, artist.songCount, artist.songCount) else ""

/** The name of a YouTube artist, with the name the listener gave it. */
@Composable
internal fun homeYtArtistTitle(item: ArtistItem): String = ArtistNameAliases.resolve(LocalArtistNameAliases.current, item.id, item.title)

/** "Author - 12 episodes" of a YouTube podcast. */
@Composable
internal fun homeYtPodcastByline(item: PodcastItem): String {
    val aliases = LocalArtistNameAliases.current
    return joinByBullet(
        item.author?.let { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        item.episodeCountText,
    )
}

/** "Author - 45:10" of a YouTube podcast episode. */
@Composable
internal fun homeYtEpisodeByline(item: EpisodeItem): String {
    val aliases = LocalArtistNameAliases.current
    return joinByBullet(
        item.author?.let { ArtistNameAliases.resolve(aliases, it.id, it.name) },
        makeTimeString(item.duration?.times(1000L)),
    )
}
