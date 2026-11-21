/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The pure parts of [VolumeControl]: pixel <-> fraction, the three detents (0, 50, 100 percent) with their haptic tick,
 * the percent label, the system volume steps and the memory of the level before a mute. No Compose state in here, so it
 * can be unit tested.
 */

package com.metrolist.music.ui.component

import kotlin.math.abs
import kotlin.math.roundToInt

object VolumeMath {
    /** The points where the thumb clicks into place and the phone gives a tick. */
    val Detents = floatArrayOf(0f, 0.5f, 1f)

    /** How close (as a fraction of the whole bar) the thumb must be to a detent to snap to it. */
    const val DetentTolerance = 0.015f

    /** A level below this is never remembered as "the level before the mute". */
    const val MinRestore = 0.05f

    /** What the left icon restores when nothing is remembered yet (the bar was opened at 0). */
    const val DefaultRestore = 0.5f

    /** One press of an arrow key or one TalkBack step. */
    const val KeyStep = 0.05f

    /** The fraction (0..1) of a touch at [x] on a track that runs from [start] to [end]. */
    fun fractionAt(
        x: Float,
        start: Float,
        end: Float,
    ): Float = if (end <= start) 0f else ((x - start) / (end - start)).coerceIn(0f, 1f)

    /** The x of a fraction on the track; the inverse of [fractionAt]. */
    fun positionAt(
        fraction: Float,
        start: Float,
        end: Float,
    ): Float = start + fraction.coerceIn(0f, 1f) * (end - start)

    /** Pulls a value that is within [tolerance] of a detent onto the detent. */
    fun snap(
        fraction: Float,
        tolerance: Float = DetentTolerance,
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        for (d in Detents) if (abs(f - d) <= tolerance) return d
        return f
    }

    /** True when a move from [from] to [to] lands on a detent or passes over one (not when it leaves one). */
    fun detentCrossed(
        from: Float,
        to: Float,
    ): Boolean {
        if (from == to) return false
        for (d in Detents) {
            if ((from < d && to >= d) || (from > d && to <= d)) return true
        }
        return false
    }

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()

    /** The label over the thumb while dragging, e.g. "40%". */
    fun label(fraction: Float): String = "${percent(fraction)}%"

    fun isMuted(fraction: Float): Boolean = fraction <= 0f

    /** [fraction] moved by [delta] and rounded to a whole percent, so repeated steps do not drift. */
    fun stepBy(
        fraction: Float,
        delta: Float,
    ): Float = (((fraction + delta).coerceIn(0f, 1f) * 100f).roundToInt()) / 100f

    /** The step of the system music stream (0..[max]) that a fraction stands for. */
    fun systemStep(
        fraction: Float,
        max: Int,
    ): Int = (fraction.coerceIn(0f, 1f) * max.coerceAtLeast(1)).roundToInt().coerceIn(0, max.coerceAtLeast(1))

    fun fromSystemStep(
        step: Int,
        max: Int,
    ): Float = if (max <= 0) 0f else (step.toFloat() / max).coerceIn(0f, 1f)
}

/**
 * The level the left icon brings back after a mute. The level is committed when a gesture ends or the volume is changed
 * from outside (hardware keys); a drag that ends at 0 does not overwrite it, so one tap on the icon returns to the level
 * the listener had before.
 */
class VolumeMuteMemory(
    initial: Float = VolumeMath.DefaultRestore,
) {
    var level: Float = initial.coerceIn(VolumeMath.MinRestore, 1f)
        private set

    fun commit(current: Float) {
        if (current >= VolumeMath.MinRestore) level = current.coerceAtMost(1f)
    }

    /** The new level after a tap on the left icon: 0 when it was playing, the remembered level when it was muted. */
    fun toggle(current: Float): Float {
        if (current > 0f) {
            commit(current)
            return 0f
        }
        return level
    }
}

/**
 * The player page's volume bar on the system music stream (report 7: "0 % masih ada suara, 4 % langsung seperti 10 %").
 *
 * A phone has 15 to 25 volume steps (some 100+), and the lowest is not silent on every phone. The bar used to round its
 * position to the nearest step: 0-3 % all landed on one audible step and 4 % jumped to the next. Now the bar is a continuous
 * position over the steps: the system is set to the step just ABOVE the position and the app trims the rest (the trim
 * multiplies the music, see MusicService.systemVolumeTrim), so the loudness moves smoothly along the phone's own curve
 * between two steps, a hardware-key step lands exactly on a step with no trim, and 0 % is silent (trim 0) whatever the
 * phone does at its lowest step.
 */
