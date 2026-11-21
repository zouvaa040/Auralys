/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "A newer version is out" notice, shared by the start-up check and the manual check on the About page.
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.utils.WebUpdate

@Composable
fun WebUpdateDialog(
    version: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_update_title)) },
        text = { Text(stringResource(R.string.web_update_message, version, BuildConfig.BASE_VERSION_NAME)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    // a phone without a browser must not crash the app over an update notice
                    runCatching { uriHandler.openUri(WebUpdate.PAGE) }
                },
            ) { Text(stringResource(R.string.web_update_now)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_update_later)) }
        },
    )
}
