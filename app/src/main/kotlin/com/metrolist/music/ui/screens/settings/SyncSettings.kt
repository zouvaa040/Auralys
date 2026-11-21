/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Sinkron ke Auralys PC": scan the QR of the PC app (or paste its code), watch the progress, read what was merged.
 * The logic lives in com.metrolist.music.sync; this file is only the screen.
 */

package com.metrolist.music.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.sync.MergeSummary
import com.metrolist.music.sync.SyncErrorKind
import com.metrolist.music.sync.SyncStep
import com.metrolist.music.sync.SyncUiState
import com.metrolist.music.sync.SyncViewModel
import com.metrolist.music.ui.component.CircularProgressIndicator
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettings(
    navController: NavController,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showPaste by rememberSaveable { mutableStateOf(false) }

    val deniedText = stringResource(R.string.sync_pc_camera_denied)
    val scanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents
            if (text != null) {
                viewModel.start(text)
            } else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
                Toast.makeText(context, deniedText, Toast.LENGTH_LONG).show()
            }
        }
    val scanPrompt = stringResource(R.string.sync_pc_scan_prompt)
    val startScan = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(scanPrompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    val savedText = stringResource(R.string.sync_pc_backup_saved)
    val saveFailedText = stringResource(R.string.sync_pc_backup_save_failed)
    var backupToSave by remember { mutableStateOf<File?>(null) }
    val saveBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val file = backupToSave
            if (uri != null && file != null) {
                scope.launch {
                    val ok = viewModel.saveBackupCopy(uri, file)
                    Toast.makeText(context, if (ok) savedText else saveFailedText, Toast.LENGTH_SHORT).show()
                }
            }
        }

    // The screen stays on while talking to the PC; waiting for the confirmation can take a few minutes.
    val view = LocalView.current
    val running = state is SyncUiState.Running
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        when (val current = state) {
            is SyncUiState.Idle -> {
                IntroCard()
                Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_scan))
                }
                OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_paste))
                }
            }

            is SyncUiState.Running -> {
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text(stepText(current.step, current.pcName), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_cancel))
                }
            }

            is SyncUiState.Finished -> {
                CardBox {
                    Text(stringResource(R.string.sync_pc_done_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.sync_pc_done_with, current.pcName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val lines = summaryLines(context, current.summary)
                    if (lines.isEmpty()) {
                        Text(stringResource(R.string.sync_pc_done_nothing), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                current.backup?.let { backup ->
                    CardBox {
                        Text(
                            stringResource(R.string.sync_pc_backup_note, backup.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                backupToSave = backup
                                saveBackupLauncher.launch(backup.name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.sync_pc_save_backup))
                        }
                    }
                }
                Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_close))
                }
            }

            is SyncUiState.Declined -> {
                CardBox { Text(stringResource(R.string.sync_pc_declined), style = MaterialTheme.typography.bodyLarge) }
                Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_close))
                }
            }

            is SyncUiState.Failed -> {
                CardBox {
                    Text(stringResource(R.string.sync_pc_failed_title), style = MaterialTheme.typography.titleLarge)
                    Text(errorText(current.kind, current.detail), style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_pc_again))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        com.metrolist.music.ui.utils.BarsBottomSpacer()
    }

    if (showPaste) {
        PasteCodeDialog(
            onDismiss = { showPaste = false },
            onStart = {
                showPaste = false
                viewModel.start(it)
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.sync_pc_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glassCard(glassShape(22.dp, 12.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun IntroCard() {
    CardBox {
        listOf(R.string.sync_pc_step_1, R.string.sync_pc_step_2, R.string.sync_pc_step_3).forEachIndexed { index, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun stepText(step: SyncStep, pcName: String): String =
    when (step) {
        SyncStep.CONNECTING -> stringResource(R.string.sync_pc_step_connecting, pcName)
        SyncStep.HANDSHAKE -> stringResource(R.string.sync_pc_step_handshake)
        SyncStep.READING -> stringResource(R.string.sync_pc_step_reading)
        SyncStep.SENDING -> stringResource(R.string.sync_pc_step_sending)
        SyncStep.WAITING_PC -> stringResource(R.string.sync_pc_step_waiting)
        SyncStep.BACKUP -> stringResource(R.string.sync_pc_step_backup)
        SyncStep.APPLYING -> stringResource(R.string.sync_pc_step_applying)
        SyncStep.FINISHING -> stringResource(R.string.sync_pc_step_finishing)
    }

@Composable
private fun errorText(kind: SyncErrorKind, detail: String?): String =
    when (kind) {
        SyncErrorKind.BAD_CODE -> stringResource(R.string.sync_pc_err_bad_code)
        SyncErrorKind.CONNECT_FAILED -> stringResource(R.string.sync_pc_err_connect)
        SyncErrorKind.CLOSED_BY_PC -> stringResource(R.string.sync_pc_err_closed)
        SyncErrorKind.TIMEOUT -> stringResource(R.string.sync_pc_err_timeout)
        SyncErrorKind.VERSION -> stringResource(R.string.sync_pc_err_version)
        SyncErrorKind.PC_ERROR -> stringResource(R.string.sync_pc_err_pc, detail ?: "?")
        SyncErrorKind.PROTOCOL -> stringResource(R.string.sync_pc_err_protocol)
        SyncErrorKind.LOCAL -> stringResource(R.string.sync_pc_err_local, detail ?: "?")
        SyncErrorKind.NETWORK -> stringResource(R.string.sync_pc_err_network)
    }

private fun summaryLines(context: Context, s: MergeSummary): List<String> =
    buildList {
        if (s.playlistsCreated > 0) add(context.getString(R.string.sync_pc_done_playlists_new, s.playlistsCreated))
        if (s.playlistsExtended > 0) {
            add(context.getString(R.string.sync_pc_done_playlists_extended, s.playlistsExtended, s.playlistSongsAppended))
        }
        if (s.likedAdded > 0) add(context.getString(R.string.sync_pc_done_liked, s.likedAdded))
        if (s.savedAdded > 0) add(context.getString(R.string.sync_pc_done_saved, s.savedAdded))
        if (s.savedSkipped > 0) add(context.getString(R.string.sync_pc_done_saved_skipped, s.savedSkipped))
        if (s.eventsAdded > 0) add(context.getString(R.string.sync_pc_done_events, s.eventsAdded))
        if (s.searchesAdded > 0) add(context.getString(R.string.sync_pc_done_searches, s.searchesAdded))
        if (s.songsAdded > 0) add(context.getString(R.string.sync_pc_done_songs, s.songsAdded))
        if (s.unresolved > 0) add(context.getString(R.string.sync_pc_done_unresolved, s.unresolved))
    }

@Composable
private fun PasteCodeDialog(
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.sync_pc_paste_title)) },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_pc_cancel)) }
            TextButton(onClick = { onStart(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.sync_pc_paste_start))
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.sync_pc_paste_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
            },
        ) {
            Text(stringResource(R.string.sync_pc_paste_clipboard))
        }
    }
}
