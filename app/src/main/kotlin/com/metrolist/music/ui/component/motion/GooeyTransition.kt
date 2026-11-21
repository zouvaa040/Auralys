/*
 * Liquid "gooey" merge/split effect.
 *
 * Adapted from Convx (com.convx.music.ui.component.floatingtabbar.GooeyTransition, GPL-3.0).
 * The standard "gooey" technique: blur, then a steep alpha threshold. Blurring bleeds nearby
 * shapes' alpha together and the threshold snaps it back to a crisp edge, so two glass shapes
 * that move apart grow a neck that thins and pinches off instead of a flat crossfade.
 *
 * Needs RenderEffect (Android 12 / API 31+). Below that the modifier is a no-op.
 */
package com.metrolist.music.ui.component.motion

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.music.ui.component.backdrop.internal.ColorFilterEffect

// Alpha (0..255 scale) snaps from 0 to 1 across a narrow band centred on 0.5: steep enough to read
// as a crisp edge, not as more blur.
private const val ThresholdSlope = 10f
private const val ThresholdMidpoint255 = 0.5f * 255f

// The threshold never varies, so the matrix filter is built once.
private val ThresholdColorFilter =
    ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, ThresholdSlope, -ThresholdSlope * ThresholdMidpoint255,
            ),
        ),
    )

// Integer-pixel radii are cached (thresholding hides sub-pixel blur changes), so a running
// transition reuses a handful of effects instead of allocating one per frame.
private val gooeyEffectCache = HashMap<Int, RenderEffect>()

@RequiresApi(Build.VERSION_CODES.S)
private fun gooeyRenderEffect(blurRadiusPx: Float): RenderEffect {
    val key = blurRadiusPx.toInt().coerceAtLeast(1)
    return gooeyEffectCache.getOrPut(key) {
        ColorFilterEffect(
            BlurEffect(key.toFloat(), key.toFloat(), TileMode.Decal),
            ThresholdColorFilter,
        )
    }
}

/**
 * Composites this element into one offscreen layer and gooifies it: blurs by [blurRadiusPx], then snaps
 * the blur back to a crisp edge via an alpha threshold. [blurRadiusPx] is read in the draw phase only.
 *
 * Zero cost at rest: when [blurRadiusPx] returns < 1 (or on API < 31) there is no offscreen layer and no
 * filter.
 */
fun Modifier.gooey(blurRadiusPx: () -> Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.graphicsLayer {
        val radius = blurRadiusPx()
        if (radius >= 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compositingStrategy = CompositingStrategy.Offscreen
            renderEffect = gooeyRenderEffect(radius)
        } else {
            compositingStrategy = CompositingStrategy.Auto
            renderEffect = null
        }
    }
}
