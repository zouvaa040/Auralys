/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Horizon" design tokens (DESIGN-V2.md, sections 2.3 to 2.6): ink on neutral surfaces, one radius scale, one spacing
 * grid, three elevation levels, one motion set, three glass tiers. Pure values with no Compose state, so they can be
 * unit tested (contrast, scale rules) and read from anywhere.
 */

package com.metrolist.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colour tokens of one palette. Everything in the app that is not a cover reads these (directly or via the Material slots). */
@Immutable
class HorizonColors(
    val dark: Boolean,
    /** Page. */
    val bg: Color,
    /** Grouped rows, sheets, dialogs, tiles. */
    val surface1: Color,
    /** Fields, segmented track, idle chips. */
    val surface2: Color,
    /** Pressed, selected row, toggle off-track. */
    val surface3: Color,
    /** Titles, icons, the primary fill. */
    val ink: Color,
    /** Secondary lines, inactive tab. */
    val text2: Color,
    /** Hints, timestamps, placeholder. Only on bg, surface-1 and surface-2. */
    val text3: Color,
    val divider: Color,
    val scrim: Color,
    val danger: Color,
    val onDanger: Color,
) {
    /** The colour of a label on an ink fill. */
    val onInk: Color get() = bg

    /** "Hitam murni": the page is black and the first surface steps down to the old page colour. */
    fun pureBlack(): HorizonColors =
        if (!dark) this else HorizonColors(dark, Color(0xFF000000), Color(0xFF0B0C0F), surface2, surface3, ink, text2, text3, divider, scrim, danger, onDanger)
}

private val InkLight = Color(0xFF0E1116)
private val InkDark = Color(0xFFF2F4F7)

/** "Day". */
val HorizonLight = HorizonColors(
    dark = false,
    bg = Color(0xFFF5F6F8),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECEEF2),
    surface3 = Color(0xFFDFE2E8),
    ink = InkLight,
    text2 = Color(0xFF4A5160),
    text3 = Color(0xFF656C7A),
    divider = InkLight.copy(alpha = 0.08f),
    scrim = InkLight.copy(alpha = 0.40f),
    danger = Color(0xFFC62828),
    onDanger = Color(0xFFFFFFFF),
)

/** "Night". */
val HorizonDark = HorizonColors(
    dark = true,
    bg = Color(0xFF0B0C0F),
    surface1 = Color(0xFF15171C),
    surface2 = Color(0xFF1E2128),
    surface3 = Color(0xFF292D36),
    ink = InkDark,
    text2 = Color(0xFFB4BAC6),
    text3 = Color(0xFF8B92A0),
    divider = Color(0xFFFFFFFF).copy(alpha = 0.10f),
    scrim = Color(0xFF000000).copy(alpha = 0.55f),
    danger = Color(0xFFFF8A80),
    onDanger = Color(0xFF0B0C0F),
)

fun horizonColors(dark: Boolean, pureBlack: Boolean = false): HorizonColors =
    (if (dark) HorizonDark else HorizonLight).let { if (pureBlack) it.pureBlack() else it }

/** The palette of the running theme (set by MetrolistTheme). */
val LocalHorizon = staticCompositionLocalOf { HorizonLight }

/** Radius scale in dp. Every rounded corner in the app should be one of these (or fully round). */
object HorizonRadius {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Sheet = 28.dp
    val all: List<Dp> = listOf(Xs, Sm, Md, Lg, Xl, Sheet)

    /** The nearest token for an arbitrary radius. 0 and "fully round" values pass through. */
    fun snap(radius: Dp): Dp {
        if (radius.value <= 0f || radius.value >= 48f) return radius
        return all.minByOrNull { kotlin.math.abs(it.value - radius.value) } ?: radius
    }
}

/** 4 pt grid. */
object HorizonSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val S32 = 32.dp
    val S40 = 40.dp
    val S56 = 56.dp
    val all: List<Dp> = listOf(S4, S8, S12, S16, S20, S24, S32, S40, S56)

    /** Page gutter. */
    val Gutter = S20
    val RowHeight = 64.dp
    val RowHeightDense = 52.dp
    val SettingsRowHeight = 56.dp
    val MenuRowHeight = 52.dp
    /** Minimum touch target. */
    val Target = 48.dp
}

