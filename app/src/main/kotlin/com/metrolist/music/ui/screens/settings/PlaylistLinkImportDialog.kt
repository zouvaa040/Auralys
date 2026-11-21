/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Link import dialog; layout idea adapted from Convx's AppleMusicPlaylistImport
 * (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0); see NOTICE.
 */

package com.metrolist.music.ui.screens.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.ui.component.CircularProgressIndicator
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.glassFill
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.utils.PlaylistLinkParser
import com.metrolist.music.utils.PlaylistLinkSource
import com.metrolist.music.utils.SpotifyWebFailure
import com.metrolist.music.viewmodels.LinkImportError
import com.metrolist.music.viewmodels.LinkImportPhase
import com.metrolist.music.viewmodels.LinkImportUiState
import com.metrolist.music.viewmodels.PlaylistLinkImportViewModel

/**
 * Dialog for "Import from Spotify / Apple Music". Shown while [PlaylistLinkImportViewModel.state]
 * has a source; open it with [PlaylistLinkImportViewModel.open].
 */
@Composable
fun PlaylistLinkImportDialog(
    viewModel: PlaylistLinkImportViewModel,
    onOpenPlaylist: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val source = state.source ?: return
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val clipboardEmpty = stringResource(R.string.auralys_import_clipboard_empty)

    DefaultDialog(
        onDismiss = { if (!state.isBusy) viewModel.close() },
        horizontalAlignment = Alignment.Start,
        icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (source == PlaylistLinkSource.SPOTIFY) R.string.auralys_import_spotify
                    else R.string.auralys_import_apple_music,
                ),
            )
        },
        buttons = {
            when (state.phase) {
                LinkImportPhase.INPUT -> {
                    TextButton(onClick = viewModel::close) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = { viewModel.start(activity) },
                        enabled = state.link.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.auralys_import_start))
                    }
                }
                LinkImportPhase.READING, LinkImportPhase.MATCHING -> {
                    TextButton(onClick = viewModel::cancel) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
                LinkImportPhase.SAVING -> Unit
                LinkImportPhase.DONE -> {
                    TextButton(onClick = viewModel::close) {
                        Text(stringResource(R.string.auralys_import_close))
                    }
                    state.createdPlaylistId?.let { id ->
                        TextButton(
                            onClick = {
                                viewModel.close()
                                onOpenPlaylist(id)
                            },
                        ) {
                            Text(stringResource(R.string.auralys_import_open_playlist))
                        }
                    }
                }
            }
        },
    ) {
        when (state.phase) {
            LinkImportPhase.INPUT -> InputContent(
                state = state,
                source = source,
                onLinkChange = viewModel::updateLink,
                onSubmit = { viewModel.start(activity) },
                onPaste = {
                    val text = runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
                    }.getOrNull()?.trim()
                    if (text.isNullOrEmpty()) {
                        Toast.makeText(context, clipboardEmpty, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.updateLink(PlaylistLinkParser.extractLink(text) ?: text)
                    }
                },
            )
            LinkImportPhase.READING -> BusyRow(
                when {
                    !state.readingFull -> stringResource(R.string.auralys_import_reading)
                    state.readTotal > 0 ->
                        stringResource(R.string.auralys_import_reading_full, state.readCount, state.readTotal)
                    else -> stringResource(R.string.auralys_import_reading_full_unknown, state.readCount)
                },
            )
            LinkImportPhase.MATCHING -> MatchingContent(state)
            LinkImportPhase.SAVING -> BusyRow(stringResource(R.string.auralys_import_saving))
            LinkImportPhase.DONE -> DoneContent(state, source)
        }
    }
}

