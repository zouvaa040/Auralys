/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.ConcurrentHashMap

/**
 * How far ahead the player may load (review 2026-11-12, finding 7).
 *
 * Round 4.3.4 let the buffer grow to the whole song (15 min, capped at 8 MiB) so a song that has loaded plays through a
 * weak or busy connection. On mobile data that means every started song is downloaded whole at once: someone who skips
 * after 20 s pays 3-8 MB per skip instead of about 1 MB. So the whole-song budget is used only on an unmetered network
 * without the app's data saver; otherwise the buffer is kept between [meteredResumeUs] and [meteredMaxUs] (still more
 * than the 50 s of 4.3.3) and at most [meteredMaxBytes]. The choice is made at every load decision, so a change of
 * network (Wi-Fi to data or back) counts from the next piece on, at the latest for the next song.
 *
 * Pure (no Android): one state per player, the delegate's answer and the facts come in as arguments.
 */
internal class LoadBudget(
    private val meteredMaxUs: Long = METERED_MAX_US,
    private val meteredResumeUs: Long = METERED_RESUME_US,
    private val meteredMaxBytes: Int = METERED_MAX_BYTES,
    private val alwaysLoadBelowUs: Long = ALWAYS_LOAD_BELOW_US,
) {
    private val loading = ConcurrentHashMap<Any, Boolean>()

    /** Whether [player] may go on loading. [delegateSays] is the whole-song load control's own answer. */
    fun shouldLoad(
        player: Any,
        delegateSays: Boolean,
        wholeSong: Boolean,
        bufferedUs: Long,
        bytesAllocated: Int,
    ): Boolean {
        if (wholeSong || !delegateSays) {
            loading[player] = delegateSays
            return delegateSays
        }
        val now =
            when {
                // never starve the player, whatever the byte count says
                bufferedUs < alwaysLoadBelowUs -> true
                bufferedUs >= meteredMaxUs || bytesAllocated >= meteredMaxBytes -> false
                bufferedUs < meteredResumeUs -> true
                // in between: keep doing what we did (no stop-start for every few kilobytes)
                else -> loading[player] ?: true
            }
        loading[player] = now
        return now
    }

    fun forget(player: Any) {
        loading.remove(player)
    }

    internal companion object {
        const val METERED_MAX_US = 90_000_000L
        const val METERED_RESUME_US = 60_000_000L
        const val METERED_MAX_BYTES = 2 * 1024 * 1024
        const val ALWAYS_LOAD_BELOW_US = 15_000_000L
    }
}

/**
 * [delegate] (the whole-song DefaultLoadControl) with the [LoadBudget] on top. Everything but the "go on loading?"
 * question is passed through unchanged, so start thresholds, the allocator and preloading work as before.
 */
@UnstableApi
internal class NetworkAwareLoadControl(
    private val delegate: LoadControl,
    private val wholeSong: () -> Boolean,
    private val budget: LoadBudget = LoadBudget(),
) : LoadControl {
    private val allocators = ConcurrentHashMap<PlayerId, Allocator>()

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) {
        budget.forget(playerId)
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        budget.forget(playerId)
        allocators.remove(playerId)
        delegate.onReleased(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId).also { allocators.putIfAbsent(playerId, it) }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // always asked, so the delegate keeps its own state in step
        val delegateSays = delegate.shouldContinueLoading(parameters)
        val allowWholeSong =
            try {
                wholeSong()
            } catch (_: Exception) {
                false
            }
        val bytes = allocators[parameters.playerId]?.totalBytesAllocated ?: 0
        return budget.shouldLoad(parameters.playerId, delegateSays, allowWholeSong, parameters.bufferedDurationUs, bytes)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}
