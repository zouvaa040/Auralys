/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ====================================================================================================================
// The finish of the picture, on the CPU so it is the same on Android 8 to 16 (round 4.3.5, "no AI look"):
//  * the grade is a set of real tone curves (one per channel: a gentle S with a soft shoulder, lifted blacks, a white point a little under
//    white) and a saturation, never a colour wash laid on top. The small differences between the three channels ARE the split toning
//    (warm highlights, cooler or greener shadows), as in a film stock;
//  * the light field: a natural lens vignette (cos^4-like falloff, about -0.4 EV in the corners) and the key light ("Cahaya utama"): a
//    gentle exposure falloff across the frame from the side the light comes from (the same light as the cards' shadows), a little warmer on
//    the lit side and cooler on the far side, like sun and sky. A multiplication of the pixels: it lights what is there, nothing is pasted;
//  * every value is rounded with a per-pixel random offset (dither), so smooth gradients never show bands;
//  * film grain (only when asked for, default 0 by the owner's rule): luminance only, strongest in the mid-tones, as real grain is.

/** One grade: three tone curves (0..255 in, 0..4080 out = 16 x the value, for the dither) and a saturation (x 256). */
internal class ToneSpec(val r: IntArray, val g: IntArray, val b: IntArray, val sat: Int)

/**
 * The parameters of a grade. [contrast] = the strength of the S (0 = straight), [gamma] = a mid-tone shift per channel (white balance without
 * clipping), [toe] = the output of black per channel (lifted, slightly tinted blacks), [white] = the output of white per channel (the soft
 * shoulder ends a little under white, slightly tinted highlights), [sat] = saturation about the luma.
 */
internal class GradeParams(val contrast: Float, val gamma: FloatArray, val toe: FloatArray, val white: FloatArray, val sat: Float)

internal object PosterTone {
    fun params(g: PosterGrade): GradeParams? =
        when (g) {
            PosterGrade.ASLI -> null
            // warm daylight on a print stock: a soft S, warm highlights, neutral shadows
            PosterGrade.HANGAT -> GradeParams(0.20f, floatArrayOf(0.965f, 0.995f, 1.05f), floatArrayOf(0.016f, 0.012f, 0.014f), floatArrayOf(0.995f, 0.972f, 0.918f), 0.97f)
            // overcast / blue hour: cool highlights, slightly blue shadows, a touch less colour
            PosterGrade.DINGIN -> GradeParams(0.16f, floatArrayOf(1.045f, 1.0f, 0.965f), floatArrayOf(0.008f, 0.016f, 0.032f), floatArrayOf(0.935f, 0.972f, 0.998f), 0.93f)
            // night city: deeper contrast, blue-violet shadows, faint magenta highlights, a little more colour (never neon washes)
            PosterGrade.NEON -> GradeParams(0.30f, floatArrayOf(1.0f, 1.035f, 0.975f), floatArrayOf(0.014f, 0.008f, 0.042f), floatArrayOf(0.992f, 0.948f, 0.985f), 1.06f)
            // a portrait negative printed soft: low contrast, lifted greenish-blue blacks, warm creamy highlights, muted colour
            PosterGrade.FILM -> GradeParams(0.06f, floatArrayOf(0.985f, 0.99f, 1.025f), floatArrayOf(0.058f, 0.062f, 0.07f), floatArrayOf(0.975f, 0.952f, 0.9f), 0.86f)
        }

    /** One channel's curve at [x] (0..1): the mid-tone shift, the S (x^k / (x^k + (1-x)^k): slope 1 + contrast at the middle, flat at both ends), then toe and white. */
    fun curve(x: Float, contrast: Float, gamma: Float, toe: Float, white: Float): Float {
        val xg = x.coerceIn(0f, 1f).pow(gamma)
        val k = 1f + contrast
        val a = xg.pow(k)
        val b = (1f - xg).pow(k)
        val s = if (a + b <= 1e-9f) xg else a / (a + b)
        return toe + (white - toe) * s
    }

    private val cache = HashMap<PosterGrade, ToneSpec>()

