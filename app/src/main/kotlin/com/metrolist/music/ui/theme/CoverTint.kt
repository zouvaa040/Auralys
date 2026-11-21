/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The only dynamic colour in Horizon (DESIGN-V2.md 2.3): the colour of the cover that is playing. It never reaches a
 * control, an icon, a text or a divider. It feeds a soft wash behind page headers, the mini player veil, the player
 * backdrop and share cards. No cover, or colour mode "Netral" = null = pure neutral.
 */

package com.metrolist.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Cover colour already clamped by [clampCoverTint], or null when nothing should be tinted. */
val LocalCoverTint = compositionLocalOf<Color?> { null }

/**
 * True when the colour mode is "Sampul": page headers (playlist, album, artist) may wash the colour of THEIR OWN cover
 * into the page, not only the colour of the song that is playing. Provided by MetrolistTheme.
 */
val LocalCoverTintEnabled = compositionLocalOf { false }

/** Wash strength at the top of a header: 16 percent on light pages, 28 percent on dark ones. */
fun coverWashAlpha(dark: Boolean): Float = if (dark) 0.28f else 0.16f

/** Share of the mini player veil that is the cover colour. */
const val MiniPlayerTintShare = 0.12f

const val CoverTintMinLightness = 0.35f
const val CoverTintMaxLightness = 0.55f
const val CoverTintMaxSaturation = 0.55f

/**
 * Pastel and neon covers must not shout: lightness is held to 0.35..0.55 and saturation to at most 0.55 (HSL).
 * Grey covers stay grey (saturation 0 is kept), they just get a mid lightness.
 */
fun clampCoverTint(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val l = (hi + lo) / 2f
    val d = hi - lo
    val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
    val h = when {
        d == 0f -> 0f
        hi == r -> (((g - b) / d) % 6f).let { if (it < 0f) it + 6f else it }
        hi == g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    val l2 = l.coerceIn(CoverTintMinLightness, CoverTintMaxLightness)
    val s2 = s.coerceAtMost(CoverTintMaxSaturation)
    return hslToColor(h, s2, l2)
}

internal fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}

/**
 * A soft vertical wash from the cover colour at the top of a page header to nothing at [height]. Does nothing when
 * no cover colour is available. One gradient, no blur, no allocation per frame beyond the brush.
 */
@Composable
fun Modifier.coverWash(height: Dp = 360.dp): Modifier {
    val tint = LocalCoverTint.current ?: return this
    val dark = LocalHorizon.current.dark
    val alpha = coverWashAlpha(dark)
    return this.drawBehind {
        val h = height.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(
                0f to tint.copy(alpha = alpha),
                1f to Color.Transparent,
                startY = 0f,
                endY = h,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, h),
        )
    }
}
