/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * How the silent video follows the audio. The audio player is the clock and is never touched; this only decides
 * what the video player does. The rules are BitChord web's videoDeck (GPLv3, like Auralys), re-implemented in Auralys'
 * own code: a 250 ms tick, a hard seek when the picture is more than 0.35 s off, and a gentle change of the video speed
 * for smaller errors. Pure Kotlin (no Android types), unit tested.
 */

package com.metrolist.music.video

import kotlin.math.abs
import kotlin.math.sign

object VideoSyncLogic {
    /** Time between two looks at the clocks. */
    const val TICK_MS = 250L

    /** Above this the picture is jumped to the audio position. */
    const val HARD_SEEK_MS = 350L

    /** Between this and [HARD_SEEK_MS] the video speed is nudged; below it the error is left alone. */
    const val NUDGE_FROM_MS = 100L

    /** The nudge is 5 percent at [NUDGE_FROM_MS] and grows to 10 percent at [HARD_SEEK_MS]. */
    const val NUDGE_MIN = 0.05f
    const val NUDGE_MAX = 0.10f

    /** At most one jump per this time, and per [BUFFERING_GAP_MS] while the video buffers. */
    const val CORRECTION_GAP_MS = 1_500L
    const val BUFFERING_GAP_MS = 4_000L

    /** While the audio is paused the picture still follows a seek, but only for errors above this and not more often than [PAUSED_GAP_MS]. */
    const val PAUSED_SEEK_MS = 250L
    const val PAUSED_GAP_MS = 300L

    /** The video holds its last frame once the audio is this close to the end of the video. */
    const val END_MARGIN_MS = 200L

    const val NO_CORRECTION = Long.MIN_VALUE

    data class Input(
        val audioPositionMs: Long,
        val videoPositionMs: Long,
        val audioPlaying: Boolean,
        val audioBuffering: Boolean,
        val videoReady: Boolean,
        val videoBuffering: Boolean,
        /** 0 when the length of the video is not known yet. */
        val videoDurationMs: Long,
        /** Speed of the audio player; the video copies it. */
        val speed: Float,
        val nowMs: Long,
        /** When the last jump was made ([NO_CORRECTION] = never). */
        val lastCorrectionAtMs: Long,
        /** A seek or a state change of the audio just happened: the throttle does not apply once. */
        val force: Boolean,
    )

    data class Decision(
        /** The video should be playing (otherwise paused on the current frame). */
        val play: Boolean,
        /** Speed for the video player (the audio speed, nudged). */
        val rate: Float,
        /** Jump the video to this position, or null. */
        val seekToMs: Long?,
    )

    fun decide(input: Input): Decision {
        val speed = input.speed.coerceIn(0.25f, 2f)
        val pastEnd = input.videoDurationMs > 0 && input.audioPositionMs >= input.videoDurationMs - END_MARGIN_MS
        val play = input.audioPlaying && !input.audioBuffering && !pastEnd
        val drift = input.videoPositionMs - input.audioPositionMs
        val sinceLast =
            if (input.lastCorrectionAtMs == NO_CORRECTION) Long.MAX_VALUE else (input.nowMs - input.lastCorrectionAtMs).coerceAtLeast(0L)

        if (pastEnd) return Decision(play = false, rate = speed, seekToMs = null)

        if (!play) {
            // paused or waiting: keep the frame in line with a seek made while paused, but do not chase small errors
            val seek = abs(drift) > PAUSED_SEEK_MS && (input.force || sinceLast >= PAUSED_GAP_MS)
            return Decision(play = false, rate = speed, seekToMs = if (seek) input.audioPositionMs else null)
        }

        val gap = if (input.videoBuffering) BUFFERING_GAP_MS else CORRECTION_GAP_MS
        val size = abs(drift)
        if (size > HARD_SEEK_MS) {
            val seek = input.force || sinceLast >= gap
            // while a jump is not allowed yet, the strongest nudge keeps the error from growing
            val rate = if (!seek && input.videoReady) nudged(speed, drift, NUDGE_MAX) else speed
            return Decision(play = true, rate = rate, seekToMs = if (seek) input.audioPositionMs else null)
        }
        if (size >= NUDGE_FROM_MS && input.videoReady) {
            val t = (size - NUDGE_FROM_MS).toFloat() / (HARD_SEEK_MS - NUDGE_FROM_MS).toFloat()
            return Decision(play = true, rate = nudged(speed, drift, NUDGE_MIN + (NUDGE_MAX - NUDGE_MIN) * t), seekToMs = null)
        }
        return Decision(play = true, rate = speed, seekToMs = null)
    }

    /** Video ahead of the audio (positive drift) = slower; video behind = faster. */
    private fun nudged(
        speed: Float,
        drift: Long,
        amount: Float,
    ): Float = speed * (1f - sign(drift.toFloat()) * amount)
}