    /** The tables of a grade, or null for ASLI (the picture as it is). */
    fun spec(g: PosterGrade): ToneSpec? {
        val p = params(g) ?: return null
        synchronized(cache) {
            cache[g]?.let { return it }
            fun table(c: Int) = IntArray(256) { v -> (curve(v / 255f, p.contrast, p.gamma[c], p.toe[c], p.white[c]) * 4080f).roundToInt().coerceIn(0, 4080) }
            return ToneSpec(table(0), table(1), table(2), (p.sat * 256f).roundToInt()).also { cache[g] = it }
        }
    }
}

/**
 * The light over the whole frame as RGB multipliers (x 4096) on a coarse grid of [cell] device pixels, read with bilinear interpolation:
 * the vignette and the key light are very smooth, so a grid of about 70 x 120 nodes is exact enough and costs nothing per frame.
 */
internal class LightField(val w: Int, val h: Int, val cell: Int, val nx: Int, val ny: Int, val r: IntArray, val g: IntArray, val b: IntArray)

internal object PosterField {
    /** The vignette's strength: (1 + V r^2)^-2 with r = 1 in the corners gives about 0.75 there (-0.42 EV), like a good lens wide open. */
    const val VIGNETTE = 0.16f

    /** Where the darker band under the mark starts (the share of the picture's height) on the 9:16 poster; other shapes use [bandStart]. */
    const val BAND_V0 = 0.80f

    /** "Vinyet" slider 0..1 as the vignette's strength (0.5 = the old [VIGNETTE]). */
    fun vignetteFor(slider: Float): Float = VIGNETTE * 2f * slider.coerceIn(0f, 1f)

    /** The band under the mark starts [bandPx] poster pixels above the bottom edge (384 on the 9:16 poster, 300 on the shorter ones). */
    fun bandStart(ratio: PosterRatio): Float = if (ratio.h >= 1920) BAND_V0 else 1f - 300f / ratio.h

    /** At full "Cahaya utama" the lit edge gets this many EV more and the far edge as many less. */
    const val SPILL_EV = 0.42f

    /** The light's colour across the frame: how much warmer (r, g, b weights) the lit side is, per grade (sun vs sky; cool and night grades get cooler light). */
    private fun warmth(g: PosterGrade): FloatArray =
        when (g) {
            PosterGrade.DINGIN -> floatArrayOf(-0.35f, 0f, 0.55f)
            PosterGrade.NEON -> floatArrayOf(0.45f, -0.35f, 0.4f)
            else -> floatArrayOf(1f, 0.35f, -0.7f)
        }

    /**
     * The field for a picture of [w] x [h]: the vignette, the darker bottom band under the mark, and the key light coming from [shadowAngle] + 180
     * degrees (the cards' shadows fall along [shadowAngle]), [spill] 0..1.
     */
    fun build(w: Int, h: Int, shadowAngle: Float, spill: Float, grade: PosterGrade, vignette: Float = VIGNETTE, bandV0: Float = BAND_V0): LightField {
        val cell = max(8, (min(w, h) / 64f).roundToInt())
        val nx = w / cell + 2
        val ny = h / cell + 2
        val r = IntArray(nx * ny)
        val g = IntArray(nx * ny)
        val b = IntArray(nx * ny)
        val a = Math.toRadians(shadowAngle.toDouble())
        val lx = (-kotlin.math.cos(a)).toFloat()
        val ly = (-kotlin.math.sin(a)).toFloat()
        val s = spill.coerceIn(0f, 1f)
        val wm = warmth(grade)
        for (j in 0 until ny) {
            val y = min(h.toFloat(), j * cell.toFloat())
            for (i in 0 until nx) {
                val x = min(w.toFloat(), i * cell.toFloat())
                val m = at(x / w, y / h, h / w.toFloat(), lx, ly, s, wm, vignette, bandV0)
                val k = j * nx + i
                r[k] = (m[0] * 4096f).roundToInt()
                g[k] = (m[1] * 4096f).roundToInt()
                b[k] = (m[2] * 4096f).roundToInt()
            }
        }
        return LightField(w, h, cell, nx, ny, r, g, b)
    }

