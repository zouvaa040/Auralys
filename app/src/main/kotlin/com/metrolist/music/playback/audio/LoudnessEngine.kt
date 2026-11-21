/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The sample maths behind "Normalisasi volume": a smooth per-track gain followed by a look-ahead peak limiter, on
 * interleaved float samples. It knows nothing about Android or ExoPlayer, so a JVM test can run synthetic music through it.
 *
 * ## Gain
 *
 * The factor moves toward the target with a one-pole glide of [GLIDE_MS], so a change of track (or of level in the
 * settings) is a ramp, never a step: no click. [reset] makes the next block jump to its target instead (a new stream
 * starts from silence, there is nothing to click against).
 *
 * ## Limiter
 *
 * A gain above 1 can push a peak past full scale, and the app does not know a track's peak. So after the gain every frame
 * is checked, and the signal is held under [CEILING] (-0.5 dBFS) by a limiter that sees [lookaheadFrames] frames ahead:
 *
 *  1. the loudest channel of the incoming frame gives the gain it would need (`greq`; 1 while under the knee);
 *  2. the smallest `greq` of the last `lookaheadFrames + 1` frames is taken (sliding minimum, O(1) per frame);
 *  3. it goes down at once and comes back up with a [RELEASE_MS] time constant (no pumping on drum hits);
 *  4. the result is averaged over the same `lookaheadFrames + 1` frames, which turns the instant drop into a ramp that
 *     ends exactly when the peak leaves the delay line.
 *
 * Because the sample that leaves the delay line is covered by every term of that average, its applied gain is never above
 * the gain it needed: the output cannot pass the ceiling. This is exact, not a tuning, and the test asserts it.
 *
 * The knee is soft (a [KNEE_START] to [CEILING] tanh curve on the peak), so the limiter starts without a corner.
 *
 * The delay line makes the output [lookaheadFrames] frames late (5 ms): the first block after [reset] starts with that
 * much silence and [drain] returns the tail at the end of a stream, so no sample is lost.
 *
 * Cheap on purpose (2 to 3 GB phones): two float arrays of a few hundred entries, one sliding-minimum deque, no allocation
 * after construction.
 */
