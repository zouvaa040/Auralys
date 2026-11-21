/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import com.metrolist.music.constants.LoudnessLevel
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The per-track gain rule behind "Normalisasi volume". Pure maths, no Android, so a unit test pins every number.
 *
 * ## What the numbers mean
 *
 * YouTube sends two figures for a track:
 *  - `perceptualLoudnessDb`: the measured integrated loudness of the track, in LUFS (an outside measurement with ffmpeg
 *    `ebur128` matched it to 0.01 LU: -11.71 LUFS in the response, -11.7 LUFS measured).
 *  - `loudnessDb`: the same figure relative to a reference level that depends on the client. The YouTube web player sends
 *    LUFS + 14 (reference -14 LUFS), the YouTube Music responses this app's test data come from send LUFS + 7 (reference
 *    -7 LUFS: loudnessDb -1.94 goes with -8.94 LUFS). Positive means louder than the reference.
 *
 * So when the perceptual figure exists it is the truth and the reference does not matter. Only when it is missing do we
 * have to assume a reference: [LEGACY_REFERENCE_LUFS], the YouTube Music one. If the real one was -14 LUFS the track is
 * treated as 7 dB louder than it is, so it gets 7 dB less gain: the error always goes toward quiet, never toward clipping.
 *
 * ## The rule
 *
 * `gain = clamp(target - measured, -MAX_CUT_DB, +MAX_BOOST_DB)`.
 *
 * The target comes from the "Tingkat kelantangan" setting ([LoudnessLevel]). The default is [DEFAULT_LEVEL], -7 LUFS,
 * which is what the rival app BitChord effectively plays at (its rule is `gain = -loudnessDb`, i.e. the reference of the
 * YouTube Music figure, capped at +3 dB) and louder than what Auralys played before (-14 LUFS).
 *
 * A boost needs headroom, and a track's peak is not known. That is why every gain above 0 dB goes through the
 * look-ahead limiter of [LoudnessEngine]: the boost can never clip, it can only be limited.
 */
object LoudnessGain {
    /** Out of the box: the loudest of the four presets (-7 LUFS). Everybody gets it unless they picked another level. */
    val DEFAULT_LEVEL: LoudnessLevel = LoudnessLevel.AGGRESSIVE

    /** The most a quiet track is raised. The old limit was +3 dB and there was no limiter behind it. */
    const val MAX_BOOST_DB = 6.0

    /** The most a loud track is lowered. Unchanged. */
    const val MAX_CUT_DB = 15.0

    /** Reference level assumed for a `loudnessDb` that arrives without `perceptualLoudnessDb` (see the class comment). */
    const val LEGACY_REFERENCE_LUFS = -7.0

    /** A measured loudness outside this range is a broken figure (a real track is between about -40 and 0 LUFS). */
    private const val MIN_PLAUSIBLE_LUFS = -70.0
    private const val MAX_PLAUSIBLE_LUFS = 10.0

    /** The track's loudness in LUFS, or null when YouTube gave nothing usable (then the track is played untouched). */
    fun measuredLufs(
        loudnessDb: Double?,
        perceptualLoudnessDb: Double?,
    ): Double? {
        perceptualLoudnessDb?.takeIf { it.isPlausibleLufs() }?.let { return it }
        return loudnessDb
            ?.takeIf { it.isFinite() }
            ?.let { it + LEGACY_REFERENCE_LUFS }
            ?.takeIf { it.isPlausibleLufs() }
    }

    /** Gain in dB that brings [measuredLufs] to [targetLufs], inside the boost and cut limits. 0 for a non-finite input. */
    fun gainDb(
        measuredLufs: Double,
        targetLufs: Double,
        maxBoostDb: Double = MAX_BOOST_DB,
        maxCutDb: Double = MAX_CUT_DB,
    ): Double {
        if (!measuredLufs.isFinite() || !targetLufs.isFinite()) return 0.0
        return (targetLufs - measuredLufs).coerceIn(-maxCutDb, maxBoostDb)
    }

    /** [gainDb] in millibels (the unit the processor and the cache use), or null when the track has no usable figure. */
    fun gainMb(
        loudnessDb: Double?,
        perceptualLoudnessDb: Double?,
        targetLufs: Double,
    ): Int? =
        measuredLufs(loudnessDb, perceptualLoudnessDb)
            ?.let { (gainDb(it, targetLufs) * 100.0).roundToInt() }

    /** dB to the linear factor a sample is multiplied by. */
    fun linearGain(gainDb: Double): Float = 10.0.pow(gainDb / 20.0).toFloat()

    /** Millibels to the linear factor. */
    fun linearGainFromMb(gainMb: Int): Float = linearGain(gainMb / 100.0)

    private fun Double.isPlausibleLufs(): Boolean = isFinite() && this in MIN_PLAUSIBLE_LUFS..MAX_PLAUSIBLE_LUFS
}