    /** The multipliers at ([u], [v]) (0..1 across the frame; [aspect] = height / width). */
    fun at(u: Float, v: Float, aspect: Float, lx: Float, ly: Float, spill: Float, warmth: FloatArray, vignette: Float = VIGNETTE, bandV0: Float = BAND_V0): FloatArray {
        val dx = (u - 0.5f) * 2f
        val dy = (v - 0.5f) * 2f
        val r2 = (dx * dx + dy * dy) / 2f
        val vig = 1f / ((1f + vignette * r2) * (1f + vignette * r2))
        // the bottom band where the mark sits: a little darker, so the white word reads on any photo
        val t = ((v - bandV0) / (1f - bandV0)).coerceIn(0f, 1f)
        val band = 1f - 0.16f * t * t * (3f - 2f * t)
        var mr = vig * band
        var mg = mr
        var mb = mr
        if (spill > 0.001f) {
            // distance along the light, in half heights (the frame is tall: the light falls off over the long side too)
            val px = dx / aspect
            val q = (px * lx + dy * ly).coerceIn(-1f, 1f)
            val shaped = q * (1.5f - 0.5f * q * q)
            val ev = 2f.pow(SPILL_EV * spill * shaped)
            val tint = 0.06f * spill * shaped
            mr *= ev * (1f + tint * warmth[0])
            mg *= ev * (1f + tint * warmth[1])
            mb *= ev * (1f + tint * warmth[2])
        }
        return floatArrayOf(mr, mg, mb)
    }
}

/**
 * Where the light in the user's photo comes from (round 4.3.5: the cards' shadows, the glints and the key light follow the photo's real light).
 * The person's shading tells it: row by row across the cut-out, the lit side is brighter than the far side, whatever the clothes' colours
 * (each row compares its own two edges). Light from above is assumed (the shadows fall down); only the side is measured.
 */
internal object PosterLightProbe {
    /** Under this mean left-right difference the light is frontal or unclear: nothing is changed. */
    const val MIN_SIDE = 0.035f

    /**
     * The left-right lighting of the person in [photo] (-1 = lit from the right .. 1 = lit from the left), or null when there is no mask, too
     * little of it, or no clear side.
     */
    fun side(photo: PosterPhoto): Float? {
        if (!photo.hasMask) return null
        val w = photo.w
        val h = photo.h
        val a = photo.alpha
        val px = photo.image.argb
        val stepY = max(1, h / 240)
        var sum = 0.0
        var weight = 0.0
        var y = 0
        while (y < h) {
            val row = y * w
            // the widest solid run of the row
            var bestL = -1
            var bestR = -1
            var x = 0
            while (x < w) {
                if ((a[row + x].toInt() and 255) >= 200) {
                    val l = x
                    while (x < w && (a[row + x].toInt() and 255) >= 200) x++
                    if (x - l > bestR - bestL) {
                        bestL = l
                        bestR = x
                    }
                } else {
                    x++
                }
            }
            val run = bestR - bestL
            if (run >= max(12, w / 40)) {
                // the outer quarters of the run: the two sides of the body or the face
                val q = run / 4
                var ls = 0L
                var rs = 0L
                for (k in 0 until q) {
                    ls += luma(px[row + bestL + k])
                    rs += luma(px[row + bestR - 1 - k])
                }
                val lm = ls.toFloat() / q
                val rm = rs.toFloat() / q
                if (lm + rm > 30f) {
                    sum += ((lm - rm) / (lm + rm)).toDouble() * run
                    weight += run.toDouble()
                }
            }
            y += stepY
        }
        if (weight <= 0.0) return null
        val s = (sum / weight).toFloat()
        return if (kotlin.math.abs(s) < MIN_SIDE) null else s.coerceIn(-1f, 1f)
    }

    /** The shadow angle (degrees, the cards' "Arah") for a measured [side]: down and away from the light, at most 50 degrees off straight down. */
    fun shadowAngle(side: Float): Float = 90f - 50f * (side / 0.15f).coerceIn(-1f, 1f)

    private fun luma(p: Int): Int = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
}

