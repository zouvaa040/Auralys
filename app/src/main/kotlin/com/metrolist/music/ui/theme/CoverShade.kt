/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Efek berbayang" (cover shade): the colour of the cover that plays washes softly into the BACKGROUND of every page,
 * fading to the page colour about half way down the screen. It is the whole-app "follow the cover" look of the older
 * versions, made quiet enough for the neutral Horizon design (DESIGN-V2.md): colour only from the cover, never on a
 * control, an icon or a text.
 *
 *  - It is drawn ONCE, behind the navigation content (MainActivity), never per page. Pages that paint nothing show it;
 *    the few pages that used to paint an opaque page colour ask [pageBackgroundColor] instead.
 *  - It is independent of [ColorMode]: it needs the cover colour even when the mode is "Netral" or "Kustom".
 *  - The numbers live in [CoverShadeMath] (pure, unit tested): opacity per level, light / dark / pure black and style,
 *    the tint guard that keeps ink text readable on the strongest wash, the ease curve.
 *  - The animated colour and strength are read in the draw phase only, so a song change (700 ms cross-fade) never
 *    recomposes a page; at rest the brush is cached by colour, strength and height (one gradient per frame at most).
 *  - Pages with their own header colour (album, playlist, artist, podcast) ask for the shade to step aside
 *    ([CoverShade.setPageOwnsHeader]); it fades out under the header instead of adding a second wash.
 */

package com.metrolist.music.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Mati / Lembut / Kuat. Stored by name under [com.metrolist.music.constants.CoverShadeKey]; absent or unknown = [DEFAULT]. */
enum class CoverShadeLevel {
    OFF,
    SOFT,
    STRONG,
    ;

    companion object {
        val DEFAULT = SOFT
    }
}

/** The numbers of the effect, with no Compose state, so they can be unit tested and tuned in one place. */
object CoverShadeMath {
    /** The cross-fade when the song or its cover changes, and when the level changes. */
    const val CrossfadeMs = 700

    /** How fast the shade steps aside under a page header (it comes back over [CrossfadeMs]). */
    const val HeaderYieldMs = 350

    /** Gradient stops: enough that the eased ramp has no visible corner. */
    const val StopCount = 14

    /** In the "Datar" style the wash is allowed but flatter. */
    const val FlatFactor = 0.8f

    /** Below this strength nothing is drawn. */
    const val MinStrength = 0.004f

    /**
     * Opacity of the tint at the very top of the page, for a cover with full colour (see [weight]).
     * Lembut: 16 percent on light pages, 22 percent on dark ones, 18 percent on pure black.
     * Kuat is about 1.75 times that: 28 / 40 / 32 percent. Pure black has a lower share than dark would give because the
     * page is black and any colour on it reads as a glow.
     */
    fun topAlpha(level: CoverShadeLevel, dark: Boolean, pureBlack: Boolean, flat: Boolean): Float {
        val base =
            when (level) {
                CoverShadeLevel.OFF -> 0f
                CoverShadeLevel.SOFT -> if (dark && pureBlack) 0.18f else if (dark) 0.22f else 0.16f
                CoverShadeLevel.STRONG -> if (dark && pureBlack) 0.32f else if (dark) 0.40f else 0.24f
            }
        return if (flat) base * FlatFactor else base
    }

    /** How far down the screen the wash reaches before it is the page colour, as a share of the screen height. */
    fun reach(level: CoverShadeLevel): Float =
        when (level) {
            CoverShadeLevel.OFF -> 0f
            CoverShadeLevel.SOFT -> 0.48f
            CoverShadeLevel.STRONG -> 0.58f
        }

    /** Light pages keep the tint's lightness in this band (HSL), dark pages a deeper band. */
    private const val LightMinL = 0.42f
    private const val LightMaxL = 0.62f
    private const val DarkMinL = 0.30f
    private const val DarkMaxL = 0.50f
    private const val MaxSaturation = 0.60f

    /** On a light page a very dark tint would dim the page; on a dark page a bright one would glow. */
    private const val LightMinLuminance = 0.12f
    private const val DarkMaxLuminance = 0.22f

