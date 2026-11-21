/**
 * Auralys - "Frost": the Kaca glass material drawn WITHOUT sampling the page.
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Used by the floating nav bar, its folded pill and the mini player wherever the page cannot be captured and blurred:
 * Android 8-11 (API 26-30, no RenderEffect) and phones where "Blur kaca" is off (low-RAM default). It is what BitChord
 * does NOT have: below Android 12 BitChord drops its liquid glass and its fold completely (LiquidGlass.kt:60).
 *
 * Why not a cheap blur of a page snapshot instead: without RenderEffect the only blur is on the CPU (a downscaled
 * bitmap of the page every frame the list moves) or a low-resolution layer stretched back up, which shimmers on moving
 * text. Neither can be shown to be cheap on a weak phone without a device, so the frost is a plain material:
 *  - a body of the theme's raised surface, opaque enough that rows sliding underneath stay unreadable;
 *  - a soft vertical sheen and a darker floor, so the pane reads as a thick, rounded piece of glass;
 *  - a bevelled rim (bright top-left, glint bottom-right) and a hairline edge;
 *  - a real elevation shadow (RenderNode outline shadow: native on every Android version, no blur mask).
 * Everything is a plain fill or stroke of the shape (anti-aliased on every GPU pipeline, no path clip). The brushes,
 * strokes and the inset outline are built once per size in [drawWithCache]: no allocation while the page scrolls under
 * the bar, and the bar itself is never redrawn by a scroll (it samples nothing).
 */
package com.metrolist.music.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.LocalHorizon

/** The numbers of the frost (plain values, unit-tested in GlassAllVersionsTest). */
object GlassFrostLook {
    /**
     * Body: the theme's surface-1 (white by day, #15171C by night) at this strength. Without a blur, whatever shows through
     * is SHARP: at 90 % and still at 95 % the rows behind were readable in the renders (dirty plastic, not glass), so the
     * body is nearly opaque (covers still tint it faintly) and the glass look comes from the sheen, rim and shadow.
     */
    const val BODY_ALPHA_LIGHT = 0.97f
    const val BODY_ALPHA_DARK = 0.97f

    /** Night: a white lift over the body, so the bar is a frosted grey and not a darker hole in a dark page. */
    const val LIFT_DARK = 0.05f

    /** Sheen from the top edge down to [SHEEN_END] of the height. */
    const val SHEEN_LIGHT = 0.50f
    const val SHEEN_DARK = 0.07f
    const val SHEEN_END = 0.55f

    /** Floor: a little darker towards the bottom edge, from [FLOOR_START] of the height. */
    const val FLOOR_LIGHT = 0.035f
    const val FLOOR_DARK = 0.14f
    const val FLOOR_START = 0.55f

    /** Rim (inside the edge): top-left, middle, bottom-right glint. */
    const val RIM_WIDTH_DP = 0.9f
    val RIM_LIGHT = floatArrayOf(0.95f, 0.22f, 0.60f)
    val RIM_DARK = floatArrayOf(0.28f, 0.03f, 0.12f)

    /** Hairline on the edge itself: ink by day (a white bar on a white page needs an edge), white by night. */
    const val EDGE_WIDTH_DP = 0.6f
    const val EDGE_LIGHT = 0.10f
    const val EDGE_DARK = 0.05f

    /** Elevation shadow. Its colours only apply from Android 9; below, the system's own shadow strength is used. */
    const val ELEVATION_DP = 12f
    const val SHADOW_AMBIENT_LIGHT = 0.10f
    const val SHADOW_SPOT_LIGHT = 0.16f
    const val SHADOW_AMBIENT_DARK = 0.32f
    const val SHADOW_SPOT_DARK = 0.44f

    /** The colour the body ends up as over a page of colour [page] (straight alpha compositing, no blur). */
    fun bodyOver(page: Color, surface1: Color, dark: Boolean): Color {
        val a = if (dark) BODY_ALPHA_DARK else BODY_ALPHA_LIGHT
        var c = mix(page, surface1, a)
        if (dark) c = mix(c, Color.White, LIFT_DARK)
        return c
    }

    /** How much of the page's own contrast still shows through the body (sharp text behind the bar). */
    fun showThrough(dark: Boolean): Float {
        val a = if (dark) BODY_ALPHA_DARK else BODY_ALPHA_LIGHT
        return (1f - a) * (if (dark) 1f - LIFT_DARK else 1f)
    }

    private fun mix(under: Color, over: Color, a: Float) = Color(
        red = under.red * (1f - a) + over.red * a,
        green = under.green * (1f - a) + over.green * a,
        blue = under.blue * (1f - a) + over.blue * a,
        alpha = 1f,
    )
}

