/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * What the service does after a playback error: the rules in one place, without Android, so they can be tested.
 *
 * The rules (round 4.3.4, reports 2-4):
 *  - a song that fails first gets a FRESH stream (another client after a refusal) and resumes at its position, with a
 *    growing pause between tries; only after [maxAttemptsPerSong] tries is it given up;
 *  - no connection is never a reason to skip: the service waits for the network;
 *  - a slow connection is retried longer, then playback STOPS with a message (the next song would be just as slow, and
 *    skipping would run through the whole queue);
 *  - a song YouTube refuses for good (removed, private, region) is skipped at once;
 *  - after [maxFailedSongsInARow] songs in a row failed without one of them really playing, playback stops with a message
 *    (before, a song that reached "ready" for a moment reset the counter, and a whole playlist ran through in seconds);
 *  - "really playing" means the position moved [progressResetMs] while playing, not just that the player was ready;
 *  - a connection error (DNS, refused, unreachable) while the phone says it is online is retried with a growing pause
 *    ([UNREACHABLE_BACKOFF_MS] with jitter), a fresh stream from the second retry on, and at once when a validated network
 *    appears; after [unreachableBudgetMs] playback stops with a message and resumes by itself when a validated network
 *    appears (review 2026-11-12, finding 6: it used to wait for a network change that never came, report 4).
 */
