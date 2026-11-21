/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Spotify" page: the connect experience (the Spotify mark large, what connecting gives, the one main action and the privacy
 * sentence) or, once connected, the account card with "Disconnect"; and the sign-in screen itself (a dialog route, so its web
 * view lives outside the page content; see SpotifyLogin.kt). It is opened from the Spotify tile next to the YouTube Music
 * sign-in (YtmAccountSection). The account logic is in com.metrolist.music.spotify.
 */

package com.metrolist.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.spotify.SpotifyAccount
import com.metrolist.music.spotify.SpotifyLoginContent
import com.metrolist.music.spotify.SpotifySession
import com.metrolist.music.ui.component.AccountButton
import com.metrolist.music.ui.component.AccountCard
import com.metrolist.music.ui.component.AccountProvider
import com.metrolist.music.ui.component.BenefitRow
import com.metrolist.music.ui.component.BrandMark
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace
import com.metrolist.music.ui.utils.BarsBottomSpacer
import com.metrolist.music.ui.utils.BarsTopSpacer
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.horizontalBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(navController: NavController) {
    val connected by SpotifySession.connected.collectAsState()
    val profile by SpotifyAccount.profile.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }

    // the account's name and avatar, once, while connected (best effort: nothing shows when Spotify does not give them).
    // loadProfile moves to the IO dispatcher itself: the first read opens the Keystore, which is not for the main thread.
    LaunchedEffect(connected) { if (connected) SpotifyAccount.loadProfile() }

    Column(
        Modifier
            .horizontalBarInsets()
            .verticalScroll(rememberScrollState()),
    ) {
        BarsTopSpacer()
        SpotifyConnectContent(
            connected = connected,
            accountName = profile?.name,
            avatarUrl = profile?.avatarUrl,
            onConnect = { navController.navigate("spotify_login") },
            onDisconnect = { confirmDisconnect = true },
        )
        BarsBottomSpacer()
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )

    if (confirmDisconnect) {
        DefaultDialog(
            onDismiss = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.spotify_disconnect_title)) },
            buttons = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        // the account runs the sign-out in its own scope: leaving this page does not stop it half way
                        SpotifyAccount.disconnect()
                    },
                ) {
                    Text(stringResource(R.string.spotify_disconnect))
                }
            },
        ) {
            Text(stringResource(R.string.spotify_disconnect_message), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The page itself, without its data sources. Not connected: the mark, what connecting gives (three short lines), the main
 * action, the privacy sentence. Connected: the account card, "Disconnect", the privacy sentence.
 */
@Composable
internal fun SpotifyConnectContent(
    connected: Boolean,
    accountName: String?,
    avatarUrl: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = HorizonSpace.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(HorizonSpace.S16))
        BrandMark(AccountProvider.SPOTIFY, 72.dp)
        Spacer(Modifier.height(HorizonSpace.S16))
        Text(
            text = stringResource(if (connected) R.string.spotify_title else R.string.spotify_connect),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!connected) {
            Spacer(Modifier.height(HorizonSpace.S8))
            Text(
                text = stringResource(R.string.spotify_hero_sub),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(HorizonSpace.S24))

        if (!connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(glassShape(HorizonRadius.Lg, HorizonRadius.Lg))
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = HorizonSpace.S16, vertical = HorizonSpace.S8),
                verticalArrangement = Arrangement.spacedBy(HorizonSpace.S4),
            ) {
                BenefitRow(R.drawable.library_music, stringResource(R.string.spotify_connect_desc))
                BenefitRow(R.drawable.play, stringResource(R.string.spotify_benefit_play))
                BenefitRow(R.drawable.gradient, stringResource(R.string.spotify_canvas_title))
            }
            Spacer(Modifier.height(HorizonSpace.S24))
            AccountButton(
                label = stringResource(R.string.spotify_connect),
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            )
        } else {
            AccountCard(AccountProvider.SPOTIFY, accountName, avatarUrl)
            Spacer(Modifier.height(HorizonSpace.S16))
            AccountButton(
                label = stringResource(R.string.spotify_disconnect),
                onClick = onDisconnect,
                quiet = true,
                danger = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            )
        }

        Spacer(Modifier.height(HorizonSpace.S16))
        // one plain sentence, a Text and not a description line: the settings pages have a budget for those
        Text(
            text = stringResource(R.string.spotify_settings_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The sign-in as a dialog route: full screen, outside the page content. Closes itself when the account is connected. */
@Composable
fun SpotifyLoginRoute(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SpotifyLoginContent(
        onConnected = { cookie ->
            // storing the cookie writes to disk and may create the Keystore key: not on the main thread
            scope.launch {
                val stored = withContext(Dispatchers.IO) { SpotifyAccount.connect(cookie) }
                if (!stored) Toast.makeText(context, R.string.spotify_login_failed, Toast.LENGTH_LONG).show()
                navController.popBackStack()
            }
        },
        onClose = { navController.popBackStack() },
    )
}
