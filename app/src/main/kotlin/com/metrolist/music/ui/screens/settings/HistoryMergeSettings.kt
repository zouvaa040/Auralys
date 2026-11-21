/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The two rows of the history merge in Backup and restore: the switch and the button that merges now.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.ui.component.CircularProgressIndicator
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.viewmodels.RemoteHistoryMergeViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun historyMergeItems(viewModel: RemoteHistoryMergeViewModel): List<Material3SettingsItem> {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val locale: Locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }

    val status =
        when {
            !ui.signedIn -> stringResource(R.string.auralys_history_merge_signed_out)
            ui.running -> stringResource(R.string.auralys_history_merge_running)
            ui.paused -> stringResource(R.string.auralys_history_merge_paused)
            ui.failed -> stringResource(R.string.auralys_history_merge_failed)
            ui.lastSuccessMs == null -> stringResource(R.string.auralys_history_merge_never)
            else ->
                pluralStringResource(
                    R.plurals.auralys_history_merge_last,
                    ui.lastAdded,
                    formatter.format(Instant.ofEpochMilli(ui.lastSuccessMs!!).atZone(ZoneId.systemDefault())),
                    ui.lastAdded,
                )
        }

    return listOf(
        Material3SettingsItem(
            icon = painterResource(R.drawable.history),
            title = { Text(stringResource(R.string.auralys_history_merge_title)) },
            trailingContent = {
                Switch(
                    checked = ui.switchOn,
                    onCheckedChange = viewModel::setSwitch,
                    enabled = ui.signedIn,
                    thumbContent = {
                        Icon(
                            painter = painterResource(id = if (ui.switchOn) R.drawable.check else R.drawable.close),
                            contentDescription = null,
                            modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize),
                        )
                    },
                )
            },
            enabled = ui.signedIn,
            onClick = { viewModel.setSwitch(!ui.switchOn) },
        ),
        Material3SettingsItem(
            icon = painterResource(R.drawable.sync),
            // the last result is real state, so it sits under the title (the settings pages have a budget of subtitle lines)
            title = {
                Column {
                    Text(stringResource(R.string.auralys_history_merge_now))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (ui.signedIn) 1f else 0.38f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            },
            trailingContent = if (ui.running) ({ CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
            enabled = ui.signedIn && !ui.running,
            onClick = viewModel::mergeNow,
        ),
    )
}
