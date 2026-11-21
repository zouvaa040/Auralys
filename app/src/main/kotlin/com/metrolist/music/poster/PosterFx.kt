/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ====================================================================================================================
// Everything on the poster that is not a card, the person, a sticker or the finish (PosterFinish): the ground, the light of the covers on
// it, the mark, and the halation of the highlights. Poster pixels (1080 x 1920), deterministic.
//
// Round 4.3.5 ("no AI look"): no pasted light leaks, no glowing blobs, no lens flare from nowhere, no scattered stars. Without a photo the
// ground is a seamless studio backdrop in a deep, quiet tone taken from the covers, lit by one soft light, computed per pixel with a dither
// (never banded); the covers' colours fall on it as two soft gel lights. With a photo the gels are fainter (the photo has its own light).
// The bloom is halation: only the real highlights spread, a little warm, as on film.

internal object PosterBrand {
    // the brand colours come from the brand file (DesignGuardTest: they live there only)
    val Navy700 = com.metrolist.music.ui.component.AuralysBrandColors.Navy
    val Navy900 = Color(0xFF050E17)
    val SunTop = com.metrolist.music.ui.component.AuralysBrandColors.Cyan
    val SunBottom = Color(0xFF2F8FE0)
    private val stripes = listOf(0f to 100f, 108f to 128f, 140f to 154f, 170f to 176f)

    /** The half sun of the Auralys mark cut by thinning stripes, with the horizon line under it, [diameter] wide from ([left], [top]). */
    fun DrawScope.stripedSun(left: Float, top: Float, diameter: Float, sunTop: Color, sunBottom: Color, line: Color, alpha: Float) {
        val s = diameter / 380f
        val dome = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(left, top), Size(diameter, diameter))) }
        val brush = Brush.verticalGradient(listOf(sunTop.copy(alpha = alpha), sunBottom.copy(alpha = alpha)), startY = top, endY = top + 176f * s)
        clipPath(dome) {
            stripes.forEach { (a, b) -> drawRect(brush, topLeft = Offset(left, top + a * s), size = Size(diameter, (b - a) * s)) }
        }
        drawRoundRect(line.copy(alpha = alpha), Offset(left + diameter * 0.18f, top + 196f * s), Size(diameter * 0.64f, 8f * s), CornerRadius(4f * s))
    }
}

/** A colour as hue (degrees), saturation and lightness (0..1). */
internal class Hsl(val h: Float, val s: Float, val l: Float)

/** The covers' shared hue (degrees) and the typical saturation of the pixels that carry it (0..1). Rounded, so equal covers give equal keys. */
internal data class DominantHue(val hue: Float, val sat: Float)

internal object PosterFx {
    fun hsl(c: Color): Hsl {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        val l = (mx + mn) / 2f
        val d = mx - mn
        val s = if (d <= 0.0001f) 0f else d / (1f - abs(2f * l - 1f))
        var h =
            if (d <= 0.0001f) {
                0f
            } else {
                when (mx) {
                    c.red -> ((c.green - c.blue) / d) % 6f
                    c.green -> (c.blue - c.red) / d + 2f
                    else -> (c.red - c.green) / d + 4f
                } * 60f
            }
        if (h < 0f) h += 360f
        return Hsl(h, s.coerceIn(0f, 1f), l)
    }