internal class PlaybackRecovery(
    private val maxAttemptsPerSong: Int = 3,
    private val maxSlowAttempts: Int = 4,
    private val maxFailedSongsInARow: Int = 3,
    private val progressResetMs: Long = 5_000L,
    private val unreachableBudgetMs: Long = UNREACHABLE_BUDGET_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    enum class Action { RETRY, WAIT_FOR_NETWORK, SKIP, STOP }

    enum class StopReason {
        /** The connection is too slow or keeps dropping. */
        NETWORK,

        /** The phone says it is online but the server cannot be reached; playback resumes when a validated network appears. */
        UNREACHABLE,

        /** Several songs in a row could not be played. */
        SONGS_FAILING,

        /** This song cannot be played and there is nothing to skip to. */
        SONG_FAILED,

        /** The phone's audio output keeps failing. */
        AUDIO_OUTPUT,
    }

    data class Decision(
        val action: Action,
        val delayMs: Long = 0L,
        /** Forget the stream address (and the running extraction) so the retry resolves a new one. */
        val freshStream: Boolean = false,
        /** Do not use the client that gave the failing address again for this song (for a few minutes). */
        val excludeClient: Boolean = false,
        /** Drop the bytes of this song in the player cache (they may belong to another format). */
        val clearCache: Boolean = false,
        /** List software decoders first from now on. */
        val softwareDecoder: Boolean = false,
        val stopReason: StopReason? = null,
        /** 1-based number of this try for the song (0 when not a retry). */
        val attempt: Int = 0,
        /** A RETRY that may run at once when a validated network appears, or a STOP after which playback resumes then. */
        val onValidatedNetwork: Boolean = false,
    )

    private val attempts = HashMap<String, Int>()
    private var failedSongsInARow = 0
    private var softwareDecoderTried = false

    // connection errors while "online": one outage, whatever song it hits (skipping would not help)
    private var unreachableTries = 0
    private var unreachableSinceMs = NOT_SET

    /** Connection-error retries in the current outage (for tests). */
    val unreachableAttempts: Int get() = unreachableTries

    // progress of the song that is playing now
    private var progressMediaId: String? = null
    private var progressBaseMs = -1L

    val failedInARow: Int get() = failedSongsInARow

    fun attemptsFor(mediaId: String): Int = attempts[mediaId] ?: 0

    fun onError(
        mediaId: String,
        kind: PlaybackFailureKind,
        networkUp: Boolean,
        canSkip: Boolean,
    ): Decision {
        // the next progress is measured from where the song is after this error
        if (progressMediaId == mediaId) progressBaseMs = -1L
        // no network at all: wait for one, never skip (the next song would fail the same way)
        if (!networkUp && kind != PlaybackFailureKind.LOCAL_FILE && kind != PlaybackFailureKind.AUDIO_OUTPUT) {
            return Decision(Action.WAIT_FOR_NETWORK)
        }
        return when (kind) {
            PlaybackFailureKind.NETWORK_DOWN -> unreachable()

            PlaybackFailureKind.AUDIO_OUTPUT -> {
                val n = bump(mediaId)
                if (n <= 2) {
                    Decision(Action.RETRY, delayMs = 1_500L * n, attempt = n)
                } else {
                    attempts.remove(mediaId)
                    Decision(Action.STOP, stopReason = StopReason.AUDIO_OUTPUT)
                }
            }

            PlaybackFailureKind.DECODER -> {
                if (!softwareDecoderTried) {
                    softwareDecoderTried = true
                    Decision(Action.RETRY, delayMs = 500L, softwareDecoder = true, clearCache = true, freshStream = true, attempt = bump(mediaId))
                } else {
                    songFailed(mediaId, canSkip)
                }
            }

            PlaybackFailureKind.LOCAL_FILE, PlaybackFailureKind.SONG_UNAVAILABLE -> songFailed(mediaId, canSkip)

            PlaybackFailureKind.NETWORK_SLOW -> {
                val n = bump(mediaId)
                if (n <= maxSlowAttempts) {
                    // the extraction may still be running: keep it (no fresh stream) and wait longer each time
                    Decision(Action.RETRY, delayMs = SLOW_BACKOFF_MS[(n - 1).coerceIn(0, SLOW_BACKOFF_MS.lastIndex)], freshStream = n >= 3, attempt = n)
                } else {
                    attempts.remove(mediaId)
                    failedSongsInARow = 0
                    Decision(Action.STOP, freshStream = true, stopReason = StopReason.NETWORK)
                }
            }

            PlaybackFailureKind.STREAM_REJECTED -> {
                val n = bump(mediaId)
                if (n <= maxAttemptsPerSong) {
                    Decision(
                        Action.RETRY,
                        delayMs = REJECTED_BACKOFF_MS[(n - 1).coerceIn(0, REJECTED_BACKOFF_MS.lastIndex)],
                        freshStream = true,
                        excludeClient = true,
                        clearCache = n >= 2,
                        attempt = n,
                    )
                } else {
                    songFailed(mediaId, canSkip)
                }
            }

            PlaybackFailureKind.CACHE_MISMATCH, PlaybackFailureKind.UNKNOWN -> {
                val n = bump(mediaId)
                if (n <= 2) {
                    Decision(Action.RETRY, delayMs = if (n == 1) 600L else 2_000L, freshStream = true, clearCache = true, attempt = n)
                } else {
                    songFailed(mediaId, canSkip)
                }
            }
        }
    }

    /**
     * A connection error (DNS, refused, no route) while the phone says it is online: a DNS hiccup, a Wi-Fi that lost its
     * upstream, a VPN or private DNS problem, a server node that refuses. Retried with a growing pause and jitter; from
     * the second retry on with a fresh stream (another address, another node); at once when a validated network appears.
     * After [unreachableBudgetMs] of this, playback stops with a message (and the service resumes it when a validated
     * network appears). Never a skip, and not counted as a failed song.
     */
    private fun unreachable(): Decision {
        val now = clock()
        if (unreachableSinceMs == NOT_SET) unreachableSinceMs = now
        if (now - unreachableSinceMs >= unreachableBudgetMs) {
            resetUnreachable()
            failedSongsInARow = 0
            return Decision(Action.STOP, freshStream = true, stopReason = StopReason.UNREACHABLE, onValidatedNetwork = true)
        }
        unreachableTries++
        val n = unreachableTries
        val base = UNREACHABLE_BACKOFF_MS[(n - 1).coerceIn(0, UNREACHABLE_BACKOFF_MS.lastIndex)]
        // +-20 % so that many phones behind one broken DNS do not all retry in the same second
        val delay = (base * (0.8 + 0.4 * jitter().coerceIn(0.0, 1.0))).toLong()
        return Decision(Action.RETRY, delayMs = delay, freshStream = n >= 2, attempt = n, onValidatedNetwork = true)
    }

    /** A validated network appeared after the outage: the next connection error starts a new outage. */
    fun onNetworkBack() = resetUnreachable()

    private fun resetUnreachable() {
        unreachableTries = 0
        unreachableSinceMs = NOT_SET
    }

    /**
     * The stall watchdog gave up on [mediaId]: a song that never started counts as a failed song (skip, and stop after
     * several in a row); a song that stalled in the middle stops with a network message.
     */
    fun onStallGiveUp(
        mediaId: String,
        neverStarted: Boolean,
        canSkip: Boolean,
    ): Decision {
        attempts.remove(mediaId)
        if (!neverStarted) {
            failedSongsInARow = 0
            return Decision(Action.STOP, freshStream = true, stopReason = StopReason.NETWORK)
        }
        return songFailed(mediaId, canSkip)
    }

    /**
     * Called about once a second with the playing song. When it has really played [progressResetMs] since it (re)started,
     * its tries and the failed-songs-in-a-row count start again.
     */
    fun onProgress(
        mediaId: String?,
        positionMs: Long,
        isPlaying: Boolean,
    ) {
        if (mediaId == null || !isPlaying) return
        if (mediaId != progressMediaId) {
            progressMediaId = mediaId
            progressBaseMs = positionMs
            return
        }
        if (progressBaseMs < 0L || positionMs < progressBaseMs) {
            progressBaseMs = positionMs
            return
        }
        if (positionMs - progressBaseMs >= progressResetMs) {
            attempts.remove(mediaId)
            failedSongsInARow = 0
            resetUnreachable()
        }
    }

    /** The user pressed play, picked a song or tapped retry: that song gets a fresh set of tries. */
    fun onUserAction(mediaId: String?) {
        if (mediaId != null) {
            attempts.remove(mediaId)
            prematureEnds.remove(mediaId)
        }
        failedSongsInARow = 0
        // someone is there: the run-through guard and the outage clock start again
        fastEndsInARow = 0
        resetUnreachable()
    }

    private val prematureEnds = HashMap<String, Int>()
    private var fastEndsInARow = 0

    /**
     * A song reached its end. True when it is the [MAX_FAST_ENDS_IN_A_ROW]th song in a row that took far less time than
     * its length (under a quarter, at the speed it played): the queue is being run through without being heard (video A:
     * a whole playlist in seconds), so playback must stop instead of going on. Whatever the cause (sound that comes out
     * silent and is skipped, streams that end at once), this is the last guard.
     *
     * Only NATURAL ends count (review 2026-11-12, finding 19): [userSeekedForwardMs] is how far the user moved the
     * position forward in this song (seek bar, seek buttons). Scrubbing to the end of three songs while previewing a
     * playlist covered them, it did not run through them, and must not stop playback.
     */
    fun onSongEnded(
        durationMs: Long,
        playedWallMs: Long,
        speed: Float,
        userSeekedForwardMs: Long = 0L,
    ): Boolean {
        val covered = playedWallMs * speed.coerceIn(0.25f, 4f) + userSeekedForwardMs.coerceAtLeast(0L)
        val fast = durationMs >= 60_000L && playedWallMs >= 0L && covered < durationMs / 4
        fastEndsInARow = if (fast) fastEndsInARow + 1 else 0
        if (fastEndsInARow >= MAX_FAST_ENDS_IN_A_ROW) {
            fastEndsInARow = 0
            return true
        }
        return false
    }

    /** [mediaId] ended well before its length (see [endedEarly]): true while it may still be resumed (twice per song). */
    fun onPrematureEnd(mediaId: String): Boolean {
        val n = (prematureEnds[mediaId] ?: 0) + 1
        prematureEnds[mediaId] = n
        return n <= MAX_PREMATURE_RESUMES
    }

    private fun bump(mediaId: String): Int {
        val n = (attempts[mediaId] ?: 0) + 1
        attempts[mediaId] = n
        return n
    }

    private fun songFailed(
        mediaId: String,
        canSkip: Boolean,
    ): Decision {
        attempts.remove(mediaId)
        failedSongsInARow++
        if (failedSongsInARow >= maxFailedSongsInARow) {
            failedSongsInARow = 0
            return Decision(Action.STOP, freshStream = true, stopReason = StopReason.SONGS_FAILING)
        }
        if (!canSkip) {
            failedSongsInARow = 0
            return Decision(Action.STOP, freshStream = true, stopReason = StopReason.SONG_FAILED)
        }
        return Decision(Action.SKIP, freshStream = true)
    }

    internal companion object {
        val REJECTED_BACKOFF_MS = longArrayOf(800L, 2_000L, 4_000L)
        val SLOW_BACKOFF_MS = longArrayOf(1_500L, 4_000L, 8_000L, 15_000L)

        /** Pauses before the retries after a connection error while online (the last one repeats), +-20 % jitter. */
        val UNREACHABLE_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 45_000L, 90_000L)

        /** How long connection errors while online are retried before playback stops with a message. */
        const val UNREACHABLE_BUDGET_MS = 5 * 60_000L
        private const val NOT_SET = Long.MIN_VALUE
        const val MAX_PREMATURE_RESUMES = 2
        const val MAX_FAST_ENDS_IN_A_ROW = 3

        /**
         * True when a song that the player moved on from at [endedAtMs] had clearly not reached its end: a song of at least
         * 30 s that stopped more than 15 s and more than 8 % before its length. Unknown lengths never count.
         */
        fun endedEarly(
            endedAtMs: Long,
            durationMs: Long,
        ): Boolean {
            if (durationMs <= 0L || durationMs == Long.MIN_VALUE + 1 || durationMs < 30_000L || endedAtMs < 0L) return false
            return endedAtMs < durationMs - 15_000L && endedAtMs < durationMs * 0.92
        }
    }
}