object SystemVolumeMapping {
    /** What to set: the music stream [index] and the app's [trim] (0..1) on top of it. */
    data class Target(
        val index: Int,
        val trim: Float,
    )

    /** How far below the first audible step the bar fades out as it nears 0 %. */
    const val FADE_BELOW_FIRST_STEP_DB = 30f

    /**
     * @param minIndex the stream's lowest step (AudioManager.getStreamMinVolume, 0 on most phones)
     * @param maxIndex the stream's highest step
     * @param db the loudness of a step in dB (0 dB at the top), see [stepDbTable]
     */
    fun target(
        fraction: Float,
        minIndex: Int,
        maxIndex: Int,
        db: FloatArray,
    ): Target {
        val lo = minIndex.coerceAtLeast(0)
        val hi = maxIndex.coerceAtLeast(lo + 1)
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return Target(lo, 0f)
        val raw = lo + f * (hi - lo)
        // a position on a step (float rounding of index / max) is that step, not the next one with a trim
        val x = Math.round(raw).toFloat().let { if (kotlin.math.abs(raw - it) < 1e-3f) it else raw }
        val index = kotlin.math.ceil(x.toDouble()).toInt().coerceIn(lo + 1, hi)
        val below = index - 1
        val part = (x - below).coerceIn(0f, 1f)
        val stepDb = dbAt(db, index)
        val targetDb =
            if (below <= lo) {
                // between the lowest step and the first audible one: fade out from the first step down
                stepDb - FADE_BELOW_FIRST_STEP_DB * (1f - part)
            } else {
                val belowDb = dbAt(db, below)
                belowDb + (stepDb - belowDb) * part
            }
        val trim = Math.pow(10.0, ((targetDb - stepDb) / 20f).toDouble()).toFloat().coerceIn(0f, 1f)
        return Target(index, if (part >= 1f) 1f else trim)
    }

    /** The bar position of a system step (what the hardware keys set): exactly on the step, no trim. */
    fun fractionOf(
        index: Int,
        minIndex: Int,
        maxIndex: Int,
    ): Float {
        val lo = minIndex.coerceAtLeast(0)
        val hi = maxIndex.coerceAtLeast(lo + 1)
        return ((index - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    }

    /**
     * The loudness of every step 0..[maxIndex]: what the phone reports ([systemDb], Android 9+) when it is usable (finite
     * and rising from step to step), else Android's default media curve.
     */
    fun stepDbTable(
        maxIndex: Int,
        systemDb: ((Int) -> Float?)?,
    ): FloatArray {
        val max = maxIndex.coerceAtLeast(1)
        val fallback = FloatArray(max + 1) { defaultCurveDb(it, max) }
        if (systemDb == null) return fallback
        val fromSystem = FloatArray(max + 1)
        for (i in 1..max) {
            val v = runCatching { systemDb(i) }.getOrNull() ?: return fallback
            if (!v.isFinite() || v < -100f || v > 1f) return fallback
            fromSystem[i] = v
        }
        for (i in 2..max) if (fromSystem[i] < fromSystem[i - 1]) return fallback
        fromSystem[0] = fromSystem[1] - FADE_BELOW_FIRST_STEP_DB
        return fromSystem
    }

    /**
     * Android's default media volume curve (index in percent of the range -> dB): 1 % -58 dB, 20 % -40 dB, 60 % -17 dB,
     * 100 % 0 dB, straight lines in between.
     */
    fun defaultCurveDb(
        index: Int,
        maxIndex: Int,
    ): Float {
        val max = maxIndex.coerceAtLeast(1)
        val p = index.coerceIn(0, max) * 100f / max
        val points = DEFAULT_CURVE
        if (p <= points[0].first) return points[0].second - FADE_BELOW_FIRST_STEP_DB * (1f - p / points[0].first)
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            val (x0, y0) = points[i - 1]
            if (p <= x1) return y0 + (y1 - y0) * (p - x0) / (x1 - x0)
        }
        return 0f
    }

    private val DEFAULT_CURVE = listOf(1f to -58f, 20f to -40f, 60f to -17f, 100f to 0f)

    private fun dbAt(
        db: FloatArray,
        index: Int,
    ): Float = db[index.coerceIn(0, db.lastIndex)]
}

/** One memory per scale: the in-app level of the player menu and the system level of the player page are different things. */
internal val PlayerGainMuteMemory = VolumeMuteMemory()
internal val SystemVolumeMuteMemory = VolumeMuteMemory()
