/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsSelection
import com.metrolist.music.lyrics.LyricsSources
import com.metrolist.music.lyrics.LyricsTiming
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.viewmodels.LyricsSourceViewModel

/** The words under a source in the picker. */
@Composable
private fun rowLabel(state: LyricsSources.RowState): String =
    stringResource(
        when (state) {
            LyricsSources.RowState.WORD -> R.string.auralys_lyrics_source_word
            LyricsSources.RowState.LINE -> R.string.auralys_lyrics_source_line
            LyricsSources.RowState.PLAIN -> R.string.auralys_lyrics_source_plain
            LyricsSources.RowState.OTHER_VERSION -> R.string.auralys_lyrics_source_other_version
            LyricsSources.RowState.LOADING -> R.string.auralys_lyrics_source_loading
            LyricsSources.RowState.NOT_FOUND -> R.string.auralys_lyrics_source_not_found
            LyricsSources.RowState.IDLE -> R.string.auralys_lyrics_source_idle
            LyricsSources.RowState.UNAVAILABLE -> R.string.auralys_lyrics_source_unavailable
        },
    )

/** The sync kind of a text in the words of the picker rows. */
@Composable
private fun syncLabel(text: String): String =
    stringResource(
        when (LyricsSelection.sync(text)) {
            LyricsSelection.Sync.WORD -> R.string.auralys_lyrics_source_word
            LyricsSelection.Sync.LINE -> R.string.auralys_lyrics_source_line
            LyricsSelection.Sync.PLAIN -> R.string.auralys_lyrics_source_plain
        },
    )

/**
 * The lyrics source picker (idea from BitChord's lyrics provider sheet, GPLv3): every source in the user's order with
 * what it has for THIS song (synced per word or per line, not synced, another version, searching, not found, tap to
 * search). A tap on a source with lyrics shows them at once and remembers the pick for this song; a source not asked
 * yet is asked first. Another version is never taken by a tap on its row, only by its "Use anyway" button.
 * "Automatic" goes back to the app's own choice (the lyrics stay when it finds none or the phone is offline), and
 * "Previous lyrics", when there is a saved text, takes back what the song had before its lyrics were replaced.
 * Every source is asked at most once per song (answers are kept).
 *
 * [shownLyrics] is the text on screen now (to mark "Previous lyrics" as the one in use).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSourceSheet(
    mediaMetadata: MediaMetadata,
    shownProvider: String?,
    onDismiss: () -> Unit,
    shownLyrics: String? = null,
    viewModel: LyricsSourceViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(mediaMetadata.id) { viewModel.open(mediaMetadata) }
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val sourcesFlow = remember(mediaMetadata.id) { viewModel.sourcesOf(mediaMetadata.id) }
    val state by sourcesFlow.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle(initialValue = emptyMap())
    val switching by viewModel.switching.collectAsStateWithLifecycle()
    val previous by viewModel.previous.collectAsStateWithLifecycle()
    val choice = choices[mediaMetadata.id]
    val previousText = previous?.text?.trim()
    val previousShown = previousText != null && shownLyrics != null && previousText == shownLyrics.trim()
    val rows =
        remember(providers, state, shownProvider, choice, previousShown) {
            LyricsSources.rows(providers, state, if (previousShown) null else shownProvider, choice) { viewModel.isUnavailable(mediaMetadata.id, it) }
        }
    val autoCurrent = LyricsSources.automaticIsCurrent(choice, shownProvider, previousShown)
    val scheme = MaterialTheme.colorScheme
    val offline = stringResource(R.string.error_no_internet_connection)
    val nothingElse = stringResource(R.string.auralys_lyrics_auto_nothing)
    val pickDone: (LyricsHelper.PickResult) -> Unit = { result ->
        when (result) {
            LyricsHelper.PickResult.APPLIED -> onDismiss()
            LyricsHelper.PickResult.NO_CONNECTION -> Toast.makeText(context, offline, Toast.LENGTH_SHORT).show()
            // the row now reads "Not found", or "Another version" with its "Use anyway" button
            LyricsHelper.PickResult.NOT_FOUND, LyricsHelper.PickResult.OTHER_VERSION -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // glass themes make the surface colours translucent; the sheet must hide what is behind it
        containerColor = scheme.surfaceContainerLow.copy(alpha = 1f),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = stringResource(R.string.auralys_lyrics_sources_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = mediaMetadata.title + " · " + mediaMetadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 2.dp, bottom = 8.dp),
            )
            LazyColumn {
                item(key = "auto") {
                    SourceRow(
                        name = stringResource(R.string.auralys_lyrics_source_auto),
                        label =
                            state.auto?.let { stringResource(R.string.auralys_lyrics_source_auto_now, it) }
                                ?: stringResource(R.string.auralys_lyrics_source_auto_pick),
                        current = autoCurrent,
                        busy = switching == LyricsSourceViewModel.AUTO,
                        enabled = !autoCurrent && switching == null,
                        onClick = {
                            viewModel.chooseAutomatic(mediaMetadata) { result ->
                                when (result) {
                                    LyricsHelper.AutoResult.APPLIED -> onDismiss()
                                    LyricsHelper.AutoResult.NO_CONNECTION -> Toast.makeText(context, offline, Toast.LENGTH_SHORT).show()
                                    LyricsHelper.AutoResult.NOTHING_FOUND -> Toast.makeText(context, nothingElse, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
                previous?.let { saved ->
                    item(key = "previous") {
                        SourceRow(
                            name = stringResource(R.string.auralys_lyrics_source_previous),
                            label = syncLabel(saved.text),
                            current = previousShown,
                            busy = switching == LyricsSourceViewModel.PREVIOUS,
                            enabled = !previousShown && switching == null,
                            onClick = { viewModel.choosePrevious(mediaMetadata) { ok -> if (ok) onDismiss() } },
                        )
                    }
                }
                items(rows, key = { it.provider }) { row ->
                    val useAnyway = stringResource(R.string.auralys_lyrics_source_use_anyway)
                    SourceRow(
                        name = row.provider,
                        label = rowLabel(row.state),
                        current = row.current,
                        busy = switching == row.provider || row.state == LyricsSources.RowState.LOADING,
                        enabled = row.tappable && switching == null,
                        dim = row.state == LyricsSources.RowState.NOT_FOUND || row.state == LyricsSources.RowState.UNAVAILABLE,
                        onClick = { viewModel.choose(mediaMetadata, row.provider, onDone = pickDone) },
                        action =
                            if (row.canUseAnyway && switching == null) {
                                useAnyway to { viewModel.choose(mediaMetadata, row.provider, useAnyway = true, onDone = pickDone) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    name: String,
    label: String,
    current: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    dim: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .alpha(if (dim) 0.55f else 1f),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(strokeWidth = 2.dp, color = scheme.onSurface, modifier = Modifier.size(18.dp))
                current -> Icon(painterResource(R.drawable.check), contentDescription = stringResource(R.string.auralys_lyrics_source_in_use), tint = scheme.onSurface)
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.25f)))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            TimingPill(text = action.first, onClick = action.second)
        }
    }
}

/**
 * The lyrics timing bar, shown at the bottom of the lyrics view so the lines can be watched while it is moved: "Earlier"
 * and "Later" in steps of 0.1 s and 0.5 s, the value in words ("0.3 s earlier"), Reset, Done, and the easiest way of
 * all: a tap on the line that is being sung now (the lyrics view does that while this bar is open).
 */