    /**
     * The colour the wash is made of. Stricter than [clampCoverTint] (which the page headers use): the lightness band
     * is narrower, and the relative luminance is held to at least 0.12 on light pages and at most 0.22 on dark ones, so
     * a navy cover cannot grey the light page and a yellow or neon cover cannot make the dark page glow. Together with
     * the opacities of [topAlpha] this keeps ink text above 7:1 and secondary text above 4.5:1 on any cover.
     */
    fun tint(raw: Color, dark: Boolean): Color {
        val hsl = toHsl(raw)
        val lo = if (dark) DarkMinL else LightMinL
        val hi = if (dark) DarkMaxL else LightMaxL
        val s = min(hsl.s, MaxSaturation)
        var l = hsl.l.coerceIn(lo, hi)
        var out = hslToColor(hsl.h, s, l)
        if (dark) {
            var guard = 0
            while (relativeLuminance(out) > DarkMaxLuminance && l > 0.04f && guard++ < 60) {
                l -= 0.01f
                out = hslToColor(hsl.h, s, l)
            }
        } else {
            var guard = 0
            while (relativeLuminance(out) < LightMinLuminance && l < 0.85f && guard++ < 60) {
                l += 0.01f
                out = hslToColor(hsl.h, s, l)
            }
        }
        return out
    }

    /**
     * How much of the opacity a cover earns. A grey, white or black cover has no colour to give, and a grey wash only
     * dirties the page: it keeps 40 percent. A cover with real colour (chroma from 0.35 up) keeps all of it.
     */
    fun weight(raw: Color): Float {
        val chroma = max(raw.red, max(raw.green, raw.blue)) - min(raw.red, min(raw.green, raw.blue))
        return 0.4f + 0.6f * smoothstep(0.06f, 0.35f, chroma)
    }

    /**
     * The share of the top opacity that is left at [t] (0 at the top edge of the wash, 1 where it ends): a cosine ease,
     * flat at both ends, so the wash has no hard start under the status bar and no visible line where it ends.
     */
    fun falloff(t: Float): Float = 0.5f * (1f + cos(PI.toFloat() * t.coerceIn(0f, 1f)))

    /** Gradient stops of the wash: the SAME rgb in every stop, only the alpha eases (a stop to Transparent would pass through grey). */
    fun stops(tint: Color, strength: Float): Array<Pair<Float, Color>> =
        Array(StopCount) { i ->
            val t = i / (StopCount - 1).toFloat()
            t to tint.copy(alpha = (strength * falloff(t)).coerceIn(0f, 1f))
        }

    /** The page colour with the wash on it at [t] down the wash, as the screen shows it. */
    fun washedAt(page: Color, tint: Color, strength: Float, t: Float): Color =
        over(tint.copy(alpha = strength * falloff(t)), page)

    /** Whether the page for this navigation route draws its own header colour (the shade steps aside there). */
    fun pageOwnsHeader(route: String?): Boolean = route != null && route in HeaderRoutes

    /** The routes of the pages that use [com.metrolist.music.ui.component.HeroHeader]. A test checks them against the navigation graph. */
    val HeaderRoutes: Set<String> =
        setOf(
            "album/{albumId}",
            "artist/{artistId}?isPodcastChannel={isPodcastChannel}",
            "online_playlist/{playlistId}",
            "online_podcast/{podcastId}",
            "local_playlist/{playlistId}",
            "auto_playlist/{playlist}",
            "cache_playlist/{playlist}",
            "top_playlist/{top}",
        )

    // ---------------------------------------------------------------- colour helpers (also used by the tests)

