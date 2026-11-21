/**
 * Auralys - the small lyric line of the full player
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.ShowMiniLyricKey
import com.metrolist.music.di.LyricsHelperEntryPoint
import com.metrolist.music.lyrics.LyricsStore
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.IslandLyrics
import com.metrolist.music.ui.component.IslandLyricsLogic
import com.metrolist.music.ui.component.IslandLyricsStatus
import com.metrolist.music.ui.component.IslandSweepText
import com.metrolist.music.ui.component.LyricsClock
import com.metrolist.music.ui.component.LyricsPlayheadSource
import com.metrolist.music.ui.component.rememberPlayerPlayhead
import com.metrolist.music.ui.component.runLyricsPlayhead
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The line that is being sung, small, under the title and artist: it is there without opening the lyrics, the sung part
 * of the line (word by word when the lyrics have word times) is bright and the rest dim. Shown only while the song has
 * lyrics with times, the option is on and the big lyrics are not open; a tap opens them. Costs one read of the player
 * per frame while a line is on screen, and nothing otherwise.
 *
 * The lyrics are the ones the player stores for the song. A song that has no lyrics row yet is looked up HERE too (the
 * full player is only composed while it is open, so this costs nothing in the library): before, only the big lyrics view
 * did the lookup, so the line stayed empty until the listener opened the lyrics once (owner report 2026-11-16). While the
 * big view is open it does the lookup itself, so this one steps aside ([visible] false) and the song is never fetched twice.
 */
@Composable
internal fun PlayerMiniLyric(
    mediaMetadata: MediaMetadata,
    visible: Boolean,
    ink: Color,
    modifier: Modifier = Modifier,
    playhead: LyricsPlayheadSource? = null,
    onClick: () -> Unit = {},
) {
    val (enabled) = rememberPreference(ShowMiniLyricKey, defaultValue = true)
    val playerConnection = LocalPlayerConnection.current ?: return
    val row by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val song by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)

    val context = LocalContext.current
    val lyricsHelper =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, LyricsHelperEntryPoint::class.java)
                .lyricsHelper()
        }
    val online by lyricsHelper.online.collectAsStateWithLifecycle()
    val hasRow = row?.id == mediaMetadata.id
    // the same rules as the lyrics view (LyricsHelper.lookupAndStore in FILL mode: nothing is stored when the sources could not be
    // reached, found lyrics are never replaced); the network coming back restarts this effect at once (online is a key)
    LaunchedEffect(mediaMetadata.id, enabled, visible, hasRow, online) {
        if (!enabled || !visible || hasRow) return@LaunchedEffect
        delay(500)
        var attempt = 0
        try {
            while (isActive) {
                val result = withContext(Dispatchers.IO) { lyricsHelper.lookupAndStore(mediaMetadata, LyricsStore.Mode.FILL) }
                if (result != LyricsStore.Result.NO_CONNECTION) break
                delay(if (attempt++ < 2) 10_000L else 30_000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // the row of the song on screen (the flow may still hold the previous song's row for a moment), parsed off the main thread
    val raw = row?.takeIf { it.id == mediaMetadata.id }?.lyrics
    val lyrics by produceState(IslandLyrics.Off, mediaMetadata.id, raw) {
        value = if (raw == null) IslandLyrics.Off else withContext(Dispatchers.Default) { IslandLyricsLogic.fromStored(raw) }
    }
    val offsetMs = (song?.song?.lyricsOffset ?: 0).toLong()
    val synced = lyrics.status == IslandLyricsStatus.SYNCED && lyrics.lines.isNotEmpty()
    val shown = enabled && visible && synced

    val source = playhead ?: rememberPlayerPlayhead(playerConnection)
    var index by remember(mediaMetadata.id) { mutableIntStateOf(-1) }
    var sweep by remember(mediaMetadata.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(shown, lyrics, source, offsetMs) {
        if (!shown) return@LaunchedEffect
        val lines = lyrics.lines
        runLyricsPlayhead(source, LyricsClock(), { null }) { positionMs, _ ->
            val at = positionMs + offsetMs
            val i = IslandLyricsLogic.indexAt(lines, at)
            if (i != index) index = i
            val s = IslandLyricsLogic.sweepAt(lines, i, at)
            if (abs(s - sweep) > 0.002f || s == 0f || s == 1f) sweep = s
        }
    }

    // The line sits right above the progress bar: the space the player keeps under the title block (20 dp) closes while the
    // line is shown, so the title, the artist and the line all sit lower (owner 2026-11-16: "mulai dari lirik ke atas
    // agak dikebawahin, lirik pas di atas slider"). This composable owns that space for that reason.
    val gap by animateDpAsState(if (shown) 0.dp else 20.dp, tween(220), label = "playerMiniLyricGap")
    Column(modifier) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(22.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Crossfade(targetState = index, animationSpec = tween(160), label = "playerMiniLyric") { k ->
                val line = lyrics.lines.getOrNull(k)
                if (line != null) {
                    IslandSweepText(
                        text = line.text,
                        rtl = line.rtl,
                        ink = ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        sweep = { if (k == index) sweep else 1f },
                        follow = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(gap))
    }
}
