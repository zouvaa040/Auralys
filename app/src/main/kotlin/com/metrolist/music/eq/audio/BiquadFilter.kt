package com.metrolist.music.eq.audio

import com.metrolist.music.eq.data.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Biquad filter implementation for EQ
 * Supports peaking (PK), low-shelf (LSC), and high-shelf (HSC) filters
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook
 */
class BiquadFilter(
    private val sampleRate: Int,
    val frequency: Double,
    gain: Double,
    val q: Double = 1.41,
    val filterType: FilterType = FilterType.PK
) {
    /** The gain in dB. Change it with [setGain]: the coefficients have to be worked out again. */
    var gain: Double = gain
        private set

    // Filter coefficients
    private var a0 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0

    // State variables for filtering (per channel)
    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0

    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    init {
        calculateCoefficients()
    }

    /**
     * Calculate biquad filter coefficients based on filter type
     * Based on Robert Bristow-Johnson's Audio EQ Cookbook
     */
    private fun calculateCoefficients() {
        when (filterType) {
            FilterType.PK -> calculatePeakingCoefficients()
            FilterType.LSC -> calculateLowShelfCoefficients()
            FilterType.HSC -> calculateHighShelfCoefficients()
            else -> {
                // Handle any unexpected filter type
                calculatePeakingCoefficients()
            }
        }
    }

    /**
     * Calculate peaking EQ coefficients (PK)
     * Boosts or cuts around a center frequency
     */
    private fun calculatePeakingCoefficients() {
        val A = 10.0.pow(gain / 40.0) // Gain in linear scale
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        // Peaking EQ coefficients
        b0 = 1.0 + alpha * A
        b1 = -2.0 * cosOmega
        b2 = 1.0 - alpha * A
        a0 = 1.0 + alpha / A
        a1 = -2.0 * cosOmega
        a2 = 1.0 - alpha / A

        // Normalize coefficients
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
        a0 = 1.0
    }

    /**
     * Calculate low-shelf coefficients (LSC)
     * Boosts or cuts frequencies below the cutoff frequency
     */
    private fun calculateLowShelfCoefficients() {
        val A = sqrt(10.0.pow(gain / 20.0)) // Gain amplitude
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val S = 1.0 // Shelf slope parameter (could be made adjustable)
        val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0)
        val sqrtA = sqrt(A)

        // Low-shelf coefficients
        val aPlusOne = A + 1.0
        val aMinusOne = A - 1.0
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        b0 = A * (aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha)
        b1 = 2.0 * A * (aMinusOne - aPlusOne * cosOmega)
        b2 = A * (aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha)
        a0 = aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha
        a1 = -2.0 * (aMinusOne + aPlusOne * cosOmega)
        a2 = aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha

        // Normalize coefficients
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
        a0 = 1.0
    }

    /**
     * Calculate high-shelf coefficients (HSC)
     * Boosts or cuts frequencies above the cutoff frequency
     */
    private fun calculateHighShelfCoefficients() {
        val A = sqrt(10.0.pow(gain / 20.0)) // Gain amplitude
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val S = 1.0 // Shelf slope parameter (could be made adjustable)
        val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0)
        val sqrtA = sqrt(A)

        // High-shelf coefficients
        val aPlusOne = A + 1.0
        val aMinusOne = A - 1.0
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        b0 = A * (aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha)
        b1 = -2.0 * A * (aMinusOne + aPlusOne * cosOmega)
        b2 = A * (aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha)
        a0 = aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha
        a1 = 2.0 * (aMinusOne - aPlusOne * cosOmega)
        a2 = aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha

        // Normalize coefficients
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
        a0 = 1.0
    }

    /**
     * Process a single sample (mono)
     */
    fun processSample(input: Double): Double {
        val output = b0 * input + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L

        // Update state
        x2L = x1L
        x1L = input
        y2L = y1L
        y1L = output

        return output
    }

    /**
     * Process stereo samples (left and right channels)
     */
    fun processStereo(inputLeft: Double, inputRight: Double): Pair<Double, Double> {
        // Left channel
        val outputLeft = b0 * inputLeft + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = inputLeft
        y2L = y1L
        y1L = outputLeft

        // Right channel
        val outputRight = b0 * inputRight + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = inputRight
        y2R = y1R
        y1R = outputRight

        return Pair(outputLeft, outputRight)
    }

    /**
     * The right channel of a stereo frame; call [processSample] for the left one. Same result as [processStereo] without
     * the Pair (and its two boxed doubles) that cost three allocations per band per frame on the audio thread: about 1.4
     * million a second with a ten-band profile, enough garbage to make the sound stutter on a slow phone.
     */
    fun processRight(input: Double): Double {
        val output = b0 * input + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = input
        y2R = y1R
        y1R = output
        return output
    }

    /**
     * A new gain for the same band. The memory of the filter (its last samples) is kept, so moving a slider while the
     * song plays does not click; [reset] would. Call it on the thread that processes the samples.
     */
    fun setGain(newGain: Double) {
        if (newGain == gain) return
        gain = newGain
        calculateCoefficients()
    }

    /** True when [band] is this very filter apart from its gain (same centre, width and type). */
    fun sameShape(band: com.metrolist.music.eq.data.ParametricEQBand): Boolean =
        frequency == band.frequency && q == band.q && filterType == band.filterType

    /** The response of this filter alone at [freq] Hz, in dB (what the graph and the headroom calculation add up). */
    fun magnitudeDb(freq: Double): Double {
        val omega = 2.0 * PI * freq / sampleRate
        val cosW = cos(omega)
        val cos2W = cos(2.0 * omega)
        val sinW = sin(omega)
        val sin2W = sin(2.0 * omega)
        val numReal = b0 + b1 * cosW + b2 * cos2W
        val numImag = b1 * sinW + b2 * sin2W
        val denReal = 1.0 + a1 * cosW + a2 * cos2W
        val denImag = a1 * sinW + a2 * sin2W
        val num = numReal * numReal + numImag * numImag
        val den = denReal * denReal + denImag * denImag
        return if (den > 0.0) 10.0 * kotlin.math.log10(kotlin.math.max(num / den, 1e-10)) else 0.0
    }

    /**
     * Reset filter state (clears history)
     */
    fun reset() {
        x1L = 0.0
        x2L = 0.0
        y1L = 0.0
        y2L = 0.0
        x1R = 0.0
        x2R = 0.0
        y1R = 0.0
        y2R = 0.0
    }
}