internal object PosterFinish {
    /** A fixed, well mixed hash of a pixel position (the dither and the grain are the same at every render: no flicker). */
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 362437
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /**
     * The finish in place on [px] (opaque ARGB, [w] x [h]): the grade's curves and saturation ([tone], may be null), the light [field]
     * (may be null), rounding with a dither. (Round 4.3.5 K: the glints no longer search the finished picture; they come from the photo
     * itself and the cards, see PosterGlints.)
     */
    fun apply(px: IntArray, w: Int, h: Int, tone: ToneSpec?, field: LightField?) {
        val nx = field?.nx ?: 0
        val rowR = IntArray(nx)
        val rowG = IntArray(nx)
        val rowB = IntArray(nx)
        val tr = tone?.r
        val tg = tone?.g
        val tb = tone?.b
        val sat = tone?.sat ?: 256
        for (y in 0 until h) {
            if (field != null) {
                val cell = field.cell
                val j = min(field.ny - 2, y / cell)
                val fy = ((y - j * cell) shl 12) / cell
                val a = j * nx
                val c = a + nx
                for (i in 0 until nx) {
                    rowR[i] = field.r[a + i] + (((field.r[c + i] - field.r[a + i]) * fy) shr 12)
                    rowG[i] = field.g[a + i] + (((field.g[c + i] - field.g[a + i]) * fy) shr 12)
                    rowB[i] = field.b[a + i] + (((field.b[c + i] - field.b[a + i]) * fy) shr 12)
                }
            }
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                var r: Int
                var g: Int
                var b: Int
                if (tr != null) {
                    r = tr[(p shr 16) and 255]
                    g = tg!![(p shr 8) and 255]
                    b = tb!![p and 255]
                    if (sat != 256) {
                        val l = (r * 77 + g * 150 + b * 29) shr 8
                        r = l + (((r - l) * sat) shr 8)
                        g = l + (((g - l) * sat) shr 8)
                        b = l + (((b - l) * sat) shr 8)
                    }
                } else {
                    r = ((p shr 16) and 255) shl 4
                    g = ((p shr 8) and 255) shl 4
                    b = (p and 255) shl 4
                }
                // 16 x value times the multiplier x 4096 = value x 65536; the random low bits round (the dither)
                val rnd = hash(x, y, 7) and 0xFFFF
                if (field != null) {
                    val cell = field.cell
                    val i = min(nx - 2, x / cell)
                    val fx = ((x - i * cell) shl 12) / cell
                    val mr = rowR[i] + (((rowR[i + 1] - rowR[i]) * fx) shr 12)
                    val mg = rowG[i] + (((rowG[i + 1] - rowG[i]) * fx) shr 12)
                    val mb = rowB[i] + (((rowB[i + 1] - rowB[i]) * fx) shr 12)
                    r = (r.coerceAtLeast(0) * mr + rnd) shr 16
                    g = (g.coerceAtLeast(0) * mg + rnd) shr 16
                    b = (b.coerceAtLeast(0) * mb + rnd) shr 16
                } else {
                    r = (r.coerceAtLeast(0) * 4096 + rnd) shr 16
                    g = (g.coerceAtLeast(0) * 4096 + rnd) shr 16
                    b = (b.coerceAtLeast(0) * 4096 + rnd) shr 16
                }
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                px[row + x] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Film grain in place: luminance only (the same step on r, g and b, so it never makes coloured speckles), two octaves (single pixels and
     * pairs, so it clumps a little like silver), strongest in the mid-tones and nearly none in deep shadow and paper white. [amount] 0..1.
     */
    fun grain(px: IntArray, w: Int, h: Int, amount: Float, scale: Float) {
        if (amount <= 0.001f) return
        val amp = amount.coerceIn(0f, 1f) * 15f
        // the weight by brightness: 4 l (1 - l), a little flattened
        val weight = IntArray(256) { v -> val l = v / 255f; ((4f * l * (1f - l)).pow(0.7f) * amp * 256f).roundToInt() }
        val coarse = max(1, (2f * scale).roundToInt())
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val l = (r * 77 + g * 150 + b * 29) shr 8
                // two uniform hashes summed: a triangle around 0, in -256..256
                val n1 = (hash(x, y, 11) and 0xFF) + (hash(x, y, 13) and 0xFF) - 255
                val n2 = (hash(x / coarse, y / coarse, 17) and 0xFF) + (hash(x / coarse, y / coarse, 19) and 0xFF) - 255
                val n = (n1 * 3 + n2 * 2) / 5
                val d = (n * weight[l] + 32768) shr 16
                if (d == 0) continue
                px[row + x] = (p and -0x1000000) or ((r + d).coerceIn(0, 255) shl 16) or ((g + d).coerceIn(0, 255) shl 8) or (b + d).coerceIn(0, 255)
            }
        }
    }
}