/** Shadow levels. The shadow colour is ink-black; cards and rows have none. */
object HorizonElevation {
    class Level(val dp: Dp, val ambientAlpha: Float, val spotAlpha: Float)
    val E0 = Level(0.dp, 0f, 0f)
    /** Glass controls over covers, the segmented thumb. */
    val E1 = Level(4.dp, 0.06f, 0.08f)
    /** Nav bar, mini player, tooltips. */
    val E2 = Level(10.dp, 0.08f, 0.10f)
    /** Dialogs, menus, sheets. */
    val E3 = Level(16.dp, 0.12f, 0.16f)

    /** Dark pages need stronger shadows to show at all. */
    fun spotAlpha(level: Level, dark: Boolean): Float = if (dark) (level.spotAlpha * 3.5f).coerceAtMost(0.55f) else level.spotAlpha
    fun ambientAlpha(level: Level, dark: Boolean): Float = if (dark) (level.ambientAlpha * 3.5f).coerceAtMost(0.45f) else level.ambientAlpha
}

/** Motion: one easing, four durations. */
object HorizonMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val PressMs = 120
    const val StateMs = 200
    const val EnterMs = 320
    const val HeroMs = 480
    const val PressScale = 0.97f
    const val PressDamping = 0.7f
    const val PressStiffness = 900f
    const val SheetDamping = 0.85f
    const val SheetStiffness = 400f
}

/**
 * The three glass tiers (DESIGN-V2.md 2.6). The numbers come from the navigation bar that already worked, made
 * consistent, and BitChord's one-third capture scale for small controls.
 */
object HorizonGlass {
    class Tier(
        /** Backdrop blur radius in dp (0 = the tier does not sample the page). */
        val blurDp: Float,
        /** Capture scale of the sampled backdrop: lower is cheaper. */
        val captureScale: Float,
        val saturation: Float,
        val veilLight: Color,
        val veilDark: Color,
        val rimWidth: Dp,
        val rimLightTop: Float,
        val rimLightBottom: Float,
        val rimDarkTop: Float,
        val rimDarkBottom: Float,
    ) {
        fun veil(dark: Boolean): Color = if (dark) veilDark else veilLight
    }

    /** Bars: navigation bar, mini player, top fade. */
    val G1 = Tier(
        blurDp = 20f, captureScale = 0.5f, saturation = 1.6f,
        veilLight = Color(0xFFFFFFFF).copy(alpha = 0.58f), veilDark = Color(0xFF101216).copy(alpha = 0.62f),
        rimWidth = 0.8.dp, rimLightTop = 0.90f, rimLightBottom = 0.25f, rimDarkTop = 0.22f, rimDarkBottom = 0.06f,
    )

    /** Controls: search field, round buttons over covers, segmented track, chips over covers. */
    val G2 = Tier(
        blurDp = 12f, captureScale = 0.33f, saturation = 1.4f,
        veilLight = Color(0xFFFFFFFF).copy(alpha = 0.46f), veilDark = Color(0xFFFFFFFF).copy(alpha = 0.12f),
        rimWidth = 0.8.dp, rimLightTop = 0.90f, rimLightBottom = 0.25f, rimDarkTop = 0.22f, rimDarkBottom = 0.06f,
    )

    /** Sheets, dialogs, menus: they open in their own window and cannot sample the page, so no blur. */
    val G3 = Tier(
        blurDp = 0f, captureScale = 1f, saturation = 1f,
        veilLight = Color(0xFFFFFFFF), veilDark = Color(0xFF15171C),
        rimWidth = 1.dp, rimLightTop = 0.70f, rimLightBottom = 0f, rimDarkTop = 0.10f, rimDarkBottom = 0f,
    )

    /** Fallback when there is no backdrop (API below 31, blur switched off): the veil gets this much more opaque. */
    const val NoBlurExtraAlpha = 0.18f
}