@Composable
private fun InputContent(
    state: LinkImportUiState,
    source: PlaylistLinkSource,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPaste: () -> Unit,
) {
    Text(
        text = stringResource(
            if (source == PlaylistLinkSource.SPOTIFY) R.string.auralys_import_spotify_desc
            else R.string.auralys_import_apple_desc,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.link,
        onValueChange = onLinkChange,
        singleLine = true,
        isError = state.error == LinkImportError.INVALID_LINK,
        label = { Text(stringResource(R.string.auralys_import_link_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (state.link.isNotBlank()) onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onPaste) {
            Text(stringResource(R.string.auralys_import_paste))
        }
    }
    state.error?.let { error ->
        Text(
            text = stringResource(
                when (error) {
                    LinkImportError.INVALID_LINK -> R.string.auralys_import_error_invalid
                    LinkImportError.FETCH_FAILED -> R.string.auralys_import_error_fetch
                    LinkImportError.EMPTY -> R.string.auralys_import_error_empty
                    LinkImportError.NO_MATCHES -> R.string.auralys_import_error_no_matches
                    LinkImportError.SAVE_FAILED -> R.string.auralys_import_error_save
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.error == null && state.cancelled) {
        Text(
            text = stringResource(R.string.auralys_import_cancelled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (source == PlaylistLinkSource.SPOTIFY) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auralys_import_spotify_limit_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchingContent(state: LinkImportUiState) {
    if (state.playlistTitle.isNotEmpty()) {
        Text(
            text = state.playlistTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
    }
    Text(
        text = stringResource(R.string.auralys_import_matching),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    val fraction = if (state.total > 0) state.processed.toFloat() / state.total else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(glassShape(4.dp, 4.dp)),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.auralys_import_progress, state.matched, state.processed, state.total),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun failureMessage(failure: SpotifyWebFailure): Int = when (failure) {
    SpotifyWebFailure.TIMEOUT -> R.string.auralys_import_fail_timeout
    SpotifyWebFailure.NETWORK -> R.string.auralys_import_fail_network
    SpotifyWebFailure.MEMORY -> R.string.auralys_import_fail_memory
    SpotifyWebFailure.PAGE -> R.string.auralys_import_fail_page
    SpotifyWebFailure.UNAVAILABLE -> R.string.auralys_import_fail_unavailable
}

/** Collapsed by default; opening it shows the raw note, which can be selected or copied to send to the developer. */
@Composable
private fun TechnicalDetails(text: String) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val copiedMessage = stringResource(R.string.auralys_import_diag_copied)
    TextButton(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            text = stringResource(if (expanded) R.string.auralys_import_diag_hide else R.string.auralys_import_diag_show),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (expanded) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Auralys", text))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }
            },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(stringResource(R.string.auralys_import_diag_copy), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DoneContent(state: LinkImportUiState, source: PlaylistLinkSource) {
    Text(
        text = stringResource(R.string.auralys_import_done, state.playlistTitle, state.matched),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (state.unmatchedCount == 0) {
            stringResource(R.string.auralys_import_all_matched)
        } else {
            stringResource(R.string.auralys_import_unmatched, state.unmatchedCount)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.mayBeTruncated) {
        Spacer(Modifier.height(8.dp))
        val declared = state.declaredCount
        val failure = state.readFailure
        Text(
            text = when {
                failure != null -> stringResource(failureMessage(failure), state.total)
                declared != null && declared > state.total ->
                    stringResource(R.string.auralys_import_truncated, state.total, declared)
                source == PlaylistLinkSource.SPOTIFY -> stringResource(R.string.auralys_import_truncated_spotify)
                else -> stringResource(R.string.auralys_import_truncated, state.total, declared ?: state.total)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        if (failure != null && state.readDiag.isNotEmpty()) TechnicalDetails(state.readDiag)
    }
    if (state.unmatched.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        val shape = glassShape(16.dp, 8.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(shape)
                .background(if (isGlassStyle) glassFill() else MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.unmatched.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val more = state.unmatchedCount - state.unmatched.size
            if (more > 0) {
                Text(
                    text = stringResource(R.string.auralys_import_unmatched_more, more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
