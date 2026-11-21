/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * Watches for a song that should play but sits in "buffering" (the pause icon shown, the time at 0:00 or frozen).
 *
 * The service's old guard looked only at the first 5 s of a song, fired once per song, and its recovery did nothing: a
 * seek to the same position while buffering is ignored by ExoPlayer and prepare() is ignored unless the player is idle.
 * This one covers the whole song, tries [initialStepsMs] (start of a song) or [midStepsMs] (later) with a growing wait,
 * and then gives up so the service can skip or stop with a message. While there is no network it does not count: waiting
 * for the network is handled elsewhere and is not a stall.
 *
 * Fed about once a second by the service; pure, so it can be tested.
 */
internal class StallWatchdog(
    private val clock: () -> Long,
    private val initialStepsMs: LongArray = longArrayOf(12_000L, 20_000L, 30_000L),
    private val midStepsMs: LongArray = longArrayOf(20_000L, 30_000L, 45_000L),
    private val startWindowMs: Long = 3_000L,
    private val recoveredAfterMs: Long = 3_000L,
) {
    sealed interface Action {
        data object None : Action

        /** Re-prepare the song at its position with a fresh stream. */
        data class Recover(
            val attempt: Int,
            val atStart: Boolean,
        ) : Action

        /** Nothing helped. */
        data class GiveUp(
            val neverStarted: Boolean,
        ) : Action
    }

    private var mediaId: String? = null
    private var stallSinceMs = -1L
    private var stallAtStart = false
    private var recoveries = 0
    private var playedSinceRecoveryFromMs = -1L

    /** The number of recoveries for the current song (for tests and logs). */
    val recoveriesForCurrent: Int get() = recoveries

    fun onTick(
        mediaId: String?,
        buffering: Boolean,
        playing: Boolean,
        playWhenReady: Boolean,
        positionMs: Long,
        networkUp: Boolean,
        eligible: Boolean,
    ): Action {
        if (mediaId != this.mediaId) {
            this.mediaId = mediaId
            recoveries = 0
            stallSinceMs = -1L
            playedSinceRecoveryFromMs = -1L
        }
        if (mediaId == null || !eligible || !playWhenReady) {
            stallSinceMs = -1L
            return Action.None
        }
        if (playing) {
            stallSinceMs = -1L
            // the song plays again: after a while its recoveries start from zero
            if (recoveries > 0) {
                if (playedSinceRecoveryFromMs < 0L) playedSinceRecoveryFromMs = positionMs
                if (positionMs - playedSinceRecoveryFromMs >= recoveredAfterMs) {
                    recoveries = 0
                    playedSinceRecoveryFromMs = -1L
                }
            }
            return Action.None
        }
        if (!buffering || !networkUp) {
            stallSinceMs = -1L
            return Action.None
        }
        val now = clock()
        if (stallSinceMs < 0L) {
            stallSinceMs = now
            stallAtStart = positionMs < startWindowMs
            return Action.None
        }
        val steps = if (stallAtStart) initialStepsMs else midStepsMs
        val limit = steps[recoveries.coerceAtMost(steps.lastIndex)]
        if (now - stallSinceMs < limit) return Action.None
        stallSinceMs = now
        playedSinceRecoveryFromMs = -1L
        if (recoveries < steps.size) {
            recoveries++
            return Action.Recover(recoveries, stallAtStart)
        }
        recoveries = 0
        return Action.GiveUp(neverStarted = stallAtStart)
    }

    fun reset() {
        mediaId = null
        recoveries = 0
        stallSinceMs = -1L
        playedSinceRecoveryFromMs = -1L
    }
}
