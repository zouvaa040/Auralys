/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.eq.data

import com.metrolist.music.eq.audio.BiquadFilter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The easy equaliser: ten sliders, one per octave, plus presets. It is stored and played as an ordinary [SavedEQProfile]
 * of ten peaking bands (so the same audio processor plays it), with its own id and a `source` that keeps it out of the
 * list of imported headphone profiles.
 */
object GraphicEq {
    /** The profile that holds the sliders as they are now. */
    const val ID = "graphic_eq"
    const val SOURCE_LIVE = "graphic"

    /** A preset the listener saved under a name of their own. */
    const val SOURCE_PRESET = "graphic_preset"

    const val MAX_DB = 12.0
    const val STEP_DB = 0.5

    /** One octave apart; with this width neighbouring bands blend into a smooth curve instead of ten separate bumps. */
    const val BAND_Q = 1.3

    val FREQUENCIES = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    val FLAT: List<Double> = List(FREQUENCIES.size) { 0.0 }

    /** The three knobs of the easy view and the bands each one moves: bass, vocals (the middle), treble. */
    val GROUPS: List<IntRange> = listOf(0..3, 4..6, 7..9)
    const val BASS = 0
    const val VOCAL = 1
    const val TREBLE = 2

    /** A preset of the list under the chips. [id] is the key of its name in the strings. */
    data class Preset(val id: String, val gains: List<Double>)

    val PRESETS: List<Preset> =
        listOf(
            Preset("flat", FLAT),
            Preset("bass", listOf(6.0, 5.0, 3.5, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            Preset("treble", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 4.0, 5.0, 5.5)),
            Preset("vocal", listOf(-2.0, -2.0, -1.0, 0.0, 1.5, 3.0, 3.5, 2.5, 0.5, -0.5)),
            Preset("pop", listOf(-1.0, 1.0, 2.5, 3.0, 2.0, 0.0, -1.0, -1.0, -0.5, -0.5)),
            Preset("rock", listOf(4.5, 3.5, 2.5, 1.0, -1.0, -1.0, 1.0, 3.0, 4.0, 4.5)),
            Preset("jazz", listOf(3.0, 2.0, 1.0, 1.5, -1.5, -1.5, 0.0, 1.0, 2.5, 3.5)),
            Preset("classic", listOf(3.0, 2.0, 1.0, 0.5, -0.5, -0.5, 0.0, 1.0, 2.0, 3.0)),
            Preset("dance", listOf(5.5, 4.5, 2.0, 0.0, -1.5, -1.0, 1.0, 3.0, 4.0, 4.5)),
            Preset("hiphop", listOf(5.0, 4.5, 3.0, 2.0, -0.5, -1.0, 1.0, -0.5, 1.5, 2.5)),
            Preset("acoustic", listOf(2.5, 2.5, 1.5, 1.0, 1.5, 1.5, 2.5, 2.5, 2.0, 1.0)),
            Preset("loudness", listOf(5.0, 3.5, 1.5, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 3.5)),
        )

    /** A gain on the grid of the sliders: half a dB steps, never beyond +-12. */
    fun snap(db: Double): Double = ((db / STEP_DB).roundToInt() * STEP_DB).coerceIn(-MAX_DB, MAX_DB)

    fun bands(gains: List<Double>): List<ParametricEQBand> =
        FREQUENCIES.mapIndexed { i, hz ->
            ParametricEQBand(frequency = hz, gain = gains.getOrElse(i) { 0.0 }, q = BAND_Q, filterType = FilterType.PK, enabled = true)
        }

    /** The biggest boost of the whole curve in dB (the bands add up where they overlap), looked for between 20 Hz and 20 kHz. */
    fun peakDb(gains: List<Double>): Double {
        val filters = bands(gains).map { BiquadFilter(48_000, it.frequency, it.gain, it.q, it.filterType) }
        var peak = 0.0
        val points = 160
        val logMin = log10(20.0)
        val logMax = log10(20_000.0)
        for (i in 0 until points) {
            val hz = 10.0.pow(logMin + (logMax - logMin) * i / (points - 1))
            var total = 0.0
            for (f in filters) total += f.magnitudeDb(hz)
            peak = max(peak, total)
        }
        return peak
    }

    /**
     * The volume to take off so the boosted sound cannot go past full scale: the peak of the curve, up to 0.1 dB above.
     * Without it a loud song with the bass up is cut off at the top of the wave and sounds torn.
     */
    fun autoPreamp(gains: List<Double>): Double {
        val peak = ceil(peakDb(gains) * 10.0) / 10.0
        return if (peak <= 0.05) 0.0 else -peak.coerceAtMost(MAX_HEADROOM_DB)
    }

    private const val MAX_HEADROOM_DB = 18.0

    fun profile(
        gains: List<Double>,
        id: String = ID,
        name: String = "",
        source: String = SOURCE_LIVE,
    ): SavedEQProfile =
        SavedEQProfile(
            id = id,
            name = name,
            deviceModel = name,
            bands = bands(gains),
            preamp = autoPreamp(gains),
            source = source,
            isCustom = false,
        )

    /** The ten gains of a profile made here, or null when [profile] is something else (an imported headphone profile). */
    fun gainsOf(profile: SavedEQProfile): List<Double>? {
        if (profile.bands.size != FREQUENCIES.size) return null
        if (profile.bands.indices.any { profile.bands[it].frequency != FREQUENCIES[it] }) return null
        return profile.bands.map { it.gain }
    }

    /** The preset that has exactly these gains, or null for a curve of the listener's own. */
    fun presetFor(gains: List<Double>): Preset? = PRESETS.firstOrNull { sameGains(it.gains, gains) }

    fun sameGains(a: List<Double>, b: List<Double>): Boolean = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.01 }

    /** What the knob of [group] shows: the mean of its bands. */
    fun groupLevel(gains: List<Double>, group: Int): Double {
        val range = GROUPS[group]
        return range.sumOf { gains[it] } / range.count()
    }

    /** Moves the bands of [group] together until their mean is [level]; the shape inside the group stays. */
    fun withGroupLevel(gains: List<Double>, group: Int, level: Double): List<Double> {
        val shift = level - groupLevel(gains, group)
        val range = GROUPS[group]
        return gains.mapIndexed { i, g -> if (i in range) snap(g + shift) else g }
    }

    fun withBand(gains: List<Double>, index: Int, db: Double): List<Double> = gains.mapIndexed { i, g -> if (i == index) snap(db) else g }
}
