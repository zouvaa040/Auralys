/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings home. A search field over the whole settings tree, then the account block and the categories as flat
 * grouped lists (see SettingsHub.kt). The pages behind the rows are unchanged.
 */

package com.metrolist.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val hasDefaultLinks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasAndroidAuto =
        remember {
            try {
                context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    val hasNewVersion = BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.BASE_VERSION_NAME
    val tiles =
        remember(hasAndroidAuto, hasDefaultLinks, hasNewVersion, latestVersionName) {
            settingsTiles(
                hasAndroidAuto = hasAndroidAuto,
                hasDefaultLinks = hasDefaultLinks,
                updaterAvailable = BuildConfig.UPDATER_AVAILABLE,
                newVersionName = latestVersionName.takeIf { hasNewVersion },
            )
        }
    var query by rememberSaveable { mutableStateOf("") }

    val open: (String) -> Unit = { route ->
        focusManager.clearFocus()
        if (route == ROUTE_DEFAULT_LINKS) openDefaultLinksSettings(context) else navController.navigate(route)
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        SettingsHubBody(
            query = query,
            onQueryChange = { query = it },
            tiles = tiles,
            onOpen = open,
            account = { YtmAccountSection(navController = navController, showSyncWhenLoggedOut = false) },
        )

        Spacer(modifier = Modifier.height(24.dp))
        com.metrolist.music.ui.utils.BarsBottomSpacer()
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

/** Android 12+: the system page with the "open supported links" switches for this app. */
private fun openDefaultLinksSettings(context: Context) {
    try {
        val intent =
            Intent(
                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                "package:${context.packageName}".toUri(),
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
    }
}
