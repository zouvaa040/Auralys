/**
 * Auralys: pure maths for the full-bleed player cover ("Sampul layar penuh").
 * No Compose or Android types here so it can be unit tested on the JVM.
 *
 * The idea (one cover that reaches the screen top, bottom part dissolves into the player background,
 * status bar scrim chosen from how bright the top of the cover is) follows BitChord's now-playing screen
 * (GPLv3, like Auralys); the code is Auralys' own.
 */

package com.metrolist.music.ui.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Share of the cover height (from the bottom) that dissolves into the background. */
const val HERO_FADE_FRACTION = 0.40f

/** Source rectangle (in image pixels) that a centre crop shows. */
data class HeroCropRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The part of an [imageW] x [imageH] image that is visible when it is centre-cropped to fill a
 * [boxW] x [boxH] box. A 16:9 video thumbnail in a square box gives its centre square.
 */
fun heroCropRect(imageW: Float, imageH: Float, boxW: Float, boxH: Float): HeroCropRect {
    if (imageW <= 0f || imageH <= 0f || boxW <= 0f || boxH <= 0f) {
        return HeroCropRect(0f, 0f, max(imageW, 0f), max(imageH, 0f))
    }
    val scale = max(boxW / imageW, boxH / imageH)
    val visibleW = min(imageW, boxW / scale)
    val visibleH = min(imageH, boxH / scale)
    return HeroCropRect((imageW - visibleW) / 2f, (imageH - visibleH) / 2f, visibleW, visibleH)
}

/** True when the image is clearly not square (video thumbnails), ratio outside 0.85..1.15. */
fun isWideOrTall(imageW: Float, imageH: Float): Boolean {
    if (imageW <= 0f || imageH <= 0f) return false
    val ratio = imageW / imageH
    return ratio < 0.85f || ratio > 1.15f
}

/**
 * Average luminance (0 = black, 1 = white) of the top [bandFraction] of the visible part of a bitmap.
 * [argb] is row-major, [width] x [height]. The visible part is [heroCropRect] for a box of
 * [boxAspect] (width / height); pass 0 or less to use the whole image width.
 * Fully transparent pixels count as black.
 */
fun heroTopBandLuminance(
    argb: IntArray,
    width: Int,
    height: Int,
    boxAspect: Float = 0f,
    bandFraction: Float = 0.15f,
): Float {
    if (width <= 0 || height <= 0 || argb.size < width * height) return 0.5f
    val rect =
        if (boxAspect > 0f) {
            heroCropRect(width.toFloat(), height.toFloat(), boxAspect * 100f, 100f)
        } else {
            HeroCropRect(0f, 0f, width.toFloat(), height.toFloat())
        }
    val x0 = rect.left.toInt().coerceIn(0, width - 1)
    val x1 = (rect.left + rect.width).toInt().coerceIn(x0 + 1, width)
    val y0 = rect.top.toInt().coerceIn(0, height - 1)
    val bandRows = max(1, (rect.height * bandFraction.coerceIn(0.01f, 1f)).toInt())
    val y1 = min(height, y0 + bandRows)
    var sum = 0.0
    var count = 0
    for (y in y0 until y1) {
        val row = y * width
        for (x in x0 until x1) {
            val p = argb[row + x]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            sum += lum * (a / 255.0)
            count++
        }
    }
    return if (count == 0) 0.5f else (sum / count).toFloat().coerceIn(0f, 1f)
}

/** Darkness of the status bar scrim: 0.16 for a dark cover top up to 0.65 for a white one. */
fun heroScrimAlpha(topLuminance: Float): Float = 0.16f + 0.49f * topLuminance.coerceIn(0f, 1f)

/**
 * Scrim alphas from the top edge (index 0) down to zero, eased so there is no visible band.
 * [steps] stops, the last one is 0.
 */
fun heroScrimAlphas(peakAlpha: Float, steps: Int = 8): List<Float> {
    val n = max(2, steps)
    return List(n) { i ->
        val t = i / (n - 1).toFloat()
        peakAlpha * (1f - t).pow(1.5f)
    }
}

/**
 * Alpha mask for the cover, as (position 0..1 from the top, alpha) pairs. Opaque down to
 * `1 - fadeFraction`, then an eased fall to fully transparent at the bottom edge.
 */
fun heroMaskStops(fadeFraction: Float = HERO_FADE_FRACTION): List<Pair<Float, Float>> {
    val f = fadeFraction.coerceIn(0.05f, 0.95f)
    val start = 1f - f
    val steps = 6
    val stops = ArrayList<Pair<Float, Float>>(steps + 2)
    stops += 0f to 1f
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val eased = 1f - t * t * (3f - 2f * t) // smoothstep: soft start and end
        stops += (start + f * t) to eased
    }
    return stops
}
