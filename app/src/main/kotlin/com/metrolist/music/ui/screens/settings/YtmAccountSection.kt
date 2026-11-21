/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The sign-in block at the top of Settings and on the You tab: one tile for YouTube Music and one for Spotify, side by side
 * (AccountKit.kt), and under them what belongs to the YouTube Music sign-in only (auto-sync switch, sign out) once it is signed
 * in. The one place where an account is connected.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.R
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.spotify.SpotifyAccount
import com.metrolist.music.spotify.SpotifySession
import com.metrolist.music.ui.component.AccountBlock
import com.metrolist.music.ui.component.AccountProvider
import com.metrolist.music.ui.component.AccountTileState
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun YtmAccountSection(
    navController: NavController,
    modifier: Modifier = Modifier,
    // the auto-sync switch means nothing before signing in, so the compact pages leave it out until then
    showSyncWhenLoggedOut: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: AccountSettingsViewModel = hiltViewModel()

    val (accountName, _) = rememberPreference(AccountNameKey, "")
    val (cookie, onCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)
    val isLoggedIn = remember(cookie) { "SAPISID" in parseCookieString(cookie) }

    val spotifyConnected by SpotifySession.connected.collectAsState()
    // the account's name only once something else has read it (the Spotify page, the library): this tile never starts a
    // network read of its own, so opening the You tab stays free
    val spotifyProfile by SpotifyAccount.profile.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        DefaultDialog(
            onDismiss = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout_dialog_title)) },
            content = {
                Text(
                    text = stringResource(R.string.logout_dialog_message),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                viewModel.logoutAndClearLibraryData(context)
                            } catch (e: Exception) {
                                reportException(e)
                            }
                            onCookieChange("")
                            showLogoutDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.logout_clear)) }
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.logoutKeepData(context, onCookieChange)
                            showLogoutDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.logout_keep)) }
            },
        )
    }

    // The account block: one tile per sign-in side by side (YouTube Music, Spotify), see AccountKit.kt. A tile opens what it
    // needs: YouTube Music not signed in goes to the sign-in, signed in to the account; Spotify always opens its own page (the
    // connect experience, and the account card with "Disconnect" once connected).
    val tiles =
        listOf(
            AccountTileState(
                provider = AccountProvider.YOUTUBE_MUSIC,
                connected = isLoggedIn,
                accountName = accountName.takeIf { isLoggedIn && it.isNotBlank() },
                onClick = { navController.navigate(if (isLoggedIn) "account" else "login") },
            ),
            AccountTileState(
                provider = AccountProvider.SPOTIFY,
                connected = spotifyConnected,
                accountName = spotifyProfile?.name?.takeIf { spotifyConnected && it.isNotBlank() },
                avatarUrl = spotifyProfile?.avatarUrl?.takeIf { spotifyConnected },
                onClick = { navController.navigate("settings/spotify") },
            ),
        )

    // what belongs to the YouTube Music sign-in only: the sync switch and signing out
    val youtubeRows =
        buildList {
            if (isLoggedIn || showSyncWhenLoggedOut) add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text(stringResource(R.string.yt_sync)) },
                    trailingContent = {
                        Switch(
                            enabled = isLoggedIn,
                            checked = ytmSync,
                            onCheckedChange = onYtmSyncChange,
                        )
                    },
                    enabled = isLoggedIn,
                    onClick = { if (isLoggedIn) onYtmSyncChange(!ytmSync) },
                ),
            )
            if (isLoggedIn) {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.logout),
                        title = { Text(stringResource(R.string.action_logout)) },
                        onClick = { showLogoutDialog = true },
                    ),
                )
            }
        }

    AccountBlock(tiles = tiles, youtubeRows = youtubeRows, modifier = modifier)
}
