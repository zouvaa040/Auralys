/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The "You" tab: everything that is about the listener rather than about music, in one place: the streak, history and
 * stats, the tools (listen together, song recognition, equalizer), the account and the way into Settings. Plain grouped
 * lists: a bare ink icon and the name, no group titles, no second lines.
 */

package com.metrolist.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.LargeTitleHeader
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.screens.settings.YtmAccountSection
import com.metrolist.music.ui.theme.HorizonSpace

@Composable
fun YouScreen(navController: NavController) {
    YouLayout(
        onOpen = { navController.navigate(it) },
        streak = { HomeStreakBanner(onClick = { navController.navigate("stats") }) },
        account = { modifier -> YtmAccountSection(navController = navController, modifier = modifier, showSyncWhenLoggedOut = false) },
    )
}

/** The page itself, without its data sources: [streak] and [account] come from the caller (they need view models). */
@Composable
internal fun YouLayout(
    onOpen: (String) -> Unit,
    streak: @Composable () -> Unit,
    account: @Composable (Modifier) -> Unit,
) {
    @Composable
    fun row(
        @DrawableRes icon: Int,
        title: String,
        route: String,
    ) = Material3SettingsItem(
        icon = painterResource(icon),
        title = { Text(title) },
        onClick = { onOpen(route) },
    )

    val activity =
        listOf(
            row(R.drawable.history, stringResource(R.string.history), "history"),
            row(R.drawable.stats, stringResource(R.string.you_stats_recap), "stats"),
        )
    val tools =
        listOf(
            row(R.drawable.group_outlined, stringResource(R.string.listen_together), "listen_together_from_topbar"),
            row(R.drawable.mic, stringResource(R.string.you_recognize), "recognition"),
            row(R.drawable.equalizer, stringResource(R.string.equalizer), "equalizer"),
        )
    val app =
        listOf(
            row(R.drawable.settings, stringResource(R.string.settings), "settings"),
            row(R.drawable.info, stringResource(R.string.about), "settings/about"),
        )

    // the side margin is set per block because the streak banner already brings its own (the page gutter)
    val gutter = Modifier.padding(horizontal = HorizonSpace.Gutter)

    Column(
        modifier =
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        // the large title (shows only where the screen provides a LargeTitleState, see LargeTitle.kt)
        LargeTitleHeader(title = stringResource(R.string.you_title))

        // hides itself while there is no streak yet
        streak()

        Spacer(Modifier.height(HorizonSpace.S16))
        Material3SettingsGroup(items = activity, modifier = gutter)

        Spacer(Modifier.height(HorizonSpace.S24))
        Material3SettingsGroup(items = tools, modifier = gutter)

        Spacer(Modifier.height(HorizonSpace.S24))
        account(gutter)

        Spacer(Modifier.height(HorizonSpace.S24))
        Material3SettingsGroup(items = app, modifier = gutter)

        Spacer(Modifier.height(HorizonSpace.S24))
        // the bottom inset is scroll content: the page runs under the nav pill and the mini player
        com.metrolist.music.ui.utils.BarsBottomSpacer()
    }
}