/** The resolved colours of one theme (remembered per theme, never per frame). */
@Immutable
internal class FrostColors(dark: Boolean, surface1: Color) {
    val body: Color = surface1.copy(alpha = if (dark) GlassFrostLook.BODY_ALPHA_DARK else GlassFrostLook.BODY_ALPHA_LIGHT)
    val lift: Color = if (dark) Color.White.copy(alpha = GlassFrostLook.LIFT_DARK) else Color.Transparent
    val sheen: Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) GlassFrostLook.SHEEN_DARK else GlassFrostLook.SHEEN_LIGHT),
        GlassFrostLook.SHEEN_END to Color.White.copy(alpha = 0f),
    )
    val floor: Brush = Brush.verticalGradient(
        GlassFrostLook.FLOOR_START to Color.Black.copy(alpha = 0f),
        1f to Color.Black.copy(alpha = if (dark) GlassFrostLook.FLOOR_DARK else GlassFrostLook.FLOOR_LIGHT),
    )
    val rim: FloatArray = if (dark) GlassFrostLook.RIM_DARK else GlassFrostLook.RIM_LIGHT
    val edge: Color = if (dark) Color.White.copy(alpha = GlassFrostLook.EDGE_DARK) else com.metrolist.music.ui.theme.HorizonLight.ink.copy(alpha = GlassFrostLook.EDGE_LIGHT)
    val ambient: Color = Color.Black.copy(alpha = if (dark) GlassFrostLook.SHADOW_AMBIENT_DARK else GlassFrostLook.SHADOW_AMBIENT_LIGHT)
    val spot: Color = Color.Black.copy(alpha = if (dark) GlassFrostLook.SHADOW_SPOT_DARK else GlassFrostLook.SHADOW_SPOT_LIGHT)
}

@Composable
internal fun rememberFrostColors(): FrostColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val surface1 = LocalHorizon.current.surface1
    return remember(dark, surface1) { FrostColors(dark, surface1) }
}

/**
 * The frost material on [shape]: shadow, body, sheen, floor, rim and edge, then the content on top. [elevation] 0 = no
 * shadow (a surface that already sits on a shadowed parent).
 */
@Composable
fun Modifier.glassFrost(shape: Shape, elevation: Dp = GlassFrostLook.ELEVATION_DP.dp): Modifier =
    glassFrost(shape, rememberFrostColors(), elevation)

internal fun Modifier.glassFrost(shape: Shape, colors: FrostColors, elevation: Dp): Modifier =
    this
        .then(
            if (elevation > 0.dp) {
                Modifier.shadow(elevation, shape, clip = false, ambientColor = colors.ambient, spotColor = colors.spot)
            } else {
                Modifier
            },
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val rimPx = GlassFrostLook.RIM_WIDTH_DP.dp.toPx()
            val edgePx = GlassFrostLook.EDGE_WIDTH_DP.dp.toPx()
            val inner = outline.inset(rimPx / 2f)
            val rimStroke = Stroke(width = rimPx)
            val edgeStroke = Stroke(width = edgePx)
            val r = colors.rim
            val rimBrush = Brush.linearGradient(
                0f to Color.White.copy(alpha = r[0]),
                0.45f to Color.White.copy(alpha = r[1]),
                0.7f to Color.White.copy(alpha = r[1]),
                1f to Color.White.copy(alpha = r[2]),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val edgeInset = outline.inset(edgePx / 2f)
            onDrawWithContent {
                drawOutline(outline, colors.body)
                if (colors.lift.alpha > 0f) drawOutline(outline, colors.lift)
                drawOutline(outline, colors.sheen)
                drawOutline(outline, colors.floor)
                drawContent()
                // edge and rim on top of the content, like the backdrop library's highlight
                drawOutline(edgeInset, colors.edge, style = edgeStroke)
                drawOutline(inner, rimBrush, style = rimStroke)
            }
        }
        // the content (press sheen, ripple, the mini player's rows) is clipped to the shape, as the backdrop node's own
        // clipped layer does in the blur / lens materials
        .clip(shape)

/** The same outline moved [d] px inwards (corners shrink with it), for strokes that must stay inside the edge. */
internal fun Outline.inset(d: Float): Outline = when (this) {
    is Outline.Rectangle -> Outline.Rectangle(rect.deflate(d))
    is Outline.Rounded -> {
        val rr = roundRect
        fun c(x: CornerRadius) = CornerRadius((x.x - d).coerceAtLeast(0f), (x.y - d).coerceAtLeast(0f))
        Outline.Rounded(
            RoundRect(
                left = rr.left + d, top = rr.top + d, right = rr.right - d, bottom = rr.bottom - d,
                topLeftCornerRadius = c(rr.topLeftCornerRadius),
                topRightCornerRadius = c(rr.topRightCornerRadius),
                bottomRightCornerRadius = c(rr.bottomRightCornerRadius),
                bottomLeftCornerRadius = c(rr.bottomLeftCornerRadius),
            ),
        )
    }
    // a free path cannot be shrunk exactly; its stroke then straddles the edge, which only a custom shape would show
    is Outline.Generic -> this
}
