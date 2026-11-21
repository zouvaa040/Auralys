/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * Pure state machine behind "the music gets quiet while another app makes a sound" (a notification, a navigation
 * prompt, a call) and, above all, "the music comes back to the volume the user chose".
 *
 * Android tells the app with [Signal.DUCK] to lower its volume and with [Signal.GAIN] to raise it again. The user's own
 * volume (the slider) is never touched: the duck is only a multiplier ([focusMultiplier]) that is combined with the
 * user volume, the mute switch and the sleep timer fade by [effectiveVolume].
 *
 * What the old code got wrong, and what this class pins down:
 *  - While ducked the app STILL HOLDS the focus. It used to mark the focus as lost, so the next play, pause or buffering
 *    event asked for the focus again. Android answers that request with "granted" but sends NO gain callback, so the duck
 *    was never lifted. Now [holdsFocus] stays true while ducked and [needsRequest] is false.
 *  - If a request is granted anyway (focus was really lost, or abandoned), [onRequestResult] lifts the duck, because no
 *    gain callback will follow.
 *  - A gain callback can be lost (some phone makers' audio services). [expireStaleDuck] lifts a duck that has lasted
 *    [maxDuckMs], and [volumeToRestore] is the guard for "the player volume is below what it should be and no duck is
 *    active".
 *
 * It knows nothing about Android, so a unit test drives it with a fake clock.
 */
class VolumeDuckGuard(
    private val clock: () -> Long,
    val duckMultiplier: Float = DEFAULT_DUCK_MULTIPLIER,
    val maxDuckMs: Long = MAX_DUCK_MS,
) {
    /** What Android told us, reduced to what matters for the volume. */
    enum class Signal {
        /** We have the focus (back). Any duck is over. */
        GAIN,

        /** Another app took the focus for good. We stop. */
        LOSS,

        /** Another app took the focus for a moment (a call). We pause. */
        LOSS_TRANSIENT,

        /** Another app plays a short sound over us (a notification). We stay on, quieter. */
        DUCK,
    }

    /** The answer of `AudioManager.requestAudioFocus`. */
    enum class RequestResult { GRANTED, DELAYED, FAILED }

    /** True while we are the owner of the audio focus in Android's eyes. A duck does not change that. */
    var holdsFocus: Boolean = false
        private set

    /** True from a [Signal.DUCK] until the matching gain, a granted request, an abandon or [expireStaleDuck]. */
    var ducked: Boolean = false
        private set

    private var duckedAtMs: Long = NEVER

    /** The part of the effective volume that comes from the focus: [duckMultiplier] while ducked, else 1. */
    val focusMultiplier: Float
        get() = if (ducked) duckMultiplier else 1f

    /** A request to Android is only needed when we do not hold the focus. Never while merely ducked. */
    val needsRequest: Boolean
        get() = !holdsFocus

    fun onSignal(signal: Signal) {
        when (signal) {
            Signal.GAIN -> {
                holdsFocus = true
                endDuck()
            }

            Signal.LOSS,
            Signal.LOSS_TRANSIENT,
            -> {
                holdsFocus = false
                endDuck()
            }

            Signal.DUCK -> {
                // holdsFocus is left alone: still true if we were playing, still false if a call paused us earlier.
                ducked = true
                duckedAtMs = clock()
            }
        }
    }

    fun onRequestResult(result: RequestResult) {
        when (result) {
            RequestResult.GRANTED -> {
                holdsFocus = true
                // Android sends no gain callback for a request, and it lifts its own ducking when it grants one.
                endDuck()
            }

            RequestResult.DELAYED,
            RequestResult.FAILED,
            -> holdsFocus = false
        }
    }

    /** We gave the focus back (the service stops). Nobody can duck us now. */
    fun onAbandoned() {
        holdsFocus = false
        endDuck()
    }

    /** True when a duck has lasted at least [maxDuckMs]: a short sound is long over, the gain callback must have been lost. */
    fun isDuckStale(): Boolean = ducked && clock() - duckedAtMs >= maxDuckMs

    /** Milliseconds until the running duck counts as stale; 0 when there is no duck. */
    fun msUntilStale(): Long = if (ducked) (maxDuckMs - (clock() - duckedAtMs)).coerceAtLeast(0L) else 0L

    /** Lifts a stale duck. Returns true when it did, so the caller applies the volume again. */
    fun expireStaleDuck(): Boolean {
        if (!isDuckStale()) return false
        endDuck()
        return true
    }

    /**
     * Defensive check, meant for "the player starts playing" and "the focus came back": when no duck is active the
     * player must not be quieter than [expected] (user volume x mute x sleep fade). Returns the volume to set, or null
     * when everything is fine (or while a duck legitimately lowers it).
     */
    fun volumeToRestore(
        actual: Float,
        expected: Float,
    ): Float? {
        if (ducked) return null
        return if (actual < expected - VOLUME_EPSILON) expected else null
    }

    private fun endDuck() {
        ducked = false
        duckedAtMs = NEVER
    }

    companion object {
        const val DEFAULT_DUCK_MULTIPLIER = 0.2f

        /** A notification, a prompt or an assistant is over well before this; a duck that is older lost its gain callback. */
        const val MAX_DUCK_MS = 30_000L

        private const val NEVER = Long.MIN_VALUE
        private const val VOLUME_EPSILON = 0.005f

        /**
         * The volume the player must have: the user's volume, silenced by the mute switch, scaled by the sleep timer fade and
         * by the focus duck. The user's volume is an input only; the result is never stored back as the user's volume.
         */
        fun effectiveVolume(
            userVolume: Float,
            muted: Boolean,
            sleepTimerMultiplier: Float,
            focusMultiplier: Float,
        ): Float {
            if (muted) return 0f
            return (userVolume * sleepTimerMultiplier * focusMultiplier).coerceIn(0f, 1f)
        }
    }
}
