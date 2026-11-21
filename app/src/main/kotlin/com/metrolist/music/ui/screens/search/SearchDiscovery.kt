/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * What the Search tab shows before you type: your recent plays, what you play most, your favourite artists and a
 * "browse all" wall of genre tiles, in the spirit of Spotify's search page. Everything but the tiles comes from the
 * local listening history, so it is instant and works offline.
 */

package com.metrolist.music.ui.screens.search

import com.metrolist.music.ui.theme.HorizonRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.component.iosAppear
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class SearchDiscoveryViewModel
    @Inject
    constructor(
        database: MusicDatabase,
    ) : ViewModel() {
        val recent = database.recentlyPlayedSongs(14).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        val top = database.mostPlayedSongs(LocalDateTime.now().minusDays(45), limit = 6).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        val artists = database.mostPlayedArtists(LocalDateTime.now().minusDays(90), limit = 10).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

/** A genre shortcut: the label and the search it runs. No colour of its own: colour belongs to covers. */
private data class GenreTile(val label: String, val query: String)

private val genreTiles =
    listOf(
        GenreTile("Pop", "lagu pop terbaru"),
        GenreTile("Dangdut", "dangdut terbaru"),
        GenreTile("Hip Hop", "hip hop indonesia"),
        GenreTile("Rock", "rock indonesia"),
        GenreTile("Indie", "indie indonesia"),
        GenreTile("Santai", "lagu santai"),
        GenreTile("Semangat", "lagu semangat"),
        GenreTile("Galau", "lagu galau"),
        GenreTile("Akustik", "akustik cover"),
        GenreTile("K-Pop", "kpop"),
        GenreTile("Jazz", "jazz santai"),
        GenreTile("Koplo", "koplo terbaru"),
        GenreTile("Fokus", "musik fokus belajar"),
        GenreTile("Tidur", "musik tidur"),
    )

@Composable
fun SearchDiscovery(
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val top by viewModel.top.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()

    SearchDiscoveryContent(
        recent = recent,
        top = top,
        artists = artists,
        onPlay = { list, index ->
            playerConnection?.playQueue(ListQueue(title = "", items = list.map { it.toMediaItem() }, startIndex = index))
        },
        onArtist = { id ->
            navController.navigate("artist/$id")
            onClose()
        },
        onSearch = onSearch,
        modifier = modifier,
    )
}

/** The page under an empty search field, without any state of its own (so it can be drawn in a test). */
@Composable
fun SearchDiscoveryContent(
    recent: List<Song>,
    top: List<Song>,
    artists: List<com.metrolist.music.db.entities.Artist>,
    onPlay: (List<Song>, Int) -> Unit,
    onArtist: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (recent.isNotEmpty()) {
            Section(stringResource(R.string.search_recent_plays), index = 0) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { "r_${it.id}" }) { song ->
                        Column(
                            Modifier.width(140.dp).pressScale(0.96f).clickable { onPlay(recent, recent.indexOf(song)) },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(HorizonRadius.Lg)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (top.isNotEmpty()) {
            Section(stringResource(R.string.search_most_played), index = 1) {
                Column(Modifier.fillMaxWidth()) {
                    top.forEachIndexed { index, song ->
                        Row(
                            Modifier.fillMaxWidth().pressScale(0.985f).clickable { onPlay(top, index) }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(HorizonRadius.Sm)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    song.artists.joinToString { it.name },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            Section(stringResource(R.string.search_favorite_artists), index = 2) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(artists, key = { "a_${it.id}" }) { artist ->
                        Column(
                            Modifier.width(96.dp).pressScale(0.95f).clickable { onArtist(artist.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AsyncImage(
                                model = artist.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Text(artist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.search_browse_all), index = 3) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                genreTiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { g ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(HorizonRadius.Lg))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .pressScale(0.97f)
                                    .clickable { onSearch(g.query) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    g.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(
    title: String,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    // iOS animations: the sections rise in one after the other (no-op while they are off)
    Column(modifier = Modifier.iosAppear(index + 1, limit = 8), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        content()
    }
}