@Composable
internal fun LyricsTimingBar(
    offsetMs: Int,
    onChange: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // the value of the last tap: quick taps must add up even before the saved value comes back from the database, and
    // the late echo of an earlier tap must not undo the later ones (review 2026-11-12)
    var current by remember { mutableIntStateOf(offsetMs) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(offsetMs) { current = LyricsTiming.shownAfterSave(current, offsetMs, SystemClock.uptimeMillis() - lastTapAt) }
    val set: (Int) -> Unit = { v ->
        current = v
        lastTapAt = SystemClock.uptimeMillis()
        onChange(v)
    }
    val offset = current
    val value =
        when (LyricsTiming.direction(offset)) {
            LyricsTiming.Direction.NONE -> stringResource(R.string.auralys_lyrics_timing_value_zero)
            LyricsTiming.Direction.EARLIER -> stringResource(R.string.auralys_lyrics_timing_value_earlier, LyricsTiming.seconds(offset))
            LyricsTiming.Direction.LATER -> stringResource(R.string.auralys_lyrics_timing_value_later, LyricsTiming.seconds(offset))
        }
    val small = LyricsTiming.seconds(LyricsTiming.SMALL_STEP_MS)
    val big = LyricsTiming.seconds(LyricsTiming.BIG_STEP_MS)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 1f),
        contentColor = scheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auralys_lyrics_timing_title), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (offset != 0) {
                    TimingPill(text = stringResource(R.string.reset), onClick = { set(0) })
                    Spacer(Modifier.width(8.dp))
                }
                TimingPill(text = stringResource(R.string.auralys_lyrics_timing_done), strong = true, onClick = onDone)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TimingStep("« $big", Modifier.weight(1f)) { set(LyricsTiming.earlier(current, LyricsTiming.BIG_STEP_MS)) }
                TimingStep("‹ $small", Modifier.weight(1f)) { set(LyricsTiming.earlier(current, LyricsTiming.SMALL_STEP_MS)) }
                TimingStep("$small ›", Modifier.weight(1f)) { set(LyricsTiming.later(current, LyricsTiming.SMALL_STEP_MS)) }
                TimingStep("$big »", Modifier.weight(1f)) { set(LyricsTiming.later(current, LyricsTiming.BIG_STEP_MS)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(stringResource(R.string.auralys_lyrics_timing_earlier), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.auralys_lyrics_timing_later), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.auralys_lyrics_timing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TimingStep(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.onSurface.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun TimingPill(
    text: String,
    onClick: () -> Unit,
    strong: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = 36.dp)
                .clip(CircleShape)
                .background(if (strong) scheme.onSurface else scheme.onSurface.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (strong) scheme.surface else scheme.onSurface)
    }
}

/** "Lyrics from X" above the lyrics, as a small tappable chip that opens the source picker. */
@Composable
internal fun LyricsSourceChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(painterResource(R.drawable.expand_more), contentDescription = null, tint = scheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
    }
}

/**
 * What the lyrics views show for a song without lyrics: "Lyrics not found" with "Choose source" (the picker) and
 * "Try again" (a lookup now). Both views use it, so the picker is reachable exactly for the songs that need it.
 */
@Composable
internal fun LyricsNotFoundState(
    onChooseSource: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    retrying: Boolean,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.lyrics_not_found),
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = message, fontSize = 20.sp, color = scheme.onSurface, modifier = Modifier.alpha(0.6f))
        if (onChooseSource != null || onRetry != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (onChooseSource != null) LyricsSourceChip(text = stringResource(R.string.auralys_lyrics_choose_source), onClick = onChooseSource)
                if (onRetry != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .clickable(enabled = !retrying, role = Role.Button, onClick = onRetry)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelMedium, color = scheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                        if (retrying) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(strokeWidth = 2.dp, color = scheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}