    fun relativeLuminance(c: Color): Float {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)).toFloat()
    }

    /** [fg] laid over [bg] in sRGB, as the screen composes it. */
    fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    /** WCAG contrast of a text colour on a page colour (a translucent text is first laid over the page). */
    fun contrast(text: Color, page: Color): Float {
        val l1 = relativeLuminance(over(text, page))
        val l2 = relativeLuminance(page)
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private class Hsl(val h: Float, val s: Float, val l: Float)

    private fun toHsl(color: Color): Hsl {
        val r = color.red
        val g = color.green
        val b = color.blue
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2f
        val d = hi - lo
        val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        val h =
            when {
                d == 0f -> 0f
                hi == r -> (((g - b) / d) % 6f).let { if (it < 0f) it + 6f else it }
                hi == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        return Hsl(h, s, l)
    }
}

/**
 * The shade of the running theme: what to draw, with the animated parts as [State]s that are read in the draw phase.
 * One instance per level; it does not change when the song does.
 */
@Stable
class CoverShade internal constructor(
    val level: CoverShadeLevel,
    private val tint: State<Color>,
    private val strength: State<Float>,
    private val reach: State<Float>,
    private val ownHeader: MutableState<Boolean>,
    private val source: MutableState<Color?>,
) {
    /** True when the level is not "Mati": pages then leave their background to the app (see [pageBackgroundColor]). */
    val active: Boolean get() = level != CoverShadeLevel.OFF

    /** The raw cover colour that feeds the wash, or null when nothing plays (the settings preview uses it). */
    val sourceCover: Color? get() = source.value

    /** Called by the shell with the result of [CoverShadeMath.pageOwnsHeader] for the page on screen. */
    fun setPageOwnsHeader(owns: Boolean) {
        if (ownHeader.value != owns) ownHeader.value = owns
    }

    // the numbers right now, for the unit tests
    internal val strengthNow: Float get() = strength.value
    internal val reachNow: Float get() = reach.value
    internal val tintNow: Color get() = tint.value

    // one brush, rebuilt only when the colour, the strength or the height changed (during a cross-fade, once per frame)
    private var brushTint = Color.Unspecified
    private var brushStrength = -1f
    private var brushReachPx = -1f
    private var brush: Brush? = null

    internal fun paint(scope: DrawScope, pageHeightPx: Float) {
        val s = strength.value
        if (s <= CoverShadeMath.MinStrength) return
        val reachPx = pageHeightPx * reach.value
        if (reachPx < 1f) return
        val c = tint.value
        var b = brush
        if (b == null || c != brushTint || s != brushStrength || reachPx != brushReachPx) {
            b = Brush.verticalGradient(*CoverShadeMath.stops(c, s), startY = 0f, endY = reachPx)
            brush = b
            brushTint = c
            brushStrength = s
            brushReachPx = reachPx
        }
        // never wider than the node itself: a top bar draws the same wash in its own small box and must not paint the page below it
        scope.drawRect(brush = b, topLeft = Offset.Zero, size = Size(scope.size.width, min(reachPx, scope.size.height)))
    }

    companion object {
        private fun <T> constant(v: T): State<T> = object : State<T> { override val value: T = v }

        /** No shade at all: the default of [LocalCoverShade], so previews and tests without a theme draw nothing. */
        val Off: CoverShade =
            CoverShade(CoverShadeLevel.OFF, constant(Color.Transparent), constant(0f), constant(0f), mutableStateOf(false), mutableStateOf(null))

        /** A shade at rest (no animation): the gallery pictures and the unit tests use it. */
        fun fixed(level: CoverShadeLevel, cover: Color?, dark: Boolean, pureBlack: Boolean, flat: Boolean): CoverShade {
            if (level == CoverShadeLevel.OFF || cover == null) {
                return CoverShade(level, constant(Color.Transparent), constant(0f), constant(CoverShadeMath.reach(level)), mutableStateOf(false), mutableStateOf(cover))
            }
            val strength = CoverShadeMath.topAlpha(level, dark, pureBlack, flat) * CoverShadeMath.weight(cover)
            return CoverShade(
                level,
                constant(CoverShadeMath.tint(cover, dark)),
                constant(strength),
                constant(CoverShadeMath.reach(level)),
                mutableStateOf(false),
                mutableStateOf(cover),
            )
        }
    }
}

/** The shade of the running theme. A normal (not static) local: only the readers recompose when the level changes. */
val LocalCoverShade = compositionLocalOf { CoverShade.Off }

/**
 * Paints the wash into the draw scope, [pageHeightPx] being the height of the screen (the wash is a share of it). Reads the
 * animated colour and strength here, in the draw phase.
 */
fun DrawScope.drawCoverShade(shade: CoverShade, pageHeightPx: Float = size.height) = shade.paint(this, pageHeightPx)

/** Draws the wash behind this node. Nothing happens (no node is added) while the level is "Mati". */
fun Modifier.coverShade(shade: CoverShade, pageHeightPx: Float = Float.NaN): Modifier =
    if (!shade.active) this else this.drawBehind { drawCoverShade(shade, if (pageHeightPx.isNaN()) size.height else pageHeightPx) }

/**
 * The colour a page paints behind itself. While the shade is on the app paints the page colour and the wash once, behind
 * the navigation content, so a page that painted an opaque colour would hide it: it gets transparent instead.
 */
@Composable
fun pageBackgroundColor(pureBlack: Boolean = false): Color =
    if (LocalCoverShade.current.active) Color.Transparent else if (pureBlack) Color.Black else MaterialTheme.colorScheme.background

/**
 * The background of a page that LIES OVER other content (the search suggestions and the search results over the browse grid):
 * it must hide what is under it, so it is always opaque. [pageBackgroundColor] (transparent while the shade is on) is only
 * for pages that nothing lies under: used on a layer that is stacked over another one it lets the layer below show through.
 *
 * While the shade is on it paints the page colour and then the wash at the very place where the app paints it behind the
 * navigation content (shifted by the top of this node in the window), so the page looks like the pages that stay transparent.
 */
@Composable
fun Modifier.occludingPageBackground(pureBlack: Boolean = false): Modifier {
    val base = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background
    val shade = LocalCoverShade.current
    val pageHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val topInWindow = remember { mutableFloatStateOf(0f) }
    if (!shade.active) return this.background(base)
    return this
        .onGloballyPositioned { topInWindow.floatValue = it.positionInWindow().y }
        .clipToBounds()
        .drawBehind {
            drawRect(base)
            translate(top = -topInWindow.floatValue) { drawCoverShade(shade, pageHeightPx) }
        }
}

/**
 * Builds the shade of [level] for the cover colour [cover] (the raw colour from [Bitmap.extractCoverTint], whatever the
 * colour mode). Cross-fades the colour when the cover changes, fades in from nothing and out to nothing, and steps aside
 * while the page on screen owns a header colour.
 */
@Composable
fun rememberCoverShade(
    level: CoverShadeLevel,
    cover: Color?,
    dark: Boolean,
    pureBlack: Boolean,
    flat: Boolean,
): CoverShade {
    val ownHeader = remember { mutableStateOf(false) }
    val source = remember { mutableStateOf<Color?>(null) }
    val strength = remember { Animatable(0f) }
    val tint = remember { Animatable(Color(0xFF808080), Color.VectorConverter(ColorSpaces.Srgb)) }
    val reach = remember { Animatable(CoverShadeMath.reach(level)) }

    val target = remember(cover, dark) { cover?.let { CoverShadeMath.tint(it, dark) } }
    val goal = if (level == CoverShadeLevel.OFF || cover == null) 0f else CoverShadeMath.topAlpha(level, dark, pureBlack, flat) * CoverShadeMath.weight(cover)

    LaunchedEffect(target) {
        if (target != null) {
            // the first cover after nothing appears in its own colour; a change of cover slides from one colour to the next
            if (strength.value < CoverShadeMath.MinStrength) {
                tint.snapTo(target)
            } else {
                tint.animateTo(target, tween(CoverShadeMath.CrossfadeMs, easing = FastOutSlowInEasing))
            }
        }
    }
    LaunchedEffect(goal) {
        snapshotFlow { ownHeader.value }.collectLatest { yielded ->
            strength.animateTo(
                if (yielded) 0f else goal,
                tween(if (yielded) CoverShadeMath.HeaderYieldMs else CoverShadeMath.CrossfadeMs, easing = FastOutSlowInEasing),
            )
        }
    }
    val goalReach = CoverShadeMath.reach(level)
    LaunchedEffect(goalReach) {
        if (goalReach > 0f) reach.animateTo(goalReach, tween(CoverShadeMath.CrossfadeMs, easing = FastOutSlowInEasing))
    }
    SideEffect { source.value = cover }

    return remember(level) { CoverShade(level, tint.asState(), strength.asState(), reach.asState(), ownHeader, source) }
}
