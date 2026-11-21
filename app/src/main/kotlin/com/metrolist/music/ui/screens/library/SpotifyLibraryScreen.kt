/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Spotify" in the Library's Collections: the connected account's playlists, Liked Songs first. A tap opens one
 * (SpotifyPlaylistScreen). Shown only while an account is connected.
 */

package com.metrolist.music.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailCornerRadius
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.spotify.SPOTIFY_LIKED_ID
import com.metrolist.music.spotify.SpotifyException
import com.metrolist.music.spotify.SpotifyLibraryViewModel
import com.metrolist.music.spotify.SpotifyLoad
import com.metrolist.music.spotify.SpotifySession
import com.metrolist.music.ui.component.CircularProgressIndicator
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.utils.backToMain

/** The plain-words message for a failed Spotify call. */
@StringRes
internal fun SpotifyException.Kind.messageRes(): Int =
    when (this) {
        SpotifyException.Kind.NOT_CONNECTED, SpotifyException.Kind.SESSION_EXPIRED -> R.string.spotify_err_expired
        SpotifyException.Kind.TOKEN_FAILED -> R.string.spotify_err_token
        SpotifyException.Kind.RATE_LIMITED -> R.string.spotify_err_rate
        SpotifyException.Kind.API_CHANGED -> R.string.spotify_err_changed
        SpotifyException.Kind.NETWORK -> R.string.spotify_err_network
        SpotifyException.Kind.OTHER -> R.string.spotify_err_other
    }

/** The sign-in is what has to be redone for these; for the rest "try again" is the only thing to offer. */
internal fun SpotifyException.Kind.needsSignIn(): Boolean =
    this == SpotifyException.Kind.NOT_CONNECTED || this == SpotifyException.Kind.SESSION_EXPIRED

/** A failed load: one plain line and the one button that helps (connect again, or retry). */
@Composable
internal fun SpotifyErrorBlock(
    kind: SpotifyException.Kind,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(kind.messageRes()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = if (kind.needsSignIn()) onReconnect else onRetry) {
            Text(stringResource(if (kind.needsSignIn()) R.string.spotify_reconnect else R.string.retry))
        }
    }
}

@Composable
fun SpotifyLibraryScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by SpotifySession.connected.collectAsStateWithLifecycle()
    // disconnecting (from anywhere) takes this page away
    LaunchedEffect(connected) { if (!connected) navController.popBackStack() }
    val likedName = stringResource(R.string.spotify_liked)

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            is SpotifyLoad.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            is SpotifyLoad.Failed ->
                SpotifyErrorBlock(
                    kind = current.kind,
                    onRetry = { viewModel.load(force = true) },
                    onReconnect = { navController.navigate("spotify_login") },
                )

            is SpotifyLoad.Ready ->
                LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                    items(current.value, key = { it.id }) { playlist ->
                        ListItem(
                            title = if (playlist.id == SPOTIFY_LIKED_ID) likedName else playlist.name,
                            subtitle = playlist.owner,
                            thumbnailContent = {
                                ItemThumbnail(
                                    thumbnailUrl = playlist.imageUrl,
                                    isActive = false,
                                    isPlaying = false,
                                    shape = RoundedCornerShape(ListThumbnailCornerRadius),
                                    modifier = Modifier.size(ListThumbnailSize),
                                )
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate("spotify/playlist/${playlist.id}") },
                        )
                    }
                    if (current.value.size <= 1) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.spotify_library_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                            )
                        }
                    }
                }
        }

        TopAppBar(
            title = { Text(text = stringResource(R.string.spotify_title), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
    }
}
