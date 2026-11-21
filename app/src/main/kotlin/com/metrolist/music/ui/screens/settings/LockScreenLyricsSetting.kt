/**
 * Auralys - setting for the lyrics on the lock screen
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.music.R
import com.metrolist.music.constants.LockScreenLyricsKey
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.utils.rememberPreference

/** The switch, and while it is on, a row for the permission it needs (checked again whenever the page comes back). */
@Composable
fun rememberLockScreenLyricsItems(): List<Material3SettingsItem> {
    val context = LocalContext.current
    val (enabled, onEnabledChange) = rememberPreference(LockScreenLyricsKey, defaultValue = false)
    var canShow by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canShow = Settings.canDrawOverlays(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toggle = { on: Boolean ->
        onEnabledChange(on)
        if (on && !canShow) openOverlayPermission(context)
    }

    return buildList {
        add(
            Material3SettingsItem(
                icon = painterResource(R.drawable.lock),
                title = { Text(stringResource(R.string.lock_lyrics_title)) },
                description = { Text(stringResource(R.string.lock_lyrics_desc)) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = toggle,
                        thumbContent = {
                            Icon(
                                painter = painterResource(if (enabled) R.drawable.check else R.drawable.close),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        },
                    )
                },
                onClick = { toggle(!enabled) },
            ),
        )
        if (enabled) {
            add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lock),
                    title = { Text(stringResource(R.string.lock_lyrics_permission_title)) },
                    description = {
                        Text(stringResource(if (canShow) R.string.lock_lyrics_permission_ok else R.string.lock_lyrics_permission_missing))
                    },
                    onClick = { if (canShow) openAppInfo(context) else openOverlayPermission(context) },
                ),
            )
        }
    }
}

private fun openOverlayPermission(context: Context) {
    val intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppInfo(context) }
}

private fun openAppInfo(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
