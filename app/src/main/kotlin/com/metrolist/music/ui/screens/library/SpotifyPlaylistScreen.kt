/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * One playlist (or Liked Songs) of the connected Spotify account. The songs show at once from Spotify's own data and are
 * matched to YouTube Music in the background; a matched song plays (Spotify itself is never played), one that was not found
 * is dimmed and skipped by the queue. "Simpan ke Pustaka" makes a normal local playlist from the matched songs.
 */

package com.metrolist.music.ui.screens.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailCornerRadius
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.spotify.SpotifyLoad
import com.metrolist.music.spotify.SpotifyMatch
import com.metrolist.music.spotify.SpotifyPlaylistUi
import com.metrolist.music.spotify.SpotifyPlaylistViewModel
import com.metrolist.music.spotify.SpotifyRow
import com.metrolist.music.spotify.SpotifySaveState
import com.metrolist.music.spotify.SpotifySession
import com.metrolist.music.spotify.matchedQueue
import com.metrolist.music.ui.component.CircularProgressIndicator
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.RowMoreButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpotifyPlaylistScreen(
    navController: NavController,
    viewModel: SpotifyPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by SpotifySession.connected.collectAsStateWithLifecycle()
    LaunchedEffect(connected) { if (!connected) navController.popBackStack() }

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val title = state.title ?: stringResource(if (viewModel.isLiked) R.string.spotify_liked else R.string.spotify_title)

    val savedText = stringResource(R.string.spotify_saved)
    val saveFailedText = stringResource(R.string.spotify_save_failed)
    LaunchedEffect(state.save) {
        when (state.save) {
            SpotifySaveState.SAVED -> Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
            SpotifySaveState.FAILED -> Toast.makeText(context, saveFailedText, Toast.LENGTH_SHORT).show()
            else -> Unit
        }
    }

    // [startRow] null = from the top ("Putar"); [shuffled] = "Acak". Only the songs matched so far are in the queue.
    fun play(startRow: Int?, shuffled: Boolean) {
        val queue = matchedQueue(state.rows, startRow)
        if (queue.songs.isEmpty()) return
        scope.launch {
            val items = withContext(Dispatchers.Default) { queue.songs.map { it.toMediaItem() } }
            val toPlay =
                when {
                    shuffled -> ListQueue.shuffled(title = title, items = items)
                    startRow == null -> ListQueue(title = title, items = items, shuffle = false)
                    else -> ListQueue(title = title, items = items, startIndex = queue.startIndex)
                }
            playerConnection.playQueue(toPlay)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val failed = state.load as? SpotifyLoad.Failed
        if (failed != null) {
            SpotifyErrorBlock(
                kind = failed.kind,
                onRetry = viewModel::load,
                onReconnect = { navController.navigate("spotify_login") },
            )
        } else {
            LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                item(key = "header") {
                    PlaylistHeader(
                        state = state,
                        title = title,
                        onPlay = { play(null, shuffled = false) },
                        onShuffle = { play(null, shuffled = true) },
                        onSave = viewModel::save,
                        onOpenSaved = { id -> navController.navigate("local_playlist/$id") },
                        onMatchAgain = viewModel::retryMissing,
                    )
                }
                if (state.load is SpotifyLoad.Loading && state.rows.isEmpty()) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.load is SpotifyLoad.Ready && state.rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.spotify_empty_playlist),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                        )
                    }
                }
                // the same song can be in a list twice, so the row's position is its key
                itemsIndexed(state.rows, key = { index, _ -> index }) { index, row ->
                    val song = row.song
                    TrackRow(
                        row = row,
                        isActive = song != null && song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        onClick = {
                            if (song != null) {
                                if (song.id == mediaMetadata?.id) playerConnection.togglePlayPause() else play(index, shuffled = false)
                            }
                        },
                        onMore = { if (song != null) menuState.show { YouTubeSongMenu(song = song, onDismiss = menuState::dismiss) } },
                    )
                }
            }
        }

        TopAppBar(
            title = { Text(text = title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
    }
}

@Composable
private fun TrackRow(
    row: SpotifyRow,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val song = row.song
    val subtitle =
        when {
            song != null -> song.artists.joinToString(", ") { it.name }
            row.match is SpotifyMatch.Missing -> stringResource(R.string.spotify_not_found)
            else -> row.track.artists.joinToString(", ")
        }
    // a song still being looked for is a little dimmed, one that was not found more so; neither can be played
    val alpha =
        when (row.match) {
            is SpotifyMatch.Found -> 1f
            SpotifyMatch.Pending -> 0.6f
            SpotifyMatch.Missing -> 0.4f
        }
    ListItem(
        title = song?.title ?: row.track.title,
        subtitle = subtitle,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = song?.thumbnail ?: row.track.imageUrl,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = RoundedCornerShape(ListThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = { if (song != null) RowMoreButton(onClick = onMore) },
        isActive = isActive,
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .then(if (song != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun PlaylistHeader(
    state: SpotifyPlaylistUi,
    title: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (String) -> Unit,
    onMatchAgain: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(
                model = state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                state.owner?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.total > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.n_song, state.total, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        // matching progress, or what was not found
        if (state.rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            if (state.matching) {
                Text(
                    text = stringResource(R.string.spotify_matching, state.processed, state.rows.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else if (state.missing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.spotify_missing_count, state.missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onMatchAgain) { Text(stringResource(R.string.spotify_match_again)) }
                }
            }
            if (state.truncated) {
                Text(
                    text = stringResource(R.string.spotify_truncated, state.rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        val canPlay = state.matched > 0
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                onClick = onPlay,
                enabled = canPlay,
                color = scheme.primary,
                shape = CircleShape,
                modifier = Modifier.weight(1f).height(48.dp).pressScale(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(text = stringResource(R.string.play), color = scheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(
                onClick = onShuffle,
                enabled = canPlay,
                color = Color.Transparent,
                shape = CircleShape,
                modifier = Modifier.size(48.dp).pressScale().glass(CircleShape),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = stringResource(R.string.shuffle),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val saved = state.save == SpotifySaveState.SAVED && state.savedPlaylistId != null
        val canSave = !state.matching && state.matched > 0 && state.save != SpotifySaveState.SAVING
        Surface(
            onClick = { if (saved) onOpenSaved(state.savedPlaylistId.orEmpty()) else onSave() },
            enabled = saved || canSave,
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp).pressScale().glass(CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text =
                        stringResource(
                            when {
                                saved -> R.string.spotify_open_saved
                                state.save == SpotifySaveState.SAVING -> R.string.spotify_saving
                                else -> R.string.spotify_save
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