class LoudnessEngine(
    val sampleRate: Int,
    val channels: Int,
    lookaheadMs: Double = LOOKAHEAD_MS,
    releaseMs: Double = RELEASE_MS,
    glideMs: Double = GLIDE_MS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
    }

    /** Frames of delay (and of look-ahead). */
    val lookaheadFrames: Int = max(1, (sampleRate * lookaheadMs / 1000.0).roundToInt())

    private val window = lookaheadFrames + 1
    private val glideCoef: Double = 1.0 - exp(-1.0 / (glideMs / 1000.0 * sampleRate))
    private val releaseCoef: Float = coefficient(releaseMs, sampleRate)

    // audio delay line: gained samples of the last lookaheadFrames frames
    private val delay = FloatArray(lookaheadFrames * channels)
    private var delayPos = 0

    // sliding minimum of greq over the last `window` frames: monotonic deque of (value, frame index)
    private val dequeValue = FloatArray(window + 1)
    private val dequeIndex = LongArray(window + 1)
    private var dequeHead = 0
    private var dequeSize = 0

    // limiter gain after the release, and its running average over `window` frames
    private val history = FloatArray(window) { 1f }
    private var historyPos = 0
    private var historySum = window.toDouble()
    private var held = 1f
    private var unityRun = 0

    private var frameIndex = 0L
    // double: a float glide stalls a few 1e-5 short of its target (the step falls under the float spacing near 1.0)
    private var gain = 1.0
    private var snapGain = true
    private val silence = FloatArray(lookaheadFrames * channels)

    /** The factor the gain is at right now (for tests). */
    val currentGain: Float get() = gain.toFloat()

    /** Forget everything and start from silence. The next block starts at its target gain. */
    fun reset() {
        delay.fill(0f)
        delayPos = 0
        dequeHead = 0
        dequeSize = 0
        history.fill(1f)
        historyPos = 0
        historySum = window.toDouble()
        held = 1f
        unityRun = 0
        frameIndex = 0L
        snapGain = true
    }

    /**
     * Processes [frames] interleaved frames from [input] into [output]; both may be the same array (in place).
     *
     * @param targetGain linear factor the gain glides to (1 = untouched)
     * @param limit true while the limiter must run (normalisation on). It also keeps running while the gain is still
     *   gliding back to 1, so switching off mid-song cannot let a boosted peak through.
     */
    fun process(
        input: FloatArray,
        output: FloatArray,
        frames: Int,
        targetGain: Float,
        limit: Boolean,
    ) {
        require(frames >= 0 && frames * channels <= input.size && frames * channels <= output.size) { "buffer too small" }
        val target = targetGain.toDouble()
        if (snapGain) {
            gain = target
            snapGain = false
        }
        val ch = channels
        var g = gain
        var base = 0
        for (f in 0 until frames) {
            g += (target - g) * glideCoef
            if (abs(target - g) < GAIN_SETTLE) g = target
            val gf = g.toFloat()
            val limiting = limit || abs(g - 1.0) > UNITY_BAND

            var peak = 0f
            val d = delayPos * ch
            for (c in 0 until ch) {
                var x = input[base + c] * gf
                if (x - x != 0f) x = 0f // NaN or infinity from a broken decoder: silence, never a poisoned limiter
                output[base + c] = delay[d + c] // the sample leaving the delay line; scaled below
                delay[d + c] = x
                val a = abs(x)
                if (a > peak) peak = a
            }
            delayPos++
            if (delayPos == lookaheadFrames) delayPos = 0

            val required = if (limiting && peak > KNEE_START) softCeiling(peak) / peak else 1f
            val windowMin = pushMinimum(required)
            held =
                if (windowMin < held) {
                    windowMin
                } else {
                    val next = held + (windowMin - held) * releaseCoef
                    if (windowMin >= 1f && 1f - next < SETTLE) 1f else next
                }
            historySum += held.toDouble() - history[historyPos].toDouble() // exact in double: no drift from float rounding
            history[historyPos] = held
            historyPos++
            if (historyPos == window) historyPos = 0
            if (held == 1f) {
                // a whole window at unity: the running sum is exactly `window`, drop any rounding drift
                if (++unityRun >= window) historySum = window.toDouble()
            } else {
                unityRun = 0
            }
            frameIndex++

            val applied = (historySum / window).toFloat()
            if (applied != 1f) {
                for (c in 0 until ch) output[base + c] *= applied
            }
            base += ch
        }
        gain = g
    }

    /**
     * Writes the [lookaheadFrames] frames still inside the delay line (end of a stream) to [output], which must hold
     * `lookaheadFrames * channels` floats. The engine is spent afterwards; [reset] it before the next stream.
     */
    fun drain(
        output: FloatArray,
        targetGain: Float,
        limit: Boolean,
    ): Int {
        process(silence, output, lookaheadFrames, targetGain, limit)
        return lookaheadFrames
    }

    private fun pushMinimum(value: Float): Float {
        val cap = dequeValue.size
        while (dequeSize > 0) {
            val last = dequeHead + dequeSize - 1
            val at = if (last >= cap) last - cap else last
            if (dequeValue[at] >= value) dequeSize-- else break
        }
        val tail = dequeHead + dequeSize
        val tailAt = if (tail >= cap) tail - cap else tail
        dequeValue[tailAt] = value
        dequeIndex[tailAt] = frameIndex
        dequeSize++
        while (dequeIndex[dequeHead] <= frameIndex - window) {
            dequeHead++
            if (dequeHead == cap) dequeHead = 0
            dequeSize--
        }
        return dequeValue[dequeHead]
    }

    companion object {
        /** -0.5 dBFS: nothing leaves the limiter above this. */
        const val CEILING = 0.94406f

        /** -1 dBFS: below this the limiter does nothing; between this and [CEILING] the curve bends smoothly. */
        const val KNEE_START = 0.89125f

        const val LOOKAHEAD_MS = 5.0
        const val RELEASE_MS = 120.0
        const val GLIDE_MS = 40.0

        private const val SETTLE = 1e-6f
        private const val GAIN_SETTLE = 1e-7
        private const val UNITY_BAND = 1e-3

        /** Peak value in, peak value out: identity up to [KNEE_START], then a tanh bend that never passes [CEILING]. */
        fun softCeiling(peak: Float): Float {
            if (peak <= KNEE_START) return peak
            val room = CEILING - KNEE_START
            return KNEE_START + room * kotlin.math.tanh(((peak - KNEE_START) / room).toDouble()).toFloat()
        }

        private fun coefficient(ms: Double, rate: Int): Float = (1.0 - exp(-1.0 / (ms / 1000.0 * rate))).toFloat()
    }
}
