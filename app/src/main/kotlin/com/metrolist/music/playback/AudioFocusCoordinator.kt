/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the focus handling needs from the player side. The service implements it on top of its current player. */
interface AudioFocusHost {
    val isPlaying: Boolean
    val isCasting: Boolean

    /** True while a crossfade owns the player volume (it applies the effective volume itself when it ends). */
    val isVolumeLocked: Boolean

    /** The volume the player has right now. */
    val currentVolume: Float

    /** The volume the player must have when no duck is active: user volume x mute x sleep fade. */
    val expectedVolume: Float

    fun pause()

    fun play()

    /** Writes the effective volume (including the focus multiplier) to the player. */
    fun applyVolume()
}

/** The two calls to Android's audio service. Behind an interface so a test can play the part of Android. */
interface AudioFocusPort {
    /** Returns one of `AudioManager.AUDIOFOCUS_REQUEST_*`. */
    fun request(): Int

    fun abandon()
}

class AudioManagerFocusPort(
    private val audioManager: AudioManager,
    listener: AudioManager.OnAudioFocusChangeListener,
) : AudioFocusPort {
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(listener)
            .setAcceptsDelayedFocusGain(true)
            .build()

    override fun request(): Int = audioManager.requestAudioFocus(focusRequest)

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

/**
 * Audio focus for the music service: pause on a call, duck on a notification, resume afterwards. The duck itself is
 * decided by [VolumeDuckGuard]; this class connects it to Android's callbacks and to the player.
 *
 * Why the service handles the focus itself instead of letting the player do it (`handleAudioFocus = true`): a crossfade
 * runs two players at the same time and the focus is per client, so the second player would take the focus from the
 * first one and pause it in the middle of the fade; casting and the AudioTrack recovery also need "was it playing when the
 * focus was taken".
 *
 * [focusMultiplier] is the focus part of the effective volume; the service combines it with the user volume.
 */
class AudioFocusCoordinator(
    private val scope: CoroutineScope,
    private val host: AudioFocusHost,
    private val guard: VolumeDuckGuard,
    private val focusMultiplier: MutableStateFlow<Float>,
    private val resumeDelayMs: Long = RESUME_DELAY_MS,
    /** The user chose to keep playing next to a game or another app: a permanent loss and a "duck" are ignored (a call still pauses). */
    private val keepPlaying: () -> Boolean = { false },
    portFactory: (AudioManager.OnAudioFocusChangeListener) -> AudioFocusPort,
) {
    private val port: AudioFocusPort = portFactory(AudioManager.OnAudioFocusChangeListener { onFocusChange(it) })

    private var reentrantFocusGain = false
    private var duckWatchdog: Job? = null

    /** True when a focus loss paused the music (or a duck happened while it played) and it should start again on the gain. */
    var wasPlayingBeforeLoss = false

    /** True while Android considers us the owner of the focus (also while ducked). */
    val hasFocus: Boolean
        get() = guard.holdsFocus

    val isDucked: Boolean
        get() = guard.ducked

    /** Called when the player wants to play. Returns whether the focus is ours. */
    fun request(): Boolean {
        // Ducked is still "ours": asking again would be answered with "granted" but without a gain callback.
        if (!guard.needsRequest) return true

        val wasDucked = guard.ducked
        val result =
            when (port.request()) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> VolumeDuckGuard.RequestResult.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> VolumeDuckGuard.RequestResult.DELAYED
                else -> VolumeDuckGuard.RequestResult.FAILED
            }
        guard.onRequestResult(result)
        Timber.tag(TAG).d("audio focus request -> %s (holds=%b)", result, guard.holdsFocus)
        if (wasDucked && !guard.ducked) {
            Timber.tag(TAG).d("audio focus granted again, duck lifted")
            cancelWatchdog()
            publishAndApply()
        }
        return guard.holdsFocus
    }

    /** Gives the focus back (the service stops). Also clears a duck, so nothing stays quiet. */
    fun abandon() {
        if (guard.holdsFocus || guard.ducked) {
            port.abandon()
        }
        guard.onAbandoned()
        cancelWatchdog()
        focusMultiplier.value = guard.focusMultiplier
    }

    /** Called when the player has started playing. Clears a pending resume and re-checks the volume. */
    fun onPlaybackResumed() {
        // Playing again: nothing is waiting for a gain any more, a later manual pause must stay paused.
        wasPlayingBeforeLoss = false
        restoreVolumeIfLost("playback resumed")
    }

    /** Stops the watchdog. The service calls it from onDestroy. */
    fun release() {
        cancelWatchdog()
    }

    internal fun onFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            -> {
                guard.onSignal(VolumeDuckGuard.Signal.GAIN)
                cancelWatchdog()
                logEvent(focusChange)
                focusMultiplier.value = guard.focusMultiplier

                if (wasPlayingBeforeLoss && !host.isPlaying && !reentrantFocusGain) {
                    reentrantFocusGain = true
                    scope.launch {
                        delay(resumeDelayMs)
                        if (guard.holdsFocus && wasPlayingBeforeLoss && !host.isPlaying) {
                            if (!host.isCasting) {
                                host.play()
                            }
                            wasPlayingBeforeLoss = false
                        }
                        reentrantFocusGain = false
                    }
                } else if (host.isPlaying) {
                    // ducked while playing and now back to full: nothing is pending, so a later manual pause stays paused
                    wasPlayingBeforeLoss = false
                }

                // Unconditional, so it is also the restore for "the player is quieter than the user volume".
                host.applyVolume()
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                guard.onSignal(VolumeDuckGuard.Signal.GAIN)
                cancelWatchdog()
                logEvent(focusChange)
                publishAndApply()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                if (keepPlaying()) {
                    Timber.tag(TAG).d("audio focus loss ignored: the user keeps the music playing")
                    return
                }
                guard.onSignal(VolumeDuckGuard.Signal.LOSS)
                cancelWatchdog()
                logEvent(focusChange)
                focusMultiplier.value = guard.focusMultiplier
                wasPlayingBeforeLoss = host.isPlaying
                if (host.isPlaying) {
                    host.pause()
                }
                // After the pause, so a lifted duck is not heard for a moment before the music stops.
                host.applyVolume()
                // Android already dropped us from its focus stack; this only releases our request object.
                port.abandon()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                guard.onSignal(VolumeDuckGuard.Signal.LOSS_TRANSIENT)
                cancelWatchdog()
                logEvent(focusChange)
                focusMultiplier.value = guard.focusMultiplier
                // Keep "was playing" once it is set: a second focus event (e.g. a notification right after a call) arrives
                // while we are already paused, and overwriting the flag with isPlaying=false meant the music never resumed.
                wasPlayingBeforeLoss = wasPlayingBeforeLoss || host.isPlaying
                if (host.isPlaying) {
                    host.pause()
                }
                host.applyVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (keepPlaying()) {
                    Timber.tag(TAG).d("audio focus duck ignored: the user keeps the music playing")
                    return
                }
                guard.onSignal(VolumeDuckGuard.Signal.DUCK)
                logEvent(focusChange)
                // A duck is not a pause: the music keeps playing, so there is nothing to resume later. (It used to set
                // "was playing" here, so a song the user paused during the duck started again by itself on the gain.)
                scheduleWatchdog()
                publishAndApply()
            }

            else -> Timber.tag(TAG).d("audio focus change ignored: %d", focusChange)
        }
    }

    /** The gain callback of a duck never came: a short sound is long over, so lift the duck. */
    internal fun onDuckWatchdog() {
        if (guard.expireStaleDuck()) {
            Timber.tag(TAG).w("audio focus duck lasted %d ms without a gain, lifting it", guard.maxDuckMs)
            publishAndApply()
        }
    }

    private fun restoreVolumeIfLost(reason: String) {
        if (host.isVolumeLocked) return
        val target = guard.volumeToRestore(host.currentVolume, host.expectedVolume) ?: return
        Timber.tag(TAG).d("player volume %.2f is below %.2f with no duck (%s), restoring", host.currentVolume, target, reason)
        host.applyVolume()
    }

    private fun publishAndApply() {
        focusMultiplier.value = guard.focusMultiplier
        host.applyVolume()
    }

    private fun scheduleWatchdog() {
        duckWatchdog?.cancel()
        duckWatchdog =
            scope.launch {
                while (guard.ducked) {
                    val wait = guard.msUntilStale()
                    if (wait <= 0L) {
                        onDuckWatchdog()
                        break
                    }
                    delay(wait)
                }
            }
    }

    private fun cancelWatchdog() {
        duckWatchdog?.cancel()
        duckWatchdog = null
    }

    private fun logEvent(focusChange: Int) {
        Timber.tag(TAG).d(
            "audio focus %s -> holds=%b ducked=%b playing=%b",
            focusName(focusChange),
            guard.holdsFocus,
            guard.ducked,
            host.isPlaying,
        )
    }

    private fun focusName(focusChange: Int): String =
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "OTHER($focusChange)"
        }

    private companion object {
        const val TAG = "AudioFocus"
        const val RESUME_DELAY_MS = 300L
    }
}
