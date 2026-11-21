/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The one line "Mode video: ..." in the media-info dialog. It is what the owner reads (and sends) when the video does not
 * work: available / no matching clip / failed: <short reason> / paused for now (try again in N seconds). The decision is a
 * pure function ([VideoStatusLine.resolve]); the composable only turns it into text.
 */

package com.metrolist.music.video

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.constants.VideoModeEnabledKey
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

/** What the line says, without the words. */
sealed interface VideoStatusLine {
    data object Off : VideoStatusLine

    /** The whole video mode rests; [seconds] until it tries again. */
    data class Cooldown(
        val seconds: Int,
    ) : VideoStatusLine

    data object Unknown : VideoStatusLine

    data object Checking : VideoStatusLine

    data object Available : VideoStatusLine

    data object NoMatch : VideoStatusLine

    data class Failed(
        val kind: VideoFailureKind,
        val retrying: Boolean,
    ) : VideoStatusLine

    companion object {
        /** The setting off wins, then a rest of the whole mode, then what happened to this song. */
        fun resolve(
            enabled: Boolean,
            cooldownRemainingMs: Long,
            status: VideoStatus,
        ): VideoStatusLine =
            when {
                !enabled -> Off
                cooldownRemainingMs > 0L -> Cooldown(((cooldownRemainingMs + 999L) / 1000L).toInt())
                else ->
                    when (status) {
                        VideoStatus.Unknown -> Unknown
                        VideoStatus.Checking -> Checking
                        VideoStatus.Available -> Available
                        VideoStatus.NoMatch -> NoMatch
                        is VideoStatus.Failed -> Failed(status.kind, status.retrying)
                    }
            }
    }
}

@StringRes
internal fun VideoFailureKind.reasonRes(): Int =
    when (this) {
        VideoFailureKind.OFFLINE -> R.string.video4_reason_offline
        VideoFailureKind.TIMEOUT -> R.string.video4_reason_timeout
        VideoFailureKind.STALL -> R.string.video4_reason_stall
        VideoFailureKind.NO_FORMAT -> R.string.video4_reason_no_format
        VideoFailureKind.UNPLAYABLE -> R.string.video4_reason_unplayable
        VideoFailureKind.AGE_GEO -> R.string.video4_reason_age_geo
        VideoFailureKind.UNSUPPORTED_CODEC -> R.string.video4_reason_codec
        VideoFailureKind.BOT_CHECK -> R.string.video4_reason_bot_check
        VideoFailureKind.FORBIDDEN -> R.string.video4_reason_forbidden
        VideoFailureKind.ATTESTATION -> R.string.video4_reason_attestation
        VideoFailureKind.SABR_PROTOCOL -> R.string.video4_reason_sabr
        VideoFailureKind.DECODER -> R.string.video4_reason_decoder
        VideoFailureKind.UNKNOWN -> R.string.video4_reason_unknown
    }

/** The text of the status line for [songId]; it follows the diagnostics and counts the rest down once a second. */
@Composable
fun rememberVideoStatusText(songId: String): String {
    val enabled by rememberPreference(VideoModeEnabledKey, true)
    val version by VideoDiagnostics.version.collectAsState()
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            clock++
        }
    }
    // reading both makes the text follow a new status and the countdown
    @Suppress("UNUSED_VARIABLE") val follow = version + clock
    val line = VideoStatusLine.resolve(enabled, GlobalVideoSession.shared.cooldownRemainingMs(), VideoDiagnostics.statusFor(songId))
    return when (line) {
        VideoStatusLine.Off -> stringResource(R.string.video4_status_off)
        is VideoStatusLine.Cooldown -> stringResource(R.string.video4_status_cooldown, line.seconds)
        VideoStatusLine.Unknown -> stringResource(R.string.video4_status_unknown)
        VideoStatusLine.Checking -> stringResource(R.string.video4_status_checking)
        VideoStatusLine.Available -> stringResource(R.string.video4_status_available)
        VideoStatusLine.NoMatch -> stringResource(R.string.video4_status_nomatch)
        is VideoStatusLine.Failed ->
            stringResource(
                if (line.retrying) R.string.video4_status_failed_retry else R.string.video4_status_failed,
                stringResource(line.kind.reasonRes()),
            )
    }
}