    fun color(h: Float, s: Float, l: Float): Color {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) =
            when ((h / 60f).toInt() % 6) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f), 1f)
    }

    /** A cover's tint as the colour of a light: its hue, a moderate saturation (a gel, not a neon tube), a medium lightness. */
    fun glowColor(c: Color): Color {
        val k = hsl(c)
        // a colourless cover (grey, black, white) lights a neutral grey, never a made-up tint
        if (max(c.red, max(c.green, c.blue)) - min(c.red, min(c.green, c.blue)) < 0.08f) return color(0f, 0f, k.l.coerceIn(0.5f, 0.6f))
        return color(k.h, (k.s * 1.1f).coerceIn(0.18f, 0.62f), k.l.coerceIn(0.5f, 0.6f))
    }

    fun lightColors(glowTint: Color?, tints: List<Color>, photoMean: Color?): List<Color> {
        if (glowTint != null) return listOf(glowColor(glowTint))
        if (tints.isNotEmpty()) return tints.map { glowColor(it) }
        if (photoMean != null) return listOf(glowColor(photoMean))
        return listOf(Color(0xFF6F9CC8))
    }

    /** The two gel lights: where they stand (poster pixels), how wide they reach. Fixed, so a card moved never changes the ground. */
    private val gelSpots = listOf(Triple(170f, 360f, 820f), Triple(930f, 1500f, 760f))

    /**
     * The covers' light over a PHOTO (Screen, so it only lights): two soft gels, one from the upper left in the first colour, one from the
     * lower right in another. Faint: the photo has its own light.
     */
    fun DrawScope.glow(colors: List<Color>, strength: Float, level: Int, ratio: PosterRatio = PosterRatio.DEFAULT) {
        if (strength <= 0.01f || colors.isEmpty()) return
        val ux = ratio.w / 1080f
        val uy = ratio.h / 1920f
        for ((i, spot) in gelSpots.withIndex()) {
            val c = colors[if (i == 0) 0 else colors.size / 2 % colors.size]
            val a = (0.22f * strength).coerceAtMost(1f)
            val center = Offset(spot.first * ux, spot.second * uy)
            val radius = spot.third * ratio.unit
            drawCircle(
                Brush.radialGradient(0f to c.copy(alpha = a), 0.45f to c.copy(alpha = a * 0.42f), 0.75f to c.copy(alpha = a * 0.12f), 1f to c.copy(alpha = 0f), center = center, radius = radius),
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen,
            )
        }
    }

    /**
     * The one hue the covers share most, for the backdrop (lead review 2026-11-13: the most saturated tint alone made five mixed covers a
     * muddy brown). Every cover's pixels vote for their hue (36 bins of 10 degrees), weighted by how colourful they are (chroma squared: a
     * grey, a near-black or a near-white pixel hardly counts) and by the card's area ([weights], one per cover, the first card a little more
     * so it wins a tie); the bins are smoothed with their neighbours and the strongest wins. Null when the covers are (nearly) colourless.
     * Deterministic: the same covers give the same hue.
     */
    fun dominantHue(covers: List<IntArray>, weights: List<Float>): DominantHue? {
        val bins = FloatArray(36)
        val sats = FloatArray(36)
        var colour = 0f
        var all = 0f
        for ((k, px) in covers.withIndex()) {
            if (px.isEmpty()) continue
            val step = max(1, px.size / 4096)
            val count = (px.size + step - 1) / step
            val w = weights.getOrElse(k) { 1f } / count
            var i = 0
            while (i < px.size) {
                val p = px[i]
                i += step
                if ((p ushr 24) < 128) continue
                all += w
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val c = (mx - mn) / 255f
                if (c < 0.08f) continue
                val d = (mx - mn).toFloat()
                var hue =
                    when (mx) {
                        r -> ((g - b) / d) % 6f
                        g -> (b - r) / d + 2f
                        else -> (r - g) / d + 4f
                    } * 60f
                if (hue < 0f) hue += 360f
                val l = (mx + mn) / 510f
                val s = (d / 255f) / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-3f)
                val wt = w * c * c
                val bin = (hue / 10f).toInt().coerceIn(0, 35)
                bins[bin] += wt
                sats[bin] += wt * s.coerceIn(0f, 1f)
                colour += wt
            }
        }
        // mean chroma squared over every pixel: under about 0.1 chroma on average the covers are grey, black or white
        if (all <= 0f || colour / all < 0.012f) return null
        var best = 0
        var bestV = -1f
        for (b in 0 until 36) {
            val v = bins[b] + 0.5f * (bins[(b + 35) % 36] + bins[(b + 1) % 36])
            if (v > bestV + 1e-9f) {
                bestV = v
                best = b
            }
        }
        // the exact hue: the weighted circular mean of the winning bin and its two neighbours
        var sx = 0.0
        var sy = 0.0
        var ws = 0f
        var ss = 0f
        for (o in -1..1) {
            val b = (best + o + 36) % 36
            val a = Math.toRadians((b * 10.0 + 5.0))
            sx += bins[b] * kotlin.math.cos(a)
            sy += bins[b] * kotlin.math.sin(a)
            ws += bins[b]
            ss += sats[b]
        }
        var hue = Math.toDegrees(kotlin.math.atan2(sy, sx)).toFloat()
        if (hue < 0f) hue += 360f
        return DominantHue((hue * 10f).roundToInt() / 10f, ((if (ws > 0f) ss / ws else 0f) * 100f).roundToInt() / 100f)
    }

    /** The hue of a light colour the user picked ("Warna cahaya"): it sets the backdrop's hue too. */
    fun hueOf(c: Color): DominantHue? {
        val k = hsl(c)
        return if (k.s < 0.08f) null else DominantHue((k.h * 10f).roundToInt() / 10f, (k.s * 100f).roundToInt() / 100f)
    }

    /** The neutral deep charcoal-blue of colourless covers (never a brown). */
    const val NEUTRAL_HUE = 215f
    const val NEUTRAL_SAT = 0.16f

    /**
     * The backdrop's two tones (the dark edge and the lit middle) for a [dominant] hue: deep and quiet (saturation at most 0.26, lightness
     * 0.075 and 0.255). A warm hue (orange, amber, 12..52 degrees) is turned towards red and kept a little richer, because a dark, grey
     * orange IS brown: it becomes a deep oxblood instead of mud. No hue: the neutral charcoal-blue.
     */
    fun backdropTones(dominant: DominantHue?): Pair<Color, Color> {
        var h = dominant?.hue ?: NEUTRAL_HUE
        var s = if (dominant == null) NEUTRAL_SAT else dominant.sat.coerceIn(0.16f, 0.26f)
        if (dominant != null && h in 12f..52f) {
            h -= 0.65f * (h - 2f)
            s = 0.34f
        }
        return color(h, s, 0.075f) to color(h, s * 0.9f, 0.255f)
    }

    /**
     * The backdrop without a photo, per pixel into [px] ([w] x [h], opaque ARGB): a seamless studio backdrop in a deep tone of the covers'
     * [tone] (see [dominantHue], [backdropTones]), lit by one soft light high behind the cards, falling off to the edges and a little darker
     * at the floor; the covers' colours from [gels] as two soft gel lights ([glow] = "Cahaya kartu"). Every value is rounded with a dither: no bands.
     */
    fun backdrop(px: IntArray, w: Int, h: Int, tone: DominantHue?, gels: List<Color>, glow: Float, ratio: PosterRatio = PosterRatio.DEFAULT) {
        val (dark, lit) = backdropTones(tone)
        val g0 = gels.getOrNull(0)
        val g1 = gels.getOrNull(if (gels.size > 1) gels.size / 2 else 0)
        val ga = 0.30f * glow.coerceIn(0f, 1f)
        val sx = ratio.w.toFloat() / w
        // the light and the gels stand at the same SHARE of the picture on every shape (on 9:16 these are the old numbers)
        val ux = ratio.w / 1080f
        val uy = ratio.h / 1920f
        val un = ratio.unit
        // the falloffs as tables of the squared distance (exp per pixel would cost ~0.1 s on a phone)
        val falloff = FloatArray(1025) { exp(-it / 1024f * 6f) }
        fun fall(d2: Float): Float = falloff[min(1024, (d2 * 1024f / 6f).toInt())]
        for (y in 0 until h) {
            val py = y * sx
            val floor = ((py - 1500f * uy) / (420f * uy)).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val pxp = x * sx
                // the backdrop light: an ellipse high behind the cards
                val dx = (pxp - 560f * ux) / (760f * ux)
                val dy = (py - 760f * uy) / (980f * uy)
                val k = fall((dx * dx + dy * dy) * 2.2f) * (1f - 0.35f * floor * floor)
                var r = dark.red + (lit.red - dark.red) * k
                var g = dark.green + (lit.green - dark.green) * k
                var b = dark.blue + (lit.blue - dark.blue) * k
                if (ga > 0.001f) {
                    if (g0 != null) {
                        val ex = (pxp - gelSpots[0].first * ux) / (gelSpots[0].third * un)
                        val ey = (py - gelSpots[0].second * uy) / (gelSpots[0].third * un)
                        val a = ga * fall((ex * ex + ey * ey) * 3.2f)
                        r = 1f - (1f - r) * (1f - g0.red * a)
                        g = 1f - (1f - g) * (1f - g0.green * a)
                        b = 1f - (1f - b) * (1f - g0.blue * a)
                    }
                    if (g1 != null) {
                        val ex = (pxp - gelSpots[1].first * ux) / (gelSpots[1].third * un)
                        val ey = (py - gelSpots[1].second * uy) / (gelSpots[1].third * un)
                        val a = ga * 0.8f * fall((ex * ex + ey * ey) * 3.2f)
                        r = 1f - (1f - r) * (1f - g1.red * a)
                        g = 1f - (1f - g) * (1f - g1.green * a)
                        b = 1f - (1f - b) * (1f - g1.blue * a)
                    }
                }
                // a triangular dither of one step: smooth tones never band, and no visible noise
                val n = ((PosterFinish.hash(x, y, 3) and 0xFF) + (PosterFinish.hash(x, y, 5) and 0xFF)) / 510f - 0.5f
                val ri = (r * 255f + n).roundToInt().coerceIn(0, 255)
                val gi = (g * 255f + n).roundToInt().coerceIn(0, 255)
                val bi = (b * 255f + n).roundToInt().coerceIn(0, 255)
                px[y * w + x] = (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    /**
     * The ground of the "Gradien" background: a smooth diagonal blend of the covers' colours (deep, never neon: lightness about 0.2..0.3) with one
     * soft light in the upper left and a darker floor, per pixel with the same dither as [backdrop]. [tints] are the covers' main colours; without
     * any, the covers' [tone] (or the neutral charcoal-blue) gives the two ends.
     */
    fun gradientGround(px: IntArray, w: Int, h: Int, tints: List<Color>, tone: DominantHue?) {
        fun deep(c: Color, l: Float): Color {
            val k = hsl(c)
            return if (k.s < 0.1f) color(NEUTRAL_HUE, NEUTRAL_SAT, l) else color(k.h, k.s.coerceIn(0.38f, 0.72f), l)
        }
        val (dark, lit) = backdropTones(tone)
        val a = if (tints.isNotEmpty()) deep(tints[0], 0.30f) else lit
        val b = if (tints.size > 1) deep(tints[tints.size / 2], 0.24f) else if (tints.isNotEmpty()) deep(tints[0], 0.17f) else dark
        val c = if (tints.size > 2) deep(tints[tints.size - 1], 0.12f) else color(NEUTRAL_HUE, NEUTRAL_SAT, 0.06f)
        for (y in 0 until h) {
            val v = y / h.toFloat()
            for (x in 0 until w) {
                val u = x / w.toFloat()
                val t = (0.34f * u + 0.66f * v).coerceIn(0f, 1f)
                // two smooth blends: a to b over the first part, b to c over the rest
                val k1 = (t / 0.62f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val k2 = ((t - 0.62f) / 0.38f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                var r = a.red + (b.red - a.red) * k1
                var g = a.green + (b.green - a.green) * k1
                var bl = a.blue + (b.blue - a.blue) * k1
                r += (c.red - r) * k2
                g += (c.green - g) * k2
                bl += (c.blue - bl) * k2
                val dx = (u - 0.28f) / 0.7f
                val dy = (v - 0.22f) / 0.6f
                val lightK = 0.16f * exp(-(dx * dx + dy * dy) * 2.4f)
                r = 1f - (1f - r) * (1f - lightK * a.red.coerceAtLeast(0.35f))
                g = 1f - (1f - g) * (1f - lightK * a.green.coerceAtLeast(0.35f))
                bl = 1f - (1f - bl) * (1f - lightK * a.blue.coerceAtLeast(0.35f))
                val n = ((PosterFinish.hash(x, y, 3) and 0xFF) + (PosterFinish.hash(x, y, 5) and 0xFF)) / 510f - 0.5f
                px[y * w + x] = (255 shl 24) or ((r * 255f + n).roundToInt().coerceIn(0, 255) shl 16) or ((g * 255f + n).roundToInt().coerceIn(0, 255) shl 8) or (bl * 255f + n).roundToInt().coerceIn(0, 255)
            }
        }
    }

    /** The ground of the "Polos" background: one deep, flat tone of the covers (no light at all; the finish's vignette gives it its depth). */
    fun solidGround(px: IntArray, w: Int, h: Int, tints: List<Color>, tone: DominantHue?) {
        val base =
            if (tints.isNotEmpty() && hsl(tints[0]).s >= 0.1f) {
                val k = hsl(tints[0])
                color(k.h, k.s.coerceIn(0.34f, 0.62f), 0.2f)
            } else {
                backdropTones(tone).second
            }
        val ri = (base.red * 255f).roundToInt()
        val gi = (base.green * 255f).roundToInt()
        val bi = (base.blue * 255f).roundToInt()
        val v = (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
        java.util.Arrays.fill(px, 0, w * h, v)
    }

    const val MARK_ICON = 58f
    private const val MARK_GAP = 16f
    const val MARK_CY = POSTER_H - 92f

    /** Where the mark sits (left, top, width, height of the logo tile and the word together). Mandatory: the picture and the tests share it. */
    fun markBox(textWidth: Int, ratio: PosterRatio = PosterRatio.DEFAULT): FloatArray {
        val total = MARK_ICON + MARK_GAP + textWidth
        return floatArrayOf((ratio.w - total) / 2f, ratio.markCy - MARK_ICON / 2f, total, MARK_ICON)
    }

    /**
     * The Auralys mark at the bottom centre: the logo tile (navy, the striped sun of the app icon) and the word in white with a soft shadow, no pill.
     * Part of EVERY poster (owner rule): always drawn, last, above everything.
     */
    fun DrawScope.mark(logo: ImageBitmap?, word: TextLayoutResult, ratio: PosterRatio = PosterRatio.DEFAULT) {
        val box = markBox(word.size.width, ratio)
        var x = box[0]
        val cy = ratio.markCy
        val top = cy - MARK_ICON / 2f
        drawRoundRect(Color.Black.copy(alpha = 0.30f), Offset(x - 3f, top + 2f), Size(MARK_ICON + 6f, MARK_ICON + 6f), CornerRadius(17f))
        drawRoundRect(
            Brush.verticalGradient(listOf(PosterBrand.Navy700, PosterBrand.Navy900), startY = top, endY = top + MARK_ICON),
            Offset(x, top),
            Size(MARK_ICON, MARK_ICON),
            CornerRadius(15f),
        )
        if (logo != null) {
            drawImage(logo, dstOffset = IntOffset(x.toInt(), top.toInt()), dstSize = IntSize(MARK_ICON.toInt(), MARK_ICON.toInt()), filterQuality = FilterQuality.High)
        } else {
            with(PosterBrand) { stripedSun(x + 9f, top + 12f, MARK_ICON - 18f, SunTop, SunBottom, Color(0xFFF2F4F7), 1f) }
        }
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(x + 0.6f, top + 0.6f), Size(MARK_ICON - 1.2f, MARK_ICON - 1.2f), CornerRadius(14.4f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
        x += MARK_ICON + MARK_GAP
        val y = cy - word.size.height / 2f
        drawText(word, Color.Black.copy(alpha = 0.34f), Offset(x + 1.5f, y + 3f))
        drawText(word, Color(0xFFF2F4F7).copy(alpha = 0.97f), Offset(x, y))
    }

    /** The mean colour of a photo from about 2000 reads. */
    fun meanColor(p: PosterImage): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val stepX = max(1, p.w / 48)
        val stepY = max(1, p.h / 48)
        var y = 0
        while (y < p.h) {
            var x = 0
            while (x < p.w) {
                val c = p.argb[y * p.w + x]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return Color(0xFF6F9CC8)
        return Color(r / (n * 255f), g / (n * 255f), b / (n * 255f), 1f)
    }
}

internal object PosterLook {
    /**
     * Halation ("Pendar"): only what is really bright spreads. The finished picture is shrunk (a quarter, a sixth in the light preview), a soft
     * threshold keeps the top of the highlights (from about 81 percent up), tinted a little warm (film's halation is red-orange), blurred tight
     * and wide, and screened back. A mid-tone or a pale wall does not glow; a lamp, a white sleeve in the sun or a specular does, a little.
     */
    fun bloom(out: android.graphics.Canvas, scene: Bitmap, amount: Float, level: Int, buf: BloomBuffers) {
        if (amount <= 0.01f) return
        val w = scene.width
        val h = scene.height
        val div = if (level >= 1) 6 else 4
        val sw = max(8, w / div)
        val sh = max(8, h / div)
        // 4.4 x - 3.8: zero under 0.86, 0.6 at white; times the warm tint of film halation (1, 0.78, 0.6)
        val k = 4.4f
        val o = -3.8f * 255f
        val threshold = ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k * 0.78f, 0f, 0f, o * 0.78f, 0f, 0f, k * 0.6f, 0f, o * 0.6f, 0f, 0f, 0f, 1f, 0f))
        // a two-step shrink keeps small highlights from vanishing between the samples
        val half = buf.bitmap(0, max(1, w / 2), max(1, h / 2))
        val small = buf.bitmap(1, sw, sh)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        android.graphics.Canvas(half).drawBitmap(scene, null, Rect(0, 0, half.width, half.height), p)
        android.graphics.Canvas(small).drawBitmap(half, null, Rect(0, 0, sw, sh), p)
        val src = buf.pixels(sw * sh)
        small.getPixels(src, 0, sw, 0, 0, sw, sh)
        PosterPost.matrix(src, sw * sh, PosterPost.fixedMatrix(threshold.array))
        val work = buf.bitmap(2, sw, sh)
        val px = buf.work(sw * sh)
        val q = if (level >= 1) 4f / 6f else 1f
        val dst = Rect(0, 0, w, h)
        val screen = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
        // sigma in quarter-size pixels: 2 (about 8 poster pixels, the halation fringe) and 6 (about 24, a faint spread); never a wide haze
        for ((sigma, a) in listOf((2f * q) to 0.4f, (6f * q) to 0.1f)) {
            System.arraycopy(src, 0, px, 0, sw * sh)
            PosterBlur.blur(px, sw, sh, sigma, clamp = true)
            work.setPixels(px, 0, sw, 0, 0, sw, sh)
            screen.alpha = ((amount * a).coerceIn(0f, 1f) * 255f).toInt()
            out.drawBitmap(work, null, dst, screen)
        }
    }
}

/** The bloom's small bitmaps and pixel arrays, kept by a renderer between frames. */
internal class BloomBuffers {
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var src: IntArray? = null
    private var tmp: IntArray? = null

    fun bitmap(i: Int, w: Int, h: Int): Bitmap = bitmaps[i]?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmaps[i] = it }

    fun pixels(n: Int): IntArray = src?.takeIf { it.size == n } ?: IntArray(n).also { src = it }

    fun work(n: Int): IntArray = tmp?.takeIf { it.size == n } ?: IntArray(n).also { tmp = it }

    fun release() {
        for (i in bitmaps.indices) {
            bitmaps[i]?.recycle()
            bitmaps[i] = null
        }
        src = null
        tmp = null
    }
}
